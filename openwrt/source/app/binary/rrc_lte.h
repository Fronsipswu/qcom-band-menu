#ifndef RRC_LTE_H
#define RRC_LTE_H
#include <stddef.h>

#define LTE_MAX_CELLS 32
#define LTE_MAX_NZP 32
struct lte_cell {
    int present;
    int crs_ports;
    int transmission_mode;
    int legacy_csi_ports;
    int max_layers;
    int stti_transmission_mode;
    int stti_max_layers;
    int stti_legacy_csi_ports;
    long pci;
    long frequency;
    unsigned char nzp_ports[LTE_MAX_NZP];
    unsigned char stti_nzp_ports[LTE_MAX_NZP];
};

struct lte_summary {
    int present;
    int crs_ports;
    int transmission_mode;
    int csi_ports;
    int max_layers;
};

struct lte_state {
    struct lte_cell cells[LTE_MAX_CELLS];
    unsigned framed;
    unsigned attempts[3];
    unsigned decoded[3];
    unsigned embedded_nr;
    unsigned nr_release;
};

struct lte_callbacks {
    void *context;
    void (*emit)(void *, int, const struct lte_summary *);
    void (*nr_config)(void *, const unsigned char *, size_t);
    void (*nr_clear)(void *);
};

void lte_decode(struct lte_state *, const struct lte_callbacks *, int, const unsigned char *, size_t);
#endif
