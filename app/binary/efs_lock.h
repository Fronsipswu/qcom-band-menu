/*
 * efs_lock.h: the LTE EARFCN-only lock, which the modem keeps in EFS item
 * files rather than behind a QMI request. Written through DIAG (subsystem
 * 0x13, EFS2) on the helper's DCI client, then the modem is restarted so it
 * reads them. SIM 1 only (the items have no second-subscription copy).
 */
#ifndef EFS_LOCK_H
#define EFS_LOCK_H

/* libdiag's diag_send_dci_async_req. */
typedef int (*dci_async_t)(int client, unsigned char *req, int req_len, unsigned char *rsp, int rsp_len,
                           void (*cb)(unsigned char *, int, void *), void *data);

void efs_lock_setup(dci_async_t send, int client);

/* Locks LTE to `earfcn` in `band`, or with earfcn 0 clears the lock; then
 * restarts the modem. Returns 0, or -1 with a short reason in *err. */
int efs_lock_set(int sim, int earfcn, int band, const char **err);

/* The lock in place: 1 with *earfcn and *band, 0 for none, -1 when the
 * items couldn't be read. */
int efs_lock_get(int sim, int *earfcn, int *band);

#endif
