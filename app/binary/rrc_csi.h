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
 * NR-ARFCN (SSB or point A frequency); 0 when unknown. The PCell's identity is
 * often not in any reconfiguration (it comes from system information), so for
 * the PCell (`pcell` set) the cell group's SpCell is used when nothing
 * matches. */
int rrc_csi_ports(int pci, uint32_t arfcn, int pcell);

#endif
