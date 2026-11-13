/*
 * qbm-qmux.c: runs qcom-bandlockd on a USB modem (OpenWrt) instead of
 * Android. Built together with qcom-bandlockd_v5-0-0.c -DQBM_QMUX.
 *
 * qcom-bandlockd talks to the modem through aarch64 Linux syscalls of its
 * own (no libc) and QRTR sockets (AF_QIPCRTR), which only exist on SoCs
 * with an integrated modem. Under QBM_QMUX its syscall wrappers call
 * qbm_sys() here instead, which:
 *
 *   - performs ordinary syscalls through libc, so the daemon runs on any
 *     architecture (its numbers are the aarch64 ones; its timespec/timeval
 *     are 64-bit, converted here for 32-bit targets), and
 *   - emulates QRTR sockets on top of QMUX, the framing a USB modem's QMI
 *     port (/dev/cdc-wdm0, qmi_wwan) uses:
 *       QRTR NEW_LOOKUP to the control port  answered here: the service is
 *                                            "at" node 1, port service+1
 *       connect() to that port               QMI CTL allocates a client ID
 *                                            for the service
 *       sendto()/recvfrom()                  QMUX frames for that client;
 *                                            the QMI message bytes are the
 *                                            same as over QRTR
 *       close()                              QMI CTL releases the client ID
 *
 * The QMI port is shared with uqmi (OpenWrt's QMI data connection). The
 * cdc-wdm driver has one read buffer per device, so whoever reads takes the
 * message: the port is opened only while a client exists and is closed with
 * the last one, and a lock file keeps two qbm-qmi runs from overlapping.
 *
 * QBM_QMI_DEBUG=1 in the environment dumps every frame to stderr.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

/* The aarch64 syscall numbers qcom-bandlockd uses. */
enum {
    A_ppoll = 73, A_openat = 56, A_close = 57, A_read = 63, A_write = 64, A_exit = 93,
    A_nanosleep = 101, A_clock_gettime = 113, A_socket = 198, A_bind = 200, A_listen = 201,
    A_connect = 203, A_sendto = 206, A_recvfrom = 207, A_setsockopt = 208, A_getsockopt = 209,
    A_accept4 = 242,
};
#define Q_AF_QIPCRTR 42
#define QRTR_PORT_CTRL 0xFFFFFFFEu
#define QRTR_TYPE_NEW_SERVER 4u
#define QRTR_TYPE_NEW_LOOKUP 10u

struct ts64 { int64_t sec, nsec; };          /* the daemon's timespec64/timeval64 */
struct qrtr_addr { uint16_t family, pad; uint32_t node, port; };
struct qrtr_ctrl { uint32_t command, service, instance, node, port; };

#define VFD_BASE 0x7000                     /* virtual fds: never real ones */
#define VMAX 16
#define VQ 8                                /* frames held per virtual socket */
#define FRAME_MAX 4096

struct vsock {
    int used, timeout_ms, svc, cid;         /* cid -1: not connected */
    int have_ctrl;                          /* a lookup reply is waiting */
    struct qrtr_ctrl ctrl;
    int qn;
    uint16_t qlen[VQ];
    uint8_t q[VQ][FRAME_MAX];
};
static struct vsock vs[VMAX];

static const char *dev_path = "/dev/cdc-wdm0";
static int dev_fd = -1, lock_fd = -1, debug;
static uint8_t rbuf[2 * FRAME_MAX];
static size_t rlen;
static uint8_t ctl_txid;

void qbm_qmux_device(const char *path) { dev_path = path; }

static void dump(const char *dir, const uint8_t *p, size_t n) {
    if (!debug) return;
    fprintf(stderr, "qmux %s %zu:", dir, n);
    for (size_t i = 0; i < n && i < 256; i++) fprintf(stderr, " %02x", p[i]);
    fprintf(stderr, n > 256 ? " ...\n" : "\n");
}

static long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

static uint16_t le16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }

/* ── The QMI port ─────────────────────────────────────────────────────────── */

static int dev_open(void) {
    if (dev_fd >= 0) return 0;
    if (lock_fd < 0) {
        mkdir("/var/lock", 0755);
        lock_fd = open("/var/lock/qbm-qmi.lock", O_RDWR | O_CREAT | O_CLOEXEC, 0600);
        if (lock_fd >= 0) {
            long end = now_ms() + 8000;
            while (flock(lock_fd, LOCK_EX | LOCK_NB) < 0) {
                if (now_ms() > end) { close(lock_fd); lock_fd = -1; errno = EBUSY; return -1; }
                usleep(50 * 1000);
            }
        }
    }
    dev_fd = open(dev_path, O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOCTTY);
    if (dev_fd < 0) return -1;
    rlen = 0;
    return 0;
}

static void dev_close(void) {
    if (dev_fd >= 0) close(dev_fd);
    dev_fd = -1;
    if (lock_fd >= 0) { flock(lock_fd, LOCK_UN); close(lock_fd); lock_fd = -1; }
}

static int dev_write(const uint8_t *p, size_t n) {
    dump("tx", p, n);
    while (n > 0) {
        ssize_t k = write(dev_fd, p, n);
        if (k < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) { struct pollfd pf = { dev_fd, POLLOUT, 0 }; poll(&pf, 1, 100); continue; }
            return -1;
        }
        p += k; n -= (size_t)k;
    }
    return 0;
}

/* The next whole QMUX frame ([0x01][u16 len][flags][svc][cid][sdu]) within
 * ms, or 0. cdc-wdm returns one message per read; a stream (a test pty) is
 * handled too. Bytes that are not a frame start are dropped. */
static size_t dev_frame(uint8_t *out, int ms) {
    long end = now_ms() + ms;
    for (;;) {
        while (rlen > 0 && rbuf[0] != 0x01) memmove(rbuf, rbuf + 1, --rlen);
        if (rlen >= 3) {
            size_t total = (size_t)le16(rbuf + 1) + 1;
            if (total < 6 || total > FRAME_MAX) { memmove(rbuf, rbuf + 1, --rlen); continue; }
            if (rlen >= total) {
                memcpy(out, rbuf, total);
                memmove(rbuf, rbuf + total, rlen - total);
                rlen -= total;
                dump("rx", out, total);
                return total;
            }
        }
        long left = end - now_ms();
        if (left <= 0) return 0;
        struct pollfd pf = { dev_fd, POLLIN, 0 };
        int r = poll(&pf, 1, (int)left);
        if (r < 0 && errno != EINTR) return 0;
        if (r > 0) {
            if (pf.revents & (POLLERR | POLLHUP | POLLNVAL)) return 0;
            ssize_t k = read(dev_fd, rbuf + rlen, sizeof rbuf - rlen);
            if (k > 0) rlen += (size_t)k;
            else if (k == 0 || (errno != EAGAIN && errno != EINTR)) return 0;
            if (rlen == sizeof rbuf) rlen = 0;               /* garbage: start over */
        }
    }
}

static size_t qmux_frame(uint8_t *f, int svc, int cid, const uint8_t *sdu, size_t n) {
    size_t len = 5 + n;
    f[0] = 0x01; f[1] = (uint8_t)len; f[2] = (uint8_t)(len >> 8);
    f[3] = 0x00; f[4] = (uint8_t)svc; f[5] = (uint8_t)cid;
    memcpy(f + 6, sdu, n);
    return 6 + n;
}

/* Hands a service frame to the virtual socket of its client, if any. */
static void route(const uint8_t *f, size_t n) {
    int svc = f[4], cid = f[5];
    for (int i = 0; i < VMAX; i++) {
        struct vsock *v = &vs[i];
        if (!v->used || v->cid != cid || v->svc != svc) continue;
        if (v->qn < VQ) { v->qlen[v->qn] = (uint16_t)(n - 6); memcpy(v->q[v->qn], f + 6, n - 6); v->qn++; }
        return;
    }
    /* Another program's client (uqmi), or a broadcast indication: dropped. */
}

/* One QMI CTL request; the reply's TLV 0x01 value is copied to out. */
static int ctl(uint16_t msg, const uint8_t *tlv1, uint16_t tlv1_len, uint8_t *out, int out_cap) {
    uint8_t sdu[64], f[FRAME_MAX];
    uint8_t txid = ++ctl_txid ? ctl_txid : ++ctl_txid;
    sdu[0] = 0x00; sdu[1] = txid;
    sdu[2] = (uint8_t)msg; sdu[3] = (uint8_t)(msg >> 8);
    uint16_t tl = (uint16_t)(3 + tlv1_len);
    sdu[4] = (uint8_t)tl; sdu[5] = (uint8_t)(tl >> 8);
    sdu[6] = 0x01; sdu[7] = (uint8_t)tlv1_len; sdu[8] = (uint8_t)(tlv1_len >> 8);
    memcpy(sdu + 9, tlv1, tlv1_len);
    size_t n = qmux_frame(f, 0, 0, sdu, 9u + tlv1_len);
    if (dev_write(f, n) < 0) return -1;
    long end = now_ms() + 3000;
    for (;;) {
        long left = end - now_ms();
        if (left <= 0) { errno = ETIMEDOUT; return -1; }
        size_t k = dev_frame(f, (int)left);
        if (!k) { errno = ETIMEDOUT; return -1; }
        if (f[4] != 0) { route(f, k); continue; }
        /* CTL SDU: [flags][txid u8][msg][len][TLVs]; flags 1 = response */
        if (k < 12 || f[6] != 0x01 || f[7] != txid || le16(f + 8) != msg) continue;
        size_t end_t = 12 + le16(f + 10), p = 12, result_ok = 0;
        if (end_t > k) end_t = k;
        int got = 0;
        while (p + 3 <= end_t) {
            uint8_t id = f[p]; uint16_t l = le16(f + p + 1);
            if (p + 3 + l > end_t) break;
            if (id == 0x02 && l >= 4) result_ok = le16(f + p + 3) == 0;
            if (id == 0x01 && out) { memcpy(out, f + p + 3, l < out_cap ? l : (uint16_t)out_cap); got = 1; }
            p += 3u + l;
        }
        if (!result_ok) { errno = EPROTO; return -1; }
        return out && !got ? -1 : 0;
    }
}

static int cid_alloc(int svc) {
    uint8_t s = (uint8_t)svc, r[2];
    if (ctl(0x0022, &s, 1, r, 2) < 0) return -1;
    return r[0] == s ? r[1] : -1;
}

static void cid_release(int svc, int cid) {
    uint8_t t[2] = { (uint8_t)svc, (uint8_t)cid };
    ctl(0x0023, t, 2, NULL, 0);
}

static int open_clients(void) {
    int n = 0;
    for (int i = 0; i < VMAX; i++) n += vs[i].used && vs[i].cid >= 0;
    return n;
}

/* ── Virtual QRTR sockets ─────────────────────────────────────────────────── */

static struct vsock *vget(long long fd) {
    if (fd < VFD_BASE || fd >= VFD_BASE + VMAX || !vs[fd - VFD_BASE].used) return NULL;
    return &vs[fd - VFD_BASE];
}

static long long v_socket(void) {
    for (int i = 0; i < VMAX; i++) if (!vs[i].used) {
        memset(&vs[i], 0, sizeof vs[i]);
        vs[i].used = 1; vs[i].cid = -1; vs[i].timeout_ms = 3000;
        return VFD_BASE + i;
    }
    return -EMFILE;
}

static long long v_close(struct vsock *v) {
    if (v->cid >= 0 && dev_fd >= 0) cid_release(v->svc, v->cid);
    v->used = 0; v->cid = -1;
    if (!open_clients()) dev_close();
    return 0;
}

static long long v_connect(struct vsock *v, const struct qrtr_addr *a) {
    if (a->port == 0 || a->port == QRTR_PORT_CTRL) return -EINVAL;
    if (v->cid >= 0) return -EISCONN;
    if (dev_open() < 0) return -errno;
    int svc = (int)a->port - 1, cid = cid_alloc(svc);
    if (cid < 0) {
        if (debug) fprintf(stderr, "qmux: no client ID for service 0x%02x\n", svc);
        if (!open_clients()) dev_close();
        return -ECONNREFUSED;
    }
    v->svc = svc; v->cid = cid;
    return 0;
}

static long long v_sendto(struct vsock *v, const uint8_t *buf, size_t n, const struct qrtr_addr *a) {
    if (a && a->port == QRTR_PORT_CTRL) {
        struct qrtr_ctrl q;
        if (n < sizeof q) return -EINVAL;
        memcpy(&q, buf, sizeof q);
        if (q.command == QRTR_TYPE_NEW_LOOKUP) {
            v->ctrl.command = QRTR_TYPE_NEW_SERVER;
            v->ctrl.service = q.service; v->ctrl.instance = q.instance;
            v->ctrl.node = 1; v->ctrl.port = q.service + 1;
            v->have_ctrl = 1;
        }
        return (long long)n;
    }
    if (v->cid < 0) return -ENOTCONN;
    if (n + 6 > FRAME_MAX) return -EMSGSIZE;
    uint8_t f[FRAME_MAX];
    size_t k = qmux_frame(f, v->svc, v->cid, buf, n);
    return dev_write(f, k) < 0 ? -errno : (long long)n;
}

static long long v_recvfrom(struct vsock *v, uint8_t *buf, size_t cap) {
    if (v->have_ctrl) {
        v->have_ctrl = 0;
        size_t n = cap < sizeof v->ctrl ? cap : sizeof v->ctrl;
        memcpy(buf, &v->ctrl, n);
        return (long long)n;
    }
    if (v->cid < 0) return -EAGAIN;                   /* lookup socket: no more servers */
    long end = now_ms() + (v->timeout_ms > 0 ? v->timeout_ms : 3000);
    for (;;) {
        if (v->qn) {
            size_t n = v->qlen[0] < cap ? v->qlen[0] : cap;
            memcpy(buf, v->q[0], n);
            memmove(v->qlen, v->qlen + 1, sizeof v->qlen[0] * (VQ - 1));
            memmove(v->q, v->q + 1, sizeof v->q[0] * (VQ - 1));
            v->qn--;
            return (long long)n;
        }
        long left = end - now_ms();
        if (left <= 0 || dev_fd < 0) return -EAGAIN;
        uint8_t f[FRAME_MAX];
        size_t k = dev_frame(f, (int)left);
        if (!k) return -EAGAIN;
        if (f[4] != 0) route(f, k);
    }
}

static void release_all(void) {
    for (int i = 0; i < VMAX; i++) if (vs[i].used) v_close(&vs[i]);
    dev_close();
}

static void on_signal(int s) { release_all(); signal(s, SIG_DFL); raise(s); }

void qbm_qmux_init(void) {
    debug = getenv("QBM_QMI_DEBUG") != NULL;
    atexit(release_all);
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
}

/* ── Syscalls ─────────────────────────────────────────────────────────────── */

static long long ret(long long x) { return x < 0 ? -(long long)errno : x; }
#define R(x) ret((long long)(x))      /* evaluates x once */
#define P(x) ((void *)(intptr_t)(x))

long long qbm_sys(long long n, long long a, long long b, long long c, long long d, long long e, long long f) {
    struct vsock *v;
    switch (n) {
    case A_read:  return R(read((int)a, P(b), (size_t)c));
    case A_write: return R(write((int)a, P(b), (size_t)c));
    case A_openat: return R(openat((int)a, P(b), (int)c | O_CLOEXEC, (mode_t)d));
    case A_close:
        if ((v = vget(a))) return v_close(v);
        return R(close((int)a));
    case A_exit: exit((int)a);
    case A_nanosleep: {
        const struct ts64 *t = P(a);
        struct timespec ts = { (time_t)t->sec, (long)t->nsec };
        return R(nanosleep(&ts, NULL));
    }
    case A_clock_gettime: {
        struct timespec ts;
        if (clock_gettime((clockid_t)a, &ts) < 0) return -errno;
        struct ts64 *t = P(b);
        t->sec = ts.tv_sec; t->nsec = ts.tv_nsec;
        return 0;
    }
    case A_ppoll: {
        const struct ts64 *t = P(c);
        int ms = t ? (int)(t->sec * 1000 + t->nsec / 1000000) : -1;
        return R(poll(P(a), (nfds_t)b, ms));          /* struct pollfd: same layout */
    }
    case A_socket:
        if (a == Q_AF_QIPCRTR) return v_socket();
        return R(socket((int)a, (int)b, (int)c));
    case A_connect:
        if ((v = vget(a))) return v_connect(v, P(b));
        return R(connect((int)a, P(b), (socklen_t)c));
    case A_setsockopt:
        if ((v = vget(a))) {
            if (b == SOL_SOCKET && c == SO_RCVTIMEO && e >= (long long)sizeof(struct ts64)) {
                const struct ts64 *t = P(d);
                v->timeout_ms = (int)(t->sec * 1000 + t->nsec / 1000);   /* timeval: usec */
            }
            return 0;
        }
        if (b == SOL_SOCKET && (c == SO_RCVTIMEO || c == SO_SNDTIMEO) && e == (long long)sizeof(struct ts64)) {
            const struct ts64 *t = P(d);
            struct timeval tv = { (time_t)t->sec, (suseconds_t)t->nsec };
            return R(setsockopt((int)a, (int)b, (int)c, &tv, sizeof tv));
        }
        return R(setsockopt((int)a, (int)b, (int)c, P(d), (socklen_t)e));
    case A_getsockopt: {
        socklen_t l = (socklen_t)*(long long *)P(e);
        int r = getsockopt((int)a, (int)b, (int)c, P(d), &l);
        *(long long *)P(e) = l;
        return R(r);
    }
    case A_sendto:
        if ((v = vget(a))) return v_sendto(v, P(b), (size_t)c, P(e));
        return R(sendto((int)a, P(b), (size_t)c, (int)d, P(e), (socklen_t)f));
    case A_recvfrom:
        if ((v = vget(a))) return v_recvfrom(v, P(b), (size_t)c);
        return R(recvfrom((int)a, P(b), (size_t)c, (int)d, P(e), P(f)));
    case A_bind:   return R(bind((int)a, P(b), (socklen_t)c));
    case A_listen: return R(listen((int)a, (int)b));
    case A_accept4: return R(accept4((int)a, P(b), P(c), (int)d));
    }
    return -ENOSYS;
}
