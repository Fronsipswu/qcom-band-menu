/* Packet capture shares the decoder lock but never performs disk/socket I/O.
 * Included after decoder state definitions; all functions are private to qbm-diagd. */
#ifndef QBM_CAPTURE_H
#define QBM_CAPTURE_H

#define CAPTURE_MAX_BYTES (8U * 1024U * 1024U)
#define CAPTURE_MAX_RECORDS 8192U
#define CAPTURE_MAX_RAW (65535U + 12U)

struct json_buffer { char *data; size_t length, capacity; int failed; };

static int json_reserve(struct json_buffer *b, size_t extra) {
    if (b->failed || extra >= CAPTURE_MAX_BYTES + 65536U || b->length > CAPTURE_MAX_BYTES + 65536U - extra - 1) {
        b->failed = 1; return 0;
    }
    size_t need = b->length + extra + 1;
    if (need <= b->capacity) return 1;
    size_t capacity = b->capacity ? b->capacity : 4096;
    while (capacity < need) capacity = capacity > (CAPTURE_MAX_BYTES + 65536U) / 2 ? CAPTURE_MAX_BYTES + 65536U : capacity * 2;
    char *next = realloc(b->data, capacity);
    if (!next) { b->failed = 1; return 0; }
    b->data = next; b->capacity = capacity;
    return 1;
}

static void json_text(struct json_buffer *b, const char *s, size_t n) {
    if (!json_reserve(b, n)) return;
    memcpy(b->data + b->length, s, n); b->length += n; b->data[b->length] = 0;
}

static void json_printf(struct json_buffer *b, const char *fmt, ...) {
    if (b->failed) return;
    va_list args, copy;
    va_start(args, fmt); va_copy(copy, args);
    int n = vsnprintf(NULL, 0, fmt, copy); va_end(copy);
    if (n < 0 || !json_reserve(b, (size_t)n)) { b->failed = 1; va_end(args); return; }
    vsnprintf(b->data + b->length, b->capacity - b->length, fmt, args);
    va_end(args); b->length += (size_t)n;
}

struct capture_event { struct capture_event *next; char *json; size_t length, allocation; };
static struct capture_event *capture_head, *capture_tail;
static size_t capture_bytes, capture_records;
static uint64_t capture_seq, capture_dropped, capture_total_dropped, capture_generation;
static char capture_id[64] = "";
/* Observed identities are independent of UI expiry/held-value policy. */
static struct { int seen; char text[48]; } capture_identities[SIMS][SLOTS * 2 + 1];

struct capture_context { int known, pci; uint32_t arfcn; const struct nr_cell *config; };
struct capture_snapshot {
    struct slot lte[SLOTS];
    struct nr_slot nr[SLOTS];
    int csi[SLOTS], csi_band[SLOTS];
};

static void capture_contexts(struct capture_context *lte_context, struct capture_context *nr_context, int replacing_nr, int packet_identity) {
    memset(lte_context, 0, sizeof(struct capture_context) * SLOTS);
    memset(nr_context, 0, sizeof(struct capture_context) * SLOTS);
    for (int i = 0; i < SLOTS; i++) {
        /* A carrier-list update is authoritative even if old measurements remain live. */
        int use_list = (capture_marks.lte_config & (1U << i)) != 0;
        if (!use_list && live[i].at_ms) lte_context[i] = (struct capture_context){1, live[i].pci, live[i].earfcn, NULL};
        else if (ca[i].known && (i == 0 || ca[i].state > 0))
            lte_context[i] = (struct capture_context){1, ca[i].pci, ca[i].earfcn, NULL};
        if (nrlive[i].at_ms) {
            const struct nr_cell *cfg = nr_cfg_for(nrlive[i].pci, nrlive[i].earfcn);
            if (!replacing_nr || cfg)
                nr_context[i] = (struct capture_context){1, nrlive[i].pci, nrlive[i].earfcn, cfg};
        }
        if ((capture_marks.nr_tb & (1U << i)) && !replacing_nr) {
            const struct nr_cell *cfg = nr_cfg_for(nrtb[i].pci, nrtb[i].arfcn);
            int same = nr_context[i].known && nr_context[i].pci == nrtb[i].pci &&
                (nr_context[i].arfcn == nrtb[i].arfcn || (cfg && nr_context[i].config == cfg));
            if (!same) nr_context[i] = (struct capture_context){1, nrtb[i].pci,
                cfg && cfg->ssb_arfcn ? cfg->ssb_arfcn : nrtb[i].arfcn, cfg};
        }
    }
    /* B825's RRC cell index is not always the ML1 carrier slot. Match live cells first. */
    for (int i = 0; i < SLOTS; i++) {
        if (!nrcc[i].known) continue;
        int claimed = 0;
        for (int j = 0; j < SLOTS; j++) if (nr_context[j].config == &nrcc[i]) claimed = 1;
        if (!claimed && !nr_context[i].known)
            nr_context[i] = (struct capture_context){1, nrcc[i].pci,
                nrcc[i].ssb_arfcn ? nrcc[i].ssb_arfcn : nrcc[i].dl_arfcn, &nrcc[i]};
    }
    /* A slot-only packet must follow the latest captured identity observation,
     * not resurrect a pre-release or pre-handover cached UI measurement. */
    if (packet_identity) for (int i = 0; i < SLOTS; i++) {
        unsigned bit = 1U << i;
        if (!(capture_marks.lte_config & bit) && !(capture_marks.lte_live & bit) && capture_identities[sim][i].seen) {
            unsigned arfcn; int pci;
            int known = sscanf(capture_identities[sim][i].text, "%u:%d", &arfcn, &pci) == 2;
            lte_context[i] = (struct capture_context){ known, known ? pci : 0, known ? arfcn : 0, NULL };
        }
        if (!replacing_nr && !((capture_marks.nr_live | capture_marks.nr_tb) & bit) && capture_identities[sim][SLOTS + i].seen) {
            unsigned arfcn; int pci;
            int known = sscanf(capture_identities[sim][SLOTS + i].text, "%u:%d", &arfcn, &pci) == 2;
            nr_context[i] = (struct capture_context){ known, known ? pci : 0, known ? arfcn : 0,
                known ? nr_cfg_for(pci, arfcn) : NULL };
        }
    }
}

static int capture_pcell(const struct capture_context *c) {
    if (nr_pcell_pci < 0) return -1;
    return c->known && nr_pcell_pci >= 0 && c->pci == nr_pcell_pci &&
        (c->arfcn == nr_pcell_arfcn || (c->config &&
         (c->config->dl_arfcn == nr_pcell_arfcn || c->config->ssb_arfcn == nr_pcell_arfcn)));
}

static void capture_snapshot_take(struct capture_snapshot *s) {
    memcpy(s->lte, win, sizeof s->lte); memcpy(s->nr, nrwin, sizeof s->nr);
    struct capture_context lc[SLOTS], nc[SLOTS];
    /* Snapshot is taken before new packet markers are populated. */
    memset(&capture_marks, 0, sizeof capture_marks);
    capture_contexts(lc, nc, 0, 1);
    for (int i = 0; i < SLOTS; i++) {
        s->csi[i] = nc[i].known ? rrc_csi_ports(nc[i].pci, nc[i].arfcn, capture_pcell(&nc[i])) : 0;
        s->csi_band[i] = nc[i].known ? rrc_csi_band(nc[i].pci, nc[i].arfcn) : 0;
    }
}

struct capture_output { struct json_buffer *updates, *resets; int first, first_reset; int window; };

static void capture_identity(struct capture_output *o, int index, const char *entity, const struct capture_context *context, int release) {
    if (o->window) return;
    char identity[48] = "";
    if (context->known) snprintf(identity, sizeof identity, "%u:%d", context->arfcn, context->pci);
    int changed = capture_identities[sim][index].seen && strcmp(capture_identities[sim][index].text, identity);
    if (changed || release) {
        json_printf(o->resets, "%s\"%s\"", o->first_reset ? "" : ",", entity);
        o->first_reset = 0;
    }
    capture_identities[sim][index].seen = 1;
    snprintf(capture_identities[sim][index].text, sizeof capture_identities[sim][index].text, "%s", identity);
}

static void capture_update_prefix(struct capture_output *o, const char *entity, const struct capture_context *c,
    const char *metric, const char *unit, const char *aggregation) {
    json_printf(o->updates, "%s{\"entity\":\"%s\",\"identity\":", o->first ? "" : ",", entity);
    o->first = 0;
    if (c->known) json_printf(o->updates, "\"%u:%d\"", c->arfcn, c->pci);
    else json_printf(o->updates, "null");
    json_printf(o->updates, ",\"metric\":\"%s\",\"unit\":\"%s\",\"aggregation\":\"%s\"", metric, unit, aggregation);
}

static void capture_mean(struct capture_output *o, const char *e, const struct capture_context *c,
    const char *key, const char *unit, double sum, long count) {
    if (count <= 0 || !isfinite(sum)) return;
    capture_update_prefix(o, e, c, key, unit, "mean");
    json_printf(o->updates, ",\"value\":%.10g,\"sum\":%.12g,\"count\":%ld}", sum / count, sum, count);
}

static void capture_sum(struct capture_output *o, const char *e, const struct capture_context *c,
    const char *key, const char *unit, long value, int zero) {
    if (value < 0 || (!value && !zero)) return;
    capture_update_prefix(o, e, c, key, unit, "sum");
    json_printf(o->updates, ",\"value\":%ld}", value);
}

static void capture_last(struct capture_output *o, const char *e, const struct capture_context *c,
    const char *key, const char *unit, long value, int known) {
    capture_update_prefix(o, e, c, key, unit, "last");
    if (known) json_printf(o->updates, ",\"value\":%ld}", value);
    else json_printf(o->updates, ",\"value\":null}");
}

static void capture_last_double(struct capture_output *o, const char *e, const struct capture_context *c,
    const char *key, const char *unit, double value) {
    capture_update_prefix(o, e, c, key, unit, "last");
    if (value > 0 && isfinite(value)) json_printf(o->updates, ",\"value\":%.10g}", value);
    else json_printf(o->updates, ",\"value\":null}");
}

static void capture_mode(struct capture_output *o, const char *e, const struct capture_context *c,
    const char *key, const char *unit, const long *after, const long *before, int count, int offset,
    const char **labels, const int *value_map) {
    long delta[16] = {0};
    for (int i = 0; i < count; i++) delta[i] = after[i] - before[i];
    int winner = argmax(delta, count);
    if (winner < 0) return;
    capture_update_prefix(o, e, c, key, unit, "mode");
    if (value_map && value_map[winner] <= 0) json_printf(o->updates, ",\"value\":null,\"counts\":{");
    else if (value_map) json_printf(o->updates, ",\"value\":%d,\"counts\":{", value_map[winner]);
    else if (labels) json_printf(o->updates, ",\"value\":\"%s\",\"counts\":{", labels[winner]);
    else json_printf(o->updates, ",\"value\":%d,\"counts\":{", winner + offset);
    int first = 1;
    for (int i = 0; i < count; i++) if (delta[i] > 0) {
        if (labels) json_printf(o->updates, "%s\"%s\":%ld", first ? "" : ",", labels[i], delta[i]);
        else json_printf(o->updates, "%s\"%d\":%ld", first ? "" : ",", i + offset, delta[i]);
        first = 0;
    }
    /* Native argmax keeps the first equally frequent entry. Preserve that
     * order explicitly; alphabetical modulation order is different. */
    json_printf(o->updates, "},\"order\":[");
    for (int i = 0; i < count; i++) {
        if (labels) json_printf(o->updates, "%s\"%s\"", i ? "," : "", labels[i]);
        else json_printf(o->updates, "%s\"%d\"", i ? "," : "", i + offset);
    }
    json_printf(o->updates, "]");
    if (value_map) {
        json_printf(o->updates, ",\"map\":{");
        for (int i = 0; i < count; i++) {
            json_printf(o->updates, "%s\"%d\":", i ? "," : "", i + offset);
            if (value_map[i] > 0) json_printf(o->updates, "%d", value_map[i]);
            else json_printf(o->updates, "null");
        }
        json_printf(o->updates, "}");
    }
    json_printf(o->updates, "}");
}

static void capture_bler(struct capture_output *o, const char *e, const struct capture_context *c, long fail, long tb) {
    if (tb <= 0 || fail < 0) return;
    capture_update_prefix(o, e, c, "bler", "%", "ratio");
    json_printf(o->updates, ",\"value\":%.10g,\"numerator\":%ld,\"denominator\":%ld,\"scale\":100}", fail * 100.0 / tb, fail, tb);
}

static void capture_emit_updates(struct capture_output *o, const struct capture_snapshot *old) {
    struct capture_context lc[SLOTS], nc[SLOTS];
    capture_contexts(lc, nc, !o->window && capture_marks.nr_config, !o->window);
    /* A provisional B825 RRC index can later be named by a different ML1
     * carrier slot. Retire the old alias rather than holding a duplicate cell. */
    if (!o->window) for (int i = 0; i < SLOTS; i++) {
        if (!(capture_marks.nr_live & (1U << i)) || !nc[i].known) continue;
        char identity[48]; snprintf(identity, sizeof identity, "%u:%d", nc[i].arfcn, nc[i].pci);
        for (int j = 0; j < SLOTS; j++) {
            if (j == i || (capture_marks.nr_live & (1U << j)) || !capture_identities[sim][SLOTS + j].seen ||
                strcmp(identity, capture_identities[sim][SLOTS + j].text)) continue;
            char entity[24]; snprintf(entity, sizeof entity, "nr:%d", j);
            nc[j] = (struct capture_context){0};
            capture_identity(o, SLOTS + j, entity, &nc[j], 1);
        }
    }
    for (int i = 0; i < SLOTS; i++) {
        const struct slot *a = &win[i], *b = &old->lte[i];
        int config = !o->window && (capture_marks.lte_config & (1U << i));
        int observed = memcmp(a, b, sizeof *a) || config || (!o->window && (capture_marks.lte_live & (1U << i)));
        int zero = o->window && lc[i].known;
        if (!observed && !zero) continue;
        char entity[24]; snprintf(entity, sizeof entity, "lte:%d", i);
        capture_identity(o, i, entity, &lc[i], config && !lc[i].known);
#define LTE_MEAN(key, unit, sumfield, nfield) capture_mean(o, entity, &lc[i], key, unit, a->sumfield - b->sumfield, a->nfield - b->nfield)
        LTE_MEAN("rsrp", "dBm", rsrp_sum, n_rsrp); LTE_MEAN("rsrq", "dB", rsrq_sum, n_rsrq);
        LTE_MEAN("sinr", "dB", sinr_sum, n_sinr);
        int fchanged = a->f_earfcn != b->f_earfcn || a->f_pci != b->f_pci;
        capture_mean(o, entity, &lc[i], "frsrp", "dBm", a->frsrp_sum - (fchanged ? 0 : b->frsrp_sum), a->n_frsrp - (fchanged ? 0 : b->n_frsrp));
        capture_mean(o, entity, &lc[i], "frsrq", "dB", a->frsrq_sum - (fchanged ? 0 : b->frsrq_sum), a->n_frsrq - (fchanged ? 0 : b->n_frsrq));
        LTE_MEAN("mcs", "index", mcs_sum, mcs_tb);
        /* Legacy v3/v4 accumulate their all-TB MCS in mcs_sum only. Keep raw
         * capture truthful without changing the existing UI decoder counters. */
        long all_mcs = a->mcs_all_sum - b->mcs_all_sum;
        if (!all_mcs && a->mcs_tb == b->mcs_tb && a->mcs_sum > b->mcs_sum) all_mcs = a->mcs_sum - b->mcs_sum;
        capture_mean(o, entity, &lc[i], "mcs_all", "index", all_mcs, a->tb - b->tb);
        LTE_MEAN("rbs", "RB", rb_sum, tb); LTE_MEAN("layers", "layers", layers_sum, recs);
#undef LTE_MEAN
#define LTE_SUM(key, unit, field) capture_sum(o, entity, &lc[i], key, unit, a->field - b->field, zero)
        LTE_SUM("dl_bytes", "bytes", bytes_all); LTE_SUM("dl_good_bytes", "bytes", bytes_pass);
        LTE_SUM("ul_bytes", "bytes", ul_bytes); LTE_SUM("tb", "count", tb); LTE_SUM("fail", "count", fail); LTE_SUM("ul_tb", "count", ul_tb);
#undef LTE_SUM
        capture_bler(o, entity, &lc[i], a->fail - b->fail, a->tb - b->tb);
        capture_mode(o, entity, &lc[i], "mod", "modulation", a->mod, b->mod, 5, 0, MOD_NAMES, NULL);
        capture_mode(o, entity, &lc[i], "rank", "layers", a->rank, b->rank, 4, 1, NULL, NULL);
        capture_mode(o, entity, &lc[i], "scheme", "index", a->scheme, b->scheme, 16, 0, NULL, NULL);
        if (a->n126 > b->n126) {
            int map[16]; for (int k = 0; k < 16; k++) map[k] = scheme_to_tm(k);
            /* Select the dominant raw scheme first, then map to TM. Pooling
             * different schemes that map to TM9 changes the existing UI rule. */
            capture_mode(o, entity, &lc[i], "tm", "index", a->scheme, b->scheme, 16, 0, NULL, map);
            if (o->window || (capture_marks.lte_tx & (1U << i)))
                capture_last(o, entity, &lc[i], "tx", "antennas", a->tx, a->tx > 0);
            capture_last(o, entity, &lc[i], "rx", "antennas", a->rx, a->rx > 0);
        }
        if (!o->window && (config || (capture_marks.lte_live & (1U << i)))) {
            capture_last(o, entity, &lc[i], "earfcn", "channel", lc[i].arfcn, lc[i].known);
            capture_last(o, entity, &lc[i], "pci", "index", lc[i].pci, lc[i].known);
        }
        if (config && ca[i].known) {
            capture_last(o, entity, &lc[i], "state", "index", ca[i].state, 1);
            capture_last(o, entity, &lc[i], "band", "band", ca[i].band, ca[i].band > 0);
            capture_last_double(o, entity, &lc[i], "bw", "MHz", bw_mhz(ca[i].bw_raw));
        }
    }
    for (int i = 0; i < SLOTS; i++) {
        const struct nr_slot *a = &nrwin[i], *b = &old->nr[i];
        int config = !o->window && capture_marks.nr_config;
        int csf = o->window ? a->have_csf : (capture_marks.nr_csf & (1U << i));
        int csi = nc[i].known ? rrc_csi_ports(nc[i].pci, nc[i].arfcn, capture_pcell(&nc[i])) : 0;
        int csi_band = nc[i].known ? rrc_csi_band(nc[i].pci, nc[i].arfcn) : 0;
        int rrc_changed = !o->window && capture_marks.rrc && (csi != old->csi[i] || csi_band != old->csi_band[i]);
        int meta = !o->window && ((capture_marks.nr_live | capture_marks.nr_tb) & (1U << i));
        int zero = o->window && nc[i].known;
        if (!memcmp(a, b, sizeof *a) && !config && !csf && !meta && !rrc_changed && !zero) continue;
        char entity[24]; snprintf(entity, sizeof entity, "nr:%d", i);
        capture_identity(o, SLOTS + i, entity, &nc[i], config && !nc[i].known);
#define NR_MEAN(key, unit, sumfield, nfield) capture_mean(o, entity, &nc[i], key, unit, a->sumfield - b->sumfield, a->nfield - b->nfield)
        NR_MEAN("rsrp", "dBm", rsrp_sum, n_rsrp); NR_MEAN("rsrq", "dB", rsrq_sum, n_rsrq);
        NR_MEAN("sinr", "dB", sinr_sum, n_sinr); NR_MEAN("csinr", "dB", csinr_sum, n_csinr); NR_MEAN("bsinr", "dB", bsinr_sum, n_bsinr);
        NR_MEAN("mcs", "index", mcs_sum, mcs_tb); NR_MEAN("mcs_all", "index", mcs_all_sum, tb);
        NR_MEAN("rbs", "RB", rb_sum, tb); NR_MEAN("layers", "layers", layers_sum, tb);
#undef NR_MEAN
#define NR_SUM(key, unit, field) capture_sum(o, entity, &nc[i], key, unit, a->field - b->field, zero)
        NR_SUM("dl_bytes", "bytes", bytes_all); NR_SUM("dl_good_bytes", "bytes", bytes_pass);
        NR_SUM("ul_bytes", "bytes", ul_bytes); NR_SUM("tb", "count", tb); NR_SUM("fail", "count", fail); NR_SUM("ul_tb", "count", ul_tb);
#undef NR_SUM
        capture_bler(o, entity, &nc[i], a->fail - b->fail, a->tb - b->tb);
        capture_mode(o, entity, &nc[i], "mod", "modulation", a->mod, b->mod, 5, 0, MOD_NAMES, NULL);
        capture_mode(o, entity, &nc[i], "rx", "antennas", a->rx, b->rx, 4, 1, NULL, NULL);
        if (csf) { capture_last(o, entity, &nc[i], "cqi", "index", a->cqi, 1); capture_last(o, entity, &nc[i], "ri", "layers", a->ri, 1); }
        if (config || meta) {
            capture_last(o, entity, &nc[i], "arfcn", "channel", nc[i].arfcn, nc[i].known);
            capture_last(o, entity, &nc[i], "pci", "index", nc[i].pci, nc[i].known);
        }
        const struct nr_cell *cfg = nc[i].config;
        if (config && cfg) {
            capture_last(o, entity, &nc[i], "dl_arfcn", "channel", cfg->dl_arfcn, cfg->dl_arfcn > 0);
            capture_last(o, entity, &nc[i], "band", "band", cfg->band, cfg->band > 0);
            capture_last(o, entity, &nc[i], "bw", "MHz", cfg->bw_mhz, cfg->bw_mhz > 0);
            capture_last(o, entity, &nc[i], "scs", "kHz", cfg->scs_khz, cfg->scs_khz > 0);
            capture_last(o, entity, &nc[i], "max_layers", "layers", cfg->max_layers, cfg->max_layers > 0);
        }
        if (!o->window && (capture_marks.nr_tb & (1U << i))) {
            capture_last(o, entity, &nc[i], "bw", "MHz", nrtb[i].bw_mhz, nrtb[i].bw_mhz > 0);
            capture_last(o, entity, &nc[i], "scs", "kHz", nrtb[i].scs_khz, nrtb[i].scs_khz > 0);
        }
        if (rrc_changed) {
            capture_last(o, entity, &nc[i], "csi_ports", "ports", csi, csi > 0);
            capture_last(o, entity, &nc[i], "rrc_band", "band", csi_band, csi_band > 0);
        }
    }
    if (!o->window && capture_marks.saw_lte_ta) {
        capture_identity(o, 0, "lte:0", &lc[0], 0);
        capture_last(o, "lte:0", &lc[0], "ta", "index", lte_ta.value, 1);
    }
    if (!o->window && (capture_marks.nr_serving || capture_marks.saw_nr_ta)) {
        struct capture_context serving = { nr_pcell_pci >= 0, nr_pcell_pci, nr_pcell_arfcn, NULL };
        capture_identity(o, SLOTS * 2, "nr:serving", &serving, 0);
        if (capture_marks.nr_serving) {
            capture_last(o, "nr:serving", &serving, "pci", "index", nr_pcell_pci, nr_pcell_pci >= 0);
            capture_last(o, "nr:serving", &serving, "arfcn", "channel", nr_pcell_arfcn, nr_pcell_arfcn > 0);
            capture_last(o, "nr:serving", &serving, "band", "band", nr_pcell_band, nr_pcell_band > 0);
        }
        if (capture_marks.saw_nr_ta) capture_last(o, "nr:serving", &serving, "ta", "index", nr_ta.value, 1);
    }
}

static void capture_discard_head(void) {
    struct capture_event *e = capture_head;
    if (!e) return;
    capture_head = e->next;
    if (!capture_head) capture_tail = NULL;
    capture_bytes -= e->allocation; capture_records--;
    free(e->json); free(e);
}

static void capture_packet(const struct capture_snapshot *before, const uint8_t *raw, int raw_length,
    const uint8_t *log, uint16_t code, int tagged, uint64_t boot_ns, uint64_t mono_ns) {
    uint64_t seq = ++capture_seq;
    if (raw_length < 12 || (unsigned)raw_length > CAPTURE_MAX_RAW || seq > 9007199254740991ULL) { capture_dropped++; capture_total_dropped++; return; }
    struct json_buffer packet = {0}, updates = {0}, resets = {0};
    struct capture_output o = { &updates, &resets, 1, 1, 0 };
    capture_emit_updates(&o, before);
    uint64_t modem_time = (uint64_t)le32(log + 4) | (uint64_t)le32(log + 8) << 32;
    json_printf(&packet, "{\"type\":\"packet\",\"seq\":%" PRIu64 ",\"tNs\":\"%" PRIu64 "\",\"monoNs\":\"%" PRIu64
        "\",\"modemTs\":\"%016" PRIx64 "\",\"sim\":%d,\"simTagged\":%s,\"code\":\"0x%04X\",\"raw\":\"",
        seq, boot_ns, mono_ns, modem_time, sim + 1, tagged ? "true" : "false", code);
    if (json_reserve(&packet, (size_t)raw_length * 2)) {
        static const char hex[] = "0123456789abcdef";
        for (int i = 0; i < raw_length; i++) { packet.data[packet.length++] = hex[raw[i] >> 4]; packet.data[packet.length++] = hex[raw[i] & 15]; }
        packet.data[packet.length] = 0;
    }
    json_printf(&packet, "\",\"updates\":[%s],\"resets\":[%s]}", updates.data ? updates.data : "", resets.data ? resets.data : "");
    int failed = packet.failed || updates.failed || resets.failed;
    free(updates.data); free(resets.data);
    struct capture_event *event = failed ? NULL : malloc(sizeof *event);
    if (!event) { free(packet.data); capture_dropped++; capture_total_dropped++; return; }
    *event = (struct capture_event){ NULL, packet.data, packet.length, packet.capacity + sizeof *event };
    while (capture_head && (capture_records >= CAPTURE_MAX_RECORDS || capture_bytes + event->allocation > CAPTURE_MAX_BYTES)) {
        capture_discard_head(); capture_dropped++; capture_total_dropped++;
    }
    if (event->allocation > CAPTURE_MAX_BYTES) { free(event->json); free(event); capture_dropped++; capture_total_dropped++; return; }
    if (capture_tail) capture_tail->next = event; else capture_head = event;
    capture_tail = event; capture_records++; capture_bytes += event->allocation;
}

static void capture_window(struct json_buffer *out) {
    struct capture_snapshot empty = {0};
    struct json_buffer resets = {0};
    struct capture_output o = { out, &resets, 1, 1, 1 };
    /* Last-packet markers must not alter the identity of a whole polling window. */
    unsigned saved_lte = capture_marks.lte_config, saved_tb = capture_marks.nr_tb;
    capture_marks.lte_config = capture_marks.nr_tb = 0;
    json_printf(out, "["); capture_emit_updates(&o, &empty); json_printf(out, "]");
    capture_marks.lte_config = saved_lte; capture_marks.nr_tb = saved_tb;
    free(resets.data);
}

struct capture_request { char cmd[16]; int enabled, limit, sim; };
static void capture_space(const char **p) { while (isspace((unsigned char)**p)) (*p)++; }
static int capture_string(const char **p, char *out, size_t cap) {
    if (*(*p)++ != '"') return 0;
    size_t n = 0;
    while (**p && **p != '"') {
        unsigned char c = (unsigned char)*(*p)++;
        if (c < 32 || c == '\\' || n + 1 >= cap) return 0;
        out[n++] = (char)c;
    }
    if (**p != '"') return 0;
    (*p)++; out[n] = 0; return 1;
}
static int capture_integer(const char **p, int *value) {
    if (!isdigit((unsigned char)**p)) return 0;
    unsigned long n = 0;
    while (isdigit((unsigned char)**p)) { n = n * 10 + (unsigned)(*(*p)++ - '0'); if (n > 100000) return 0; }
    *value = (int)n; return 1;
}
static int capture_parse_request(const char *p, struct capture_request *r) {
    *r = (struct capture_request){ "", -1, 256, 1 };
    unsigned seen = 0;
    capture_space(&p); if (*p++ != '{') return 0;
    capture_space(&p);
    while (*p && *p != '}') {
        char key[16]; if (!capture_string(&p, key, sizeof key)) return 0;
        capture_space(&p); if (*p++ != ':') return 0; capture_space(&p);
        unsigned bit;
        if (!strcmp(key, "cmd")) { bit = 1; if (!capture_string(&p, r->cmd, sizeof r->cmd)) return 0; }
        else if (!strcmp(key, "enabled")) {
            bit = 2;
            if (!strncmp(p, "true", 4)) { r->enabled = 1; p += 4; }
            else if (!strncmp(p, "false", 5)) { r->enabled = 0; p += 5; }
            else return 0;
        } else if (!strcmp(key, "limit")) { bit = 4; if (!capture_integer(&p, &r->limit)) return 0; }
        else if (!strcmp(key, "sim")) { bit = 8; if (!capture_integer(&p, &r->sim)) return 0; }
        else return 0;
        if (seen & bit) return 0;
        seen |= bit;
        capture_space(&p);
        if (*p != ',') break;
        p++; capture_space(&p); if (*p == '}') return 0;
    }
    if (*p++ != '}') return 0;
    capture_space(&p);
    if (*p || !(seen & 1) || r->sim < 1 || r->sim > SIMS || r->limit < 1 || r->limit > 256) return 0;
    if (!strcmp(r->cmd, "capture")) return r->enabled >= 0 && !(seen & (4 | 8));
    if (!strcmp(r->cmd, "events")) return !(seen & (2 | 8));
    if (!strcmp(r->cmd, "stats")) return !(seen & (2 | 4));
    return (!strcmp(r->cmd, "stop") || !strcmp(r->cmd, "version")) && seen == 1;
}

static int capture_peer_allowed(long actual, long allowed) { return actual == 0 || actual == allowed; }

/* The caller restores reported drops and counts undelivered drained records if writing fails. */
static size_t capture_command(const struct capture_request *r, struct json_buffer *out, uint64_t *reported_drops) {
    pthread_mutex_lock(&lock);
    size_t drained = 0;
    if (reported_drops) *reported_drops = 0;
    if (!strcmp(r->cmd, "capture")) {
        if (r->enabled) {
            while (capture_head) capture_discard_head();
            capture_dropped = capture_total_dropped = capture_seq = 0;
            memset(capture_identities, 0, sizeof capture_identities);
            snprintf(capture_id, sizeof capture_id, "%" PRIu64 "-%" PRIu64, clock_ns(CLOCK_BOOTTIME), ++capture_generation);
        }
        capture_enabled = r->enabled;
        json_printf(out, "{\"ok\":true,\"version\":\"%s\",\"captureId\":\"%s\",\"enabled\":%s}\n",
            HELPER_VERSION, capture_id, capture_enabled ? "true" : "false");
    } else {
        json_printf(out, "{\"ok\":true,\"version\":\"%s\",\"captureId\":\"%s\",\"dropped\":%" PRIu64 ",\"totalDropped\":%" PRIu64 ",\"events\":[",
            HELPER_VERSION, capture_id, capture_dropped, capture_total_dropped);
        struct capture_event *e = capture_head;
        while (e && drained < (size_t)r->limit) {
            if (drained) json_printf(out, ",");
            json_text(out, e->json, e->length); e = e->next; drained++;
        }
        json_printf(out, "]}\n");
        if (!out->failed) {
            for (size_t i = 0; i < drained; i++) capture_discard_head();
            if (reported_drops) *reported_drops = capture_dropped;
            capture_dropped = 0;
        } else drained = 0;
    }
    pthread_mutex_unlock(&lock);
    return drained;
}

#endif
