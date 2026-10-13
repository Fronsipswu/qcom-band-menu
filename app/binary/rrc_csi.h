/*
 * rrc_csi.h: the NR CSI-RS port count each serving cell is configured with,
 * from the RRC reconfigurations in log 0xB821 (NR5G RRC OTA Packet).
 */
#ifndef RRC_CSI_H
#define RRC_CSI_H

#include <stdint.h>

/* Applies one B821 log payload (from its version word on). Messages other
 * than RRC reconfigurations are ignored. */
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

#endif
