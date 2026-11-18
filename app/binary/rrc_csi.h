/*
 * rrc_csi.h: the NR CSI-RS port count, band and bandwidth (channel and active
 * BWP) each serving cell is configured with, from the RRC messages and SIB1s
 * in log 0xB821 (NR5G RRC OTA Packet).
 */
#ifndef RRC_CSI_H
#define RRC_CSI_H

#include <stdint.h>

/* The SIM (0 = SIM 1, 1 = SIM 2) the next calls apply to; each keeps its
 * own configuration. */
void rrc_csi_select(int index);

/* Applies one B821 log payload (from its version word on). Messages other
 * than RRC setups, reconfigurations and SIB1s are ignored. */
void rrc_csi_b821(const uint8_t *payload, int length);

/* CSI-RS ports configured for CSI acquisition on the cell with this PCI and
 * SSB NR-ARFCN; 0 when unknown. The SpCell's identity is usually not in RRC
 * (RRCSetup doesn't name it), so a serving cell that matches no configured
 * cell is taken to be the SpCell. `pcell`: 1 = this is the PCell, 0 = it is
 * not (no SpCell fallback), -1 = not known. */
int rrc_csi_ports(int pci, uint32_t arfcn, int pcell);

/* The NR band the cell's common configuration names (frequencyBandList,
 * first entry); 0 when the cell's configuration hasn't been seen. */
int rrc_csi_band(int pci, uint32_t arfcn);

/* The cell's channel bandwidth in MHz (its RRC configuration, else its SIB1)
 * and, in *bwp_mhz, the size of its active downlink BWP; both 0 when unknown
 * (the cell's configuration hasn't been seen). `max_rbs`: the widest
 * downlink TB seen on the cell lately (0 = none); one wider than the BWP
 * marks the RRC view stale, and the BWP is 0 until the next BWP change. */
int rrc_csi_bandwidth(int pci, uint32_t arfcn, int max_rbs, int *bwp_mhz);

/* A cell's SIB1 cell selection values; each has_* is 0 when SIB1 left it out. */
struct rrc_sib1_selection {
    int has_q_rxlevmin, q_rxlevmin_dbm;   /* minimum RX level to camp */
    int has_q_qualmin, q_qualmin_db;      /* minimum quality (RSRQ) to camp */
    int has_ssb_power, ssb_power_dbm;     /* SSB transmit power per resource element */
};

/* The cell's SIB1 values in *out; returns 0 when its SIB1 hasn't been seen. */
int rrc_csi_sib1_selection(int pci, uint32_t arfcn, struct rrc_sib1_selection *out);

/* A cell's TDD pattern (tdd-UL-DL-ConfigurationCommon), with pattern2, when
 * present, added to pattern1: 3 ms DDDSUU + 2 ms DDDD = 5 ms, 7 DL and 2 UL
 * slots. Symbols are those of the mixed slots. */
struct rrc_tdd {
    int period_us;
    int dl_slots, ul_slots;
    int dl_symbols, ul_symbols;
};

/* The cell's TDD pattern from its RRC configuration, else its SIB1; returns
 * 0 when neither has been seen or the cell is FDD. */
int rrc_csi_tdd(int pci, uint32_t arfcn, struct rrc_tdd *out);

#endif
