/* LTE RRC reconfiguration: the SCell antenna port count and identity. */
#include "rrc_lte.h"
#include "rrc_lte_gen.h"
#include <string.h>

/* Spare enumeration values are unknown, never inferred port counts. */
static int antenna_ports(long encoded, int maximum) {
    return encoded >= 0 && encoded < maximum ? 1 << encoded : 0;
}

/* Absent layer limits retain prior state; the enumeration is not the count. */
static void apply_layers(struct lte_cell *cell, const LTE_AntennaInfoDedicated_v10i0_t *antenna) {
    if (antenna && antenna->maxLayersMIMO_r10) {
        cell->max_layers = antenna_ports(*antenna->maxLayersMIMO_r10 + 1, 4);
    }
}

/* Zero-power resources never contribute to CSI-RS antenna ports. */
static void apply_legacy_csi(struct lte_cell *cell, const LTE_CSI_RS_Config_r10_t *config) {
    if (!config || !config->csi_RS_r10) {
        return;
    }
    const struct LTE_CSI_RS_Config_r10__csi_RS_r10 *csi = config->csi_RS_r10;
    if (csi->present == LTE_CSI_RS_Config_r10__csi_RS_r10_PR_release) {
        cell->legacy_csi_ports = 0;
    } else if (csi->present == LTE_CSI_RS_Config_r10__csi_RS_r10_PR_setup && csi->choice.setup) {
        cell->legacy_csi_ports = antenna_ports(csi->choice.setup->antennaPortsCount_r10, 4);
    }
}

/* Extended IDs replace the root ID for the same NZP element. */
static void add_nzp(struct lte_cell *cell, const LTE_CSI_RS_ConfigNZP_r11_t *resource) {
    long index = resource->csi_RS_ConfigNZPId_r11;
    if (resource->ext1 && resource->ext1->csi_RS_ConfigNZPId_v1310) {
        index = *resource->ext1->csi_RS_ConfigNZPId_v1310;
    }
    if (index >= 0 && index < LTE_MAX_NZP) {
        cell->nzp_ports[index] = antenna_ports(resource->antennaPortsCount_r11, 4);
    }
}

/* Release is processed before AddMod so a replacement survives the message. */
static void apply_nzp(struct lte_cell *cell, const LTE_CSI_RS_ConfigNZPToReleaseList_r11_t *release,
                      const LTE_CSI_RS_ConfigNZPToAddModList_r11_t *add) {
    if (release) {
        for (int i = 0; i < release->list.count; i++) {
            long index = *release->list.array[i];
            if (index >= 0 && index < LTE_MAX_NZP) {
                cell->nzp_ports[index] = 0;
            }
        }
    }
    if (add) {
        for (int i = 0; i < add->list.count; i++) {
            add_nzp(cell, add->list.array[i]);
        }
    }
}

/* Release-13 extends the identifier range, not the per-resource port encoding. */
static void apply_extended_nzp(struct lte_cell *cell,
                               const LTE_CSI_RS_ConfigNZPToReleaseListExt_r13_t *release,
                               const LTE_CSI_RS_ConfigNZPToAddModListExt_r13_t *add) {
    if (release) {
        for (int i = 0; i < release->list.count; i++) {
            long index = *release->list.array[i];
            if (index >= 0 && index < LTE_MAX_NZP) {
                cell->nzp_ports[index] = 0;
            }
        }
    }
    if (add) {
        for (int i = 0; i < add->list.count; i++) {
            add_nzp(cell, add->list.array[i]);
        }
    }
}

/* Short-TTI configuration has its own antenna namespace and release semantics. */
static void apply_stti(struct lte_cell *cell, const LTE_PhysicalConfigDedicatedSTTI_r15_t *config) {
    static const int modes[] = {1, 2, 3, 4, 6, 8, 9, 10};
    if (!config) {
        return;
    }
    if (config->present == LTE_PhysicalConfigDedicatedSTTI_r15_PR_release) {
        cell->stti_transmission_mode = 0;
        cell->stti_max_layers = 0;
        cell->stti_legacy_csi_ports = 0;
        memset(cell->stti_nzp_ports, 0, sizeof cell->stti_nzp_ports);
        return;
    }
    if (config->present != LTE_PhysicalConfigDedicatedSTTI_r15_PR_setup || !config->choice.setup) {
        return;
    }
    const struct LTE_PhysicalConfigDedicatedSTTI_r15__setup *physical = config->choice.setup;
    const LTE_AntennaInfoDedicatedSTTI_r15_t *antenna = physical->antennaInfoDedicatedSTTI_r15;
    if (antenna && antenna->present == LTE_AntennaInfoDedicatedSTTI_r15_PR_release) {
        cell->stti_transmission_mode = 0;
        cell->stti_max_layers = 0;
    } else if (antenna && antenna->present == LTE_AntennaInfoDedicatedSTTI_r15_PR_setup &&
               antenna->choice.setup) {
        const struct LTE_AntennaInfoDedicatedSTTI_r15__setup *setup = antenna->choice.setup;
        if (setup->transmissionModeDL_nonMBSFN_r15) {
            long index = *setup->transmissionModeDL_nonMBSFN_r15;
            cell->stti_transmission_mode = index >= 0 && index < 8 ? modes[index] : 0;
        }
        if (setup->maxLayersMIMO_STTI_r15) {
            cell->stti_max_layers = antenna_ports(*setup->maxLayersMIMO_STTI_r15 + 1, 3);
        }
    }
    if (physical->csi_RS_Config_r15) {
        const LTE_CSI_RS_Config_r15_t *csi = physical->csi_RS_Config_r15;
        if (csi->present == LTE_CSI_RS_Config_r15_PR_release) {
            cell->stti_legacy_csi_ports = 0;
        } else if (csi->present == LTE_CSI_RS_Config_r15_PR_setup && csi->choice.setup) {
            struct lte_cell temporary = {0};
            temporary.legacy_csi_ports = cell->stti_legacy_csi_ports;
            apply_legacy_csi(&temporary, csi->choice.setup->csi_RS_Config_r10);
            cell->stti_legacy_csi_ports = temporary.legacy_csi_ports;
        }
    }
    const LTE_CSI_RS_ConfigNZPToReleaseList_r15_t *release = physical->csi_RS_ConfigNZPToReleaseList_r15;
    if (release) {
        for (int i = 0; i < release->list.count; i++) {
            long index = *release->list.array[i];
            if (index >= 0 && index < LTE_MAX_NZP) {
                cell->stti_nzp_ports[index] = 0;
            }
        }
    }
    const LTE_CSI_RS_ConfigNZPToAddModList_r15_t *add = physical->csi_RS_ConfigNZPToAddModList_r15;
    if (add) {
        struct lte_cell temporary = {0};
        memcpy(temporary.nzp_ports, cell->stti_nzp_ports, sizeof temporary.nzp_ports);
        for (int i = 0; i < add->list.count; i++) {
            add_nzp(&temporary, add->list.array[i]);
        }
        memcpy(cell->stti_nzp_ports, temporary.nzp_ports, sizeof cell->stti_nzp_ports);
    }
}

/* DefaultValue does not disclose the explicit transmission mode. */
static void apply_pcell_physical(struct lte_cell *cell, const LTE_PhysicalConfigDedicated_t *config) {
    if (!config) {
        return;
    }
    cell->present = 1;
    if (config->antennaInfo) {
        const struct LTE_PhysicalConfigDedicated__antennaInfo *antenna = config->antennaInfo;
        cell->transmission_mode = 0;
        if (antenna->present == LTE_PhysicalConfigDedicated__antennaInfo_PR_explicitValue &&
            antenna->choice.explicitValue) {
            cell->transmission_mode = antenna->choice.explicitValue->transmissionMode + 1;
        }
    }
    if (config->ext2) {
        const struct LTE_PhysicalConfigDedicated__ext2 *extension = config->ext2;
        if (extension->antennaInfo_r10) {
            const struct LTE_PhysicalConfigDedicated__ext2__antennaInfo_r10 *antenna =
                extension->antennaInfo_r10;
            cell->transmission_mode = 0;
            if (antenna->present == LTE_PhysicalConfigDedicated__ext2__antennaInfo_r10_PR_explicitValue_r10 &&
                antenna->choice.explicitValue_r10) {
                long mode = antenna->choice.explicitValue_r10->transmissionMode_r10;
                cell->transmission_mode = mode < 10 ? mode + 1 : 0;
            }
        }
        apply_legacy_csi(cell, extension->csi_RS_Config_r10);
    }
    if (config->ext4) {
        apply_nzp(cell, config->ext4->csi_RS_ConfigNZPToReleaseList_r11,
                  config->ext4->csi_RS_ConfigNZPToAddModList_r11);
    }
    if (config->ext7) {
        apply_extended_nzp(cell, config->ext7->csi_RS_ConfigNZPToReleaseListExt_r13,
                           config->ext7->csi_RS_ConfigNZPToAddModListExt_r13);
    }
    if (config->ext11) {
        apply_stti(cell, config->ext11->physicalConfigDedicatedSTTI_r15);
    }
}

/* SCell downlink antenna state is independent of its optional uplink configuration. */
static void apply_scell_physical(struct lte_cell *cell,
                                 const LTE_PhysicalConfigDedicatedSCell_r10_t *config) {
    if (!config) {
        return;
    }
    if (config->nonUL_Configuration_r10) {
        const struct LTE_PhysicalConfigDedicatedSCell_r10__nonUL_Configuration_r10 *downlink =
            config->nonUL_Configuration_r10;
        if (downlink->antennaInfo_r10) {
            long mode = downlink->antennaInfo_r10->transmissionMode_r10;
            cell->transmission_mode = mode < 10 ? mode + 1 : 0;
        }
        apply_legacy_csi(cell, downlink->csi_RS_Config_r10);
    }
    if (config->ext1) {
        apply_nzp(cell, config->ext1->csi_RS_ConfigNZPToReleaseList_r11,
                  config->ext1->csi_RS_ConfigNZPToAddModList_r11);
    }
    if (config->ext4) {
        apply_extended_nzp(cell, config->ext4->csi_RS_ConfigNZPToReleaseListExt_r13,
                           config->ext4->csi_RS_ConfigNZPToAddModListExt_r13);
    }
    if (config->ext8) {
        apply_stti(cell, config->ext8->physicalConfigDedicatedSTTI_r15);
    }
}

/* A new private carrier key invalidates state at a reused SCell index. */
static void apply_scell(struct lte_state *state, int index, long pci, long frequency,
                        const LTE_RadioResourceConfigCommonSCell_r10_t *common,
                        const LTE_RadioResourceConfigDedicatedSCell_r10_t *dedicated,
                        const LTE_AntennaInfoDedicated_v10i0_t *layers) {
    if (index <= 0 || index >= LTE_MAX_CELLS) {
        return;
    }
    struct lte_cell *cell = &state->cells[index];
    if ((pci && cell->pci && pci != cell->pci) ||
        (frequency && cell->frequency && frequency != cell->frequency)) {
        memset(cell, 0, sizeof *cell);
    }
    cell->present = 1;
    if (pci) {
        cell->pci = pci;
    }
    if (frequency) {
        cell->frequency = frequency;
    }
    if (common) {
        cell->crs_ports =
            antenna_ports(common->nonUL_Configuration_r10.antennaInfoCommon_r10.antennaPortsCount, 3);
    }
    if (dedicated) {
        apply_scell_physical(cell, dedicated->physicalConfigDedicatedSCell_r10);
    }
    apply_layers(cell, layers);
}

/* AddMod-list elements use absolute indexes, not positions within the list. */
static void apply_scell_list(struct lte_state *state, const LTE_SCellToAddModList_r10_t *list) {
    if (!list) {
        return;
    }
    for (int i = 0; i < list->list.count; i++) {
        const LTE_SCellToAddMod_r10_t *config = list->list.array[i];
        long pci = 0;
        long frequency = 0;
        if (config->cellIdentification_r10) {
            pci = config->cellIdentification_r10->physCellId_r10 + 1;
            frequency = config->cellIdentification_r10->dl_CarrierFreq_r10 + 1;
        }
        apply_scell(state, config->sCellIndex_r10, pci, frequency, config->radioResourceConfigCommonSCell_r10,
                    config->radioResourceConfigDedicatedSCell_r10,
                    config->ext2 ? config->ext2->antennaInfoDedicatedSCell_v10i0 : NULL);
    }
}

/* Release-13 allows serving-cell indexes beyond the original five SCells. */
static void apply_extended_scells(struct lte_state *state, const LTE_SCellToAddModListExt_r13_t *list) {
    if (!list) {
        return;
    }
    for (int i = 0; i < list->list.count; i++) {
        const LTE_SCellToAddModExt_r13_t *config = list->list.array[i];
        long pci = 0;
        long frequency = 0;
        if (config->cellIdentification_r13) {
            pci = config->cellIdentification_r13->physCellId_r13 + 1;
            frequency = config->cellIdentification_r13->dl_CarrierFreq_r13 + 1;
        }
        apply_scell(state, config->sCellIndex_r13, pci, frequency, config->radioResourceConfigCommonSCell_r13,
                    config->radioResourceConfigDedicatedSCell_r13, config->antennaInfoDedicatedSCell_r13);
    }
}

/* Release and endc-ReleaseAndAdd replace the NR secondary context, not the master. */
static void apply_nr(struct lte_state *state, const struct lte_callbacks *callbacks,
                     const LTE_RRCConnectionReconfiguration_v1510_IEs_t *extension) {
    if (!extension || !extension->nr_Config_r15) {
        return;
    }
    const struct LTE_RRCConnectionReconfiguration_v1510_IEs__nr_Config_r15 *config = extension->nr_Config_r15;
    if (config->present == LTE_RRCConnectionReconfiguration_v1510_IEs__nr_Config_r15_PR_release) {
        state->nr_release++;
        callbacks->nr_clear(callbacks->context);
        return;
    }
    if (config->present != LTE_RRCConnectionReconfiguration_v1510_IEs__nr_Config_r15_PR_setup ||
        !config->choice.setup) {
        return;
    }
    if (config->choice.setup->endc_ReleaseAndAdd_r15) {
        state->nr_release++;
        callbacks->nr_clear(callbacks->context);
    }
    const OCTET_STRING_t *encoded = config->choice.setup->nr_SecondaryCellGroupConfig_r15;
    if (encoded) {
        state->embedded_nr++;
        callbacks->nr_config(callbacks->context, encoded->buf, encoded->size);
    }
}

/* Full configuration and handover invalidate stale antenna state before applying deltas. */
static void apply_reconfiguration(struct lte_state *state, const struct lte_callbacks *callbacks,
                                  const LTE_RRCConnectionReconfiguration_r8_IEs_t *config) {
    const LTE_RRCConnectionReconfiguration_v890_IEs_t *v890 = config->nonCriticalExtension;
    const LTE_RRCConnectionReconfiguration_v920_IEs_t *v920 = v890 ? v890->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1020_IEs_t *v1020 = v920 ? v920->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1130_IEs_t *v1130 = v1020 ? v1020->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1250_IEs_t *v1250 = v1130 ? v1130->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1310_IEs_t *v1310 = v1250 ? v1250->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1430_IEs_t *v1430 = v1310 ? v1310->nonCriticalExtension : NULL;
    const LTE_RRCConnectionReconfiguration_v1510_IEs_t *v1510 = v1430 ? v1430->nonCriticalExtension : NULL;
    if ((v920 && v920->fullConfig_r9) || config->mobilityControlInfo) {
        memset(state->cells, 0, sizeof state->cells);
        callbacks->nr_clear(callbacks->context);
    }
    struct lte_cell *pcell = &state->cells[0];
    if (config->mobilityControlInfo) {
        const LTE_MobilityControlInfo_t *mobility = config->mobilityControlInfo;
        pcell->present = 1;
        pcell->pci = mobility->targetPhysCellId + 1;
        if (mobility->radioResourceConfigCommon->antennaInfoCommon) {
            pcell->crs_ports =
                antenna_ports(mobility->radioResourceConfigCommon->antennaInfoCommon->antennaPortsCount, 3);
        }
    }
    if (config->radioResourceConfigDedicated) {
        apply_pcell_physical(pcell, config->radioResourceConfigDedicated->physicalConfigDedicated);
    }
    if (v1020) {
        if (v1020->sCellToReleaseList_r10) {
            for (int i = 0; i < v1020->sCellToReleaseList_r10->list.count; i++) {
                long index = *v1020->sCellToReleaseList_r10->list.array[i];
                if (index > 0 && index < LTE_MAX_CELLS) {
                    memset(&state->cells[index], 0, sizeof state->cells[index]);
                }
            }
        }
        apply_scell_list(state, v1020->sCellToAddModList_r10);
    }
    if (v1310) {
        if (v1310->sCellToReleaseListExt_r13) {
            for (int i = 0; i < v1310->sCellToReleaseListExt_r13->list.count; i++) {
                long index = *v1310->sCellToReleaseListExt_r13->list.array[i];
                if (index > 0 && index < LTE_MAX_CELLS) {
                    memset(&state->cells[index], 0, sizeof state->cells[index]);
                }
            }
        }
        apply_extended_scells(state, v1310->sCellToAddModListExt_r13);
    }
    if (v890 && v890->lateNonCriticalExtension) {
        const OCTET_STRING_t *encoded = v890->lateNonCriticalExtension;
        LTE_RRCConnectionReconfiguration_v8m0_IEs_t *late = NULL;
        asn_dec_rval_t decoded =
            uper_decode_complete(NULL, &asn_DEF_LTE_RRCConnectionReconfiguration_v8m0_IEs, (void **)&late,
                                 encoded->buf, encoded->size);
        if (decoded.code == RC_OK && decoded.consumed == encoded->size && late->nonCriticalExtension) {
            apply_layers(pcell, late->nonCriticalExtension->antennaInfoDedicatedPCell_v10i0);
        }
        ASN_STRUCT_FREE(asn_DEF_LTE_RRCConnectionReconfiguration_v8m0_IEs, late);
    }
    apply_nr(state, callbacks, v1510);
}

/* Summaries distinguish unconfigured CSI from a configured one-port resource. */
static struct lte_summary summarize_lte(const struct lte_cell *cell) {
    struct lte_summary summary = {0};
    summary.present = cell->present;
    summary.crs_ports = cell->crs_ports;
    summary.transmission_mode = cell->transmission_mode ? cell->transmission_mode : cell->stti_transmission_mode;
    summary.max_layers = cell->max_layers ? cell->max_layers : cell->stti_max_layers;
    summary.csi_ports = cell->legacy_csi_ports;
    if (cell->stti_legacy_csi_ports > summary.csi_ports) {
        summary.csi_ports = cell->stti_legacy_csi_ports;
    }
    for (int i = 0; i < LTE_MAX_NZP; i++) {
        if (cell->stti_nzp_ports[i] > summary.csi_ports) {
            summary.csi_ports = cell->stti_nzp_ports[i];
        }
        if (cell->nzp_ports[i] > summary.csi_ports) {
            summary.csi_ports = cell->nzp_ports[i];
        }
    }
    return summary;
}

/* Placeholders must never decode non-target DL choices as configuration messages. */
static void decode_dcch(struct lte_state *state, const struct lte_callbacks *callbacks,
                        const unsigned char *message, size_t length) {
    unsigned choice = message[0] >> 3;
    if (choice != 4 && choice != 5) {
        return;
    }
    state->attempts[0]++;
    LTE_DL_DCCH_Message_t *decoded_message = NULL;
    asn_dec_rval_t decoded =
        uper_decode_complete(NULL, &asn_DEF_LTE_DL_DCCH_Message, (void **)&decoded_message, message, length);
    if (decoded.code == RC_OK && decoded.consumed == length && decoded_message->message &&
        decoded_message->message->present == LTE_DL_DCCH_MessageType_PR_c1) {
        state->decoded[0]++;
        const struct LTE_DL_DCCH_MessageType__c1 *root = decoded_message->message->choice.c1;
        if (root->present == LTE_DL_DCCH_MessageType__c1_PR_rrcConnectionRelease) {
            memset(state->cells, 0, sizeof state->cells);
            callbacks->nr_clear(callbacks->context);
        } else if (root->present == LTE_DL_DCCH_MessageType__c1_PR_rrcConnectionReconfiguration) {
            const LTE_RRCConnectionReconfiguration_t *config = root->choice.rrcConnectionReconfiguration;
            if (config->criticalExtensions.present ==
                LTE_RRCConnectionReconfiguration__criticalExtensions_PR_c1) {
                const struct LTE_RRCConnectionReconfiguration__criticalExtensions__c1 *extension =
                    config->criticalExtensions.choice.c1;
                if (extension->present ==
                    LTE_RRCConnectionReconfiguration__criticalExtensions__c1_PR_rrcConnectionReconfiguration_r8) {
                    apply_reconfiguration(state, callbacks,
                                          extension->choice.rrcConnectionReconfiguration_r8);
                }
            }
        }
    }
    ASN_STRUCT_FREE(asn_DEF_LTE_DL_DCCH_Message, decoded_message);
}

/* Setup and reestablishment start a new dedicated LTE context. */
static void decode_ccch(struct lte_state *state, const struct lte_callbacks *callbacks,
                        const unsigned char *message, size_t length) {
    state->attempts[1]++;
    LTE_DL_CCCH_Message_t *decoded_message = NULL;
    asn_dec_rval_t decoded =
        uper_decode_complete(NULL, &asn_DEF_LTE_DL_CCCH_Message, (void **)&decoded_message, message, length);
    if (decoded.code == RC_OK && decoded.consumed == length && decoded_message->message &&
        decoded_message->message->present == LTE_DL_CCCH_MessageType_PR_c1) {
        state->decoded[1]++;
        const struct LTE_DL_CCCH_MessageType__c1 *root = decoded_message->message->choice.c1;
        const LTE_RadioResourceConfigDedicated_t *dedicated = NULL;
        if (root->present == LTE_DL_CCCH_MessageType__c1_PR_rrcConnectionSetup) {
            const LTE_RRCConnectionSetup_t *config = root->choice.rrcConnectionSetup;
            if (config->criticalExtensions.present == LTE_RRCConnectionSetup__criticalExtensions_PR_c1 &&
                config->criticalExtensions.choice.c1->present ==
                    LTE_RRCConnectionSetup__criticalExtensions__c1_PR_rrcConnectionSetup_r8) {
                dedicated = config->criticalExtensions.choice.c1->choice.rrcConnectionSetup_r8
                                ->radioResourceConfigDedicated;
            }
        } else if (root->present == LTE_DL_CCCH_MessageType__c1_PR_rrcConnectionReestablishment) {
            const LTE_RRCConnectionReestablishment_t *config = root->choice.rrcConnectionReestablishment;
            if (config->criticalExtensions.present ==
                    LTE_RRCConnectionReestablishment__criticalExtensions_PR_c1 &&
                config->criticalExtensions.choice.c1->present ==
                    LTE_RRCConnectionReestablishment__criticalExtensions__c1_PR_rrcConnectionReestablishment_r8) {
                dedicated = config->criticalExtensions.choice.c1->choice.rrcConnectionReestablishment_r8
                                ->radioResourceConfigDedicated;
            }
        }
        memset(state->cells, 0, sizeof state->cells);
        callbacks->nr_clear(callbacks->context);
        if (dedicated) {
            apply_pcell_physical(&state->cells[0], dedicated->physicalConfigDedicated);
        }
    }
    ASN_STRUCT_FREE(asn_DEF_LTE_DL_CCCH_Message, decoded_message);
}

/* Emit effective changes only after applying every LTE serving-cell delta. */
void lte_decode(struct lte_state *state, const struct lte_callbacks *callbacks, int channel,
                const unsigned char *message, size_t length) {
    if (!length) {
        return;
    }
    struct lte_summary before[LTE_MAX_CELLS];
    for (int i = 0; i < LTE_MAX_CELLS; i++) {
        before[i] = summarize_lte(&state->cells[i]);
    }
    if (channel == 0) {
        decode_dcch(state, callbacks, message, length);
    } else if (channel == 1) {
        decode_ccch(state, callbacks, message, length);
    }
    for (int i = 0; i < LTE_MAX_CELLS; i++) {
        struct lte_summary after = summarize_lte(&state->cells[i]);
        if (memcmp(&before[i], &after, sizeof after)) {
            callbacks->emit(callbacks->context, i, &after);
        }
    }
}
