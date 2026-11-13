/*
 * qbm-diagd USB DIAG backend: included by qbm-diagd.c when built with
 * -DQBM_USB_DIAG, in place of the Android libdiag/DCI code. Everything above
 * that point (decoders, write_stats, the socket) is shared unchanged.
 *
 * For a Qualcomm modem on USB (e.g. an OpenWrt router), whose DIAG interface
 * appears as a serial port (/dev/ttyUSBn, the "option" driver). The modem
 * speaks raw DIAG there: HDLC-style framing (0x7E end flag, 0x7D escape,
 * CRC-16/X.25 little-endian before the flag), no libdiag in between.
 *
 *   qbm-diagd -dev /dev/ttyUSB0 [-uid N] [-watchdog MS] [-print MS]
 *             [-modem auto|qcom|mtk] [-at DEV] [-mtk-trim 0|1]
 *   qbm-diagd -query '{"cmd":"stats","sim":1}'
 *   qbm-diagd -mtk-replay CAPTURE [-print MS] [-mtk-log CODE]   (offline: an FM350 capture)
 *   qbm-diagd -mtk-packets DIR                  (offline: write the MTK host commands)
 *
 *   -dev       the DIAG port, or "auto" (default): the first /dev/ttyUSB0..15
 *              that answers a DIAG log ID range request. Probing writes DIAG
 *              commands to the modem's other ports too (AT, NMEA), which
 *              ignore them; set the port to avoid that. On an FM350 "auto" is
 *              its debug port, USB interface 09.
 *   -modem     auto (default): a MediaTek (USB 0e8d) modem that answers
 *              AT+CGMM with an FM350 model uses the MediaTek backend
 *              (qbm-mtk.c), anything else Qualcomm DIAG
 *   -at        the AT port for that question, or "auto" (default): the
 *              MediaTek device's USB interface 06, else the first of its ports
 *              that answers
 *   -mtk-trim  1 (default): trim the FM350's DHL trace filters (written to the
 *              modem's NVRAM only when they differ; see qbm-mtk.c)
 *   -uid       also accept socket clients with this uid (root always is)
 *   -watchdog  exit after MS without a "stats" request (default 0 = never;
 *              the Android helper uses 15000)
 *   -print     every MS, print SIM 1's stats line to stdout (testing; each
 *              print starts a new window, like a "stats" request does)
 *   -query     client mode: send one request line to the running daemon,
 *              print its reply line and exit (1 if no daemon is running).
 *              For scripts and the LuCI backend: BusyBox nc has no abstract
 *              sockets.
 *
 * Socket and protocol: as on Android (abstract "qbm_diagd", NDJSON).
 *
 * Unlike DCI, the USB port is a whole DIAG session: this helper owns the log
 * mask while it runs (other DIAG tools on the same port would fight over it).
 * It sets only equipment 0xB (LTE/NR) items for CODES, turns off F3 messages
 * and event reports so the port carries only those logs, and clears the log
 * mask again on exit. If the port goes away (modem reset, USB re-enumeration)
 * it reopens it and subscribes again.
 *
 * Frames handled (see qc-diag-monitor core/modem.py and monitor.py):
 *   0x10 log:        [0x10][more][u16 len][record: u16 len, u16 code, u64 ts, payload]
 *   0x98 multi-SIM:  [0x98][ver][u16 reserved][u32 subscription][inner frame]
 *   0x73 responses:  log-mask replies, read during subscription
 */

#include <fcntl.h>
#include <sys/time.h>
#include <sys/ioctl.h>
#include <termios.h>

#define USB_DEFAULT_DEV "auto"
#define USB_PROBE_MAX 16
#define DIAG_LOG_F 0x10
#define DIAG_MULTI_SIM_F 0x98
#define DIAG_LOG_CONFIG_F 0x73
#define LOG_CONFIG_DISABLE_OP 0
#define LOG_CONFIG_RETRIEVE_ID_RANGES_OP 1
#define LOG_CONFIG_SET_MASK_OP 3
#define LOG_EQUIP_ID_LTE_NR 0x0B
#define LOG_LAST_ITEM_FALLBACK 0x09FF   /* qc-diag-monitor's fixed value */
#define FRAME_MAX 65536

/* F3 message subsystem ranges, as qc-diag-monitor turns them off. */
static const uint16_t MSG_SSID_RANGES[][2] = {
    { 0x0000, 0x00c8 }, { 0x01f4, 0x02bc }, { 0x03e8, 0x04b0 }, { 0x07d0, 0x0898 },
    { 0x0bb8, 0x0c80 }, { 0x0fa0, 0x1068 }, { 0x1194, 0x12c0 }, { 0x1388, 0x1450 },
    { 0x157c, 0x1644 }, { 0x1770, 0x1838 }, { 0x1964, 0x1a2c }, { 0x1b58, 0x1ce8 },
    { 0x1f40, 0x2008 }, { 0x2134, 0x21fc }, { 0x2328, 0x23f0 }, { 0x251c, 0x25e4 },
    { 0x27d8, 0x2968 },
};

/* ── CRC-16/X.25 and framing ─────────────────────────────────────────────── */

static uint16_t crc_table[256];

static void crc_init(void) {
    for (int i = 0; i < 256; i++) {
        uint16_t c = (uint16_t)i;
        for (int k = 0; k < 8; k++) c = (c & 1) ? (uint16_t)((c >> 1) ^ 0x8408) : (uint16_t)(c >> 1);
        crc_table[i] = c;
    }
}

static uint16_t crc16(const uint8_t *p, size_t n) {
    uint16_t c = 0xFFFF;
    while (n--) c = (uint16_t)((c >> 8) ^ crc_table[(c ^ *p++) & 0xFF]);
    return (uint16_t)(c ^ 0xFFFF);
}

static int tty_write_all(int fd, const uint8_t *p, size_t n) {
    while (n > 0) {
        ssize_t k = write(fd, p, n);
        if (k < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) { struct pollfd pf = { fd, POLLOUT, 0 }; poll(&pf, 1, 200); continue; }
            return -1;
        }
        p += k; n -= (size_t)k;
    }
    return 0;
}

/* Sends one DIAG command: payload, CRC (LE), escaped, then the 0x7E flag. */
static int diag_send(int fd, const uint8_t *data, size_t n) {
    static uint8_t out[2 * 4096 + 8];
    if (n > 4096) return -1;
    uint16_t c = crc16(data, n);
    size_t o = 0;
    for (size_t i = 0; i < n + 2; i++) {
        uint8_t b = i < n ? data[i] : (uint8_t)(i == n ? c & 0xFF : c >> 8);
        if (b == 0x7E || b == 0x7D) { out[o++] = 0x7D; out[o++] = b ^ 0x20; }
        else out[o++] = b;
    }
    out[o++] = 0x7E;
    return tty_write_all(fd, out, o);
}

static void put32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }
static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }

/* ── Receive side ────────────────────────────────────────────────────────── */

static uint8_t frame[FRAME_MAX];
static size_t frame_len;
static int frame_esc, frame_overflow;
static long n_frames, n_crc_err;

/* The latest 0x73 reply, for the subscription steps that wait for one. */
static uint8_t cfg_reply[128];
static size_t cfg_reply_len;
static int cfg_reply_new;

static void route_frame(const uint8_t *f, size_t n, int sub) {
    if (n < 1) return;
    if (f[0] == DIAG_MULTI_SIM_F) {
        if (n < 9) return;
        int s = (int)le32(f + 4);
        sim_tags = 1;
        route_frame(f + 8, n - 8, s < 1 ? 1 : s);   /* 1-based, as in DCI; 0 = SIM 1 */
        return;
    }
    if (f[0] == DIAG_LOG_F) {
        if (n < 16) return;
        const uint8_t *rec = f + 4;
        int rlen = (int)(n - 4);
        if (le16(rec) < 12 || le16(rec) > rlen) return;
        rlen = le16(rec);
        decode_log(0, sub, rec, rlen);
        return;
    }
    if (f[0] == DIAG_LOG_CONFIG_F && n >= 12) {
        size_t k = n < sizeof cfg_reply ? n : sizeof cfg_reply;
        memcpy(cfg_reply, f, k);
        cfg_reply_len = k;
        cfg_reply_new = 1;
    }
}

static void feed_bytes(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) {
        uint8_t b = p[i];
        if (b == 0x7E) {
            if (!frame_overflow && frame_len >= 3) {
                uint16_t want = (uint16_t)(frame[frame_len - 2] | frame[frame_len - 1] << 8);
                if (crc16(frame, frame_len - 2) == want) { n_frames++; route_frame(frame, frame_len - 2, 1); }
                else n_crc_err++;
            }
            frame_len = 0; frame_esc = 0; frame_overflow = 0;
            continue;
        }
        if (frame_overflow) continue;
        if (b == 0x7D) { frame_esc = 1; continue; }
        if (frame_esc) { b ^= 0x20; frame_esc = 0; }
        if (frame_len >= sizeof frame) { frame_overflow = 1; continue; }
        frame[frame_len++] = b;
    }
}

/* Reads what the port has; -1 when it is gone. */
static int tty_pump(int fd) {
    uint8_t buf[16384];
    for (;;) {
        ssize_t k = read(fd, buf, sizeof buf);
        if (k > 0) { feed_bytes(buf, (size_t)k); continue; }
        if (k < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
        return -1;                                   /* EOF or EIO: unplugged */
    }
}

/* Waits up to ms for a 0x73 reply to operation op. */
static int wait_cfg_reply(int fd, uint32_t op, int ms) {
    long end = now_ms() + ms;
    cfg_reply_new = 0;
    while (now_ms() < end && !stop_flag) {
        struct pollfd pf = { fd, POLLIN, 0 };
        if (poll(&pf, 1, 100) > 0 && tty_pump(fd) < 0) return 0;
        if (cfg_reply_new && cfg_reply_len >= 12 && le32(cfg_reply + 4) == op) return 1;
        cfg_reply_new = 0;
    }
    return 0;
}

/* ── Port setup and subscription ─────────────────────────────────────────── */

static int tty_open(const char *dev) {
    int fd = open(dev, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    struct termios t;
    if (tcgetattr(fd, &t) == 0) {
        cfmakeraw(&t);
        cfsetispeed(&t, B115200);
        cfsetospeed(&t, B115200);
        t.c_cflag |= CLOCAL | CREAD;
        /* VMIN 1: with O_NONBLOCK an empty read is EAGAIN, so 0 means hangup.
         * (VMIN 0 makes n_tty return 0 for "no data", before O_NONBLOCK.) */
        t.c_cc[VMIN] = 1;
        t.c_cc[VTIME] = 0;
        tcsetattr(fd, TCSANOW, &t);
    }
    /* DTR low then high, as qc-diag-monitor does: some modems only start
     * the DIAG channel on a DTR edge. */
    int dtr = TIOCM_DTR;
    ioctl(fd, TIOCMBIC, &dtr);
    usleep(100 * 1000);
    ioctl(fd, TIOCMBIS, &dtr);
    usleep(100 * 1000);
    tcflush(fd, TCIOFLUSH);
    frame_len = 0; frame_esc = 0; frame_overflow = 0;
    return fd;
}

static void log_mask_disable(int fd) {
    uint8_t c[8];
    put32(c, DIAG_LOG_CONFIG_F);
    put32(c + 4, LOG_CONFIG_DISABLE_OP);
    diag_send(fd, c, sizeof c);
}

static void note(const char *msg);

/* Silences the port, then sets equipment 0xB's log mask to CODES. probe:
 * auto-detection, where a port without a log ID range reply is not DIAG
 * (fail quietly) rather than a DIAG port without the query (fallback). */
static int subscribe(int fd, int probe) {
    uint8_t c[4096];

    c[0] = 0x00;                                     /* version: wakes some modems */
    diag_send(fd, c, 1);
    usleep(200 * 1000);
    tty_pump(fd);

    c[0] = 0x60; c[1] = 0x00;                        /* event reports off */
    diag_send(fd, c, 2);
    log_mask_disable(fd);
    for (size_t r = 0; r < sizeof MSG_SSID_RANGES / sizeof MSG_SSID_RANGES[0]; r++) {
        uint16_t a = MSG_SSID_RANGES[r][0], b = MSG_SSID_RANGES[r][1];
        size_t n = 8 + 4u * (size_t)(b - a + 1);     /* F3 messages off for a..b */
        if (n > sizeof c) continue;
        memset(c, 0, n);
        c[0] = 0x7D; c[1] = 0x04;
        put16(c + 2, a); put16(c + 4, b); put16(c + 6, 0);
        diag_send(fd, c, n);
        tty_pump(fd);
    }

    /* Equipment 0xB's log item count from the modem; the fixed 0x9FF otherwise. */
    uint32_t last = LOG_LAST_ITEM_FALLBACK;
    put32(c, DIAG_LOG_CONFIG_F);
    put32(c + 4, LOG_CONFIG_RETRIEVE_ID_RANGES_OP);
    diag_send(fd, c, 8);
    if (wait_cfg_reply(fd, LOG_CONFIG_RETRIEVE_ID_RANGES_OP, 1500) && cfg_reply_len >= 12 + 4 * (LOG_EQUIP_ID_LTE_NR + 1)) {
        uint32_t v = le32(cfg_reply + 12 + 4 * LOG_EQUIP_ID_LTE_NR);
        if (v > 0 && v < 0x1000) last = v;
    } else {
        if (probe) return 0;
        char m[96]; snprintf(m, sizeof m, "no log ID range reply, using 0x%X", (unsigned)last); note(m);
    }

    /* As qc-diag-monitor's _build_log_mask: the field is the item count the
     * range reply gave, and the mask is that many bits. */
    size_t mask_bytes = (last + 7) / 8;
    memset(c, 0, 16 + mask_bytes);
    put32(c, DIAG_LOG_CONFIG_F);
    put32(c + 4, LOG_CONFIG_SET_MASK_OP);
    put32(c + 8, LOG_EQUIP_ID_LTE_NR);
    put32(c + 12, last);
    for (int i = 0; i < NCODES; i++) {
        uint32_t item = CODES[i] & 0x0FFF;
        if (item < last) c[16 + item / 8] |= (uint8_t)(1u << (item % 8));
        else fprintf(stderr, "qbm-diagd: 0x%04X is beyond this modem's log range\n", CODES[i]);
    }
    diag_send(fd, c, 16 + mask_bytes);
    if (!wait_cfg_reply(fd, LOG_CONFIG_SET_MASK_OP, 1500)) {
        note("no reply to the log mask (is this the DIAG port?)");
        return 0;
    }
    uint32_t status = cfg_reply_len >= 12 ? le32(cfg_reply + 8) : 1;
    char m[96];
    if (status != 0) { snprintf(m, sizeof m, "log mask rejected, status %u", (unsigned)status); note(m); return 0; }
    snprintf(m, sizeof m, "subscribed to %d log codes (equipment 0xB, last item 0x%X)", NCODES, (unsigned)last);
    note(m);
    return 1;
}

/* ── Opening the port ────────────────────────────────────────────────────── */

static char active_dev[64] = "";

/* Prints msg only when it differs from the previous one: a missing modem
 * would otherwise log the same line every few seconds. */
static void note(const char *msg) {
    static char last[160];
    if (strcmp(msg, last)) {
        fprintf(stderr, "qbm-diagd: %s\n", msg);
        snprintf(last, sizeof last, "%s", msg);
    }
}

static int open_port(const char *want) {
    char msg[160];
    if (strcmp(want, "auto")) {
        int fd = tty_open(want);
        if (fd < 0) { snprintf(msg, sizeof msg, "open %s: %s (retrying)", want, strerror(errno)); note(msg); return -1; }
        if (!subscribe(fd, 0)) { close(fd); snprintf(msg, sizeof msg, "%s: no DIAG answer (retrying)", want); note(msg); return -1; }
        snprintf(active_dev, sizeof active_dev, "%s", want);
        return fd;
    }
    int seen = 0;
    for (int i = 0; i < USB_PROBE_MAX && !stop_flag; i++) {
        char dev[32];
        snprintf(dev, sizeof dev, "/dev/ttyUSB%d", i);
        if (access(dev, F_OK) != 0) continue;
        seen++;
        int fd = tty_open(dev);
        if (fd < 0) continue;
        if (subscribe(fd, 1)) {
            snprintf(active_dev, sizeof active_dev, "%s", dev);
            snprintf(msg, sizeof msg, "auto: DIAG on %s", dev);
            note(msg);
            return fd;
        }
        close(fd);
    }
    note(seen ? "auto: no /dev/ttyUSB* answered as DIAG (retrying)" : "auto: no /dev/ttyUSB* ports (retrying)");
    return -1;
}

#include "qbm-mtk.c"   /* MediaTek (FM350) backend */

/* ── Which modem ─────────────────────────────────────────────────────────── */

enum { MODEM_AUTO, MODEM_QCOM, MODEM_MTK };
static char modem_model[64] = "";

/* A sysfs attribute of /dev/ttyUSBn's interface (up = "..") or its USB device ("../.."). */
static int tty_attr(int n, const char *up, const char *attr, char *v, size_t cap) {
    char p[128];
    snprintf(p, sizeof p, "/sys/class/tty/ttyUSB%d/device/%s/%s", n, up, attr);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    int ok = fgets(v, (int)cap, f) != NULL;
    fclose(f);
    if (ok) v[strcspn(v, "\r\n")] = 0;
    return ok;
}

static int mediatek_ports(void) {
    char v[16];
    for (int i = 0; i < USB_PROBE_MAX; i++)
        if (tty_attr(i, "../..", "idVendor", v, sizeof v) && !strcmp(v, "0e8d")) return 1;
    return 0;
}

/* Sends AT+CGMM on dev; the model line of the reply (empty if none). */
static int at_model(const char *dev, char *model, size_t cap) {
    int fd = open(dev, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return 0;
    struct termios t;
    if (tcgetattr(fd, &t) == 0) {
        cfmakeraw(&t);
        cfsetispeed(&t, B115200);
        cfsetospeed(&t, B115200);
        t.c_cflag |= CLOCAL | CREAD;
        tcsetattr(fd, TCSANOW, &t);
    }
    tcflush(fd, TCIOFLUSH);
    const char *q = "\r\nAT+CGMM\r\n";
    char buf[512];
    size_t len = 0;
    int done = 0;
    if (tty_write_all(fd, (const uint8_t *)q, strlen(q)) == 0) {
        long end = now_ms() + 1500;
        while (now_ms() < end && !done && len < sizeof buf - 1) {
            struct pollfd pf = { fd, POLLIN, 0 };
            if (poll(&pf, 1, 100) <= 0) continue;
            ssize_t k = read(fd, buf + len, sizeof buf - 1 - len);
            if (k <= 0) continue;
            len += (size_t)k;
            buf[len] = 0;
            done = strstr(buf, "\r\nOK") || strstr(buf, "ERROR");
        }
    }
    close(fd);
    buf[len] = 0;
    if (!strstr(buf, "OK")) return 0;
    /* the first line that is neither the echo, a result nor an unsolicited +XXX: report */
    model[0] = 0;
    for (char *line = strtok(buf, "\r\n"); line; line = strtok(NULL, "\r\n")) {
        if (!*line || !strncmp(line, "AT", 2) || !strcmp(line, "OK") || line[0] == '+') continue;
        snprintf(model, cap, "%s", line);
        break;
    }
    return 1;
}

/* auto: Qualcomm unless a MediaTek USB device is present and its AT port names an FM350. */
static int detect_modem(const char *at) {
    if (!mediatek_ports()) return MODEM_QCOM;
    char model[64] = "", v[16];
    int answered = 0;
    if (strcmp(at, "auto")) answered = at_model(at, model, sizeof model);
    for (int pass = 0; pass < 2 && !answered; pass++) {
        for (int i = 0; i < USB_PROBE_MAX && !answered; i++) {
            if (!tty_attr(i, "../..", "idVendor", v, sizeof v) || strcmp(v, "0e8d")) continue;
            /* first the FM350's AT interface (06), then any MediaTek port but the debug port (09) */
            int intf = tty_attr(i, "..", "bInterfaceNumber", v, sizeof v) ? (int)strtol(v, NULL, 16) : -1;
            if (pass == 0 ? intf != 6 : intf == 6 || intf == 9) continue;
            char dev[32];
            snprintf(dev, sizeof dev, "/dev/ttyUSB%d", i);
            answered = at_model(dev, model, sizeof model);
        }
    }
    if (!answered) { note("MediaTek modem: no AT port answered AT+CGMM (retrying)"); return MODEM_AUTO; }
    snprintf(modem_model, sizeof modem_model, "%s", model);
    if (strstr(model, "FM350")) return MODEM_MTK;
    char msg[128];
    snprintf(msg, sizeof msg, "MediaTek modem %s is not supported (only the FM350)", model[0] ? model : "(no model)");
    note(msg);
    return MODEM_AUTO;
}

/* ── Client mode ─────────────────────────────────────────────────────────── */

static int query(const char *req) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return 1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    size_t n = strlen(SOCKET_NAME);
    memcpy(a.sun_path + 1, SOCKET_NAME, n);
    if (connect(fd, (struct sockaddr *)&a, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n)) < 0) {
        printf("{\"ok\":false,\"error\":\"not running\"}\n");
        close(fd);
        return 1;
    }
    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    if (write_all(fd, req, strlen(req)) < 0 || write_all(fd, "\n", 1) < 0) { close(fd); return 1; }
    static char buf[262144];
    size_t len = 0;
    while (len < sizeof buf - 1) {
        ssize_t k = read(fd, buf + len, sizeof buf - 1 - len);
        if (k <= 0) break;
        len += (size_t)k;
        if (memchr(buf, '\n', len)) break;
    }
    close(fd);
    if (len == 0) { printf("{\"ok\":false,\"error\":\"no reply\"}\n"); return 1; }
    fwrite(buf, 1, len, stdout);
    return 0;
}

/* ── main ────────────────────────────────────────────────────────────────── */

int main(int argc, char **argv) {
    const char *dev = USB_DEFAULT_DEV, *at = "auto", *replay = NULL;
    int replay_log = -1;
    long app_uid = 0, watchdog = 0, print_ms = 0;
    int want_modem = MODEM_AUTO;
    for (int i = 1; i < argc; i++) {
        if (i + 1 < argc && !strcmp(argv[i], "-dev")) dev = argv[++i];
        else if (i + 1 < argc && !strcmp(argv[i], "-uid")) app_uid = strtol(argv[++i], NULL, 10);
        else if (i + 1 < argc && !strcmp(argv[i], "-watchdog")) watchdog = strtol(argv[++i], NULL, 10);
        else if (i + 1 < argc && !strcmp(argv[i], "-print")) print_ms = strtol(argv[++i], NULL, 10);
        else if (i + 1 < argc && !strcmp(argv[i], "-query")) return query(argv[i + 1]);
        else if (i + 1 < argc && !strcmp(argv[i], "-modem")) {
            i++;
            want_modem = !strcmp(argv[i], "mtk") ? MODEM_MTK : !strcmp(argv[i], "qcom") ? MODEM_QCOM : MODEM_AUTO;
        }
        else if (i + 1 < argc && !strcmp(argv[i], "-at")) at = argv[++i];
        else if (i + 1 < argc && !strcmp(argv[i], "-mtk-trim")) mtk_trim = atoi(argv[++i]) != 0;
        else if (i + 1 < argc && !strcmp(argv[i], "-mtk-replay")) replay = argv[++i];
        else if (i + 1 < argc && !strcmp(argv[i], "-mtk-log")) replay_log = (int)strtol(argv[++i], NULL, 0);
        else if (i + 1 < argc && !strcmp(argv[i], "-mtk-packets")) return mtk_dump_packets(argv[i + 1]);
        else {
            fprintf(stderr, "usage: %s [-dev /dev/ttyUSB0] [-uid N] [-watchdog MS] [-print MS]\n"
                            "          [-modem auto|qcom|mtk] [-at /dev/ttyUSB2] [-mtk-trim 0|1]\n"
                            "       %s -query '{\"cmd\":\"stats\",\"sim\":1}'\n"
                            "       %s -mtk-replay CAPTURE [-print MS]\n", argv[0], argv[0], argv[0]);
            return 2;
        }
    }
    if (replay) return mtk_replay(replay, 5000000, print_ms > 0 ? print_ms : 1000, replay_log);
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
    crc_init();
    lib_route = "usb";

    /* Bind first: a second instance fails here, before touching DIAG. */
    int lfd = listen_socket();
    if (lfd < 0) { fprintf(stderr, "socket %s: %s\n", SOCKET_NAME, strerror(errno)); return 1; }

    int tfd = -1, modem = want_modem;
    long next_open = 0, last_request = now_ms(), last_print = now_ms();
    window_start = now_ms();
    int cfd = -1;
    char in[512];
    size_t in_len = 0;
    static char out[16384];

    while (!stop_flag && (watchdog <= 0 || now_ms() - last_request < watchdog)) {
        if (tfd < 0 && now_ms() >= next_open) {
            /* the modem is identified again after each loss of the port */
            modem = want_modem != MODEM_AUTO ? want_modem : detect_modem(at);
            if (modem == MODEM_MTK) {
                tfd = mtk_open(dev);
                if (tfd >= 0) mtk_connect(tfd);
            } else if (modem == MODEM_QCOM) {
                tfd = open_port(dev);
            }
            if (tfd < 0) next_open = now_ms() + 3000;
        }

        struct pollfd pf[3] = { { lfd, POLLIN, 0 }, { tfd, POLLIN, 0 }, { cfd, POLLIN, 0 } };
        if (poll(pf, 3, 250) < 0 && errno != EINTR) break;   /* fd -1 entries are ignored */

        if (tfd >= 0 && modem == MODEM_MTK) {
            if (((pf[1].revents & (POLLIN | POLLHUP | POLLERR)) && mtk_pump(tfd) < 0)) {
                char msg[160];
                snprintf(msg, sizeof msg, "%s went away, reopening", active_dev);
                note(msg);
                close(tfd);
                tfd = -1;
                next_open = now_ms() + 2000;
            } else {
                mtk_tick(tfd);
            }
        } else if (tfd >= 0 && (pf[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            if (tty_pump(tfd) < 0) {
                char msg[160];
                snprintf(msg, sizeof msg, "%s went away, reopening", active_dev);
                note(msg);
                close(tfd);
                tfd = -1;
                next_open = now_ms() + 2000;
            }
        }

        if (print_ms > 0 && now_ms() - last_print >= print_ms) {
            last_print = now_ms();
            int n = modem == MODEM_MTK ? write_stats_mtk(out, sizeof out, 1) : write_stats(out, sizeof out, 1);
            if (n > 0) { fwrite(out, 1, (size_t)n, stdout); fflush(stdout); }
        }

        if (pf[0].revents & POLLIN) {
            int nfd = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
            if (nfd >= 0) {
                struct ucred cr;
                socklen_t cl = sizeof cr;
                if (getsockopt(nfd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) < 0 || (cr.uid != 0 && (long)cr.uid != app_uid)) {
                    close(nfd);
                } else {
                    if (cfd >= 0) close(cfd);           /* newest client wins */
                    cfd = nfd;
                    in_len = 0;
                }
            }
        }
        if (cfd >= 0 && (pf[2].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t k = read(cfd, in + in_len, sizeof in - 1 - in_len);
            if (k <= 0) { close(cfd); cfd = -1; continue; }
            in_len += (size_t)k;
            in[in_len] = 0;
            char *nl;
            while ((nl = strchr(in, '\n')) != NULL) {
                *nl = 0;
                if (strstr(in, "\"stats\"")) {
                    last_request = now_ms();
                    const char *n_sim = strstr(in, "\"sim\":");
                    int s = n_sim ? atoi(n_sim + 6) : 1;
                    int n = modem == MODEM_MTK ? write_stats_mtk(out, sizeof out, s) : write_stats(out, sizeof out, s);
                    if (n < 0 || write_all(cfd, out, (size_t)n) < 0) { close(cfd); cfd = -1; break; }
                } else if (strstr(in, "\"stop\"")) {
                    write_all(cfd, "{\"ok\":true,\"stopping\":true}\n", 28);
                    stop_flag = 1;
                } else if (strstr(in, "\"mtk_restore\"")) {
                    /* "Restore MTK DIAG profile": the modem's original trace filters, once the
                     * session is up (and no more trimming in this run) */
                    const char *r = modem == MODEM_MTK && tfd >= 0 ? "{\"ok\":true,\"restoring\":true}\n"
                                                                   : "{\"ok\":false,\"error\":\"no FM350 connected\"}\n";
                    if (modem == MODEM_MTK && tfd >= 0) mtk_restore_pending = 1;
                    write_all(cfd, r, strlen(r));
                } else if (strstr(in, "\"mtk_log\"")) {
                    /* debugging: the last decoded record of a code, e.g. "code":"0x9001" */
                    static char rec[262144];
                    const char *c = strstr(in, "\"code\"");
                    c = c ? strpbrk(c + 6, "0123456789") : NULL;
                    int n = c ? mtk_log_json(rec, sizeof rec, (uint16_t)strtol(c, NULL, 0))
                              : snprintf(rec, sizeof rec, "{\"ok\":false,\"error\":\"code: e.g. 0x9001\"}\n");
                    if (n > 0) write_all(cfd, rec, (size_t)n < sizeof rec ? (size_t)n : sizeof rec - 1);
                } else if (modem == MODEM_MTK) {
                    char msg[512];
                    int n = mtk_status(msg, sizeof msg, tfd >= 0);
                    if (n > 0) write_all(cfd, msg, (size_t)n < sizeof msg ? (size_t)n : sizeof msg - 1);
                } else {
                    char msg[320];
                    int n = snprintf(msg, sizeof msg, "{\"ok\":true,\"version\":\"%s\",\"modem\":\"%s\",\"model\":\"%s\",\"dev\":\"%s\",\"port_open\":%s,\"frames\":%ld,\"crc_errors\":%ld}\n",
                                     HELPER_VERSION, modem == MODEM_QCOM ? "qcom" : "unknown", modem_model,
                                     tfd >= 0 ? active_dev : dev, tfd >= 0 ? "true" : "false", n_frames, n_crc_err);
                    if (n > 0) write_all(cfd, msg, (size_t)n < sizeof msg ? (size_t)n : sizeof msg - 1);
                }
                size_t used = (size_t)(nl - in) + 1;
                memmove(in, nl + 1, in_len - used + 1);
                in_len -= used;
            }
            if (in_len >= sizeof in - 1) in_len = 0;    /* overlong line: drop */
        }
    }

    /* Leave the modem quiet: no log mask (Qualcomm) or session (MediaTek) after we go. */
    stopping = 1;
    if (tfd >= 0 && modem == MODEM_MTK) { mtk_send(tfd, mtk_host(0x2F, MTK_DISCONNECT, sizeof MTK_DISCONNECT)); usleep(100 * 1000); close(tfd); }
    else if (tfd >= 0) { log_mask_disable(tfd); usleep(100 * 1000); close(tfd); }
    if (cfd >= 0) close(cfd);
    close(lfd);
    return 0;
}
