/*
 * qbm-diagd: QBM's DIAG helper (root, dynamic, separate from the static QMI
 * daemon). Subscribes to LTE PDSCH logs through diag-router's Diag Consumer
 * Interface (vendor libdiag), decodes them on the phone and serves a compact
 * per-carrier summary over an abstract socket.
 *
 * DCI only: never takes the on-device (MD) logging session, never touches
 * the main log masks and never switches logging modes, so other DIAG tools
 * and USB diag keep working. Read-only towards the radio.
 *
 *   qbm-diagd -uid <app uid>      (no LD_LIBRARY_PATH: see DiagClient)
 *
 * Socket: abstract "qbm_diagd", one NDJSON request per line, only from <app
 * uid> (SO_PEERCRED):
 *   {"cmd":"stats","sim":N} -> SIM N's per-carrier summary since the previous
 *                       "stats" (N 1 or 2, default 1)
 *   {"cmd":"stop"}   -> unsubscribe, release and exit
 * Without a "stats" request for WATCHDOG_MS the helper unsubscribes and exits
 * by itself, so it never keeps logging after the app goes away.
 *
 * Logs (every version decoded through a layout table in qbm_layouts.h):
 *   LTE 0xB173 PDSCH Stat Indication: per TB MCS, modulation, RBs, layers,
 *          CRC, TB size, carrier slot
 *       0xB126 PDSCH Demapper Config: TX/RX antennas, rank, scheme
 *       0xB139 PUSCH Tx Report: UL TB size and carrier slot
 *       0xB184 CA Metrics: PCell and SCell EARFCN/PCI/band/BW/state per slot
 *       0xB193 Serving Cell Meas Response, subpacket 0x19: per-carrier
 *          RSRP/RSRQ/SINR and which carrier each slot is (serving records)
 *       0xB168 Random Access MSG2: the RAR's timing advance (the "ta" of
 *          the stats JSON, raw index; see b168())
 *   NR  0xB883 UL physical channel schedule, 0xB887 PDSCH status, 0xB97F
 *       measurements, 0xB8D8 SNR, 0xB8A7 CSF, 0xB825 RRC configuration,
 *       0xB823 serving cell (see below), 0xB88A RACH Attempt: the RAR's
 *       timing advance (the "nr_ta" of the stats JSON; see b88a())
 *   NR on X65 (no B8D8): 0xB8DD serving SNR (B8D8's successor), 0xB96A
 *       per-beam measurements (strongest beam's SNR)
 *   NR  0xB821 RRC OTA: reconfigurations, decoded (rrc_csi.c) for each
 *       cell's configured CSI-RS ports ("csi_ports")
 * NR SINR, per carrier: "sinr" is SS-SINR (B8D8/B8DD SSB records, best RX
 * chain; matches the UE's own MeasurementReports), "csinr" is CSI-SINR
 * (TRS records, best RX chain), "bsinr" the strongest SSB beam's SNR (B96A,
 * X65).
 * Checked on X55, X60, X65, X70 (SM8550), X75 and X85 logs, 2026-10-12.
 * Only C-RNTI and SPS-C-RNTI grants are counted (no paging/SI/RA).
 */
#include <android/dlext.h>
#include <dlfcn.h>
#include <math.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "qbm_layouts.h"   /* generated per-version layouts */
#include "rrc_csi.h"       /* CSI-RS ports from RRC reconfigurations */

#define HELPER_VERSION "1.22.12"
#define SOCKET_NAME "qbm_diagd"
#define WATCHDOG_MS 4000              /* app polls about every 1-1.5 s */
#define DIAG_PROC_MSM 0
#define DIAG_CON_MPSS (1 << 1)
#define DCI_NO_ERROR 1001
#define SLOTS 8

typedef int (*lsm_init_t)(void *);
typedef int (*set_version_t)(int, int);
typedef int (*lsm_deinit_t)(void);
typedef int (*reg_client_t)(int *, uint16_t *, int, void *);
typedef int (*rel_client_t)(int *);
typedef int (*reg_stream_t)(int, void (*)(unsigned char *, int), void (*)(unsigned char *, int));
typedef int (*log_cfg_t)(int, int, uint16_t *, int);

static uint16_t CODES[] = { 0xB173, 0xB126, 0xB139, 0xB184, 0xB193, 0xB168, /* LTE */
                            0xB883, 0xB887, 0xB97F, 0xB8D8, 0xB8A7, 0xB825, 0xB823, 0xB88A, /* NR */
                            0xB8DD, 0xB96A,                             /* NR, X65 */
                            0xB821 };                                   /* NR RRC */
#define NCODES 17

/* ── Per-carrier window (reset on every "stats") ─────────────────────────── */

struct slot {
    /* B173, C/SPS-C RNTI TBs only */
    /* mcs_sum/mcs_tb leave out reserved-index retransmissions (see b173());
     * mcs_all_sum is over every TB, as before 1.22.3, kept for comparison. */
    long tb, fail, bytes_pass, bytes_all, mcs_sum, mcs_tb, mcs_all_sum, rb_sum, mod[5];
    long recs, layers_sum;
    /* B139 PUSCH TBs, including retransmissions */
    long ul_tb, ul_bytes;
    /* B126 */
    long n126, rank[4], scheme[16];
    int tx, rx;
    /* B193, means over the window */
    double rsrp_sum, rsrq_sum, sinr_sum;
    long n_rsrp, n_rsrq, n_sinr;
};

/* B184: the latest carrier list. */
struct ca_cell { int known; uint32_t earfcn; int pci, band, bw_raw, state; };

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/* Dual SIM: with the DCI extended header (diag_dci_set_version 1) every log
 * names its subscription (1 = SIM 1, 2 = SIM 2), and each SIM keeps its own
 * state. Per-SIM variables are arrays over SIMS, used through a macro that
 * picks the current SIM, `sim` (set under `lock` by on_log and write_stats).
 * Without the header (older libdiag) everything is SIM 1. */
#define SIMS 2
static int sim = 0;
static int sim_tags = 0;               /* the extended header was granted */

static struct slot win_s[SIMS][SLOTS];
#define win (win_s[sim])
static struct ca_cell ca_s[SIMS][SLOTS];   /* [0] = PCell, [n] = SCell n */
#define ca (ca_s[sim])
static int ca_seen_s[SIMS];
#define ca_seen (ca_seen_s[sim])
/* A SIM that sent no log for SILENT_MS is off or out of service (an idle
 * SIM still measures every paging cycle, <= 2.56 s): its last carrier lists
 * are not shown. */
#define SILENT_MS 10000
static long last_log_s[SIMS];
#define last_log_ms (last_log_s[sim])
static long n_logs = 0;
static const char *lib_route = "default";   /* how libdiag was opened: "default" or "sphal" */
static volatile int stopping = 0;
static volatile sig_atomic_t stop_flag = 0;

static void on_signal(int s) { (void)s; stop_flag = 1; }
static long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000; }
static uint16_t le16(const uint8_t *p) { return p[0] | (p[1] << 8); }
static uint32_t le32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* Little-endian bit field (bit 0 = LSB of byte 0), as in the Qualcomm ICD. */
static uint32_t bits(const uint8_t *p, int off, int len) {
    uint32_t v = 0;
    for (int i = 0; i < len; i++) {
        int b = off + i;
        v |= (uint32_t)((p[b >> 3] >> (b & 7)) & 1) << i;
    }
    return v;
}

/* Reads a generated { bit offset, width } position; width 0 = absent. */
static uint32_t at(const uint8_t *p, struct pos f) { return f.bits ? bits(p, f.off, f.bits) : 0; }
/* Whether a position lies within an n-byte buffer. */
static int fits(struct pos f, int n) { return f.off + f.bits <= n * 8; }

/* The layout for version v in a generated table, or NULL (unsupported). */
#define FIND_LAYOUT(table, v, out) do { \
        (out) = NULL; \
        for (size_t i_ = 0; i_ < sizeof (table) / sizeof (table)[0]; i_++) if ((table)[i_].ver == (v)) (out) = &(table)[i_]; \
    } while (0)

/* NR logs start with their version: minor (16 bits), then major. */
static uint32_t nr_ver(const uint8_t *p) { return (uint32_t)le16(p + 2) << 16 | le16(p); }

static int mod_index(int raw) {     /* 2,4,6,8,10 bits/symbol -> QPSK..1024QAM */
    switch (raw) { case 2: return 0; case 4: return 1; case 6: return 2; case 8: return 3; case 10: return 4; }
    return -1;
}

/* ── Decoders (called with lock held) ────────────────────────────────────── */

/* Versions 3 and 4 have variable-size PCell records. Both have a six-byte
 * record header and six-byte TBs; v4 adds a two-byte PMCH/area trailer and
 * 12 placeholder bytes for each absent slot below two TBs. */
static void b173_legacy(const uint8_t *p, int n) {
    int ver = p[0], count = p[1], pos = 4;
    for (int r = 0; r < count; r++) {
        if (pos + 6 > n) return;
        const uint8_t *rec = p + pos;
        int layers = rec[3], ntb = rec[4];
        if (ntb > 4) return;
        int rec_bytes = ver == 3 ? 6 + ntb * 6
                                 : 8 + ntb * 6 + (ntb < 2 ? (2 - ntb) * 12 : 0);
        if (pos + rec_bytes > n) return;
        struct slot *s = &win[0];
        int counted = 0;
        for (int t = 0; t < ntb && t < 2; t++) {
            const uint8_t *tb = rec + 6 + t * 6;
            int rnti = tb[1] & 0xf;
            if (rnti > 1) continue;
            int crc = tb[0] >> 7, size = (tb[2] | tb[3] << 8) & 0x3fff;
            s->tb++;
            if (!crc) s->fail++;
            s->bytes_all += size;
            if (crc) s->bytes_pass += size;
            s->mcs_sum += tb[4];
            s->rb_sum += ver == 3 ? rec[2] : tb[5];
            counted = 1;
        }
        if (counted) { s->recs++; s->layers_sum += layers; }
        pos += rec_bytes;
    }
}

/* LTE PDSCH Stat Indication: fixed-size records with up to two TB slots;
 * positions per version from qbm_layouts.h, except legacy v3/v4 above. */
static void b173(const uint8_t *p, int n) {
    if (n < 4) return;
    if (p[0] == 3 || p[0] == 4) { b173_legacy(p, n); return; }
    const struct b173_layout *L;
    FIND_LAYOUT(B173_LAYOUTS, p[0], L);
    if (!L) return;
    int count = at(p, L->rec_count), rec_bytes = L->rec_bits / 8, pos = L->rec_start / 8;
    for (int r = 0; r < count && pos + rec_bytes <= n; r++, pos += rec_bytes) {
        const uint8_t *rec = p + pos;
        int layers = at(rec, L->layers), ntb = at(rec, L->ntb), carrier = at(rec, L->carrier);
        if (ntb > 2) ntb = 2;
        if (carrier >= SLOTS) continue;
        struct slot *s = &win[carrier];
        int counted = 0;
        for (int t = 0; t < ntb; t++) {
            const uint8_t *tb = rec + (L->tb_start + t * L->tb_bits) / 8;
            int rnti = at(tb, L->rnti);
            if (rnti > 1) continue;                     /* C-RNTI / SPS-C-RNTI only */
            int crc = at(tb, L->crc);
            int size = at(tb, L->tb_size);
            s->tb++;
            if (!crc) s->fail++;
            s->bytes_all += size;
            if (crc) s->bytes_pass += size;
            /* MCS mean: LTE retransmissions mostly carry a reserved index
             * (29-31; 28 with QPSK on the 256QAM table) that gives only the
             * modulation. Up to 1.22.2 they were averaged in ("mcs_all" keeps
             * that figure); in the test logs they raised the mean by 0.1-1.9
             * on average per log. They still count for throughput and BLER. */
            int mcs = at(tb, L->mcs);
            int mod_raw = L->mod.bits ? (int)at(tb, L->mod) : 0;
            s->mcs_all_sum += mcs;
            if (mcs < 29 && !(mcs == 28 && mod_raw == 2)) {
                s->mcs_sum += mcs;
                s->mcs_tb++;
            }
            s->rb_sum += at(tb, L->rbs);
            if (L->mod.bits) {
                int m = mod_index(mod_raw);
                if (m >= 0) s->mod[m]++;
            }
            counted = 1;
        }
        if (counted) { s->recs++; s->layers_sum += layers; }
    }
}

/* LTE PUSCH scheduling records. PHY throughput counts every non-dropped TB;
 * retransmissions are traffic over the air and are deliberately included. */
static void b139(const uint8_t *p, int n) {
    if (n < 1) return;
    const struct b139_layout *L;
    FIND_LAYOUT(B139_LAYOUTS, p[0], L);
    if (!L || !fits(L->rec_count, n)) return;
    int count = at(p, L->rec_count);
    int start = L->rec_start / 8;
    int size = L->rec_bits / 8;
    if (!size || start > n || count > (n - start) / size) return;
    if (start + count * size != n) return;
    long add_tb[SLOTS] = { 0 };
    long add_bytes[SLOTS] = { 0 };
    for (int i = 0; i < count; i++) {
        const uint8_t *record = p + start + i * size;
        int carrier = L->carrier.bits ? at(record, L->carrier) : 0;
        if (carrier >= SLOTS || (L->drop.bits && at(record, L->drop))) continue;
        add_tb[carrier]++;
        add_bytes[carrier] += at(record, L->tb_size);
    }
    for (int i = 0; i < SLOTS; i++) {
        win[i].ul_tb += add_tb[i];
        win[i].ul_bytes += add_bytes[i];
    }
}

/* LTE PDSCH demapper configuration: one carrier per log, fixed-size records;
 * positions per version from qbm_layouts.h. */
static void b126(const uint8_t *p, int n) {
    if (n < 8) return;
    const struct b126_layout *L;
    FIND_LAYOUT(B126_LAYOUTS, p[0], L);
    if (!L) return;
    int carrier = L->carrier.bits ? at(p, L->carrier) : 0;
    int count = L->rec_count.bits ? at(p, L->rec_count) : 1;
    if (carrier >= SLOTS) return;
    static const int txmap[4] = { 1, 2, 4, 0 }, rxmap[4] = { 1, 2, 3, 4 };
    struct slot *s = &win[carrier];
    int size = L->rec_bits / 8, pos = L->rec_start / 8;
    for (int r = 0; r < count && pos + size <= n; r++, pos += size) {
        const uint8_t *rec = p + pos;
        if (at(rec, L->rnti) > 1) continue;             /* C-RNTI / SPS-C-RNTI only */
        s->n126++;
        s->rank[at(rec, L->rank)]++;
        s->scheme[at(rec, L->scheme)]++;
        int tx = txmap[at(rec, L->tx)];
        if (tx) s->tx = tx;
        s->rx = rxmap[at(rec, L->rx)];
    }
}

/* LTE CA metrics: the PCell and an SCell list; positions per version from
 * qbm_layouts.h. */
static void b184(const uint8_t *p, int n) {
    if (n < 2) return;
    const struct b184_layout *L;
    FIND_LAYOUT(B184_LAYOUTS, p[0], L);
    if (!L || L->sc_start / 8 > n) return;
    int count = at(p, L->sc_count), sc_bytes = L->sc_bits / 8;
    const uint8_t *pc = p + L->pcell_at / 8;
    memset(ca, 0, sizeof ca);
    ca[0].known = 1;
    ca[0].earfcn = at(pc, L->p_earfcn);
    ca[0].pci = at(pc, L->p_pci);
    ca[0].band = at(pc, L->p_band);
    ca[0].bw_raw = L->p_bw.bits ? (int)at(pc, L->p_bw) : -1;
    ca[0].state = 2;
    for (int i = 0; i < count && i + 1 < SLOTS; i++) {
        int off = L->sc_start / 8 + i * sc_bytes;
        if (off + sc_bytes > n) break;
        const uint8_t *c = p + off;
        /* The SCell's own carrier slot where the version has one: during an
         * SCell swap a lone SCell can be listed first with slot 2. */
        int slot = L->s_slot.bits ? (int)at(c, L->s_slot) : i + 1;
        if (slot < 1 || slot >= SLOTS) slot = i + 1;
        ca[slot].known = 1;
        ca[slot].earfcn = at(c, L->s_earfcn);
        ca[slot].pci = at(c, L->s_pci);
        ca[slot].band = at(c, L->s_band);
        ca[slot].bw_raw = L->s_bw.bits ? (int)at(c, L->s_bw) : -1;
        ca[slot].state = (int)at(c, L->s_state) + L->state_add; /* normalized: 0 absent, 1 deactivated, 2 activated */
    }
    ca_seen = 1;
}

/* ML1 measurement scales; null codes skipped. */
static int rsrp_db(int raw, double *v) { if (raw == 0xFFF || raw == 0x961) return 0; *v = -180.0 + raw / 16.0; return *v >= -180 && *v <= -30; }
static int rsrq_db(int raw, double *v) { if (raw == 0x1FF || raw == 0x281) return 0; *v = -30.0 + raw / 16.0; return *v >= -30 && *v <= 10; }

/* SINR in 0.1 dB steps from -20 dB (9-bit fields up to 30 dB, 10-bit up to 40 dB). */
static int snr_tenth_db(int raw, double high, double *v) {
    int max_code = (int)((high + 20.0) * 10);
    if (raw == 0x1FF || raw == max_code + 1) return 0;
    *v = -20.0 + raw / 10.0;
    return *v >= -20.0 && *v <= (high > 45.0 ? high : 45.0);
}

/* The combined value, else the best valid chain (absent chains skipped). */
static int best(const uint8_t *r, int mask, struct pos combined, int combined_add, const struct pos *chain, int (*conv)(int, double *), double *out) {
    int code = (int)at(r, combined);
    if (code != (1 << combined.bits) - 1 && conv(code + combined_add, out)) return 1;
    int have = 0;
    for (int i = 0; i < 4; i++) {
        double v;
        if ((mask & (1 << i)) && chain[i].bits && conv((int)at(r, chain[i]), &v) && (!have || v > *out)) { *out = v; have = 1; }
    }
    return have;
}

/* One packet's serving record per carrier slot (the strongest, as in the
 * Python parser), added to the window once the packet is read. */
struct meas { int have_rsrp, have_rsrq, have_sinr; double rsrp, rsrq, sinr; uint32_t earfcn; int pci; };

/* Which carrier each slot is, from B193's serving records. B184 is only
 * sent on CA events on some modems (X85: 19 in a whole drive log), so a
 * helper started after CA was set up may never see one; B193 arrives
 * several times a second. */
struct live_cell { uint32_t earfcn; int pci; long at_ms; };
static struct live_cell live_s[SIMS][SLOTS];
#define live (live_s[sim])
#define LIVE_MS 3000

/* Idle: the modem measures once per paging cycle (1.28 or 2.56 s), so many
 * 1 s windows have no sample. A row stays shown for HOLD_MS after its last
 * serving record and keeps each value's last mean that long, for the same
 * cell only (matching carriers still uses LIVE_MS). */
#define HOLD_MS 5000
struct held { double v; long at_ms; uint32_t arfcn; int pci; };
static struct held lte_held_s[SIMS][SLOTS][3], nr_held_s[SIMS][SLOTS][5];
#define lte_held (lte_held_s[sim])
#define nr_held (nr_held_s[sim])

/* The window's mean when it has samples (remembered for this cell), else the
 * remembered value while it's under HOLD_MS old and for the same cell. A
 * negative pci (cell unknown) never holds. */
static int held_value(struct held *h, double sum, long n, uint32_t arfcn, int pci, long now, double *v) {
    if (n) {
        *v = sum / n;
        *h = (struct held){ *v, now, arfcn, pci };
        return 1;
    }
    if (pci >= 0 && h->at_ms && now - h->at_ms < HOLD_MS && h->arfcn == arfcn && h->pci == pci) {
        *v = h->v;
        return 1;
    }
    return 0;
}

static void b193_cell(const struct b193_layout *L, const uint8_t *r, uint32_t earfcn, struct meas *pk) {
    if (L->serving.bits && !at(r, L->serving)) return;  /* serving cell records only (older: one cell, serving) */
    int slot = at(r, L->slot);                          /* absent before CA: the PCell */
    if (slot >= SLOTS) return;
    /* Chains outside the cell's valid-RX mask are skipped (zeros on X85). */
    int mask = L->valid_rx.bits ? (int)at(r, L->valid_rx) : 0xF;
    struct meas m = { 0 };
    m.earfcn = earfcn;
    m.pci = (int)at(r, L->pci);
    m.have_rsrp = best(r, mask, L->rsrp, L->rsrp_add, L->rsrp_ch, rsrp_db, &m.rsrp);
    m.have_rsrq = best(r, mask, L->rsrq, 0, L->rsrq_ch, rsrq_db, &m.rsrq);
    for (int i = 0; i < 4; i++) {
        if (!(mask & (1 << i)) || !L->snr_ch[i].bits) continue;
        double db;
        if (L->snr_linear) {                            /* v59: linear, referenced to 0xFD00 */
            uint32_t lin = at(r, L->snr_ch[i]);
            if (lin == 0) continue;
            db = 10.0 * log10(lin / 64768.0);
            if (db <= -30 || db >= 60) continue;
        } else if (!snr_tenth_db((int)at(r, L->snr_ch[i]), L->snr_ch[i].bits == 10 ? 40 : 30, &db)) {
            continue;
        }
        if (!m.have_sinr || db > m.sinr) { m.sinr = db; m.have_sinr = 1; }
    }
    /* Aliases of one carrier in a packet: keep the stronger RSRP. */
    struct meas *prev = &pk[slot];
    int seen = prev->have_rsrp || prev->have_rsrq || prev->have_sinr;
    if (!seen || !prev->have_rsrp || (m.have_rsrp && m.rsrp >= prev->rsrp)) *prev = m;
}

/* The end of a field in bits; 0 for an absent field. */
static int end_bit(struct pos f) { return f.bits ? f.off + f.bits : 0; }

/* The last bit a B193 cell record must hold for this layout's fields. */
static int b193_fields_end(const struct b193_layout *L) {
    int end = 0;
    struct pos single[] = { L->pci, L->slot, L->serving, L->valid_rx, L->rsrp, L->rsrq };
    for (int i = 0; i < 6; i++)
        if (end_bit(single[i]) > end) end = end_bit(single[i]);
    for (int i = 0; i < 4; i++) {
        if (end_bit(L->rsrp_ch[i]) > end) end = end_bit(L->rsrp_ch[i]);
        if (end_bit(L->rsrq_ch[i]) > end) end = end_bit(L->rsrq_ch[i]);
        if (end_bit(L->snr_ch[i]) > end) end = end_bit(L->snr_ch[i]);
    }
    return end;
}

/* The size of one cell record. A subpacket version can come in two record
 * sizes with the same field positions (v59: 164 bytes on one SM8550
 * firmware, 148 on another), so a cell list that splits evenly into
 * `count` records gives the size; the table's size is the fallback. */
static uint32_t b193_cell_bytes(const struct b193_layout *L, uint32_t list_bytes, uint32_t count) {
    uint32_t table = L->cell_bits / 8;
    if (count == 0 || list_bytes % count != 0) return table;
    uint32_t measured = list_bytes / count;
    return (int)(measured * 8) >= b193_fields_end(L) ? measured : table;
}

static void b193(const uint8_t *p, int n) {
    if (n < 4 || p[0] != 1) return;                     /* packet version 1 */
    int nsub = p[1], pos = 4;
    struct meas pk[SLOTS];
    memset(pk, 0, sizeof pk);
    for (int k = 0; k < nsub; k++) {
        if (pos + 4 > n) return;
        int id = p[pos], ver = p[pos + 1], size = le16(p + pos + 2);
        /* Branches disagree whether the size includes the 4-byte header.
         * The Python parser prefers the reading that leaves room for the
         * remaining headers, then "includes the header"; the shorter body
         * always leaves at least as much room, so that's "includes the
         * header" whenever it fits (seen on SM8550: v59, 176 -> 172). */
        int remaining = n - (pos + 4), blen;
        if (size >= 4 && size - 4 <= remaining) blen = size - 4;
        else if (size <= remaining) blen = size;
        else return;
        const uint8_t *body = p + pos + 4;
        pos += 4 + blen;
        if (id != 0x19) continue;
        const struct b193_layout *L;
        FIND_LAYOUT(B193_LAYOUTS, ver, L);
        if (!L || blen * 8 < L->cells_at || !fits(L->count, blen)) continue;
        /* Older versions have no cell list: the body is the one cell. */
        uint32_t count = L->count.bits ? at(body, L->count) : 1, earfcn = at(body, L->earfcn);
        uint32_t first = L->cells_at / 8;
        uint32_t cell = L->count.bits ? b193_cell_bytes(L, blen - first, count) : L->cell_bits / 8;
        for (uint32_t c = 0; c < count && first + (c + 1) * cell <= (uint32_t)blen; c++)
            b193_cell(L, body + first + c * cell, earfcn, pk);
    }
    for (int i = 0; i < SLOTS; i++) {
        struct slot *s = &win[i];
        if (pk[i].have_rsrp) { s->rsrp_sum += pk[i].rsrp; s->n_rsrp++; }
        if (pk[i].have_rsrq) { s->rsrq_sum += pk[i].rsrq; s->n_rsrq++; }
        if (pk[i].have_sinr) { s->sinr_sum += pk[i].sinr; s->n_sinr++; }
        if (pk[i].have_rsrp || pk[i].have_rsrq || pk[i].have_sinr) {
            live[i].earfcn = pk[i].earfcn;
            live[i].pci = pk[i].pci;
            live[i].at_ms = now_ms();
        }
    }
}

/* ── Timing Advance (random-access RAR) ──────────────────────────────────── */

/* The serving card shows the raw TA index: the LTE RAR TA is an 11-bit index
 * in 16Ts units (36.213), the NR one a 12-bit index (38.213). A TA belongs to
 * the cell it was received on, so it is shown only while that cell is still
 * the PCell; after a cell change the card shows "–" until the next random
 * access. */

/* A serving cell's identity, as the TA is tied to it. */
struct cell_id { int known; uint32_t arfcn; int pci; };

/* The last random access's TA and the cell it was tied to (bound = 0 until
 * the serving cell was known once after it arrived). */
struct ta {
    int have;
    int value;
    long ms;                /* when it arrived, or was last re-bound */
    int bound;
    struct cell_id cell;
};
static struct ta lte_ta_s[SIMS], nr_ta_s[SIMS];
#define lte_ta (lte_ta_s[sim])
#define nr_ta (nr_ta_s[sim])

/* A random access that completes a handover reports the target cell while the
 * measurements still name the old one for a moment: a value younger than
 * REBIND_MS moves to the new cell instead of being dropped. */
#define REBIND_MS 5000

static void ta_store(struct ta *t, int value) {
    t->have = 1;
    t->value = value;
    t->ms = now_ms();
    t->bound = 0;
}

/* Ties a stored TA to the current serving cell; returns whether it may be
 * shown. */
static int ta_bind(struct ta *t, struct cell_id serving, long now) {
    if (!t->have)
        return 0;
    if (!serving.known)
        return t->bound;                 /* keep the earlier tie until the cell is known */
    if (!t->bound) {                     /* first known cell after the value arrived */
        t->bound = 1;
        t->cell = serving;
        return 1;
    }
    if (t->cell.arfcn == serving.arfcn && t->cell.pci == serving.pci)
        return 1;
    if (now - t->ms <= REBIND_MS) {      /* handover just completed: follow it */
        t->cell = serving;
        t->ms = now;
        return 1;
    }
    t->have = 0;                         /* an older value belongs to the old cell */
    return 0;
}

/* LTE Random Access MSG2: the RAR's timing advance. Version 24 only (X60,
 * X70, X80 and X85 all send it; v1 never appeared in a log). A failed RACH
 * has the included bit clear; values past the 11-bit index maximum are the
 * modem's "not present" filler (0xFFFF). Only the PCell's (cell index 0):
 * an SCell's random access (connected mode, index 1+) has its own, usually
 * much smaller TA, which would otherwise replace the PCell's on its card. */
static void b168(const uint8_t *p, int n) {
    if (n < 12 || p[0] != 24) return;
    const uint8_t *b = p + 1;                            /* 11-byte body */
    if (bits(b, 24, 3) != 0) return;                     /* cell index: PCell only */
    if (!bits(b, 50, 1)) return;                         /* timing_advance_included */
    uint32_t ta = bits(b, 72, 16);
    if (ta > 1282) return;
    ta_store(&lte_ta, (int)ta);
}

/* NR RACH Attempt: the RAR's timing advance. The container layout differs
 * per log version (the message offsets move), so only TA positions validated
 * on real logs are decoded (each matched every RAR of its log). The message
 * bitmask says which of Msg1..Msg4 the record holds (bit 1 = Msg2): an
 * attempt that got no RAR has TA 0 there, which isn't a measurement. */
static const struct { uint32_t ver; int mask, off, bytes; } B88A_TA[] = {
    { 0x00020007, 23,  64, 108 },   /* 2.7, X60 */
    { 0x00030008, 16, 104, 160 },   /* 3.8, X65: Msg2's T_RNTI (102) returns as
                                     * Msg4's C-RNTI in every contention-based
                                     * access, and RA-RNTI fits Msg1 (38.321) */
    { 0x0003000A, 19, 120, 180 },   /* 3.10, X70: msg2 block at 112, TA 8 bytes in */
    { 0x0003000E, 19, 140, 196 },   /* 3.14, X75 (Quectel module): same checks as 3.8 */
    { 0x00030012, 19, 176, 232 },   /* 3.18, X80 */
    { 0x00030013, 19, 184, 240 },   /* 3.19, X85 */
};

static void b88a(const uint8_t *p, int n) {
    if (n < 4) return;
    uint32_t ver = nr_ver(p);
    for (size_t i = 0; i < sizeof B88A_TA / sizeof B88A_TA[0]; i++) {
        if (B88A_TA[i].ver != ver) continue;
        if (n < B88A_TA[i].bytes) return;
        if (!(p[B88A_TA[i].mask] & 2)) return;           /* no Msg2 (RAR) */
        uint32_t ta = le16(p + B88A_TA[i].off);
        if (ta > 3846) return;                           /* 12-bit index maximum */
        ta_store(&nr_ta, (int)ta);
        return;
    }
}

/* ── NR ──────────────────────────────────────────────────────────────────── */

/* Per NR carrier (cc_id 0-7, the modem's numbering shared by B825, B97F,
 * B8D8 and B8A7), reset on every "stats". */
struct nr_slot {
    /* B887, C-RNTI and CS-RNTI TBs only. mcs_sum/mcs_tb leave out
     * retransmissions signalled with a reserved MCS index (see b887());
     * mcs_all_sum is over every TB, as before 1.22.2, kept for comparison. */
    long tb, fail, bytes_pass, bytes_all, mcs_sum, mcs_tb, mcs_all_sum, rb_sum, layers_sum, mod[5], rx[4];
    /* B883 PUSCH TBs, including retransmissions */
    long ul_tb, ul_bytes;
    /* B97F serving cell; B8D8/B8DD best chain of SSB records (SS-SINR) and
     * of TRS records (CSI-SINR); B96A strongest beam's SNR: means over the
     * window */
    double rsrp_sum, rsrq_sum, sinr_sum, csinr_sum, bsinr_sum;
    long n_rsrp, n_rsrq, n_sinr, n_csinr, n_bsinr;
    /* B8A7: last report in the window */
    int cqi, ri, have_csf;
};
static struct nr_slot nrwin_s[SIMS][SLOTS];
#define nrwin (nrwin_s[sim])

/* Length of a count-prefixed channel block. */
static int b883_counted_bytes(const uint8_t *p, int n, int base_bits,
                              int item_bits) {
    int base = base_bits / 8;
    if (base > n) return -1;
    int item = item_bits / 8;
    if (!item || !n) return -1;
    int count = p[0];
    if (count > (n - base) / item) return -1;
    return base + count * item;
}

/* NR UL scheduling records contain a variable sequence of channel blocks.
 * Walk the complete packet first and publish byte totals only when its final
 * boundary is exact, so a malformed record cannot partly update a window. */
static void b883(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b883_layout *L;
    FIND_LAYOUT(B883_LAYOUTS, nr_ver(p), L);
    if (!L || !fits(L->rec_count, n)) return;
    int records = at(p, L->rec_count);
    int pos = L->rec_start / 8;
    long add_tb[SLOTS] = { 0 };
    long add_bytes[SLOTS] = { 0 };
    for (int r = 0; r < records; r++) {
        int record = pos;
        if (record > n || !fits(L->carrier_count, n - record)) return;
        int carriers = at(p + record, L->carrier_count);
        pos = record + L->carriers_at / 8;
        for (int c = 0; c < carriers; c++) {
            int header = L->header_bits / 8;
            if (header > n - pos) return;
            const uint8_t *carrier_header = p + pos;
            int carrier = at(carrier_header, L->carrier);
            int mask = at(carrier_header, L->mask);
            pos += header;
            if (mask & 16) return;
            if (mask & 1) {
                int size = L->pusch_bits / 8;
                if (size > n - pos || !fits(L->tb_size, size)) return;
                if (carrier < SLOTS) {
                    add_tb[carrier]++;
                    add_bytes[carrier] += at(p + pos, L->tb_size);
                }
                pos += size;
            }
            if (mask & 2) {
                int size = b883_counted_bytes(p + pos, n - pos,
                                              L->pucch_base_bits,
                                              L->pucch_item_bits);
                if (size < 0) return;
                pos += size;
            }
            if (mask & 4) {
                int size = b883_counted_bytes(p + pos, n - pos,
                                              L->srs_base_bits,
                                              L->srs_item_bits);
                if (size < 0) return;
                pos += size;
            }
            if (mask & 8) {
                int size = L->prach_bits / 8;
                if (size > n - pos) return;
                pos += size;
            }
        }
    }
    if (pos != n) return;
    for (int i = 0; i < SLOTS; i++) {
        nrwin[i].ul_tb += add_tb[i];
        nrwin[i].ul_bytes += add_bytes[i];
    }
}

/* B825 serving-cell list (replaced by every B825; empty = idle or no NR). */
struct nr_cell {
    int known, pci, band, bw_mhz, scs_khz, max_layers;
    uint32_t dl_arfcn, ssb_arfcn;
};
static struct nr_cell nrcc_s[SIMS][SLOTS];
#define nrcc (nrcc_s[sim])
static int nr_b825_seen_s[SIMS];
#define nr_b825_seen (nr_b825_seen_s[sim])
/* B97F: which carrier each cc_id is right now (SSB NR-ARFCN, serving PCI). */
static struct live_cell nrlive_s[SIMS][SLOTS];
#define nrlive (nrlive_s[sim])
/* B887: each carrier's bandwidth and SCS as its TBs report them, for the
 * cell named (B825 is only sent when the configuration changes, so a helper
 * started mid-session may never see one). */
struct nr_tb_info { int pci, bw_mhz, scs_khz; uint32_t arfcn; };
static struct nr_tb_info nrtb_s[SIMS][SLOTS];
#define nrtb (nrtb_s[sim])
/* B823: the PCell (serving cell) and its band (0 = not in this version). */
static int nr_pcell_pci_s[SIMS] = { -1, -1 };
#define nr_pcell_pci (nr_pcell_pci_s[sim])
static uint32_t nr_pcell_arfcn_s[SIMS];
#define nr_pcell_arfcn (nr_pcell_arfcn_s[sim])
static int nr_pcell_band_s[SIMS];
#define nr_pcell_band (nr_pcell_band_s[sim])
static long nr_pcell_ms_s[SIMS];
#define nr_pcell_ms (nr_pcell_ms_s[sim])

/* NR5G RRC Configuration Info: count-field arrays (CC groups) before the
 * serving cell list, then the cells; positions per version from
 * qbm_layouts.h, bandwidth codes per version (3.1/3.2 have no 35/45 MHz). */
static void b825(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b825_layout *L;
    FIND_LAYOUT(B825_LAYOUTS, nr_ver(p), L);
    if (!L || L->arrays_at > n * 8 || !fits(L->cell_count, n)) return;
    int pos = L->arrays_at / 8;
    for (int k = 0; k < 2; k++) pos += at(p, L->skip[k]) * (L->skip_bits[k] / 8);
    int cc_n = at(p, L->cell_count), stride = L->cell_bits / 8;
    memset(nrcc, 0, sizeof nrcc);
    nr_b825_seen = 1;
    for (int i = 0; i < cc_n && pos + stride <= n; i++, pos += stride) {
        const uint8_t *c = p + pos;
        /* The CC Id is only where the cell is kept (cells are matched to
         * carriers by PCI and NR-ARFCN). NSA SCG cells can be numbered past
         * the table (X85: n3 = 7, n78 = 8); those take the first free entry. */
        int id = at(c, L->cc);
        if (id >= SLOTS || nrcc[id].known)
            for (id = 0; id < SLOTS && nrcc[id].known; id++) {}
        if (id >= SLOTS) continue;
        struct nr_cell *x = &nrcc[id];
        x->known = 1;
        x->pci = at(c, L->pci);
        x->dl_arfcn = at(c, L->dl_arfcn);
        x->ssb_arfcn = at(c, L->ssb_arfcn);
        x->band = at(c, L->band);
        int bw = at(c, L->bw), scs = at(c, L->scs);
        x->bw_mhz = bw < 18 ? L->bw_mhz[bw] : 0;
        x->scs_khz = L->scs.bits && scs < 5 ? 15 << scs : 0;
        x->max_layers = at(c, L->max_layers);
    }
}

/* NR5G RRC Serving Cell Info: the PCell's PCI, DL NR-ARFCN and band. The
 * band is the modem's own, so it settles overlapping bands (n77/n78) where
 * the NR-ARFCN can't; B825, which also names it, only comes with a
 * reconfiguration. */
static void b823(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b823_layout *L;
    FIND_LAYOUT(B823_LAYOUTS, nr_ver(p), L);
    if (!L || !fits(L->pci, n) || !fits(L->dl_arfcn, n)) return;
    nr_pcell_pci = at(p, L->pci);
    nr_pcell_arfcn = at(p, L->dl_arfcn);
    nr_pcell_band = L->band.bits && fits(L->band, n) ? (int)at(p, L->band) : 0;
    nr_pcell_ms = now_ms();
}

/* The B825 cell with this PCI and NR-ARFCN (SSB or DL), or NULL. */
static const struct nr_cell *nr_cfg_for(int pci, uint32_t arfcn) {
    for (int i = 0; i < SLOTS; i++)
        if (nrcc[i].known && nrcc[i].pci == pci && (nrcc[i].ssb_arfcn == arfcn || nrcc[i].dl_arfcn == arfcn)) return &nrcc[i];
    return NULL;
}

/* The cc_id a (PCI, NR-ARFCN) belongs to, from B97F's live map, then B825;
 * -1 when unknown. */
static int nr_cc_for(int pci, uint32_t arfcn) {
    long now = now_ms();
    for (int i = 0; i < SLOTS; i++)
        if (nrlive[i].at_ms && now - nrlive[i].at_ms < LIVE_MS && nrlive[i].pci == pci && nrlive[i].earfcn == arfcn) return i;
    for (int i = 0; i < SLOTS; i++)
        if (nrcc[i].known && nrcc[i].pci == pci && (nrcc[i].ssb_arfcn == arfcn || nrcc[i].dl_arfcn == arfcn)) return i;
    return -1;
}

/* The B887 layout for a version (qbm_layouts.h). 3.7 and 3.8 have no table
 * entry of their own; they're decoded with 3.6's layout. */
static const struct b887_layout *b887_layout(uint32_t ver) {
    if (ver == 0x30007 || ver == 0x30008) ver = 0x30006;
    const struct b887_layout *L;
    FIND_LAYOUT(B887_LAYOUTS, ver, L);
    return L;
}

/* NR5G MAC PDSCH Status: records, each with an item per TB. Each item names
 * its cell (PCI, NR-ARFCN), which picks the carrier. */
static void b887(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b887_layout *L = b887_layout(nr_ver(p));
    if (!L || (L->rec_count.off + L->rec_count.bits) > n * 8) return;
    int records = at(p, L->rec_count), pos = L->rec_start / 8;
    int item_bytes = L->item_bits / 8;
    for (int r = 0; r < records; r++) {
        if (pos + L->items_at / 8 > n) return;
        const uint8_t *rec = p + pos;
        int items = at(rec, L->item_count);
        if (items == 0 || items > 16) return;
        pos += L->items_at / 8;
        for (int k = 0; k < items; k++, pos += item_bytes) {
            if (pos + item_bytes > n) return;
            const uint8_t *it = p + pos;
            int rnti = at(it, L->rnti);
            if (rnti != 0 && rnti != 3) continue;       /* C-RNTI / CS-RNTI only */
            int pci = at(it, L->pci);
            uint32_t arfcn = at(it, L->arfcn);
            int cc = nr_cc_for(pci, arfcn);
            /* Else the item's SCell field: it equals the cc_id (seen on
             * SM8550: 0 for the n41 carrier, 1 for n28). */
            if (cc < 0) cc = at(it, L->scell);
            if (cc >= SLOTS) continue;
            /* Bandwidth code: 3GPP's list without 35/45 MHz (n41 90 MHz = 11,
             * n28 20 MHz = 3 on SM8550); SCS from the numerology. */
            static const int tb_bw[13] = { 5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100 };
            int bwc = L->bw.bits ? (int)at(it, L->bw) : 99, mu = L->scs_mu.bits ? (int)at(it, L->scs_mu) : 99;
            nrtb[cc].pci = pci;
            nrtb[cc].arfcn = arfcn;
            nrtb[cc].bw_mhz = bwc < 13 ? tb_bw[bwc] : 0;
            /* FR2 uses another code list: code 1 = 100 MHz (66 RBs at 120 kHz
             * on X55/X60 mmWave captures; the generic list would say 10 MHz).
             * Other FR2 codes are unconfirmed and left unknown. */
            if (L->band_type.bits && at(it, L->band_type)) nrtb[cc].bw_mhz = bwc == 1 ? 100 : 0;
            nrtb[cc].scs_khz = mu < 4 ? 15 << mu : 0;
            struct nr_slot *s = &nrwin[cc];
            int size = at(it, L->tb_size), crc = at(it, L->crc);
            s->tb++;
            if (!crc) s->fail++;
            s->bytes_all += size;
            if (crc) s->bytes_pass += size;
            /* MCS mean: a retransmission may be signalled with a reserved
             * index (28-31 on the 256QAM table, 29-31 on 64QAM) that only
             * gives the modulation; the TB size is the first transmission's.
             * Such TBs still count for throughput, BLER, RBs and layers, but
             * not for the MCS mean. In the test logs (X55 to X85) no new
             * transmission used 28-31 on the 256QAM table, while nearly every
             * retransmission did, raising the old all-TB mean by about 1-2 at
             * 10% BLER. Up to 1.22.1 the mean included them ("mcs_all" keeps
             * that figure for comparison). Versions without a new-transmission
             * field leave out 29-31, reserved on every table. */
            int mcs = at(it, L->mcs);
            int reserved = L->new_tx.bits ? (!at(it, L->new_tx) && mcs >= 28) : mcs >= 29;
            s->mcs_all_sum += mcs;
            if (!reserved) {
                s->mcs_sum += mcs;
                s->mcs_tb++;
            }
            s->rb_sum += at(it, L->rbs);
            s->layers_sum += at(it, L->layers) + 1;      /* raw field: layers - 1 */
            if (L->mod.bits) {
                int m = at(it, L->mod);
                if (m < 5) s->mod[m]++;
            }
            if (L->rx.bits) {
                int rxc = at(it, L->rx);
                if (rxc < 4) s->rx[rxc]++;              /* 0-3: 1-4 RX */
            }
        }
    }
}

/* NR5G ML1 Searcher Measurement Database Update Ext: per carrier layer
 * (NR-ARFCN, cc_id from 2.9), its cells with RSRP/RSRQ (Q7), each followed by
 * its beams. Positions per version from qbm_layouts.h. */
static double q7(int32_t v) { return v / 128.0; }
static int nr_slot_by_unique_pci(int pci, uint32_t *arfcn) {
    int match = -1;
    for (int i = 0; i < SLOTS; i++) if (nrcc[i].known && nrcc[i].pci == pci) {
        if (match >= 0) return -1;                /* ambiguous PCI: do not mis-assign */
        match = i;
    }
    if (match >= 0 && arfcn)
        *arfcn = nrcc[match].ssb_arfcn ? nrcc[match].ssb_arfcn : nrcc[match].dl_arfcn;
    return match;
}
/* NR-ARFCN -> kHz (3GPP 38.104 global raster). */
static long nr_khz(uint32_t n) {
    if (n < 600000) return 5L * n;
    if (n < 2016667) return 3000000L + 15L * (n - 600000);
    return 24250080L + 60L * (n - 2016667);
}

/* B825 0.x names a carrier by its DL NR-ARFCN only (no SSB ARFCN), while
 * B97F before 2.9 names it by its SSB raster (X55, n258: SSB 40-164
 * ARFCNs from the DL ARFCN of the same 100 MHz carrier). The B825 cell whose
 * bandwidth contains this SSB (nearest, same PCI) is that carrier; its SSB
 * ARFCN is learned so the other matches by (PCI, NR-ARFCN) work. -1 if none. */
static int nr_learn_ssb(uint32_t ssb, int spci) {
    int best = -1;
    long best_d = 0;
    for (int i = 0; i < SLOTS; i++) {
        const struct nr_cell *c = &nrcc[i];
        if (!c->known || c->ssb_arfcn || !c->bw_mhz || (spci != 0xFFFF && c->pci != spci)) continue;
        long d = labs(nr_khz(ssb) - nr_khz(c->dl_arfcn));
        if (d <= c->bw_mhz * 500L && (best < 0 || d < best_d)) { best = i; best_d = d; }
    }
    if (best >= 0) nrcc[best].ssb_arfcn = ssb;
    return best;
}

/* A pre-2.9 layer naming a serving cell while B825 lists none (RRC idle,
 * where B825 is empty, or a helper started mid-connection): the slot this
 * NR-ARFCN already holds (the PCI changes on reselection), else the first
 * slot not live. Idle X60 (B97F 2.7) reports its camped cell this way and
 * would otherwise show nothing until a connection brings a B825. -1 while
 * B825 lists cells: a layer it doesn't name isn't a configured carrier. */
static int nr_slot_unconfigured(uint32_t arfcn) {
    long now = now_ms();
    for (int i = 0; i < SLOTS; i++) if (nrcc[i].known) return -1;
    for (int i = 0; i < SLOTS; i++)
        if (nrlive[i].at_ms && now - nrlive[i].at_ms < LIVE_MS && nrlive[i].earfcn == arfcn) return i;
    for (int i = 0; i < SLOTS; i++)
        if (!nrlive[i].at_ms || now - nrlive[i].at_ms >= LIVE_MS) return i;
    return -1;
}

static void b97f(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b97f_layout *Lay;
    FIND_LAYOUT(B97F_LAYOUTS, nr_ver(p), Lay);
    if (!Lay || (Lay->layers_at / 8) > n) return;
    int layers = at(p, Lay->layer_count), pos = Lay->layers_at / 8;
    int layer_bytes = Lay->cells_at / 8, cell_bytes = Lay->beams_at / 8, beam_bytes = Lay->beam_bits / 8;
    for (int l = 0; l < layers && pos + layer_bytes <= n; l++) {
        const uint8_t *L = p + pos;
        uint32_t arfcn = at(L, Lay->arfcn);
        int cc = Lay->cc.bits ? (int)at(L, Lay->cc) : -1, ncell = at(L, Lay->ncell), spci = at(L, Lay->spci);
        pos += layer_bytes;
        if (cc < 0 && Lay->arfcn.bits) {                 /* before 2.9: match by frequency */
            for (int i = 0; i < SLOTS && cc < 0; i++)
                if (nrcc[i].known && (nrcc[i].ssb_arfcn == arfcn || nrcc[i].dl_arfcn == arfcn)) cc = i;
            if (cc < 0) cc = nr_learn_ssb(arfcn, spci);
            if (cc < 0 && spci != 0xFFFF) cc = nr_slot_unconfigured(arfcn);
        } else if (cc < 0 && Lay->spci.bits) {            /* 2.3: raster frequency is not an ARFCN */
            cc = nr_slot_by_unique_pci(spci, &arfcn);
        }
        for (int c = 0; c < ncell && pos + cell_bytes <= n; c++) {
            const uint8_t *C = p + pos;
            int pci = at(C, Lay->pci), beams = at(C, Lay->nbeam);
            if (cc >= 0 && cc < SLOTS && spci != 0xFFFF && pci == spci) {
                double rsrp = q7((int32_t)at(C, Lay->rsrp)), rsrq = q7((int32_t)at(C, Lay->rsrq));
                struct nr_slot *s = &nrwin[cc];
                if (rsrp > -160 && rsrp < -20) { s->rsrp_sum += rsrp; s->n_rsrp++; }
                if (rsrq > -40 && rsrq < 10) { s->rsrq_sum += rsrq; s->n_rsrq++; }
                nrlive[cc].earfcn = arfcn;
                nrlive[cc].pci = pci;
                nrlive[cc].at_ms = now_ms();
            }
            pos += cell_bytes + beams * beam_bytes;
        }
    }
}

/* NR5G LL1 FW Serving SNR (B8D8; B8DD on X65): carrier and four linear
 * chain SNRs, 10*log10(raw/1024); the best chain is used. Records measured
 * on SSB give SS-SINR, on TRS (a CSI-RS for tracking) CSI-SINR: two
 * different measurements (4-9 dB apart on SM8550). Versions without the
 * reference-signal field count as SS-SINR. */
static void serving_snr(const struct b8d8_layout *layout, const uint8_t *payload, int size) {
    if (!layout || !fits(layout->carrier, size) || !fits(layout->rs, size))
        return;
    if (layout->payload_bytes && size != layout->payload_bytes)
        return;
    int carrier = at(payload, layout->carrier);
    if (layout->pci.bits) {
        if (!fits(layout->pci, size) || !fits(layout->arfcn, size))
            return;
        /* Older records rotate between frequencies without a usable slot. */
        carrier = nr_cc_for(at(payload, layout->pci), at(payload, layout->arfcn));
    }
    if (carrier < 0 || carrier >= SLOTS)
        return;
    int have = 0;
    double best_db = 0;
    for (int i = 0; i < 4; i++) {
        if (!fits(layout->snr[i], size))
            return;
        uint32_t raw = at(payload, layout->snr[i]);
        if (!raw)
            continue;
        double db = 10.0 * log10(raw / 1024.0);
        if (!have || db > best_db) {
            best_db = db;
            have = 1;
        }
    }
    if (!have || best_db <= -30 || best_db >= 60)
        return;
    if (layout->rs.bits && at(payload, layout->rs)) {
        nrwin[carrier].csinr_sum += best_db;
        nrwin[carrier].n_csinr++;
    } else {
        nrwin[carrier].sinr_sum += best_db;
        nrwin[carrier].n_sinr++;
    }
}

static void b8d8(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b8d8_layout *L;
    FIND_LAYOUT(B8D8_LAYOUTS, nr_ver(p), L);
    serving_snr(L, p, n);
}

static void b8dd(const uint8_t *p, int n) {
    if (n < 4) return;
    const struct b8d8_layout *L;
    FIND_LAYOUT(B8DD_LAYOUTS, nr_ver(p), L);
    serving_snr(L, p, n);
}

/* Keep the strongest beam separately for each configured cell. Older groups
 * contain beams of different PCIs, so their first beam cannot name the group. */
static void b96a_beams(const struct b96a_layout *layout, const uint8_t *beams,
                      int count, uint32_t arfcn, int packet_pci) {
    struct meas best_beam[SLOTS] = {{0}};
    for (int i = 0; i < count; i++) {
        const uint8_t *beam = beams + i * (layout->beam_bits / 8);
        int pci = layout->beam_pci.bits ? (int)at(beam, layout->beam_pci) : packet_pci;
        int carrier = nr_cc_for(pci, arfcn);
        if (carrier < 0 || carrier >= SLOTS)
            continue;
        double rsrp = -1e9;
        double snr = -1e9;
        for (int chain = 0; chain < 2; chain++) {
            double power = (int16_t)at(beam, layout->rsrp[chain]) / 128.0;
            double ratio = (int16_t)at(beam, layout->snr[chain]) / 128.0;
            if (power > rsrp)
                rsrp = power;
            if (ratio > snr)
                snr = ratio;
        }
        struct meas *best = &best_beam[carrier];
        if (rsrp > -160 && rsrp < -20 && (!best->have_rsrp || rsrp > best->rsrp)) {
            best->have_rsrp = 1;
            best->rsrp = rsrp;
            best->sinr = snr;
        }
    }
    for (int carrier = 0; carrier < SLOTS; carrier++) {
        const struct meas *best = &best_beam[carrier];
        if (best->have_rsrp && best->sinr > -30 && best->sinr < 60) {
            nrwin[carrier].bsinr_sum += best->sinr;
            nrwin[carrier].n_bsinr++;
        }
    }
}

/* Validate the whole parent/group/beam hierarchy before applying any values.
 * Newer layouts omit parent counts and carry groups through the payload end. */
static int b96a_walk(const struct b96a_layout *layout, const uint8_t *payload, int size, int apply) {
    int legacy = layout->parent_count.bits != 0;
    int parents = legacy ? (int)at(payload, layout->parent_count) : 1;
    int position = (legacy ? layout->parents_at : layout->groups_at) / 8;
    int header_bytes = layout->group_bits / 8;
    int beam_bytes = layout->beam_bits / 8;
    int pci = at(payload, layout->pci);
    for (int parent = 0; parent < parents; parent++) {
        int groups = -1;
        if (legacy) {
            int parent_bytes = layout->parent_bits / 8;
            if (position > size || parent_bytes > size - position)
                return 0;
            groups = at(payload + position, layout->group_count);
            position += parent_bytes;
        }
        for (int group = 0; legacy ? group < groups : position < size; group++) {
            if (position > size || header_bytes > size - position)
                return 0;
            const uint8_t *header = payload + position;
            int beams = at(header, layout->g_count);
            uint32_t arfcn = at(header, layout->g_arfcn);
            position += header_bytes;
            if (beams > (size - position) / beam_bytes)
                return 0;
            if (apply)
                b96a_beams(layout, payload + position, beams, arfcn, pci);
            position += beams * beam_bytes;
        }
    }
    return position == size;
}

/* Per-beam SNR is separate from the serving reference-signal SINR. */
static void b96a(const uint8_t *payload, int size) {
    if (size < 4)
        return;
    const struct b96a_layout *layout;
    FIND_LAYOUT(B96A_LAYOUTS, nr_ver(payload), layout);
    if (!layout || !fits(layout->pci, size) || !fits(layout->parent_count, size))
        return;
    if (b96a_walk(layout, payload, size, 0))
        b96a_walk(layout, payload, size, 1);
}

/* Bound all fixed-size reports before updating state: a truncated later
 * record must not leave a partially applied packet. */
static int b8a7_complete(const struct b8a7_layout *layout, const uint8_t *payload, int size) {
    int position = layout->rec_start / 8;
    int records = at(payload, layout->rec_count);
    int header_bytes = layout->reps_at / 8;
    int report_bytes = layout->rep_bits / 8;
    for (int i = 0; i < records; i++) {
        if (position > size || header_bytes > size - position
            || !fits(layout->rep_count, size - position))
            return 0;
        int reports = at(payload + position, layout->rep_count);
        position += header_bytes;
        if (reports > (size - position) / report_bytes)
            return 0;
        position += reports * report_bytes;
    }
    return position == size;
}

/* Every record can update the resource carrier's RI/CQI. L1-RSRP reports
 * have no CSI metrics. Variable-size layouts retain the first-report-only
 * fallback until their intervening blocks can be traversed safely. */
static void b8a7(const uint8_t *payload, int size) {
    if (size < 4)
        return;
    const struct b8a7_layout *layout;
    FIND_LAYOUT(B8A7_LAYOUTS, nr_ver(payload), layout);
    if (!layout || !fits(layout->rec_count, size))
        return;
    int report_bytes = layout->rep_bits / 8;
    int records = at(payload, layout->rec_count);
    if (report_bytes && !b8a7_complete(layout, payload, size))
        return;
    if (!report_bytes && records > 1)
        records = 1;
    int position = layout->rec_start / 8;
    for (int i = 0; i < records; i++) {
        if (position > size || !fits(layout->rep_count, size - position))
            return;
        int reports = at(payload + position, layout->rep_count);
        position += layout->reps_at / 8;
        if (!report_bytes && reports > 1)
            reports = 1;
        for (int j = 0; j < reports; j++, position += report_bytes) {
            if (position > size || !fits(layout->cqi, size - position)
                || !fits(layout->ri, size - position) || !fits(layout->carrier, size - position)
                || !fits(layout->quantity, size - position))
                return;
            const uint8_t *report = payload + position;
            if (at(report, layout->quantity) & 32)
                continue;
            int carrier = at(report, layout->carrier);
            if (carrier >= SLOTS)
                continue;
            nrwin[carrier].ri = at(report, layout->ri) + 1;
            nrwin[carrier].cqi = at(report, layout->cqi);
            nrwin[carrier].have_csf = 1;
        }
    }
}

/* DCI log: [u16 len][u16 code][u64 timestamp][payload]. With the extended
 * header: [0x98][1][2 reserved][u32 subscription][i32 -1][log] (seen on
 * SM8550 with two SIMs; every log carries it). */
static void on_log(unsigned char *ptr, int len) {
    if (len < 12 || stopping) return;
    int sub = 1;
    if (len >= 24 && (int32_t)le32(ptr + 8) == -1 && le16(ptr + 12) + 12 == len) {
        sub = (int)le32(ptr + 4);
        ptr += 12;
        len -= 12;
    }
    if (sub < 1 || sub > SIMS) return;
    uint16_t code = le16(ptr + 2);
    const uint8_t *pl = ptr + 12;
    int pn = len - 12;
    pthread_mutex_lock(&lock);
    sim = sub - 1;
    rrc_csi_select(sim);
    last_log_ms = now_ms();
    n_logs++;
    if (code == 0xB173) b173(pl, pn);
    else if (code == 0xB126) b126(pl, pn);
    else if (code == 0xB139) b139(pl, pn);
    else if (code == 0xB184) b184(pl, pn);
    else if (code == 0xB193) b193(pl, pn);
    else if (code == 0xB168) b168(pl, pn);
    else if (code == 0xB883) b883(pl, pn);
    else if (code == 0xB887) b887(pl, pn);
    else if (code == 0xB97F) b97f(pl, pn);
    else if (code == 0xB8D8) b8d8(pl, pn);
    else if (code == 0xB8DD) b8dd(pl, pn);
    else if (code == 0xB96A) b96a(pl, pn);
    else if (code == 0xB8A7) b8a7(pl, pn);
    else if (code == 0xB825) b825(pl, pn);
    else if (code == 0xB823) b823(pl, pn);
    else if (code == 0xB88A) b88a(pl, pn);
    else if (code == 0xB821) rrc_csi_b821(pl, pn);
    pthread_mutex_unlock(&lock);
}
static void on_event(unsigned char *p, int len) { (void)p; (void)len; }

/* ── Stats ───────────────────────────────────────────────────────────────── */

static const char *MOD_NAMES[5] = { "QPSK", "16QAM", "64QAM", "256QAM", "1024QAM" };

/* Majority transmission scheme -> transmission mode. The scheme is per grant
 * (a TM4 cell can fall back to TX diversity), so the majority is used. Ports
 * 7-10 schemes are reported as TM9 (TM8 is a TDD-only rarity). */
static int scheme_to_tm(int s) {
    switch (s) {
    case 1: return 1; case 2: return 2; case 3: return 3; case 4: return 4;
    case 5: return 5; case 6: return 6; case 7: return 7;
    case 8: case 9: case 10: case 11: return 9;
    }
    return 0;
}

/* B184 bandwidth: RB count or 0-5 index -> MHz (0 when unknown). */
static double bw_mhz(int raw) {
    static const int rbs[6] = { 6, 15, 25, 50, 75, 100 };
    static const double mhz[6] = { 1.4, 3, 5, 10, 15, 20 };
    for (int i = 0; i < 6; i++) if (raw == rbs[i]) return mhz[i];
    return raw >= 0 && raw <= 5 ? mhz[raw] : 0;
}

static int argmax(const long *v, int n) {
    int best = -1;
    for (int i = 0; i < n; i++) if (v[i] > 0 && (best < 0 || v[i] > v[best])) best = i;
    return best;
}

static long window_start;

/* The NR PCell a TA is tied to: B823's carrier, else (no B823 yet) the first
 * live carrier, which in SA with one carrier is the PCell. */
static struct cell_id nr_ta_cell(int pcell, const int *live_of, const struct nr_cell *const *cfg_of) {
    struct cell_id id = { 0, 0, -1 };
    if (pcell >= 0) {
        id.known = 1;
        if (live_of[pcell]) {
            id.arfcn = nrlive[pcell].earfcn;
            id.pci = nrlive[pcell].pci;
        } else {
            const struct nr_cell *c = cfg_of[pcell];
            id.arfcn = c->ssb_arfcn ? c->ssb_arfcn : c->dl_arfcn;
            id.pci = c->pci;
        }
        return id;
    }
    for (int i = 0; i < SLOTS; i++) {
        if (!live_of[i])
            continue;
        id.known = 1;
        id.arfcn = nrlive[i].earfcn;
        id.pci = nrlive[i].pci;
        return id;
    }
    return id;
}

static int write_stats(char *out, size_t cap, int for_sim) {
    pthread_mutex_lock(&lock);
    sim = for_sim >= 1 && for_sim <= SIMS ? for_sim - 1 : 0;
    rrc_csi_select(sim);
    long now = now_ms(), ms = now - window_start;
    if (ms < 1) ms = 1;
    size_t o = 0;
#define PUT(...) do { int k = snprintf(out + o, o < cap ? cap - o : 0, __VA_ARGS__); if (k > 0) o += (size_t)k; } while (0)
    PUT("{\"ok\":true,\"version\":\"%s\",\"lib\":\"%s\",\"ms\":%ld,\"logs\":%ld,\"sim\":%d,\"sim_tags\":%s,\"ca_known\":%s,\"carriers\":[",
        HELPER_VERSION, lib_route, ms, n_logs, sim + 1, sim_tags ? "true" : "false", ca_seen ? "true" : "false");
    int first = 1;
    int silent = !last_log_ms || now - last_log_ms > SILENT_MS;
    /* A B184 is only current while its PCell is the live one: after a
     * handover or release without a new B184 (X85 sends them on events
     * only), its SCell list belongs to the old PCell. */
    int live0 = live[0].at_ms && now - live[0].at_ms < LIVE_MS;
    int b184_current = !live0 || (ca[0].known && ca[0].earfcn == live[0].earfcn && ca[0].pci == live[0].pci);
    for (int i = 0; i < SLOTS && !silent; i++) {
        struct slot *s = &win[i];
        /* Which carrier this slot is: B193's live serving record first (it
         * follows handovers and reconfigurations even without a B184), then
         * the last B184. Band, bandwidth and state come from B184 only when
         * it names the same carrier. Shown HOLD_MS after its last record. */
        int is_live = live[i].at_ms && now - live[i].at_ms < HOLD_MS;
        int b184_ok = ca[i].known && b184_current && (!is_live || (ca[i].earfcn == live[i].earfcn && ca[i].pci == live[i].pci));
        int configured = is_live || (b184_ok && (i == 0 || ca[i].state > 0));
        if (!configured && s->tb == 0 && s->ul_tb == 0 && s->n126 == 0 && s->n_rsrp == 0) continue;
        PUT("%s{\"slot\":%d", first ? "" : ",", i);
        first = 0;
        if (is_live || b184_ok) {
            /* Without B184 a live SCell counts as activated when it carried
             * data this window, else configured. */
            int state = b184_ok ? ca[i].state : (i == 0 || s->tb > 0 ? 2 : 1);
            PUT(",\"earfcn\":%u,\"pci\":%d,\"state\":%d", is_live ? live[i].earfcn : ca[i].earfcn,
                is_live ? live[i].pci : ca[i].pci, state);
            if (b184_ok) {
                PUT(",\"band\":%d", ca[i].band);
                double bw = bw_mhz(ca[i].bw_raw);
                if (bw > 0) PUT(",\"bw\":%.1f", bw);
            }
        }
        {
            uint32_t ch = is_live ? live[i].earfcn : b184_ok ? ca[i].earfcn : 0;
            int pci = is_live ? live[i].pci : b184_ok ? ca[i].pci : -1;
            double v;
            if (held_value(&lte_held[i][0], s->rsrp_sum, s->n_rsrp, ch, pci, now, &v)) PUT(",\"rsrp\":%.2f", v);
            if (held_value(&lte_held[i][1], s->rsrq_sum, s->n_rsrq, ch, pci, now, &v)) PUT(",\"rsrq\":%.2f", v);
            if (held_value(&lte_held[i][2], s->sinr_sum, s->n_sinr, ch, pci, now, &v)) PUT(",\"sinr\":%.2f", v);
        }
        PUT(",\"tb\":%ld,\"mbps\":%.3f,\"mbps_sched\":%.3f",
            s->tb, s->bytes_pass * 8.0 / (ms * 1000.0), s->bytes_all * 8.0 / (ms * 1000.0));
        if (s->ul_tb > 0) PUT(",\"ul_mbps\":%.3f", s->ul_bytes * 8.0 / (ms * 1000.0));
        if (s->tb > 0) {
            int m = argmax(s->mod, 5);
            PUT(",\"bler\":%.2f", 100.0 * s->fail / s->tb);
            if (s->mcs_tb > 0) PUT(",\"mcs\":%.2f", (double)s->mcs_sum / s->mcs_tb);
            PUT(",\"mcs_all\":%.2f,\"rbs\":%.2f", (double)s->mcs_all_sum / s->tb, (double)s->rb_sum / s->tb);
            if (m >= 0) PUT(",\"mod\":\"%s\"", MOD_NAMES[m]);
        }
        if (s->recs > 0) PUT(",\"layers\":%.2f", (double)s->layers_sum / s->recs);
        if (s->n126 > 0) {
            int r = argmax(s->rank, 4), tm = scheme_to_tm(argmax(s->scheme, 16));
            if (r >= 0) PUT(",\"rank\":%d", r + 1);
            if (tm) PUT(",\"tm\":%d", tm);
            if (s->tx) PUT(",\"tx\":%d", s->tx);
            if (s->rx) PUT(",\"rx\":%d", s->rx);
        }
        PUT("}");
    }
    PUT("]");

    /* NR carriers by the modem's carrier slot (cc_id, as B97F, B887, B8D8 and
     * B8A7 number them). B825 numbers its cells by RRC SCell index, which
     * changes when an SCell is replaced while the slot stays (X75: an n28
     * SCell swapping PCI moves between B825 ids 1/2 and 3 while B97F keeps
     * its slot), so its cells are matched to live slots by PCI and NR-ARFCN. A B825 cell no
     * live slot claims is shown under its own id (before any B97F). PCell
     * from B823. */
    const struct nr_cell *cfg_of[SLOTS] = { 0 };
    int live_of[SLOTS];
    for (int i = 0; i < SLOTS; i++) live_of[i] = nrlive[i].at_ms && now - nrlive[i].at_ms < HOLD_MS;
    /* After a reconfiguration a slot's old cell can stay live for LIVE_MS
     * while its new slot already reports it: keep only the fresher. */
    for (int i = 0; i < SLOTS; i++)
        for (int j = 0; j < SLOTS; j++)
            if (i != j && live_of[i] && live_of[j] && nrlive[i].pci == nrlive[j].pci && nrlive[i].earfcn == nrlive[j].earfcn
                && nrlive[i].at_ms < nrlive[j].at_ms) live_of[i] = 0;
    for (int i = 0; i < SLOTS; i++) if (live_of[i]) cfg_of[i] = nr_cfg_for(nrlive[i].pci, nrlive[i].earfcn);
    for (int i = 0; i < SLOTS; i++) {
        if (live_of[i] || !nrcc[i].known) continue;
        int claimed = 0;
        for (int j = 0; j < SLOTS; j++) if (cfg_of[j] == &nrcc[i]) claimed = 1;
        if (!claimed) cfg_of[i] = &nrcc[i];
    }
    int pcell = -1;
    if (nr_pcell_pci >= 0)
        for (int i = 0; i < SLOTS && pcell < 0; i++) {
            const struct nr_cell *c = cfg_of[i];
            if ((c && c->pci == nr_pcell_pci && (c->ssb_arfcn == nr_pcell_arfcn || c->dl_arfcn == nr_pcell_arfcn))
                || (live_of[i] && nrlive[i].pci == nr_pcell_pci && nrlive[i].earfcn == nr_pcell_arfcn)) pcell = i;
        }
    /* Each RAT's TA is shown only while its cell is the PCell (see ta_bind).
     * LTE PCell: slot 0's live serving record. */
    struct cell_id lte_cell = { live[0].at_ms && now - live[0].at_ms < HOLD_MS, live[0].earfcn, live[0].pci };
    int show_lte_ta = ta_bind(&lte_ta, lte_cell, now);
    int show_nr_ta = ta_bind(&nr_ta, nr_ta_cell(pcell, live_of, cfg_of), now);
    PUT(",\"nr\":[");
    first = 1;
    for (int i = 0; i < SLOTS && !silent; i++) {
        struct nr_slot *s = &nrwin[i];
        const struct nr_cell *c = cfg_of[i];
        int lv = live_of[i], cfg = c != NULL;
        if (!cfg && !lv) continue;
        /* "pcell" only when B823 named it; otherwise the app decides. */
        PUT("%s{\"cc\":%d,\"pcell\":%s", first ? "" : ",", i, pcell >= 0 ? (pcell == i ? "true" : "false") : "null");
        first = 0;
        PUT(",\"pci\":%d,\"arfcn\":%u", lv ? nrlive[i].pci : c->pci,
            lv ? nrlive[i].earfcn : (c->ssb_arfcn ? c->ssb_arfcn : c->dl_arfcn));
        /* RRC names cells by PCI and SSB frequency, not by carrier slot. */
        int csi_ports = rrc_csi_ports(lv ? nrlive[i].pci : c->pci, lv ? nrlive[i].earfcn : c->ssb_arfcn,
                                      pcell >= 0 ? pcell == i : -1);
        if (csi_ports) PUT(",\"csi_ports\":%d", csi_ports);
        /* Band: B825's, else B823's for the PCell, else what the RRC
         * configuration names (B825 only comes with a reconfiguration, so a
         * helper started mid-session can lack it; the RRC band is the first
         * of the cell's band list). */
        int band = cfg && c->band ? c->band : 0;
        if (!band && pcell == i) band = nr_pcell_band;
        if (!band) band = rrc_csi_band(lv ? nrlive[i].pci : c->pci, lv ? nrlive[i].earfcn : c->ssb_arfcn);
        if (cfg) {
            PUT(",\"dl_arfcn\":%u,\"band\":%d", c->dl_arfcn, band);
            if (c->bw_mhz) PUT(",\"bw\":%d", c->bw_mhz);
            /* B825 0.x has no SCS: this carrier's TBs give it (same cell). */
            const struct nr_tb_info *t = &nrtb[i];
            int tb_cell = t->pci == c->pci && (t->arfcn == c->dl_arfcn || t->arfcn == c->ssb_arfcn);
            if (c->scs_khz) PUT(",\"scs\":%d", c->scs_khz);
            else if (tb_cell && t->scs_khz) PUT(",\"scs\":%d", t->scs_khz);
            if (c->max_layers) PUT(",\"max_layers\":%d", c->max_layers);
        } else {
            if (band) PUT(",\"band\":%d", band);
            /* No B825: bandwidth and SCS from this carrier's TBs (same cell). */
            struct nr_tb_info *t = &nrtb[i];
            if (t->pci == nrlive[i].pci && t->arfcn == nrlive[i].earfcn) {
                if (t->bw_mhz) PUT(",\"bw\":%d", t->bw_mhz);
                if (t->scs_khz) PUT(",\"scs\":%d", t->scs_khz);
            }
        }
        {
            uint32_t ch = lv ? nrlive[i].earfcn : (c->ssb_arfcn ? c->ssb_arfcn : c->dl_arfcn);
            int pci = lv ? nrlive[i].pci : c->pci;
            double v;
            if (held_value(&nr_held[i][0], s->rsrp_sum, s->n_rsrp, ch, pci, now, &v)) PUT(",\"rsrp\":%.2f", v);
            if (held_value(&nr_held[i][1], s->rsrq_sum, s->n_rsrq, ch, pci, now, &v)) PUT(",\"rsrq\":%.2f", v);
            if (held_value(&nr_held[i][2], s->sinr_sum, s->n_sinr, ch, pci, now, &v)) PUT(",\"sinr\":%.2f", v);
            if (held_value(&nr_held[i][3], s->csinr_sum, s->n_csinr, ch, pci, now, &v)) PUT(",\"csinr\":%.2f", v);
            if (held_value(&nr_held[i][4], s->bsinr_sum, s->n_bsinr, ch, pci, now, &v)) PUT(",\"bsinr\":%.2f", v);
        }
        if (s->have_csf) PUT(",\"cqi\":%d,\"ri\":%d", s->cqi, s->ri);
        PUT(",\"tb\":%ld,\"mbps\":%.3f,\"mbps_sched\":%.3f",
            s->tb, s->bytes_pass * 8.0 / (ms * 1000.0), s->bytes_all * 8.0 / (ms * 1000.0));
        if (s->ul_tb > 0) PUT(",\"ul_mbps\":%.3f", s->ul_bytes * 8.0 / (ms * 1000.0));
        if (s->tb > 0) {
            int m = argmax(s->mod, 5), rx = argmax(s->rx, 4);
            PUT(",\"bler\":%.2f", 100.0 * s->fail / s->tb);
            if (s->mcs_tb > 0) PUT(",\"mcs\":%.2f", (double)s->mcs_sum / s->mcs_tb);
            PUT(",\"mcs_all\":%.2f,\"rbs\":%.2f,\"layers\":%.2f", (double)s->mcs_all_sum / s->tb,
                (double)s->rb_sum / s->tb, (double)s->layers_sum / s->tb);
            if (m >= 0) PUT(",\"mod\":\"%s\"", MOD_NAMES[m]);
            if (rx >= 0) PUT(",\"rx\":%d", rx + 1);
        }
        PUT("}");
    }
    PUT("]");
    /* The last random access's TA, as the raw index. */
    if (!silent && show_lte_ta) PUT(",\"lte_ta\":%d", lte_ta.value);
    if (!silent && show_nr_ta) PUT(",\"nr_ta\":%d", nr_ta.value);
    PUT("}\n");
#undef PUT
    /* Every SIM's window restarts: the next answer for either SIM covers the
     * time since this one. */
    memset(win_s, 0, sizeof win_s);
    memset(nrwin_s, 0, sizeof nrwin_s);
    window_start = now;
    pthread_mutex_unlock(&lock);
    return o < cap ? (int)o : -1;
}

/* ── Socket ──────────────────────────────────────────────────────────────── */

static int listen_socket(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    size_t n = strlen(SOCKET_NAME);
    memcpy(a.sun_path + 1, SOCKET_NAME, n);             /* abstract: leading NUL */
    if (bind(fd, (struct sockaddr *)&a, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n)) < 0 || listen(fd, 2) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int write_all(int fd, const char *s, size_t n) {
    while (n > 0) {
        ssize_t k = write(fd, s, n);
        if (k < 0) { if (errno == EINTR) continue; return -1; }
        s += k; n -= (size_t)k;
    }
    return 0;
}

/*
 * Opens vendor libdiag. A binary in /data runs in the linker's "default"
 * namespace; older Android lets that namespace load /vendor/lib64 (with a
 * warning), newer Android refuses ("not accessible for the namespace").
 * Then the library is opened in the platform's exported "sphal" namespace,
 * the one system processes use for same-process vendor libraries, whose
 * paths include /vendor/lib64 and which links libdiag's system dependencies.
 * QBM_DIAG_FORCE_SPHAL=1 skips the first attempt (testing).
 */
#define LIBDIAG "/vendor/lib64/libdiag.so"
typedef struct android_namespace_t *(*get_ns_t)(const char *);

static void *open_libdiag(void) {
    char err1[512] = "skipped";
    const char *force = getenv("QBM_DIAG_FORCE_SPHAL");
    if (!force || strcmp(force, "1")) {
        void *h = dlopen(LIBDIAG, RTLD_NOW);
        if (h) return h;
        snprintf(err1, sizeof err1, "%s", dlerror());
    }
    void *dla = dlopen("/apex/com.android.runtime/lib64/bionic/libdl_android.so", RTLD_NOW);
    if (!dla) dla = dlopen("libdl_android.so", RTLD_NOW);
    get_ns_t get_ns = dla ? (get_ns_t)dlsym(dla, "android_get_exported_namespace") : NULL;
    struct android_namespace_t *ns = get_ns ? get_ns("sphal") : NULL;
    if (!ns) {
        fprintf(stderr, "dlopen libdiag: %s\nsphal namespace: not available (%s)\n", err1,
                !dla ? "no libdl_android" : !get_ns ? "no android_get_exported_namespace" : "not exported");
        return NULL;
    }
    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = ns;
    void *h = android_dlopen_ext(LIBDIAG, RTLD_NOW, &info);
    if (!h) {
        fprintf(stderr, "dlopen libdiag: %s\nsphal namespace: %s\n", err1, dlerror());
        return NULL;
    }
    lib_route = "sphal";
    return h;
}

#define SYM(T, name) T name = (T)dlsym(h, #name); if (!name) { fprintf(stderr, "libdiag: missing %s\n", #name); return 1; }

int main(int argc, char **argv) {
    long app_uid = -1;
    for (int i = 1; i + 1 < argc; i++) if (!strcmp(argv[i], "-uid")) app_uid = strtol(argv[i + 1], NULL, 10);
    if (app_uid < 0) { fprintf(stderr, "usage: %s -uid <app uid>\n", argv[0]); return 2; }
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    /* Bind first: a second instance fails here, before touching DIAG. */
    int lfd = listen_socket();
    if (lfd < 0) { fprintf(stderr, "socket %s: %s\n", SOCKET_NAME, strerror(errno)); return 1; }

    void *h = open_libdiag();
    if (!h) return 1;
    SYM(lsm_init_t, Diag_LSM_Init);
    SYM(lsm_deinit_t, Diag_LSM_DeInit);
    SYM(reg_client_t, diag_register_dci_client);
    SYM(rel_client_t, diag_release_dci_client);
    SYM(reg_stream_t, diag_register_dci_stream_proc);
    SYM(log_cfg_t, diag_log_stream_config);

    if (!Diag_LSM_Init(NULL)) { fprintf(stderr, "Diag_LSM_Init failed\n"); return 1; }
    int client = 0, sig = SIGCONT;
    uint16_t periph = DIAG_CON_MPSS;
    int r = diag_register_dci_client(&client, &periph, DIAG_PROC_MSM, &sig);
    if (r != DCI_NO_ERROR) { fprintf(stderr, "DCI register failed: %d\n", r); Diag_LSM_DeInit(); return 1; }
    /* The extended header names each log's subscription (dual SIM). */
    set_version_t diag_dci_set_version = (set_version_t)dlsym(h, "diag_dci_set_version");
    sim_tags = diag_dci_set_version && diag_dci_set_version(client, 1) == DCI_NO_ERROR;
    diag_register_dci_stream_proc(client, on_log, on_event);
    r = diag_log_stream_config(client, 1, CODES, NCODES);
    if (r != DCI_NO_ERROR) {
        fprintf(stderr, "DCI subscribe failed: %d\n", r);
        diag_release_dci_client(&client);
        Diag_LSM_DeInit();
        return 1;
    }
    window_start = now_ms();

    long last_request = now_ms();
    int cfd = -1;
    char in[512];
    size_t in_len = 0;
    static char out[8192];
    while (!stop_flag && now_ms() - last_request < WATCHDOG_MS) {
        struct pollfd pf[2] = { { lfd, POLLIN, 0 }, { cfd, POLLIN, 0 } };
        if (poll(pf, cfd >= 0 ? 2 : 1, 500) < 0 && errno != EINTR) break;
        if (pf[0].revents & POLLIN) {
            int nfd = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
            if (nfd >= 0) {
                struct ucred cr;
                socklen_t cl = sizeof cr;
                if (getsockopt(nfd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) < 0 || ((long)cr.uid != app_uid && cr.uid != 0)) {
                    close(nfd);
                } else {
                    if (cfd >= 0) close(cfd);           /* newest client wins */
                    cfd = nfd;
                    in_len = 0;
                }
            }
        }
        if (cfd >= 0 && (pf[1].revents & (POLLIN | POLLHUP | POLLERR))) {
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
                    int n = write_stats(out, sizeof out, n_sim ? atoi(n_sim + 6) : 1);
                    if (n < 0 || write_all(cfd, out, (size_t)n) < 0) { close(cfd); cfd = -1; break; }
                } else if (strstr(in, "\"stop\"")) {
                    write_all(cfd, "{\"ok\":true,\"stopping\":true}\n", 28);
                    stop_flag = 1;
                } else {
                    char msg[96];
                    int n = snprintf(msg, sizeof msg, "{\"ok\":true,\"version\":\"%s\"}\n", HELPER_VERSION);
                    write_all(cfd, msg, (size_t)n);
                }
                size_t used = (size_t)(nl - in) + 1;
                memmove(in, nl + 1, in_len - used + 1);
                in_len -= used;
            }
            if (in_len >= sizeof in - 1) in_len = 0;    /* overlong line: drop */
        }
    }

    /* Unsubscribe, let in-flight callbacks drain, then release (releasing
     * under load without the drain segfaulted in Diag_LSM_DeInit). */
    diag_log_stream_config(client, 0, CODES, NCODES);
    usleep(500 * 1000);
    stopping = 1;
    usleep(200 * 1000);
    diag_release_dci_client(&client);
    Diag_LSM_DeInit();
    if (cfd >= 0) close(cfd);
    close(lfd);
    return 0;
}
