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

#endif
