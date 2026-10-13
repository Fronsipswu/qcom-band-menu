/*
 * devdiag.c: a DCI client on the kernel DIAG driver (/dev/diag), the same
 * requests libdiag makes for one: register a DCI client, set its log codes,
 * read its records. It never switches the logging mode and sets no log mask
 * but its own client's, so other DIAG tools keep working.
 */
#include "devdiag.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define DEV_DIAG "/dev/diag"

/* The driver's interface (diagchar.h, diag_dci.h). */
#define IOCTL_DCI_DEINIT 21
#define IOCTL_DCI_REG 23
#define DCI_DATA_TYPE 0x40       /* a write: a DCI request; a read: DCI records */
#define DCI_LOG_TYPE (-1)        /* a log record, or a log-code request */
#define DCI_EXT_HDR_TYPE (-3)    /* an extended header ahead of a record */
#define EXT_HDR_LEN 8
#define DCI_FIRST_ERROR 1002     /* driver error codes 1002-1007; 1001 is "no error" */
#define DCI_LAST_ERROR 1007

static int fd = -1;
static int client_id;
static pthread_t reader;
static volatile int running;
static volatile int reader_done;
static volatile long records;
static long started_ms;
static void (*deliver)(unsigned char *, int);
static unsigned char buffer[256 * 1024];

static long clock_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

static int int_at(const unsigned char *p) {
    int v;
    memcpy(&v, p, sizeof v);
    return v;
}

static int is_dci_error(long r) {
    return r < 0 || (r >= DCI_FIRST_ERROR && r <= DCI_LAST_ERROR);
}

int devdiag_available(void) {
    return access(DEV_DIAG, F_OK) == 0;
}

long devdiag_records(void) {
    return records;
}

/* A DCI log-code request: [DCI_DATA_TYPE][DCI_LOG_TYPE][client][set/clear]
 * [count][u16 codes]. */
static long set_codes(const uint16_t *codes, int count, int enable) {
    unsigned char request[20 + 2 * 64];
    if (count > 64)
        count = 64;
    int header[5] = { DCI_DATA_TYPE, DCI_LOG_TYPE, client_id, enable, count };
    memcpy(request, header, sizeof header);
    memcpy(request + sizeof header, codes, 2 * (size_t)count);
    return write(fd, request, sizeof header + 2 * (size_t)count);
}

/* One read of DCI data: [DCI_DATA_TYPE][token][data length], then records,
 * each [DCI_LOG_TYPE][log packet] or [DCI_EXT_HDR_TYPE][8 bytes]. */
static void deliver_records(unsigned char *data, long size) {
    long end = 12 + (long)int_at(data + 8);
    if (end > size)
        end = size;
    long pos = 12;
    while (running && pos + 4 <= end) {
        int type = int_at(data + pos);
        pos += 4;
        if (type == DCI_EXT_HDR_TYPE) {
            pos += EXT_HDR_LEN;
            continue;
        }
        if (type != DCI_LOG_TYPE || pos + 12 > end) {
            fprintf(stderr, "devdiag: record type %d, skipping the rest of the read\n", type);
            return;
        }
        int length = data[pos] | data[pos + 1] << 8;
        if (length < 12 || pos + length > end)
            return;
        if (records == 0)
            fprintf(stderr, "devdiag: first log 0x%04X after %ld ms\n", data[pos + 2] | data[pos + 3] << 8,
                    clock_ms() - started_ms);
        records++;
        deliver(data + pos, length);
        pos += length;
    }
}

/* Reads block in the driver until it has data; other data types (mask
 * updates the driver hands every client) are skipped. */
static void *read_loop(void *unused) {
    (void)unused;
    int first = 1;
    while (running) {
        ssize_t n = read(fd, buffer, sizeof buffer);
        if (n < 0 && errno != EINTR) {
            fprintf(stderr, "devdiag: read: %s\n", strerror(errno));
            usleep(100 * 1000);
            continue;
        }
        if (n < 12 || int_at(buffer) != DCI_DATA_TYPE)
            continue;
        if (first) {
            fprintf(stderr, "devdiag: first DCI read: %zd bytes, token %d, length %d\n", n, int_at(buffer + 4),
                    int_at(buffer + 8));
            first = 0;
        }
        deliver_records(buffer, n);
    }
    reader_done = 1;
    return NULL;
}

/* Interrupts the reader's blocking read (installed without SA_RESTART). */
static void wake(int s) {
    (void)s;
}

int devdiag_start(const uint16_t *codes, int count, void (*on_log)(unsigned char *, int)) {
    deliver = on_log;
    started_ms = clock_ms();
    fd = open(DEV_DIAG, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "devdiag: open %s: %s\n", DEV_DIAG, strerror(errno));
        return -1;
    }
    /* Registration record (client id, notification list, signal type,
     * token): all zero asks for no notifications and the modem on this
     * SoC. The driver assigns the id and returns it. The spare zero bytes
     * cover a driver whose record is padded. */
    unsigned char registration[32] = { 0 };
    int id = ioctl(fd, IOCTL_DCI_REG, registration);
    if (id <= 0 || is_dci_error(id)) {
        fprintf(stderr, "devdiag: DCI register failed: %d (%s)\n", id, id < 0 ? strerror(errno) : "driver error");
        close(fd);
        fd = -1;
        return -1;
    }
    client_id = id;

    struct sigaction action;
    memset(&action, 0, sizeof action);
    action.sa_handler = wake;
    sigaction(SIGUSR1, &action, NULL);
    running = 1;
    reader_done = 0;
    if (pthread_create(&reader, NULL, read_loop, NULL) != 0) {
        fprintf(stderr, "devdiag: no reader thread\n");
        running = 0;
        ioctl(fd, IOCTL_DCI_DEINIT, &client_id);
        close(fd);
        fd = -1;
        return -1;
    }
    long r = set_codes(codes, count, 1);
    if (is_dci_error(r)) {
        fprintf(stderr, "devdiag: log codes refused: %ld (%s)\n", r, r < 0 ? strerror(errno) : "driver error");
        devdiag_stop(codes, count);
        return -1;
    }
    fprintf(stderr, "devdiag: DCI client %d, %d log codes (%ld)\n", client_id, count, r);
    return 0;
}

void devdiag_stop(const uint16_t *codes, int count) {
    if (fd < 0)
        return;
    set_codes(codes, count, 0);
    ioctl(fd, IOCTL_DCI_DEINIT, &client_id);
    running = 0;
    /* The signal can land just before the reader enters read(), so it is
     * repeated until the reader is out (about a second at most). */
    for (int i = 0; i < 20 && !reader_done; i++) {
        pthread_kill(reader, SIGUSR1);
        usleep(50 * 1000);
    }
    if (reader_done) {
        pthread_join(reader, NULL);
        close(fd);
    } else {
        pthread_detach(reader);  /* still in read(): the fd stays open until exit */
    }
    fd = -1;
}
