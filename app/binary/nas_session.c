/*
 * nas_session.c: see nas_session.h. Message layouts from 3GPP TS 24.501
 * (5GSM, clause 8.3 and 9.11.4) and TS 24.301 (ESM, clause 8.3 and 9.9.4).
 * Every other information element is skipped by its length, and nothing
 * else (addresses, identities) is kept.
 */
#include "nas_session.h"

#include <ctype.h>
#include <string.h>
#include <strings.h>

#define SIMS 2
#define IDS 16                 /* PDU session IDs 1-15, EPS bearer IDs 5-15 */

struct entry {
    int present;
    long order;                /* when it was set up; the newest wins */
    int default_qfi;           /* 5GS: the default QoS rule's flow, 0 = unknown */
    struct nas_session value;
};

static struct entry five_gs[SIMS][IDS], eps[SIMS][IDS];
static long next_order;

/* ── Common ──────────────────────────────────────────────────────────────── */

/* The length of the optional IE at m[p] (whole, IEI included); 0 when it
 * runs past the end. `two` lists the IEIs that are 2-octet TV. TLV-E IEIs
 * are 0x7x in both protocols. */
static int ie_size(const uint8_t *m, int n, int p, const uint8_t *two, int ntwo) {
    uint8_t iei = m[p];
    int size;
    if (iei >= 0x80)
        size = 1;
    else if (memchr(two, iei, (size_t)ntwo))
        size = 2;
    else if ((iei & 0xF0) == 0x70)
        size = p + 3 <= n ? 3 + (m[p + 1] << 8 | m[p + 2]) : n + 1;
    else
        size = p + 2 <= n ? 2 + m[p + 1] : n + 1;
    return p + size <= n ? size : 0;
}

/* An APN/DNN: length-prefixed labels, joined with dots. */
static void read_apn(const uint8_t *b, int n, char *out, size_t cap) {
    size_t o = 0;
    for (int p = 0; p < n;) {
        int label = b[p++];
        if (label < 1 || p + label > n) { out[0] = 0; return; }
        if (o && o + 1 < cap) out[o++] = '.';
        for (int i = 0; i < label; i++) {
            if (!isprint(b[p + i]) || b[p + i] == '"' || b[p + i] == '\\') { out[0] = 0; return; }
            if (o + 1 < cap) out[o++] = (char)b[p + i];
        }
        p += label;
    }
    out[o] = 0;
    /* EPS sends the operator identifier too ("internet.mnc004.mcc520.gprs"). */
    if (o > 19) {
        char *mnc = out + o - 19;   /* ".mncDDD.mccDDD.gprs" */
        if (!strncasecmp(mnc, ".mnc", 4) && !strncasecmp(mnc + 7, ".mcc", 4) && !strcasecmp(mnc + 14, ".gprs"))
            *mnc = 0;
    }
}

static int is_data(const struct nas_session *s) {
    char first[8] = { 0 };
    for (int i = 0; i < 7 && s->apn[i] && s->apn[i] != '.'; i++)
        first[i] = (char)tolower((unsigned char)s->apn[i]);
    return strcmp(first, "ims") && strcmp(first, "sos");
}

/* ── 5GS (TS 24.501) ─────────────────────────────────────────────────────── */

/* Session-AMBR (9.11.4.14): unit, 16-bit value, for downlink then uplink.
 * Unit 1 is 1 kbps; each next unit is 4 times larger, and 6 = 1 Mbps,
 * 11 = 1 Gbps (so steps of 1000 every five units). */
static long long ambr_5gs(uint8_t unit, const uint8_t *value) {
    if (unit < 1 || unit > 20)
        return 0;
    long long kbps = value[0] << 8 | value[1];
    for (int i = 0; i < (unit - 1) % 5; i++) kbps *= 4;
    for (int i = 0; i < (unit - 1) / 5; i++) kbps *= 1000;
    return kbps;
}

static void session_ambr(struct entry *e, const uint8_t *b, int n) {
    if (n < 6)
        return;
    e->value.dl_kbps = ambr_5gs(b[0], b + 1);
    e->value.ul_kbps = ambr_5gs(b[3], b + 4);
}

/* QoS rules (9.11.4.13): the default rule's QFI, from a rule being created. */
static void qos_rules(struct entry *e, const uint8_t *b, int n) {
    for (int p = 0; p + 3 <= n;) {
        int len = b[p + 1] << 8 | b[p + 2];
        const uint8_t *rule = b + p + 3;
        if (p + 3 + len > n)
            return;
        int operation = len ? rule[0] >> 5 : 0;
        if (operation == 1 && (rule[0] & 0x10) && len >= 3)
            e->default_qfi = rule[len - 1] & 0x3F;
        p += 3 + len;
    }
}

/* QoS flow descriptions (9.11.4.12): the default flow's 5QI (parameter 1),
 * or the only flow's when the default isn't known. */
static void qos_flows(struct entry *e, const uint8_t *b, int n) {
    int flows = 0, only = 0;
    for (int p = 0; p + 3 <= n;) {
        int qfi = b[p] & 0x3F, count = b[p + 2] & 0x3F, five_qi = 0;
        p += 3;
        for (int i = 0; i < count; i++) {
            if (p + 2 > n || p + 2 + b[p + 1] > n)
                return;
            if (b[p] == 1 && b[p + 1] >= 1)
                five_qi = b[p + 2];
            p += 2 + b[p + 1];
        }
        flows++;
        if (five_qi && qfi == e->default_qfi)
            e->value.qos_class = five_qi;
        if (five_qi)
            only = five_qi;
    }
    if (!e->default_qfi && flows == 1 && only)
        e->value.qos_class = only;
}

static void five_gs_optional(struct entry *e, const uint8_t *m, int n, int p) {
    static const uint8_t two[] = { 0x59, 0x56 };
    while (p < n) {
        int size = ie_size(m, n, p, two, sizeof two);
        if (!size)
            return;
        const uint8_t *body = m + p + ((m[p] & 0xF0) == 0x70 ? 3 : 2);
        int len = size - ((m[p] & 0xF0) == 0x70 ? 3 : 2);
        switch (m[p]) {
        case 0x25: read_apn(body, len, e->value.apn, sizeof e->value.apn); break;   /* DNN */
        case 0x2A: session_ambr(e, body, len); break;                               /* Session-AMBR */
        case 0x7A: qos_rules(e, body, len); break;                                  /* QoS rules */
        case 0x79: qos_flows(e, body, len); break;                                  /* QoS flow descriptions */
        }
        p += size;
    }
}

void nas_session_b800(int sim, const uint8_t *payload, int length) {
    /* Version 1: version, 3 reserved, the release (3), then the message. */
    if (sim < 0 || sim >= SIMS || length < 11 || payload[0] != 1)
        return;
    const uint8_t *m = payload + 7;
    int n = length - 7, id = m[1];
    if (m[0] != 0x2E || id < 1 || id >= IDS)
        return;
    struct entry *e = &five_gs[sim][id];
    switch (m[3]) {
    case 0xC2: {   /* PDU session establishment accept */
        memset(e, 0, sizeof *e);
        int p = 5;   /* after the session type and SSC mode */
        if (p + 2 > n) return;
        int rules = m[p] << 8 | m[p + 1];
        if (p + 2 + rules > n) return;
        qos_rules(e, m + p + 2, rules);
        p += 2 + rules;
        if (p + 1 > n || p + 1 + m[p] > n) return;
        session_ambr(e, m + p + 1, m[p]);
        p += 1 + m[p];
        five_gs_optional(e, m, n, p);
        e->present = 1;
        e->order = ++next_order;
        break;
    }
    case 0xCB:     /* PDU session modification command */
        if (e->present)
            five_gs_optional(e, m, n, 4);
        break;
    case 0xD3:     /* PDU session release command */
        memset(e, 0, sizeof *e);
        break;
    }
}

/* ── EPS (TS 24.301) ─────────────────────────────────────────────────────── */

/* One APN-AMBR direction (9.9.4.2, with 24.008's bit rate coding): octet 3,
 * its extension (octet 5) and its second extension (octet 7), kbps. */
static long long ambr_eps(int base, int ext, int ext2) {
    long long kbps;
    if (ext >= 1 && ext <= 74) kbps = 8600 + ext * 100;
    else if (ext >= 75 && ext <= 186) kbps = 16000 + (ext - 74) * 1000LL;
    else if (ext >= 187 && ext <= 250) kbps = 128000 + (ext - 186) * 2000LL;
    else if (base >= 1 && base <= 63) kbps = base;
    else if (base >= 64 && base <= 127) kbps = 64 + (base - 64) * 8;
    else if (base >= 128 && base <= 254) kbps = 576 + (base - 128) * 64;
    else kbps = 0;
    if (ext2 >= 1 && ext2 <= 254)
        kbps += ext2 * 256000LL;
    return kbps;
}

static void apn_ambr(struct entry *e, const uint8_t *b, int n) {
    if (n < 2)
        return;
    e->value.dl_kbps = ambr_eps(b[0], n >= 3 ? b[2] : 0, n >= 5 ? b[4] : 0);
    e->value.ul_kbps = ambr_eps(b[1], n >= 4 ? b[3] : 0, n >= 6 ? b[5] : 0);
}

static void eps_optional(struct entry *e, const uint8_t *m, int n, int p) {
    static const uint8_t two[] = { 0x32, 0x58 };
    while (p < n) {
        int size = ie_size(m, n, p, two, sizeof two);
        if (!size)
            return;
        int head = (m[p] & 0xF0) == 0x70 ? 3 : 2;
        if (m[p] == 0x5E)                     /* APN-AMBR */
            apn_ambr(e, m + p + head, size - head);
        else if (m[p] == 0x5B && size > head) /* new EPS QoS: the QCI first */
            e->value.qos_class = m[p + head];
        p += size;
    }
}

void nas_session_b0e2(int sim, const uint8_t *payload, int length) {
    /* Version 1: version, the release (3), then the message. */
    if (sim < 0 || sim >= SIMS || length < 7 || payload[0] != 1)
        return;
    const uint8_t *m = payload + 4;
    int n = length - 4, id = m[0] >> 4;
    if ((m[0] & 0x0F) != 2 || id < 5 || id >= IDS)
        return;
    struct entry *e = &eps[sim][id];
    switch (m[2]) {
    case 0xC1: {   /* Activate default EPS bearer context request */
        memset(e, 0, sizeof *e);
        int p = 3;
        if (p + 2 > n || p + 1 + m[p] > n) return;
        e->value.qos_class = m[p + 1];        /* EPS QoS: the QCI first */
        p += 1 + m[p];
        if (p + 1 > n || p + 1 + m[p] > n) return;
        read_apn(m + p + 1, m[p], e->value.apn, sizeof e->value.apn);
        p += 1 + m[p];
        if (p + 1 > n || p + 1 + m[p] > n) return;
        p += 1 + m[p];                        /* PDN address */
        eps_optional(e, m, n, p);
        e->present = 1;
        e->order = ++next_order;
        break;
    }
    case 0xC9:     /* Modify EPS bearer context request */
        if (e->present)
            eps_optional(e, m, n, 3);
        break;
    case 0xCD:     /* Deactivate EPS bearer context request */
        memset(e, 0, sizeof *e);
        break;
    }
}

/* ── Query ───────────────────────────────────────────────────────────────── */

int nas_session_get(int sim, int five_g, struct nas_session *out) {
    if (sim < 0 || sim >= SIMS)
        return 0;
    const struct entry *table = five_g ? five_gs[sim] : eps[sim], *best = NULL;
    for (int i = 0; i < IDS; i++)
        if (table[i].present && is_data(&table[i].value) && (!best || table[i].order > best->order))
            best = &table[i];
    if (!best)
        return 0;
    *out = best->value;
    return 1;
}
