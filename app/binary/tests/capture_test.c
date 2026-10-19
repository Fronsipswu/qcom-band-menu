#define QBM_NATIVE_TEST 1
#include "../qbm-diagd.c"
#include <assert.h>

static void put16(uint8_t *p, unsigned value) { p[0] = value; p[1] = value >> 8; }
static void put32(uint8_t *p, uint32_t value) { for (int i = 0; i < 4; i++) p[i] = value >> (i * 8); }
static void field(uint8_t *p, struct pos f, uint32_t value) {
    for (int i = 0; i < f.bits; i++) {
        unsigned bit = f.off + i;
        p[bit / 8] = (uint8_t)((p[bit / 8] & ~(1U << (bit % 8))) | (((value >> i) & 1U) << (bit % 8)));
    }
}
static void emit(uint16_t code, const uint8_t *payload, int bytes, int subscription, int tagged, long millis) {
    uint8_t frame[4096] = {0};
    int offset = tagged ? 12 : 0;
    assert(bytes + offset + 12 < (int)sizeof frame);
    if (tagged) { frame[0] = 0x98; frame[1] = 1; put32(frame + 4, subscription); put32(frame + 8, UINT32_MAX); }
    put16(frame + offset, bytes + 12); put16(frame + offset + 2, code);
    put32(frame + offset + 4, 0x89abcdefU); put32(frame + offset + 8, 0x01234567U);
    memcpy(frame + offset + 12, payload, bytes);
    test_mono_ns = (uint64_t)millis * 1000000ULL;
    test_boot_ns = test_mono_ns + 7000000000ULL;
    on_log(frame, bytes + offset + 12);
}
static void lte_config(int pci, int secondary, long millis) {
    const struct b184_layout *L = &B184_LAYOUTS[2];
    uint8_t payload[128] = {0}; payload[0] = L->ver;
    field(payload, L->sc_count, secondary);
    uint8_t *pc = payload + L->pcell_at / 8;
    field(pc, L->p_earfcn, 1650); field(pc, L->p_pci, pci); field(pc, L->p_band, 3); field(pc, L->p_bw, 100);
    if (secondary) {
        uint8_t *sc = payload + L->sc_start / 8;
        field(sc, L->s_earfcn, 1700); field(sc, L->s_pci, 22); field(sc, L->s_band, 3);
        field(sc, L->s_bw, 50); field(sc, L->s_state, 2);
    }
    emit(0xB184, payload, L->sc_start / 8 + secondary * L->sc_bits / 8, 1, 1, millis);
}
static void lte_tb(int two, int subscription, long millis) {
    const struct b173_layout *L = &B173_LAYOUTS[1];
    uint8_t payload[128] = {0}; payload[0] = L->ver; field(payload, L->rec_count, 1);
    uint8_t *record = payload + L->rec_start / 8;
    field(record, L->layers, two ? 2 : 1); field(record, L->ntb, two ? 2 : 1); field(record, L->carrier, 0);
    for (int i = 0; i < (two ? 2 : 1); i++) {
        uint8_t *tb = record + (L->tb_start + i * L->tb_bits) / 8;
        field(tb, L->rnti, 0); field(tb, L->crc, i == 0);
        field(tb, L->tb_size, two ? (i + 1) * 1000 : 500);
        field(tb, L->mcs, two ? (i + 1) * 10 : 4); field(tb, L->rbs, two ? (i + 1) * 10 : 8);
        field(tb, L->mod, i ? 4 : 2);
    }
    emit(0xB173, payload, (L->rec_start + L->rec_bits) / 8, subscription, 1, millis);
}
static void lte_signal(int combined, int chain, long millis) {
    const struct b193_layout *L = NULL; FIND_LAYOUT(B193_LAYOUTS, 40, L); assert(L);
    uint8_t payload[2048] = {0}; payload[0] = 1; payload[1] = 1; payload[4] = 0x19; payload[5] = L->ver;
    int body_size = (L->cells_at + L->cell_bits) / 8;
    put16(payload + 6, body_size + 4);
    uint8_t *body = payload + 8, *cell = body + L->cells_at / 8;
    field(body, L->earfcn, 1650); field(body, L->count, 1);
    field(cell, L->pci, 11); field(cell, L->slot, 0); field(cell, L->serving, 1); field(cell, L->valid_rx, 1);
    field(cell, L->rsrp, combined); field(cell, L->rsrp_ch[0], chain);
    field(cell, L->rsrq, 320); field(cell, L->rsrq_ch[0], 320); field(cell, L->snr_ch[0], 300);
    emit(0xB193, payload, 8 + body_size, 1, 1, millis);
}
static void nr_snr(long millis, int tenth) {
    const struct b8d8_layout *L = &B8D8_LAYOUTS[0]; uint8_t payload[24] = {0};
    put32(payload, L->ver); field(payload, L->carrier, 1); field(payload, L->snr[0], tenth == 10 ? 10240 : 102400);
    emit(0xB8D8, payload, sizeof payload, 1, 1, millis);
}
static void nr_csf(long millis) {
    const struct b8a7_layout *L = &B8A7_LAYOUTS[0]; uint8_t payload[256] = {0};
    put32(payload, L->ver); field(payload, L->rec_count, 1);
    uint8_t *record = payload + L->rec_start / 8; field(record, L->rep_count, 1);
    uint8_t *report = record + L->reps_at / 8;
    field(report, L->carrier, 2); field(report, L->ri, 2); field(report, L->cqi, 12);
    emit(0xB8A7, payload, (L->rec_start + L->reps_at + L->rep_bits) / 8, 2, 1, millis);
}
static void lte_schemes(int second, long millis) {
    const struct b126_layout *L = NULL; FIND_LAYOUT(B126_LAYOUTS, 144, L); assert(L);
    const int first[] = {4, 4, 4, 8, 8, 9, 9}, later[] = {8, 8, 9, 9};
    const int *values = second ? later : first;
    int count = second ? 4 : 7;
    uint8_t payload[1024] = {0}; payload[0] = L->ver; field(payload, L->rec_count, count);
    for (int i = 0; i < count; i++) {
        uint8_t *record = payload + (L->rec_start + i * L->rec_bits) / 8;
        field(record, L->rnti, 0); field(record, L->tx, 1); field(record, L->rx, 1);
        field(record, L->rank, values[i] == 4 ? 0 : 1); field(record, L->scheme, values[i]);
    }
    emit(0xB126, payload, (L->rec_start + count * L->rec_bits) / 8, 1, 1, millis);
}
static void nr_alias(void) {
    const struct b825_layout *C = NULL;
    for (size_t i = 0; i < sizeof B825_LAYOUTS / sizeof B825_LAYOUTS[0]; i++)
        if (B825_LAYOUTS[i].ssb_arfcn.bits) { C = &B825_LAYOUTS[i]; break; }
    assert(C);
    uint8_t payload[1024] = {0}; put32(payload, C->ver); field(payload, C->cell_count, 1);
    uint8_t *cell = payload + C->arrays_at / 8;
    field(cell, C->cc, 3); field(cell, C->pci, 9); field(cell, C->dl_arfcn, 640000);
    field(cell, C->ssb_arfcn, 640000); field(cell, C->band, 78); field(cell, C->bw, 3);
    emit(0xB825, payload, (C->arrays_at + C->cell_bits) / 8, 1, 1, 1200);
    const struct b97f_layout *L = NULL;
    for (size_t i = 0; i < sizeof B97F_LAYOUTS / sizeof B97F_LAYOUTS[0]; i++)
        if (B97F_LAYOUTS[i].cc.bits && B97F_LAYOUTS[i].arfcn.bits) { L = &B97F_LAYOUTS[i]; break; }
    assert(L); memset(payload, 0, sizeof payload); put32(payload, L->ver); field(payload, L->layer_count, 1);
    uint8_t *layer = payload + L->layers_at / 8; cell = layer + L->cells_at / 8;
    field(layer, L->arfcn, 640000); field(layer, L->cc, 1); field(layer, L->ncell, 1); field(layer, L->spci, 9);
    field(cell, L->pci, 9); field(cell, L->nbeam, 0); field(cell, L->rsrp, (uint32_t)(-95 * 128)); field(cell, L->rsrq, (uint32_t)(-10 * 128));
    emit(0xB97F, payload, (L->layers_at + L->cells_at + L->beams_at) / 8, 1, 1, 1220);
}
static void reset_decoders(void) {
    memset(win_s, 0, sizeof win_s); memset(nrwin_s, 0, sizeof nrwin_s); memset(ca_s, 0, sizeof ca_s);
    memset(ca_seen_s, 0, sizeof ca_seen_s); memset(live_s, 0, sizeof live_s); memset(nrlive_s, 0, sizeof nrlive_s);
    memset(nrcc_s, 0, sizeof nrcc_s); memset(nrtb_s, 0, sizeof nrtb_s); memset(last_log_s, 0, sizeof last_log_s);
    memset(lte_held_s, 0, sizeof lte_held_s); memset(nr_held_s, 0, sizeof nr_held_s); memset(rsrp_base_votes, 0, sizeof rsrp_base_votes);
    memset(lte_ta_s, 0, sizeof lte_ta_s); memset(nr_ta_s, 0, sizeof nr_ta_s); memset(&capture_marks, 0, sizeof capture_marks);
    sim = 0; sim_tags = 1; n_logs = 0; stopping = 0; window_start = 1000;
    test_mono_ns = 1000000000ULL; test_boot_ns = 8000000000ULL;
}
static void print_case(const char *name, const char *json) {
    size_t length = strlen(json); while (length && json[length - 1] == '\n') length--;
    printf("{\"case\":\"%s\",\"data\":", name); fwrite(json, 1, length, stdout); puts("}");
}
static struct json_buffer command(const char *text) {
    struct capture_request r; assert(capture_parse_request(text, &r)); struct json_buffer out = {0};
    capture_command(&r, &out, NULL); assert(!out.failed); return out;
}
static void toggle(int on) { struct json_buffer b = command(on ? "{\"cmd\":\"capture\",\"enabled\":true}" : "{\"cmd\":\"capture\",\"enabled\":false}"); free(b.data); }
static void traffic(void) {
    lte_config(11, 1, 1000); lte_tb(1, 1, 1005); lte_tb(0, 1, 1025);
    nr_snr(1030, 10); nr_snr(1035, 20); nr_csf(1040); nr_csf(1045);
    lte_signal(600, 1240, 1050); lte_signal(640, 1280, 1055);
    uint8_t unknown[4] = {0xff, 0, 0, 0}; emit(0xBEEE, unknown, sizeof unknown, 1, 0, 1060);
}
int main(void) {
    struct capture_request r;
    const char *invalid[] = {"", "{}", "{\"cmd\":\"events\",\"limit\":0}", "{\"cmd\":\"events\",\"limit\":257}",
        "{\"cmd\":\"events\",\"limit\":-1}", "{\"cmd\":\"events\",\"limit\":999999999999999999999}",
        "{\"cmd\":\"capture\"}", "{\"cmd\":\"capture\",\"enabled\":1}", "{\"cmd\":\"capture\",\"enabled\":truefalse}",
        "{\"cmd\":\"events\",\"cmd\":\"stats\"}", "{\"cmd\":\"events\",}", "{\"cmd\":\"stats\",\"sim\":3}",
        "{\"cmd\":\"stats\",\"note\":\"capture\"}", "{\"cmd\":\"st\\u0061ts\"}", "{\"cmd\":\"events\"}junk"};
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) assert(!capture_parse_request(invalid[i], &r));
    assert(capture_parse_request(" { \"cmd\" : \"events\", \"limit\" : 256 } ", &r) && r.limit == 256);
    assert(capture_parse_request("{\"cmd\":\"stats\",\"sim\":2}", &r) && r.sim == 2);
    assert(capture_peer_allowed(12345, 12345)); assert(capture_peer_allowed(0, 12345)); assert(!capture_peer_allowed(10000, 12345));
    char stats[131072]; reset_decoders(); toggle(0); traffic();
    assert(capture_records == 0); test_mono_ns = 1100000000ULL; test_boot_ns = 8100000000ULL;
    assert(write_stats(stats, sizeof stats, 1) > 0); print_case("stats_off", stats);
    reset_decoders(); toggle(1); traffic();
    struct slot saved[SLOTS]; memcpy(saved, win_s[0], sizeof saved);
    assert(capture_records == 10);
    struct json_buffer events = command("{\"cmd\":\"events\",\"limit\":256}");
    assert(!memcmp(saved, win_s[0], sizeof saved)); assert(window_start == 1000); print_case("packets", events.data); free(events.data);
    test_mono_ns = 1100000000ULL; test_boot_ns = 8100000000ULL;
    assert(write_stats(stats, sizeof stats, 1) > 0); print_case("stats_on", stats);
    assert(win_s[0][0].tb == 0 && nrwin_s[1][2].have_csf == 0);
    lte_config(33, 0, 1110); lte_tb(0, 1, 1115);
    char old_id[64]; strcpy(old_id, capture_id); toggle(0);
    assert(!strcmp(old_id, capture_id) && capture_records == 2);
    lte_tb(0, 1, 1120); assert(capture_records == 2);
    events = command("{\"cmd\":\"events\",\"limit\":256}"); print_case("released", events.data); free(events.data);
    assert(capture_records == 0); assert(win_s[0][0].tb == 2);
    toggle(1); assert(strcmp(old_id, capture_id)); assert(capture_seq == 0 && win_s[0][0].tb == 2);
    nr_alias(); events = command("{\"cmd\":\"events\",\"limit\":256}"); print_case("aliases", events.data); free(events.data);
    reset_decoders(); toggle(1); lte_config(11, 0, 1000); lte_schemes(0, 1005); lte_schemes(1, 1025);
    events = command("{\"cmd\":\"events\",\"limit\":256}"); print_case("modes", events.data); free(events.data);
    test_mono_ns = 1100000000ULL; test_boot_ns = 8100000000ULL;
    assert(write_stats(stats, sizeof stats, 1) > 0); print_case("mode_stats", stats);
    toggle(1);
    /* Queue byte/record limits and dropped-oldest accounting under a producer burst. */
    uint8_t payload[1024] = {0};
    for (int i = 0; i < 9000; i++) emit(0xBEEE, payload, sizeof payload, 1, 0, 2000 + i);
    assert(capture_bytes <= CAPTURE_MAX_BYTES && capture_records <= CAPTURE_MAX_RECORDS);
    assert(capture_dropped > 0 && capture_records + capture_dropped == 9000);
    printf("{\"case\":\"queue\",\"data\":{\"bytes\":%zu,\"records\":%zu,\"dropped\":%" PRIu64 "}}\n", capture_bytes, capture_records, capture_dropped);
    size_t remaining = capture_records;
    events = command("{\"cmd\":\"events\",\"limit\":256}"); print_case("overflow", events.data); free(events.data);
    assert(capture_records == remaining - 256 && capture_dropped == 0 && capture_total_dropped > 0);
    while (capture_head) { events = command("{\"cmd\":\"events\",\"limit\":256}"); free(events.data); }
    toggle(0); assert(capture_bytes == 0);
    puts("{\"case\":\"assertions\",\"data\":true}");
    return 0;
}
