/*
 * nas_session.h: the data session's APN (DNN), QoS class and aggregate
 * maximum bit rate, from the session management messages the network sends
 * when a session is set up or changed: 0xB800 (5GSM, plain, downlink) and
 * 0xB0E2 (EPS ESM, plain, downlink). Only those three values are read.
 */
#ifndef NAS_SESSION_H
#define NAS_SESSION_H

#include <stdint.h>

/* Applies one log payload (from its version byte on) for SIM `sim` (0, 1). */
void nas_session_b800(int sim, const uint8_t *payload, int length);
void nas_session_b0e2(int sim, const uint8_t *payload, int length);

struct nas_session {
    char apn[64];              /* "" when the message left it out */
    int qos_class;             /* 5QI (5GS) or QCI (EPS); 0 when unknown */
    long long dl_kbps, ul_kbps;   /* session/APN-AMBR; 0 when unknown */
};

/* The data session (the newest one that isn't IMS or emergency) of SIM `sim`
 * set up over 5GS (`five_g` = 1) or EPS; returns 0 when none was seen. */
int nas_session_get(int sim, int five_g, struct nas_session *out);

#endif
