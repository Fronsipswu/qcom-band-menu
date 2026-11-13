#!/bin/sh
# Builds qbm-diagd (USB DIAG statistics) and qbm-qmi (band/cell locking over
# QMI) for an OpenWrt router, as static binaries (no dependency on the
# router's musl version).
#
#   ./build-openwrt.sh /path/to/openwrt-sdk [out-dir]
#
# Tested with openwrt-sdk-24.10.8-mediatek-filogic (aarch64_cortex-a53, musl),
# e.g. the Cudy TR3000. Any OpenWrt SDK should work: the toolchain is found
# under staging_dir/toolchain-*.
set -eu
SDK=${1:?usage: $0 /path/to/openwrt-sdk [out-dir]}
OUT=${2:-.}
HERE=$(cd "$(dirname "$0")" && pwd)

export STAGING_DIR="$SDK/staging_dir"
CC=$(ls "$STAGING_DIR"/toolchain-*/bin/*-openwrt-linux-musl*-gcc 2>/dev/null | head -n 1)
[ -x "$CC" ] || { echo "no musl gcc under $STAGING_DIR/toolchain-*/bin" >&2; exit 1; }

# _GNU_SOURCE: struct ucred on musl. HAVE_TM_GMTOFF: musl has tm_gmtoff, but
# the asn1c runtime in rrc_nr_gen.c only detects it on glibc and the BSDs.
CFLAGS="-O2 -D_GNU_SOURCE -DHAVE_TM_GMTOFF"

mkdir -p "$OUT"
# The generated ASN.1 decoders are large (rrc_nr_gen.c takes a few minutes):
# each is rebuilt only when it changes.
for gen in rrc_nr_gen rrc_lte_gen; do
    if [ ! -f "$OUT/$gen.o" ] || [ "$HERE/$gen.c" -nt "$OUT/$gen.o" ]; then
        echo "compiling $gen.c (slow)..."
        "$CC" $CFLAGS -c -o "$OUT/$gen.o" "$HERE/$gen.c"
    fi
done
"$CC" $CFLAGS -DQBM_USB_DIAG -Wall -Wextra -Wno-missing-field-initializers -static -s \
    -o "$OUT/qbm-diagd" "$HERE/qbm-diagd.c" "$HERE/rrc_csi.c" "$HERE/rrc_lte.c" \
    "$OUT/rrc_nr_gen.o" "$OUT/rrc_lte_gen.o" -lm -lpthread
echo "built $OUT/qbm-diagd"

# qbm-qmi: qcom-bandlockd's band/cell locking over the QMI port (cdc-wdm).
"$CC" -O2 -DQBM_QMUX -Wall -Wno-unused-function -Wno-misleading-indentation \
    -Wno-pointer-to-int-cast -Wno-int-to-pointer-cast -static -s \
    -o "$OUT/qbm-qmi" "$HERE/qcom-bandlockd_v5-0-0.c" "$HERE/qbm-qmux.c"
echo "built $OUT/qbm-qmi"
