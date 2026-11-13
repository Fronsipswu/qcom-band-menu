#
# qbm-diagd: LTE/NR carrier statistics from a Qualcomm modem's USB DIAG port,
# or a Fibocom FM350's (MediaTek) USB debug port.
#
# Sources: app/binary of this repository (or a src/ folder next to this
# Makefile, for a copy of the package outside the repository).
#
include $(TOPDIR)/rules.mk

PKG_NAME:=qbm-diagd
PKG_VERSION:=2.0.0
PKG_RELEASE:=19

PKG_LICENSE:=GPL-3.0-only

include $(INCLUDE_DIR)/package.mk

QBM_SRC:=$(if $(wildcard $(CURDIR)/src/qbm-diagd.c),$(CURDIR)/src,$(CURDIR)/../../app/binary)
QBM_FILES:=qbm-diagd.c qbm-diagd-usb.c qbm_layouts.h qbm-mtk.c qbm_mtk_tables.h \
	rrc_csi.c rrc_csi.h rrc_nr_gen.c rrc_nr_gen.h rrc_lte.c rrc_lte.h rrc_lte_gen.c rrc_lte_gen.h \
	qcom-bandlockd_v5-0-0.c qbm-qmux.c

define Package/qbm-diagd
  SECTION:=net
  CATEGORY:=Network
  TITLE:=Modem statistics (Qualcomm DIAG, FM350 debug port) and band/cell locking (QMI)
  URL:=https://github.com/Fronsipswu/qcom-band-menu
endef

define Package/qbm-diagd/description
  Reads LTE and NR physical layer logs from a Qualcomm modem's USB DIAG
  port (/dev/ttyUSB*, option driver) and serves per-carrier statistics:
  RSRP/RSRQ/SINR per carrier and RX chain, MCS, modulation, layers, BLER,
  throughput, carrier aggregation, NR neighbours and timing advance.
  A Fibocom FM350 (MediaTek; identified by AT+CGMM) is read from its USB
  debug port (interface 09) instead: ICD logs decoded with the firmware's
  own layouts.
  qbm-qmi: band, RAT, NR mode, cell and operator locking over the modem's
  QMI port (/dev/cdc-wdm*), one request per run, alongside uqmi.
  Needs kmod-usb-serial-option (DIAG) and kmod-usb-net-qmi-wwan (QMI).
endef

define Package/qbm-diagd/conffiles
/etc/config/qbm-diagd
endef

define Build/Prepare
	mkdir -p $(PKG_BUILD_DIR)
	$(foreach f,$(QBM_FILES),$(CP) $(QBM_SRC)/$(f) $(PKG_BUILD_DIR)/;)
endef

# _GNU_SOURCE: struct ucred on musl. HAVE_TM_GMTOFF: musl has tm_gmtoff, but
# the asn1c runtime in rrc_nr_gen.c only detects it on glibc and the BSDs.
# -static: like bin/ (build-openwrt.sh), so the binary does not depend on the
# router's libgcc_s/musl versions.
define Build/Compile
	$(TARGET_CC) $(TARGET_CPPFLAGS) $(TARGET_CFLAGS) -D_GNU_SOURCE -DHAVE_TM_GMTOFF -DQBM_USB_DIAG \
		-Wno-missing-field-initializers -static \
		-o $(PKG_BUILD_DIR)/qbm-diagd \
		$(PKG_BUILD_DIR)/qbm-diagd.c $(PKG_BUILD_DIR)/rrc_csi.c $(PKG_BUILD_DIR)/rrc_nr_gen.c \
		$(PKG_BUILD_DIR)/rrc_lte.c $(PKG_BUILD_DIR)/rrc_lte_gen.c \
		$(TARGET_LDFLAGS) -lm -lpthread
	$(TARGET_CC) $(TARGET_CPPFLAGS) $(TARGET_CFLAGS) -DQBM_QMUX \
		-Wno-pointer-to-int-cast -Wno-int-to-pointer-cast -static \
		-o $(PKG_BUILD_DIR)/qbm-qmi \
		$(PKG_BUILD_DIR)/qcom-bandlockd_v5-0-0.c $(PKG_BUILD_DIR)/qbm-qmux.c \
		$(TARGET_LDFLAGS)
endef

define Package/qbm-diagd/install
	$(INSTALL_DIR) $(1)/usr/sbin $(1)/etc/init.d $(1)/etc/config
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/qbm-diagd $(1)/usr/sbin/qbm-diagd
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/qbm-qmi $(1)/usr/sbin/qbm-qmi
	$(INSTALL_BIN) ./files/qbm-diagd.init $(1)/etc/init.d/qbm-diagd
	$(SED) 's/\r//g' $(1)/etc/init.d/qbm-diagd
	$(INSTALL_CONF) ./files/qbm-diagd.config $(1)/etc/config/qbm-diagd
endef

# Start the new binary now: a running old instance keeps the DIAG port, and a
# stopped one would leave the Network page without statistics until reboot.
define Package/qbm-diagd/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/qbm-diagd enable
/etc/init.d/qbm-diagd restart
exit 0
endef

$(eval $(call BuildPackage,qbm-diagd))
