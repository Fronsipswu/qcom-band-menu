/*
 * efs_lock.c: see efs_lock.h. Three item files hold the lock (all
 * little-endian):
 *   lte/rrc/csp/earfcn_lock                4 bytes: EARFCN u16, EARFCN u16
 *   lte/ML1/camp_band_earfcn               4 bytes: band u16, EARFCN u16
 *   lte/rrc/efs/cell_restrict_opt_params  36 bytes: EARFCN u32 at 0x1c and 0x20
 * Clearing deletes the first two and zeroes the EARFCNs of the third. The
 * modem reads them when it starts, so the writes are committed (EFS sync)
 * and the modem restarted. Checked on an SM8550 (X70), 2026-11-19.
 */
#include "efs_lock.h"

#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define EARFCN_LOCK "/nv/item_files/modem/lte/rrc/csp/earfcn_lock"
#define CAMP_BAND   "/nv/item_files/modem/lte/ML1/camp_band_earfcn"
#define RESTRICT    "/nv/item_files/modem/lte/rrc/efs/cell_restrict_opt_params"
#define RESTRICT_SIZE 36

enum { EFS_HELLO = 0, EFS_UNLINK = 8, EFS_PUT = 38, EFS_GET = 39, EFS_SYNC = 48, EFS_SYNC_STATUS = 49 };

static dci_async_t send_req;
static int dci_client;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static unsigned char reply[2048];
static int reply_len;
static unsigned char rsp_buf[4096];

void efs_lock_setup(dci_async_t send, int client) {
    send_req = send;
    dci_client = client;
}

static void on_reply(unsigned char *p, int n, void *data) {
    (void)data;
    pthread_mutex_lock(&mu);
    if (n > (int)sizeof reply) n = sizeof reply;
    if (n > 0) memcpy(reply, p, (size_t)n);
    reply_len = n > 0 ? n : 0;
    pthread_cond_signal(&cv);
    pthread_mutex_unlock(&mu);
}

/* One DIAG request; the reply's length (in `reply`), or -1. */
static int xfer(unsigned char *req, int n) {
    if (!send_req)
        return -1;
    pthread_mutex_lock(&mu);
    reply_len = -1;
    pthread_mutex_unlock(&mu);
    if (send_req(dci_client, req, n, rsp_buf, sizeof rsp_buf, on_reply, NULL) != 1001)
        return -1;
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 4;
    pthread_mutex_lock(&mu);
    while (reply_len < 0 && pthread_cond_timedwait(&cv, &mu, &until) == 0) {}
    int got = reply_len;
    pthread_mutex_unlock(&mu);
    /* An answer to another command (or DIAG's "bad command") doesn't count. */
    if (got < 4 || reply[0] != req[0] || reply[1] != req[1] || reply[2] != req[2])
        return -1;
    return got;
}

static int32_t i32(const unsigned char *p) { return (int32_t)(p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24); }
static void put16(unsigned char *p, int v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); }
static void put32(unsigned char *p, uint32_t v) { put16(p, (int)(v & 0xFFFF)); put16(p + 2, (int)(v >> 16)); }

static void header(unsigned char *req, int op) {
    req[0] = 0x4B; req[1] = 0x13; req[2] = (unsigned char)op; req[3] = 0;
}

static void hello(void) {
    unsigned char req[44] = { 0 };
    header(req, EFS_HELLO);
    for (int i = 0; i < 6; i++) put32(req + 4 + i * 4, 0x100000);   /* window sizes */
    put32(req + 28, 1); put32(req + 32, 1); put32(req + 36, 1);     /* version 1 */
    put32(req + 40, 0xFFFFFFFF);
    xfer(req, sizeof req);
}

/* An item's `size` bytes: 0, -2 when it doesn't exist, -1 on any other failure.
 * The modem wants the item's exact size. */
static int get_item(const char *path, unsigned char *out, int size) {
    unsigned char req[160] = { 0 };
    size_t plen = strlen(path) + 1;
    header(req, EFS_GET);
    put32(req + 4, (uint32_t)size);
    put32(req + 8, (uint32_t)plen);
    memcpy(req + 14, path, plen);
    int n = xfer(req, 14 + (int)plen);
    if (n < 12) return -1;
    if (i32(reply + 8) == 2) return -2;            /* ENOENT */
    if (i32(reply + 8) != 0 || i32(reply + 4) != size || n < 14 + size) return -1;
    memcpy(out, reply + 14, (size_t)size);
    return 0;
}

static int put_item(const char *path, const unsigned char *data, int size) {
    unsigned char req[200] = { 0 };
    size_t plen = strlen(path) + 1;
    header(req, EFS_PUT);
    put16(req + 4, size);
    put32(req + 8, 0x1 | 0x40 | 0x200 | 0x40000);  /* write, create, truncate, item file */
    put16(req + 12, 0777);
    memcpy(req + 14, data, (size_t)size);
    memcpy(req + 14 + size, path, plen);
    int n = xfer(req, 14 + size + (int)plen);
    return n >= 8 && reply[6] == 0 && reply[7] == 0 ? 0 : -1;
}

static int unlink_item(const char *path) {
    unsigned char req[160];
    size_t plen = strlen(path) + 1;
    header(req, EFS_UNLINK);
    memcpy(req + 4, path, plen);
    int n = xfer(req, 4 + (int)plen);
    return n >= 8 && (i32(reply + 4) == 0 || i32(reply + 4) == 2) ? 0 : -1;
}

/* Commits the EFS journal, so the items survive the restart. */
static int sync_efs(void) {
    unsigned char req[16] = { 0 };
    uint32_t token = 0;
    int started = 0;
    for (int attempt = 0; attempt < 6 && !started; attempt++) {
        header(req, EFS_SYNC);
        put16(req + 4, 1);
        req[6] = '/'; req[7] = 0;
        int n = xfer(req, 8);
        if (n >= 14 && i32(reply + 10) == 0) { token = (uint32_t)i32(reply + 6); started = 1; }
        else usleep(200 * 1000);
    }
    if (!started) return -1;
    /* The status answers 1 once the commit is through on this modem; give it
     * a moment either way. */
    for (int i = 0; i < 5; i++) {
        usleep(100 * 1000);
        header(req, EFS_SYNC_STATUS);
        put16(req + 4, 1);
        put32(req + 6, token);
        req[10] = '/'; req[11] = 0;
        int n = xfer(req, 12);
        if (n >= 7 && reply[6] == 1) return 0;
    }
    return 0;
}

static int restart_modem(void) {
    unsigned char req[4] = { 0x4B, 0x25, 0x03, 0x00 };
    return xfer(req, 4) >= 4 ? 0 : -1;
}

int efs_lock_set(int sim, int earfcn, int band, const char **err) {
    *err = NULL;
    if (sim != 1) { *err = "SIM 1 only"; return -1; }
    if (earfcn < 0 || earfcn > 65535 || (earfcn && (band < 1 || band > 255))) { *err = "Invalid EARFCN"; return -1; }
    hello();
    /* The restriction item keeps its other bytes; a missing one starts as the
     * modem's default (first byte 1). */
    unsigned char restrict_item[RESTRICT_SIZE] = { 1 };
    int got = get_item(RESTRICT, restrict_item, RESTRICT_SIZE);
    if (got == -1) { *err = "EFS read failed"; return -1; }
    put32(restrict_item + 0x1C, (uint32_t)earfcn);
    put32(restrict_item + 0x20, (uint32_t)earfcn);
    int ok;
    if (earfcn) {
        unsigned char lock[4], camp[4];
        put16(lock, earfcn); put16(lock + 2, earfcn);
        put16(camp, band); put16(camp + 2, earfcn);
        ok = put_item(RESTRICT, restrict_item, RESTRICT_SIZE) == 0 && put_item(EARFCN_LOCK, lock, 4) == 0
             && put_item(CAMP_BAND, camp, 4) == 0;
        /* Read back before restarting the modem for it. */
        unsigned char check[4];
        int e = 0, b = 0;
        if (ok) ok = get_item(EARFCN_LOCK, check, 4) == 0 && !memcmp(check, lock, 4)
                     && efs_lock_get(sim, &e, &b) == 1 && e == earfcn && b == band;
    } else {
        ok = unlink_item(EARFCN_LOCK) == 0 && unlink_item(CAMP_BAND) == 0
             && (got == -2 || put_item(RESTRICT, restrict_item, RESTRICT_SIZE) == 0);
    }
    if (!ok) { *err = "EFS write failed"; return -1; }
    if (sync_efs() < 0) { *err = "EFS sync failed"; return -1; }
    if (restart_modem() < 0) { *err = "Modem restart failed"; return -1; }
    return 0;
}

int efs_lock_get(int sim, int *earfcn, int *band) {
    *earfcn = *band = 0;
    if (sim != 1) return 0;
    hello();
    unsigned char camp[4];
    int r = get_item(CAMP_BAND, camp, 4);
    if (r == -2) return 0;
    if (r < 0) return -1;
    *band = camp[0] | camp[1] << 8;
    *earfcn = camp[2] | camp[3] << 8;
    return 1;
}
