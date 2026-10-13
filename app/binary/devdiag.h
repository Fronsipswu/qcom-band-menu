/*
 * devdiag.h: a DCI client on the kernel DIAG driver (/dev/diag), for phones
 * whose kernel still has it (diag-router replaced it on newer kernels).
 */
#ifndef DEVDIAG_H
#define DEVDIAG_H

#include <stdint.h>

/* Whether this phone's kernel has the DIAG driver. */
int devdiag_available(void);

/* Registers a DCI client, subscribes to `codes` and starts a reader thread
 * that hands each log packet ([u16 len][u16 code][u64 timestamp][payload])
 * to `on_log`. 0 on success; -1 with the reason on stderr. */
int devdiag_start(const uint16_t *codes, int count, void (*on_log)(unsigned char *, int));

/* Log packets delivered so far. */
long devdiag_records(void);

/* Unsubscribes, releases the client and stops the reader. */
void devdiag_stop(const uint16_t *codes, int count);

#endif
