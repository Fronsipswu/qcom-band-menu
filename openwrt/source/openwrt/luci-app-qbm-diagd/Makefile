#
# LuCI pages for qbm-diagd: live LTE/NR carrier statistics and settings.
#
# A plain package rather than luci.mk, so it builds in a bare OpenWrt SDK:
# it only installs files. luci-base is an install-time dependency
# (EXTRA_DEPENDS) and is not built.
#
include $(TOPDIR)/rules.mk

PKG_NAME:=luci-app-qbm-diagd
PKG_VERSION:=2.0.0
PKG_RELEASE:=16

PKG_LICENSE:=GPL-3.0-only

include $(INCLUDE_DIR)/package.mk

define Package/luci-app-qbm-diagd
  SECTION:=luci
  CATEGORY:=LuCI
  SUBMENU:=3. Applications
  TITLE:=LuCI support for qbm-diagd (modem statistics, band and cell locking)
  DEPENDS:=+qbm-diagd
  EXTRA_DEPENDS:=luci-base (>=0), rpcd (>=0)
  PKGARCH:=all
endef

define Package/luci-app-qbm-diagd/description
  Modem > QBM: Network, live LTE and 5G NR carrier
  aggregation tables from qbm-diagd in the QBM app's layout (signal per
  carrier and antenna, MCS, RBs, modulation, rank, BLER, throughput,
  uplink, NR neighbours); band, network mode and operator
  locking, and LTE/NR cell locking through qbm-qmi; settings. A Fibocom
  FM350 is locked with AT+GTACT (no cell locking yet).
endef

define Build/Compile
endef

define Package/luci-app-qbm-diagd/install
	$(INSTALL_DIR) $(1)/www/luci-static/resources/view/qbm-diagd
	$(INSTALL_DATA) ./htdocs/luci-static/resources/view/qbm-diagd/*.js $(1)/www/luci-static/resources/view/qbm-diagd/
	$(INSTALL_DIR) $(1)/www/luci-static/resources/qbm-diagd
	$(INSTALL_DATA) ./htdocs/luci-static/resources/qbm-diagd/*.js $(1)/www/luci-static/resources/qbm-diagd/
	$(INSTALL_DIR) $(1)/usr/libexec/rpcd
	$(INSTALL_BIN) ./root/usr/libexec/rpcd/luci.qbm-diagd $(1)/usr/libexec/rpcd/
	$(SED) 's/\r//g' $(1)/usr/libexec/rpcd/luci.qbm-diagd
	$(INSTALL_DIR) $(1)/usr/share/rpcd/acl.d
	$(INSTALL_DATA) ./root/usr/share/rpcd/acl.d/luci-app-qbm-diagd.json $(1)/usr/share/rpcd/acl.d/
	$(INSTALL_DIR) $(1)/usr/share/luci/menu.d
	$(INSTALL_DATA) ./root/usr/share/luci/menu.d/luci-app-qbm-diagd.json $(1)/usr/share/luci/menu.d/
endef

# rpcd loads plugins and ACLs at start; LuCI caches its menu index.
# "reload" does not re-scan /usr/libexec/rpcd, so a plugin installed after
# rpcd started (or one that failed while broken) stays unregistered until a
# restart: that is the LuCI "No answer from qbm-diagd" with no daemon problem.
define Package/luci-app-qbm-diagd/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
rm -f /tmp/luci-indexcache* 2>/dev/null
rm -rf /tmp/luci-modulecache 2>/dev/null
/etc/init.d/rpcd restart 2>/dev/null
exit 0
endef

$(eval $(call BuildPackage,luci-app-qbm-diagd))
