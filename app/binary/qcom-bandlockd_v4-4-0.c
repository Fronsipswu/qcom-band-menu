/*
 * qcom-bandlockd.c
 * Root daemon for Qualcomm QMI NAS band/RAT/mode locking on ARM64 Android.
 *
 * This is a migration of qcom-band-menu-v3.2.c from an interactive terminal
 * tool into a long-lived background daemon meant to be driven by an app,
 * not a human typing commands. The two things that change are:
 *
 *   1. Modem-facing transport: UNCHANGED. Every byte this daemon puts on
 *      the wire to the modem, and every TLV id/length/interpretation, is
 *      identical to qcom-band-menu-v3.2.c. This file was built by carrying
 *      every QMI/TLV/validation function over verbatim -- discover_service,
 *      open_nas, exchange, bind_sim, query, dms_exchange, query_hardware,
 *      addtlv, setter, plist/mset/mhas/wbit/set64, hw_gsm_has/hw_wcdma_has,
 *      cmd_rat, cmd_lte(+hardware), cmd_gsm(+hardware), cmd_wcdma(+hardware),
 *      cmd_nr(+hardware), cmd_mode, cmd_reset, reopen_bound -- are the same
 *      code, doing the same syscalls, sending the same bytes. The only
 *      exceptions, and the *only* places touched inside that set, are:
 *        - setter(): instead of printing the modem's result/error codes to
 *          a terminal, it now records them into s->last_op so they can be
 *          serialized as JSON fields (see "Command rejections" below).
 *        - exchange(): instead of printing a verbose TX/RX hex+TLV dump to
 *          a terminal, it now unconditionally records the raw request and
 *          the last-seen response into s->diag (cheap memcpy, no syscalls);
 *          this is serialized into the response's "diagnostics" field only
 *          when verbose mode is on.
 *        - cmd_lte()/cmd_nr(): the print_rejected() call (which printed
 *          "Unsupported band(s): ..." to a terminal) is now record_rejected()
 *          (which stores the same information into s->last_rejected_* for
 *          the JSON "error.rejected_bands" field). The validation logic
 *          that decides *which* bands are unsupported is untouched.
 *        - cmd_gsm()/cmd_wcdma(): same swap, for their inline (GSM/WCDMA
 *          use a different validity check than the generic bitmask one,
 *          see hw_gsm_has/hw_wcdma_has) unsupported-band print blocks.
 *      No TLV id, no TLV length, no validation threshold, no fallback
 *      order (e.g. legacy-vs-extended LTE TLV selection) changed.
 *
 *   2. App-facing interface: everything that used to be a human typing a
 *      line at a TUI and reading formatted text back is now a newline-
 *      delimited JSON (NDJSON) request/response exchange over a Unix
 *      domain socket. See qcom-bandlockd-app-interface.md for the full
 *      protocol reference; the short version is below.
 *
 * Transport to the app:
 *   AF_UNIX SOCK_STREAM, abstract-namespace address (no filesystem path,
 *   no permissions/SELinux-label juggling for a socket file). Default name
 *   "qcom_bandlockd", override with -name. One client connection is served
 *   at a time; a second concurrent connection simply waits in the listen
 *   backlog until the first disconnects. Every line sent by the client is
 *   one JSON request object; every line sent back is exactly one JSON
 *   response object. See the .md file for the full command/field reference.
 *
 * Peer authentication:
 *   The daemon requires -uid <uid> at launch (the caller -- normally the
 *   app, via libsu or similar, since it's the one that knows its own
 *   android.os.Process.myUid()) and checks every accepted connection's
 *   SO_PEERCRED uid against it, silently dropping any connection that
 *   doesn't match. There is no other authentication layer -- anything
 *   that can run code as that uid can drive the modem through this daemon,
 *   which is the same trust boundary the app already has by virtue of
 *   being the one that requested root to launch this in the first place.
 *
 * Command rejections -- how they reach the app:
 *   Every response has "ok":true|false. When false, "error" is an object
 *   with a "stage" field distinguishing *where* the failure happened:
 *     "bad_request"    the JSON request itself was malformed, missing a
 *                       required field, or named an unknown "cmd" -- the
 *                       daemon never touched the modem.
 *     "validation"     the request was well-formed but named band(s) the
 *                       modem's own hardware-capability report doesn't
 *                       support -- the daemon never touched the modem for
 *                       *this* request either (mirrors the original CLI's
 *                       "print supported/rejected bands, do not send"
 *                       behavior). "rejected_bands" lists exactly which
 *                       requested bands were the problem.
 *     "transport"      the request reached exchange(), a SET/GET was sent,
 *                       but no matching reply arrived (send failure, or 16
 *                       non-matching packets / timeout with no match).
 *     "modem_rejected" the modem replied, but its own TLV_RESULT said
 *                       failure. "result" and "code" carry the *exact*
 *                       numeric QMI result/error codes the modem returned
 *                       (e.g. result=1/code=1, which is what an invalid
 *                       SET TLV id looks like on this device -- see the
 *                       "Independent SA/NSA band locking" note below).
 *     "daemon"          anything else (SIM bind/reconnect failure, no
 *                       reply to a GET query, internal fallback bucket).
 *   "message" always carries the same human-readable status text the
 *   original CLI's status line used to show (setstatus() is unchanged),
 *   so nothing about *why* something failed was lost in this migration --
 *   it just also comes back as a structured, parseable field now instead
 *   of only as English prose.
 *
 * Independent SA/NSA band locking (carried over from v3.2, unchanged):
 *   TLV 0x2C/0x2D are the ids the device uses to REPORT the current NR-SA/
 *   NR-NSA band state on a GET reply. They are NOT valid write ids -- an
 *   earlier revision reused them on SET and the modem rejected it with
 *   result=0x0001/error=0x0001. The correct write ids, cross-referenced
 *   from a separate tool's working captures, are 0x2F (NR-SA) and 0x30
 *   (NR-NSA); GET keeps reading 0x2C/0x2D. cmd_nr() sends only
 *   TLV_DURATION (0x17) plus the one 64-byte NR mask being set, same as
 *   every other incremental setter in this file.
 *
 * NR MODE (carried over from v3.2, unchanged):
 *   On GET, id 0x2B (the same id the "nr" command uses on SET for its
 *   64-byte combined band mask) is reused for a 4-byte current-mode
 *   report: value byte 0 is 0x02/0x01/0x00 for sa/nsa/both, matching
 *   cmd_mode()'s own encoding. query() parses this (guarded to length==4
 *   so it can never collide with the 64-byte meaning), so NR MODE is
 *   correct from the very first query.
 *
 * Launch flags:
 *   -uid <uid>     REQUIRED. Only accept client connections whose
 *                  SO_PEERCRED uid equals this value.
 *   -name <name>   Abstract socket name. Default: qcom_bandlockd
 *   -verbose       Start with verbose diagnostics on (default: off). Same
 *                  effect as sending {"cmd":"verbose_set","verbose":true}
 *                  as the first request; can be toggled at any time.
 *
 * Usage:
 *   qcom-bandlockd -uid 10234 [-name qcom_bandlockd] [-verbose]
 *
 * Build:
 * clang --target=aarch64-linux-gnu -fuse-ld=lld -O2 -nostdlib -static \
 *   -fno-stack-protector -fno-builtin -Wl,-e,_start \
 *   -Wl,--build-id=none -o qcom-bandlockd qcom-bandlockd.c
 *
 * NOTE ON SYSCALL NUMBERS: bind/listen/getsockopt/accept4 (200/201/209/242)
 * are the standard generic-ABI aarch64 numbers and follow the same table
 * socket/connect/sendto/recvfrom/setsockopt (198/203/206/207/208) already
 * came from and have been running successfully on the target device
 * throughout this project's earlier revisions. They could not be verified
 * by compiling/running in the environment this file was written in (no
 * aarch64 toolchain or device available there) -- please confirm the
 * daemon starts and accepts a connection on first deployment.
 *
 * v4.0.4 -- NR independent SA/NSA band-lock capability probe:
 *   Some older devices/firmware only understand the combined NR band mask
 *   (TLV 0x2B) and reject independent SA-only (0x2F) / NSA-only (0x30) SET
 *   requests outright. The app previously had no way to know this ahead of
 *   time, so it could offer per-domain NR band controls that always failed
 *   on those devices. This revision ports the read-back-then-resend probe
 *   from the standalone qcom-band-menu-compatibility tool into the daemon
 *   itself: it reads the modem's current NR-SA/NR-NSA masks (TLV 0x2C/
 *   0x2D, exactly as query() already does) and resends those same masks
 *   through the independent SET ids (0x2F/0x30). Because the mask being
 *   written back is identical to the one already active, the probe cannot
 *   change what's locked -- it only reveals whether the modem accepts
 *   writes to those TLV ids at all. The probe runs once automatically at
 *   daemon startup (see run()) and again any time the app sends a
 *   {"cmd":"query_nr_independent_capability"} request, so the app can
 *   check it both on daemon spawn and on its own launch as required. The
 *   result -- including the raw QMI result/error codes, and, in verbose
 *   mode, the full request/response hex + decoded TLVs for both probes --
 *   is exposed via the new "nr_independent_capability" field that now
 *   appears in every response's "state" object, so the app can gate its
 *   NR-SA/NR-NSA UI without a round-trip on every screen. See
 *   query_nr_independent_capability()/jcapability() below. Every response
 *   now also carries a top-level "version" field ("4.0.4") for the app to
 *   check protocol/daemon compatibility.
 *
 * v4.1.0 -- Cell lock (specific EARFCN/PCI and NR ARFCN/PCI/multi-PCI/
 *   gNodeB-allow-list locking), ported from qcom-cell-lock-test.c:
 *   Distinct from the band-family locking above (TLV ids 0x11-0x30 on
 *   NAS messages 0x0033/0x0034), this is a *separate* QMI feature that
 *   pins the modem to one or more specific cells (by EARFCN+PCI for LTE,
 *   or by ARFCN/PCI/multi-PCI-list/gNodeB-allow-list for NR), using its
 *   own message ids (0x00D7/0x00D8 for LTE, 0x010E/0x010F for NR) and its
 *   own TLV numbering local to those messages -- see the TLV_LTECELL_ and
 *   TLV_NRCELL_ defines below. Every byte this sends is carried over
 *   unchanged from qcom-cell-lock-test.c's set_lte/clear_lte/nr_unlock/
 *   nr_arfcn/nr_pci/nr_multi/nr_gnb; only the sink changed (JSON fields
 *   instead of terminal prints), same as the rest of this file's
 *   architecture.
 *
 *   Two behaviors carried over/confirmed from that tool, worth knowing:
 *     - LTE cell-lock GET (TLV 0x10 on message 0x00D7) reports each
 *       locked cell's EARFCN as a 16-bit field, even though the SET side
 *       (message 0x00D8) accepts a full 32-bit EARFCN. This is ported
 *       exactly as captured/tested, not "fixed" to 32 bits -- confirmed
 *       intentional, not a porting bug. An EARFCN above 65535 (e.g. some
 *       B70/B71 channels) will read back truncated even though the SET
 *       that locked it carried the full value.
 *     - NR cell-lock clear (type=2 via message 0x010E) is rejected by
 *       some/most firmware when no NR cell lock is currently active --
 *       unlike LTE clear, which always succeeds. Confirmed real modem
 *       behavior, not a bug in this daemon. cmd_nr_cell_lock_clear()
 *       handles this by reading the current lock type first and, if it's
 *       already "none", skipping the write entirely and reporting
 *       synthetic success -- the app never sees this particular
 *       rejection. See cmd_nr_cell_lock_clear() below.
 *
 *   New commands: query_lte_cell_lock, query_nr_cell_lock,
 *   lte_cell_lock_set, lte_cell_lock_clear, nr_cell_lock_pci_set,
 *   nr_cell_lock_arfcn_set, nr_cell_lock_multi_pci_set,
 *   nr_cell_lock_gnb_set, nr_cell_lock_clear. New state fields:
 *   "lte_cell_lock", "nr_cell_lock". See qcom-bandlockd-app-interface.md.
 *
 * v4.3.0 -- PLMN (manual network selection) lock:
 *   QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE (NAS message id 0x0033 --
 *   the SAME message id MSG_SET already is, used throughout this file for
 *   RAT/GSM/WCDMA/LTE/NR band and mode preferences) has an optional TLV
 *   0x16, "Network Selection Preference", not previously implemented
 *   here: enum8 net_sel_pref (0x00=AUTOMATIC, 0x01=MANUAL) + uint16 mcc +
 *   uint16 mnc (5 bytes total). GET (message id 0x0034, MSG_GET) reports
 *   the same TLV id with the same 5-byte shape -- unlike some of this
 *   device's other fields (e.g. NR-SA/NR-NSA, TLV 0x2C/0x2D on GET vs
 *   0x2F/0x30 on SET), this one is NOT split by direction. Sent the same
 *   incremental way every other setter in this file uses: TLV_DURATION
 *   (0x17) plus the one TLV being changed, nothing else. query() now also
 *   parses TLV 0x16 out of every GET reply (same TLV walk as RAT/GSM/LTE/
 *   NR, no separate round trip needed), so PLMN lock state is included in
 *   every response's "state.plmn_lock" field for free. New commands:
 *   plmn_lock_set (fields "mcc","mnc", each 0-999), plmn_lock_clear (no
 *   fields, returns to AUTOMATIC). See jplmn_lock()/cmd_plmn_lock_set()/
 *   cmd_plmn_lock_clear() below and qcom-bandlockd-app-interface.md.
 *
 * v4.3.1 -- Confirmed locked-PLMN report (TLV 0x1B):
 *   TLV 0x16 (v4.3.0, above) is the network selection *preference* --
 *   what was asked for. A separate GET-only TLV, 0x1B "Manual Network
 *   Selection PLMN", is the authoritative report of which PLMN the modem
 *   actually has locked/registered to, and unlike TLV 0x16 it carries
 *   mnc_includes_pcs_digit (mcc:u16, mnc:u16, mnc_includes_pcs_digit:bool,
 *   5 bytes), needed to tell a reported MNC of 90 apart from 090. query()
 *   now also parses this (same TLV walk, still no extra round trip), into
 *   the new "state.plmn_lock.locked_plmn" field -- null until TLV 0x1B
 *   has actually been seen in a GET reply, otherwise an object with raw
 *   "mcc"/"mnc"/"mnc_includes_pcs_digit" plus a convenience pre-padded
 *   "mnc_display" string ("090" vs "90") so the app doesn't have to
 *   reimplement that logic. No new commands -- this rides along with the
 *   existing "query"/every other query. See jplmn_lock()/jmnc_padded().
 *
 * v4.3.2 -- plmn_lock.valid fix (field-confirmed device behavior):
 *   A real device was found that never includes TLV 0x16 in its GET
 *   replies at all, only TLV 0x1B -- a "refresh" right after a successful
 *   plmn_lock_set showed "locked_plmn" fully populated while TLV 0x16 was
 *   simply absent from that same reply. state.plmn_lock's top-level
 *   "valid" was, until this version, tied to TLV 0x16 alone
 *   (s->netsel_valid) -- meaning on that device the whole object looked
 *   invalid to the app even though "locked_plmn" had real, useful data.
 *   "valid" is now s->netsel_valid || s->manual_plmn_valid (true if
 *   EITHER TLV showed up in the last GET). The app-facing implication:
 *   don't gate reading "locked_plmn" on "valid" being true first --
 *   check "locked_plmn" for null directly; it's independent of "valid"
 *   and of "mode"/"mode_raw"/"mcc"/"mnc" (which remain specifically tied
 *   to TLV 0x16 and can be null while "locked_plmn" is populated, as on
 *   this device). No wire-format or TLV-parsing change, JSON-shape-only.
 *
 * v4.3.4 -- reset no longer fails whole on a known-unsupported NR domain:
 *   Field-observed on a real device (via nr_independent_capability, which
 *   already runs automatically at startup): independent NR-SA/NR-NSA SET
 *   (TLV 0x2F/0x30) rejected with result=1/code=17 on that device, exactly
 *   as the SA/NSA capability probe had already predicted -- but
 *   cmd_reset() unconditionally attempted both anyway after the combined
 *   NR mask (0x2B) had already restored successfully, so that one
 *   predictable rejection failed the *entire* reset command, discarding
 *   the GSM/WCDMA/LTE/combined-NR restoration that had already succeeded
 *   (setter() failure makes cmd_reset() return 0 immediately). cmd_reset()
 *   now checks s->nr_cap (from the same probe) before attempting each of
 *   the independent SA/NSA SETs, and skips (does not attempt) whichever
 *   domain(s) the probe already found rejected (sa_supported/nsa_supported
 *   == 0) -- the combined NR mask sent earlier in the same call is this
 *   project's established fallback for that case, so nothing is left
 *   unrestored, just not redundantly (and predictably-failingly)
 *   re-attempted. Domains the probe reports as unknown (never probed, or
 *   the corresponding current mask wasn't present -- sa_supported/
 *   nsa_supported == -1) still get attempted, same as before this
 *   version, since "unknown" isn't evidence it will fail. The final
 *   status message says so explicitly when anything was skipped, instead
 *   of unconditionally reporting a full restore. No new commands or
 *   fields -- see cmd_reset() below.
 *
 * v4.4.0 -- LTE cell lock: multi-PCI support (field-confirmed on device):
 *   TLV_LTECELL_SET_LOCK (0x01 on message 0x00D8) was already, byte-for-
 *   byte, a count-prefixed list TLV -- value: count:u8, then count*[pci:u16,
 *   earfcn:u32] -- not a single-cell-only shape with a "count" that could
 *   only ever be 0 or 1. cmd_lte_cell_lock_set() just never exercised the
 *   >1 case: it always wrote count=1. This was confirmed field-testable and
 *   working with count>1 (multiple {pci,earfcn} pairs, same or different
 *   EARFCN per entry) via a standalone test tool before being ported here --
 *   no new TLV id, no new message id, no wire-format change of any kind,
 *   only the request now carries more entries when asked to.
 *   IMPORTANT (see qcom-bandlockd-app-interface.md and the new .md written
 *   for the app-frontend LLM): this is NOT a frequency-only/EARFCN-only
 *   lock. Every entry still requires an explicit PCI; QMI_NAS_SET_CELL_
 *   CONFIG has no "match any PCI on this EARFCN" mode in this NAS spec
 *   version (confirmed by checking the vendor QMI NAS spec directly -- NR5G
 *   cell lock has an explicit ARFCN-only ConfigurationType, LTE cell lock
 *   does not). Multi-PCI locking to every currently-visible PCI on a
 *   channel is the closest available approximation, not a true wildcard --
 *   a PCI that later appears on that EARFCN but wasn't in the list you
 *   locked to will still be excluded.
 *   New command: lte_cell_lock_multi_pci_set (fields "earfcn", "pci_list"
 *   -- non-empty array of integers 0-503, max 64, mirrors nr_cell_lock_
 *   multi_pci_set's "pci_list" shape). lte_cell_lock_set (single "pci") is
 *   unchanged as a convenience wrapper -- it now calls the same underlying
 *   cmd_lte_cell_lock_multi_pci_set() with a one-element list, so both
 *   commands share one code path and one TLV builder. LTE_CELL_LOCK_MAX
 *   raised from 16 to 64 (see its definition) so a GET immediately after a
 *   large multi-PCI SET reads back the full list instead of truncating it.
 *   See cmd_lte_cell_lock_multi_pci_set()/jlte_cell_lock() below.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long u64;
typedef long s64;

enum { SYS_close=57,SYS_read=63,SYS_write=64,SYS_exit=93,SYS_nanosleep=101,SYS_clock_gettime=113,
       SYS_socket=198,SYS_connect=203,SYS_bind=200,SYS_listen=201,
       SYS_sendto=206,SYS_recvfrom=207,SYS_setsockopt=208,SYS_getsockopt=209,SYS_accept4=242 };
enum { AF_UNIX=1,AF_QIPCRTR=42,SOCK_STREAM=1,SOCK_DGRAM=2,SOL_SOCKET=1,SO_RCVTIMEO=20,SO_PEERCRED=17,CLOCK_MONOTONIC=1 };
#define QRTR_CTRL_NODE 1u
#define QRTR_PORT_CTRL 0xFFFFFFFEu
#define NAS_SERVICE 3u
#define DMS_SERVICE 2u
#define DMS_MSG_GET_BANDS 0x0045u
#define NAS_PACKED_INSTANCE 1u
#define QRTR_TYPE_NEW_LOOKUP 0x0Au
#define QRTR_TYPE_NEW_SERVER 0x04u
#define MSG_SET 0x0033u
#define MSG_GET 0x0034u
#define MSG_BIND 0x0045u
#define TLV_RESULT 0x02u
#define TLV_MODE 0x11u
#define TLV_LEGACY 0x12u
#define TLV_LTE 0x15u
#define TLV_DURATION 0x17u
#define TLV_EXT_LTE_GET 0x23u
#define TLV_EXT_LTE_SET 0x24u
#define TLV_NR_COMBINED 0x2Bu
#define TLV_NR_SA_GET 0x2Cu   /* read-back id, confirmed working: query() parses this from GET replies */
#define TLV_NR_NSA_GET 0x2Du  /* read-back id, confirmed working: query() parses this from GET replies */
#define TLV_NR_SA_SET 0x2Fu   /* write id for an independent NR-SA lock */
#define TLV_NR_NSA_SET 0x30u  /* write id for an independent NR-NSA lock */
#define TLV_NR_MODE 0x2Eu
#define TLV_NET_SEL_PREF 0x16u /* Network Selection Preference -- SAME id and SAME 5-byte value (enum8 net_sel_pref, uint16 mcc, uint16 mnc) on both SET (0x0033) and GET (0x0034), unlike some of this device's other fields. See the v4.3.0 header note. */
#define TLV_MANUAL_PLMN 0x1Bu  /* "Manual Network Selection PLMN" -- GET (0x0034) reply only, distinct from TLV 0x16. This is the authoritative report of which PLMN is actually locked/registered, and it's the only one of the two that carries mnc_includes_pcs_digit (needed to know whether a reported MNC of e.g. 90 means 090 or 90). Value: mcc:u16 LE, mnc:u16 LE, mnc_includes_pcs_digit:bool (5 bytes). See the v4.3.1 header note. */

/* ── Cell lock: a separate QMI feature from the band-family locking above.
 * Its own message ids, and TLV ids that are only meaningful *within* those
 * specific messages (QMI TLV numbering is per-message, not global -- e.g.
 * TLV_NRCELL_SET_PCI and TLV_NRCELL_GET_TYPE are both 0x10 but never
 * appear in the same buffer, since one is a SET request TLV and the other
 * a GET reply TLV on a different message id entirely). See the v4.1.0
 * header note above and qcom-cell-lock-test.c for where these came from. */
#define MSG_LTE_CELL_LOCK_GET 0x00D7u
#define MSG_LTE_CELL_LOCK_SET 0x00D8u
#define MSG_NR_CELL_LOCK_SET  0x010Eu
#define MSG_NR_CELL_LOCK_GET  0x010Fu

/* LTE cell-lock SET (0x00D8) TLVs */
#define TLV_LTECELL_SET_LOCK  0x01u /* value: count:u8, then count*[pci:u16,earfcn:u32] (1 byte when count=0/clearing, 1+6*count bytes otherwise). Was only ever written with count<=1 before v4.4.0; see that header note -- this is the same field qcom-cell-lock-test.c's set_lte()/clear_lte() wrote, just exercised beyond count=1 now. */
#define TLV_LTECELL_SET_APPLY 0x10u /* value: apply:u8=1 */
/* LTE cell-lock GET (0x00D7) reply TLV */
#define TLV_LTECELL_GET_LIST  0x10u /* value: count:u8, then count*[pci:u16,earfcn:u16] -- EARFCN read back as 16-bit, see v4.1.0 header note */

/* NR cell-lock SET (0x010E) TLVs */
#define TLV_NRCELL_SET_TYPE   0x01u /* value: type:u32 -- 0=PCI,1=ARFCN,2=none,3=multi-PCI,4=gNodeB allow-list */
#define TLV_NRCELL_SET_PCI    0x10u /* value: pci:u16,scs:u32,arfcn:u32,band_mask:64B (74 bytes) -- for type=0 */
#define TLV_NRCELL_SET_ARFCN  0x11u /* value: count:u8,arfcn:u32,scs:u32 (9 bytes; count always 1 here) -- for type=1 */
#define TLV_NRCELL_SET_MULTI  0x12u /* value: count:u8,pci[]:u16*count,scs_mask:u16,arfcn:u32,band_mask:64B -- for type=3 */
#define TLV_NRCELL_SET_GNB    0x13u /* value: count:u8,gnb_id[]:u64*count,id_bits:u8 -- for type=4 */
/* NR cell-lock GET (0x010F) reply TLVs -- note the ids shift by one vs the SET side above; this is exactly what the modem reports and is carried over as-is. */
#define TLV_NRCELL_GET_TYPE   0x10u /* value: type:u32, same enum as TLV_NRCELL_SET_TYPE */
#define TLV_NRCELL_GET_PCI    0x11u /* value: same 74-byte shape as TLV_NRCELL_SET_PCI's value */
#define TLV_NRCELL_GET_ARFCN  0x12u /* value: count:u8, then count*[arfcn:u32,scs:u32] (8 bytes/entry) */
#define TLV_NRCELL_GET_MULTI  0x13u /* presence-only marker -- qcom-cell-lock-test.c never decoded this TLV's contents for multi-PCI read-back, only reported that it was present; carried over identically, see jnr_cell_lock() */
#define TLV_NRCELL_GET_GNB    0x14u /* value: count:u8, then count*gnb_id:u64, then an optional trailing id_bits:u8 */

#define LTE_CELL_LOCK_MAX 64u      /* cap on LTE cell-lock entries, both directions: GET decode AND SET encode (v4.4.0 -- previously 16 and GET-only, since the SET side only ever wrote one entry; see the v4.4.0 header note on lte_cell_lock_multi_pci_set). Matches NR_CELL_MULTI_PCI_MAX so the two multi-cell features share one mental model. */
#define NR_CELL_ARFCN_MAX 16u      /* cap on decoded NR ARFCN-list GET entries kept in state (the SET side only ever writes one) */
#define NR_CELL_MULTI_PCI_MAX 64u  /* cap on multi-PCI SET request PCI list, matches qcom-cell-lock-test.c's own cap */
#define NR_CELL_GNB_MAX 32u        /* cap on gNodeB allow-list SET/GET entries, matches qcom-cell-lock-test.c's own cap */

#define DAEMON_VERSION "4.4.0"

struct sockaddr_qrtr{u16 family,pad;u32 node,port;};
struct qrtr_ctrl_pkt{u32 command,service,instance,node,port;};
struct timeval64{s64 sec,usec;};
struct timespec64{s64 sec,nsec;};
struct sockaddr_un{u16 family;char path[108];};
struct ucred{u32 pid,uid,gid;};

/* Outcome of the single most recent setter()/exchange() transaction, kept
 * around so the JSON response layer can turn it into a structured error
 * without setter() itself knowing anything about JSON. */
struct opresult{int ok;int have_reply;int have_result;u16 result,error;};

/* Raw capture of the single most recent QMI transaction, populated
 * unconditionally by exchange() (cheap -- just a bounded memcpy, no
 * syscalls) and only serialized into a response when verbose is on. */
struct diag{
 int have;u16 msg;
 u8 tx[640];u32 tx_len;
 u8 rx[2048];u32 rx_len;
 int got_match;int send_failed;int timed_out;int skipped_count;
};

/* Result of probing whether this device/firmware accepts independent
 * NR-SA (0x2F) / NR-NSA (0x30) SET writes, as opposed to only the combined
 * NR mask (0x2B). See query_nr_independent_capability() for how this is
 * populated -- ported from qcom-band-menu-compatibility.c's probe(). Each
 * *_supported value is -1 (unknown/no data -- either the current mask
 * wasn't present in the last GET, or the probe SET got no usable reply),
 * 0 (modem replied with a non-zero TLV_RESULT -- rejected), or 1 (modem
 * accepted the write). *_diag is a raw wire capture of that one probe
 * transaction, independent of the general-purpose s->diag (which the next
 * exchange() call after this, for anything else, will overwrite) --
 * serialized only when verbose is on, same rule as s->diag. */
struct nr_indep_probe{
 int ran;
 int sa_present,nsa_present;
 int sa_supported,nsa_supported;
 u16 sa_result,sa_error,nsa_result,nsa_error;
 struct diag sa_diag,nsa_diag;
};

/* ── Cell lock state (v4.1.0, ported from qcom-cell-lock-test.c). ────────
 * See the header's v4.1.0 note for the message/TLV layout this mirrors. */
enum { NRCELL_TYPE_PCI=0, NRCELL_TYPE_ARFCN=1, NRCELL_TYPE_NONE=2, NRCELL_TYPE_MULTI=3, NRCELL_TYPE_GNB=4, NRCELL_TYPE_UNKNOWN=99 };

struct lte_cell_entry{ u16 pci,earfcn; };
struct lte_cell_lock{
 int valid;
 u32 count;
 struct lte_cell_entry entries[LTE_CELL_LOCK_MAX];
};

struct nr_cell_pci{ u16 pci; u32 scs; u32 arfcn; u8 band_mask[64]; };
struct nr_cell_arfcn_entry{ u32 arfcn,scs; };
struct nr_cell_gnb{ u32 count; u64 ids[NR_CELL_GNB_MAX]; int have_id_bits; u32 id_bits; };
struct nr_cell_lock{
 int valid;
 u32 type;                                    /* NRCELL_TYPE_* -- from TLV_NRCELL_GET_TYPE */
 int have_pci; struct nr_cell_pci pci;         /* from TLV_NRCELL_GET_PCI */
 int have_arfcn_list; u32 arfcn_count; struct nr_cell_arfcn_entry arfcn[NR_CELL_ARFCN_MAX]; /* from TLV_NRCELL_GET_ARFCN */
 int have_multi_marker;                        /* TLV_NRCELL_GET_MULTI seen but not decoded -- matches source tool */
 int have_gnb; struct nr_cell_gnb gnb;         /* from TLV_NRCELL_GET_GNB */
};

struct state{
 s64 fd;u32 node,port;int sim;u16 rat;
 u8 legacy[8],lte[8],extlte[32],sa[64],nsa[64];int valid;
 int sa_present,nsa_present;
 u8 hw_legacy[8],hw_lte[32],hw_nr[64];int hw_valid;
 int nr_mode[2],nr_mode_known[2];
 int netsel_valid;u8 net_sel_pref;u16 plmn_mcc,plmn_mnc; /* TLV 0x16, see v4.3.0 header note */
 int manual_plmn_valid;u16 manual_plmn_mcc,manual_plmn_mnc;int manual_plmn_pcs; /* TLV 0x1B, see v4.3.1 header note -- distinct from the TLV 0x16 fields above */
 char status[160];
 int verbose;
 struct opresult last_op;
 u32 last_rejected_bands[64];int last_rejected_count;char last_rejected_label[16];
 struct diag diag;
 struct nr_indep_probe nr_cap;
 struct lte_cell_lock lte_cell;
 struct nr_cell_lock nr_cell;
};

static inline s64 sc1(s64 n,s64 a){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;__asm__ volatile("svc 0":"+r"(x0):"r"(x8):"memory");return x0;}
static inline s64 sc2(s64 n,s64 a,s64 b){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;register s64 x1 __asm__("x1")=b;__asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x8):"memory");return x0;}
static inline s64 sc3(s64 n,s64 a,s64 b,s64 c){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;register s64 x1 __asm__("x1")=b;register s64 x2 __asm__("x2")=c;__asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x2),"r"(x8):"memory");return x0;}
static inline s64 sc4(s64 n,s64 a,s64 b,s64 c,s64 d){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;register s64 x1 __asm__("x1")=b;register s64 x2 __asm__("x2")=c;register s64 x3 __asm__("x3")=d;__asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory");return x0;}
static inline s64 sc5(s64 n,s64 a,s64 b,s64 c,s64 d,s64 e){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;register s64 x1 __asm__("x1")=b;register s64 x2 __asm__("x2")=c;register s64 x3 __asm__("x3")=d;register s64 x4 __asm__("x4")=e;__asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x8):"memory");return x0;}
static inline s64 sc6(s64 n,s64 a,s64 b,s64 c,s64 d,s64 e,s64 f){register s64 x8 __asm__("x8")=n;register s64 x0 __asm__("x0")=a;register s64 x1 __asm__("x1")=b;register s64 x2 __asm__("x2")=c;register s64 x3 __asm__("x3")=d;register s64 x4 __asm__("x4")=e;register s64 x5 __asm__("x5")=f;__asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8):"memory");return x0;}

static u64 slen(const char*s){u64 n=0;while(s[n])n++;return n;}
static void zero(void*p,u64 n){u8*b=p;while(n--)*b++=0;}
static void copy(void*d,const void*s,u64 n){u8*dd=d;const u8*ss=s;while(n--)*dd++=*ss++;}
static int eq(const char*a,const char*b){while(*a&&*b&&*a==*b){a++;b++;}return *a==*b;}
static int starts(const char*s,const char*p){while(*p)if(*s++!=*p++)return 0;return 1;}
static u16 le16(const u8*p){return (u16)p[0]|((u16)p[1]<<8);}
static u32 le32(const u8*p){return (u32)p[0]|((u32)p[1]<<8)|((u32)p[2]<<16)|((u32)p[3]<<24);}
static u64 le64(const u8*p){return (u64)p[0]|((u64)p[1]<<8)|((u64)p[2]<<16)|((u64)p[3]<<24)|((u64)p[4]<<32)|((u64)p[5]<<40)|((u64)p[6]<<48)|((u64)p[7]<<56);}
static void put16(u8*p,u16 v){p[0]=(u8)v;p[1]=(u8)(v>>8);}
static void put32(u8*p,u32 v){p[0]=(u8)v;p[1]=(u8)(v>>8);p[2]=(u8)(v>>16);p[3]=(u8)(v>>24);}
static void put64(u8*p,u64 v){int i;for(i=0;i<8;i++){p[i]=(u8)v;v>>=8;}}
static void setstatus(struct state*s,const char*x){u64 i=0;while(x[i]&&i+1<sizeof(s->status)){s->status[i]=x[i];i++;}s->status[i]=0;}
/* Daemon-startup diagnostics only (before any client is connected -- there
 * is no JSON response channel yet to report a NAS-open/SIM-bind failure
 * through). Whoever launches this process (the app, via libsu or similar)
 * should capture stderr. Nothing at or after the accept() loop writes here. */
static void elog(const char*s){sc3(SYS_write,2,(s64)s,(s64)slen(s));}

/* ── JSON writer: bounded append into a caller-owned buffer ─────────────
 * No allocation, no generic nesting tracker -- every JSON object/array in
 * this file is hand-assembled by the function that knows its exact shape,
 * with jputc('{'/'}'/'['/']') and manual commas between fields. Every
 * append is bounds-checked against cap; on a too-small buffer this simply
 * stops writing further bytes rather than overflowing (the response would
 * come out truncated/invalid JSON in that edge case, never unsafe). */
static void jput(char*b,u32*pos,u32 cap,const char*s){while(*s){if(*pos+1<cap)b[(*pos)++]=*s;s++;}}
static void jputc(char*b,u32*pos,u32 cap,char c){if(*pos+1<cap)b[(*pos)++]=c;}
static void jint(char*b,u32*pos,u32 cap,u32 v){char t[12];int n=0,i;if(!v){jputc(b,pos,cap,'0');return;}while(v){t[n++]=(char)('0'+v%10);v/=10;}for(i=n-1;i>=0;i--)jputc(b,pos,cap,t[i]);}
static void jbool(char*b,u32*pos,u32 cap,int v){jput(b,pos,cap,v?"true":"false");}
static void jstr(char*b,u32*pos,u32 cap,const char*s){
 jputc(b,pos,cap,'"');
 while(*s){
  unsigned char c=(unsigned char)*s;
  if(c=='"'||c=='\\'){jputc(b,pos,cap,'\\');jputc(b,pos,cap,(char)c);}
  else if(c=='\n')jput(b,pos,cap,"\\n");
  else if(c=='\r')jput(b,pos,cap,"\\r");
  else if(c=='\t')jput(b,pos,cap,"\\t");
  else if(c<0x20){jput(b,pos,cap,"\\u00");{char h[3];h[0]="0123456789abcdef"[(c>>4)&0xf];h[1]="0123456789abcdef"[c&0xf];h[2]=0;jput(b,pos,cap,h);}}
  else jputc(b,pos,cap,(char)c);
  s++;
 }
 jputc(b,pos,cap,'"');
}
static void jhex_bytes(char*b,u32*pos,u32 cap,const u8*p,u32 n){
 u32 i;for(i=0;i<n;i++){char h[2];h[0]="0123456789ABCDEF"[(p[i]>>4)&0xf];h[1]="0123456789ABCDEF"[p[i]&0xf];jputc(b,pos,cap,h[0]);jputc(b,pos,cap,h[1]);}
}
static u32 u32_to_dec(u32 v,char*out){char t[12];u32 n=0,i;if(!v){out[0]='0';return 1;}while(v){t[n++]=(char)('0'+v%10);v/=10;}for(i=0;i<n;i++)out[i]=t[n-1-i];return n;}

/* ── JSON reader: field extractors for a flat, protocol-controlled schema
 * ─────────────────────────────────────────────────────────────────────
 * This is deliberately not a general-purpose JSON parser. The daemon
 * defines both sides of this protocol (see the .md spec), and every
 * request is a flat object -- no nested objects, no key ever repeated
 * with a different meaning at a different nesting depth. json_find scans
 * for a top-level `"key":` and returns a pointer to the value; it does not
 * track nesting depth, so a request containing a *string value* equal to
 * one of the reserved field names (e.g. a "mode" field whose string
 * content is literally the text `id`) could confuse it -- acceptable for
 * a controlled, same-app-uid protocol where the worst case is a rejected
 * request, never a memory-safety issue (every scan is bounded by the
 * NUL-terminated input buffer, every write is bounds-checked against an
 * explicit output capacity). */
static const char*json_find(const char*j,const char*key){
 u32 klen=(u32)slen(key);const char*p=j;
 while(*p){
  if(*p=='"'){
   const char*start=p+1;const char*q=start;
   while(*q&&*q!='"'){if(*q=='\\'&&q[1])q++;q++;}
   if((u32)(q-start)==klen){
    u32 i;int match=1;
    for(i=0;i<klen;i++)if(start[i]!=key[i]){match=0;break;}
    if(match){
     const char*v=q+1;
     while(*v==' '||*v=='\t'||*v=='\n'||*v=='\r')v++;
     if(*v==':'){v++;while(*v==' '||*v=='\t'||*v=='\n'||*v=='\r')v++;return v;}
    }
   }
   p=*q?q+1:q;
  } else p++;
 }
 return 0;
}
static int json_get_int(const char*j,const char*key,s64*out){
 const char*v=json_find(j,key);s64 val=0;int neg=0,any=0;
 if(!v)return 0;
 if(*v=='-'){neg=1;v++;}
 while(*v>='0'&&*v<='9'){val=val*10+(*v-'0');v++;any=1;}
 if(!any)return 0;
 *out=neg?-val:val;return 1;
}
static int json_get_bool(const char*j,const char*key,int*out){
 const char*v=json_find(j,key);
 if(!v)return 0;
 if(v[0]=='t'&&v[1]=='r'&&v[2]=='u'&&v[3]=='e'){*out=1;return 1;}
 if(v[0]=='f'&&v[1]=='a'&&v[2]=='l'&&v[3]=='s'&&v[4]=='e'){*out=0;return 1;}
 return 0;
}
static int json_get_string(const char*j,const char*key,char*out,u32 outcap){
 const char*v=json_find(j,key);u32 i=0;
 if(!v||*v!='"')return 0;
 v++;
 while(*v&&*v!='"'&&i+1<outcap){
  if(*v=='\\'&&v[1]){
   v++;
   if(*v=='n')out[i++]='\n';else if(*v=='t')out[i++]='\t';else out[i++]=*v;
   v++;
  } else out[i++]=*v++;
 }
 out[i]=0;return 1;
}
static int json_get_int_array(const char*j,const char*key,u32*out,int cap){
 const char*v=json_find(j,key);int c=0;
 if(!v||*v!='[')return -1;
 v++;
 for(;;){
  while(*v==' '||*v=='\t'||*v=='\n'||*v=='\r'||*v==',')v++;
  if(*v==']')return c;
  if(!(*v>='0'&&*v<='9'))return -1;
  {u32 n=0;while(*v>='0'&&*v<='9'){n=n*10u+(u32)(*v-'0');v++;}if(c<cap)out[c++]=n;else return -1;}
 }
}
/* Same as json_get_int_array() but for u64 elements -- gNodeB identifiers
 * (nr_cell_lock_gnb_set's "gnb_ids") aren't guaranteed to fit in 32 bits. */
static int json_get_int_array_u64(const char*j,const char*key,u64*out,int cap){
 const char*v=json_find(j,key);int c=0;
 if(!v||*v!='[')return -1;
 v++;
 for(;;){
  while(*v==' '||*v=='\t'||*v=='\n'||*v=='\r'||*v==',')v++;
  if(*v==']')return c;
  if(!(*v>='0'&&*v<='9'))return -1;
  {u64 n=0;while(*v>='0'&&*v<='9'){n=n*10ull+(u64)(*v-'0');v++;}if(c<cap)out[c++]=n;else return -1;}
 }
}
static void build_csv_from_array(char*buf,u32 cap,const u32*v,int c){
 u32 pos=0;int i;
 for(i=0;i<c;i++){
  if(i){if(pos+1<cap)buf[pos++]=',';}
  {char tmp[12];u32 n=u32_to_dec(v[i],tmp),k;for(k=0;k<n&&pos+1<cap;k++)buf[pos++]=tmp[k];}
 }
 if(pos<cap)buf[pos]=0;else buf[cap-1]=0;
}
/* Extracts the "bands" field, which may be a JSON array of band numbers
 * ([1,3,28]) or one of the special strings "all"/"hardware"/"none" that
 * cmd_lte()/cmd_nr()/cmd_gsm()/cmd_wcdma() already special-case. Rebuilds
 * the exact "1,3,28" CSV text form those functions' existing plist()-based
 * parser already expects, so their validation logic is reused unchanged. */
static int extract_bands_arg(const char*req,char*out,u32 outcap){
 const char*v=json_find(req,"bands");
 if(!v)return 0;
 if(*v=='"')return json_get_string(req,"bands",out,outcap);
 if(*v=='['){
  u32 arr[128];int c=json_get_int_array(req,"bands",arr,128);
  if(c<0)return 0;
  build_csv_from_array(out,outcap,arr,c);
  return 1;
 }
 return 0;
}

/* ── Everything below this point through cmd_reset()/cmd_mode() is the
 * QMI/TLV layer, carried over from qcom-band-menu-v3.2.c. Functions are
 * byte-for-byte identical to that file unless a comment says otherwise;
 * the only bodies that changed are exchange(), setter(), and the
 * print_rejected()-equivalent call sites inside cmd_lte/cmd_nr/cmd_gsm/
 * cmd_wcdma, each marked below. ────────────────────────────────────── */

static u16 txid(void){struct timespec64 t;if(sc2(SYS_clock_gettime,CLOCK_MONOTONIC,(s64)&t)<0)return 1;return (u16)((u64)t.nsec^(u64)t.sec^((u64)t.nsec>>16));}

static int discover_service(u32 service,u32*node,u32*port){
 struct qrtr_ctrl_pkt q,r;struct sockaddr_qrtr a;struct timeval64 tv={2,0};s64 fd,n;int t;
 fd=sc3(SYS_socket,AF_QIPCRTR,SOCK_DGRAM,0);if(fd<0)return 0;
 if(sc5(SYS_setsockopt,fd,SOL_SOCKET,SO_RCVTIMEO,(s64)&tv,sizeof(tv))<0){sc1(SYS_close,fd);return 0;}
 a.family=AF_QIPCRTR;a.pad=0;a.node=QRTR_CTRL_NODE;a.port=QRTR_PORT_CTRL;
 q.command=QRTR_TYPE_NEW_LOOKUP;q.service=service;q.instance=NAS_PACKED_INSTANCE;q.node=0;q.port=0;
 for(t=0;t<3;t++){n=sc6(SYS_sendto,fd,(s64)&q,sizeof(q),0,(s64)&a,sizeof(a));if(n!=(s64)sizeof(q))continue;for(;;){n=sc6(SYS_recvfrom,fd,(s64)&r,sizeof(r),0,0,0);if(n<0)break;if(n==(s64)sizeof(r)&&r.command==QRTR_TYPE_NEW_SERVER&&r.service==service&&r.instance==NAS_PACKED_INSTANCE&&r.port){*node=r.node;*port=r.port;sc1(SYS_close,fd);return 1;}}}
 sc1(SYS_close,fd);return 0;
}

static int open_nas(struct state*s){struct sockaddr_qrtr a;struct timeval64 tv={3,0};if(!discover_service(NAS_SERVICE,&s->node,&s->port)){setstatus(s,"NAS discovery failed.");return 0;}s->fd=sc3(SYS_socket,AF_QIPCRTR,SOCK_DGRAM,0);if(s->fd<0){setstatus(s,"NAS socket failed.");return 0;}if(sc5(SYS_setsockopt,s->fd,SOL_SOCKET,SO_RCVTIMEO,(s64)&tv,sizeof(tv))<0){sc1(SYS_close,s->fd);return 0;}a.family=AF_QIPCRTR;a.pad=0;a.node=s->node;a.port=s->port;if(sc3(SYS_connect,s->fd,(s64)&a,sizeof(a))<0){sc1(SYS_close,s->fd);return 0;}return 1;}

/* CHANGED from v3.2: no more verbose text dump to a terminal. Every SET/GET
 * transaction is now unconditionally recorded into s->diag (tx bytes, and
 * whichever rx packet was last seen -- matching or not), which the JSON
 * layer serializes into the "diagnostics" response field only when
 * s->verbose is on. Every actual wire behavior below -- txid, header
 * layout, the retry-on-non-matching-packet loop, the 16-packet give-up
 * threshold -- is identical to v3.2. */
static int exchange(struct state*s,u16 msg,const u8*pl,u16 plen,u8*rsp,u32 cap,u32*rn){
 u8 req[640];u16 t=txid();s64 n;int skipped=0;
 if(!t)t=1;if(7u+plen>sizeof(req))return 0;
 req[0]=0;put16(req+1,t);put16(req+3,msg);put16(req+5,plen);if(plen)copy(req+7,pl,plen);
 s->diag.have=1;s->diag.msg=msg;s->diag.got_match=0;s->diag.send_failed=0;s->diag.timed_out=0;s->diag.skipped_count=0;s->diag.rx_len=0;
 {u32 tl=7u+plen;if(tl>sizeof(s->diag.tx))tl=sizeof(s->diag.tx);copy(s->diag.tx,req,tl);s->diag.tx_len=tl;}
 n=sc6(SYS_sendto,s->fd,(s64)req,7u+plen,0,0,0);
 if(n!=(s64)(7u+plen)){s->diag.send_failed=1;return 0;}
 /* A NAS socket may receive indications or a delayed response first. Keep
    reading until the response matching this transaction and message arrives. */
 for(;;){
  n=sc6(SYS_recvfrom,s->fd,(s64)rsp,cap,0,0,0);
  if(n<0){s->diag.timed_out=1;return 0;}
  {u32 rl=(u32)n;if(rl>sizeof(s->diag.rx))rl=sizeof(s->diag.rx);copy(s->diag.rx,rsp,rl);s->diag.rx_len=rl;}
  if(n>=7&&rsp[0]==2&&le16(rsp+1)==t&&le16(rsp+3)==msg){*rn=(u32)n;s->diag.got_match=1;return 1;}
  s->diag.skipped_count=++skipped;
  if(skipped>=16)return 0;
 }
}
/* Finds the TLV_RESULT (0x02) TLV in a response and extracts result/error.
 * Returns 1 if found (regardless of success/failure), 0 if the TLV is
 * missing or the response is malformed. Unchanged from v3.2. */
static int parse_result(const u8*r,u32 n,u16*result,u16*error){
 u32 p=7,e;if(n<7)return 0;e=7u+le16(r+5);if(e>n)e=n;
 while(p+3<=e){
  u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;
  if(p+3u+l>e)return 0;
  if(id==TLV_RESULT&&l>=4){*result=le16(v);*error=le16(v+2);return 1;}
  p+=3u+l;
 }
 return 0;
}
static int result_ok(const u8*r,u32 n){u16 res=0,err=0;if(!parse_result(r,n,&res,&err))return 0;return res==0;}
static int bind_sim(struct state*s,int sim){u8 p[4]={1,1,0,0},r[128];u32 n;p[3]=(u8)(sim-1);if(!exchange(s,MSG_BIND,p,4,r,sizeof(r),&n)||!result_ok(r,n)){setstatus(s,"SIM bind failed.");return 0;}s->sim=sim;return 1;}
static int query(struct state*s){u8 r[2048];u32 n,p,e;zero(s->legacy,8);zero(s->lte,8);zero(s->extlte,32);zero(s->sa,64);zero(s->nsa,64);s->sa_present=0;s->nsa_present=0;s->rat=0;s->netsel_valid=0;s->net_sel_pref=0;s->plmn_mcc=0;s->plmn_mnc=0;s->manual_plmn_valid=0;s->manual_plmn_mcc=0;s->manual_plmn_mnc=0;s->manual_plmn_pcs=0;if(!exchange(s,MSG_GET,0,0,r,sizeof(r),&n)||!result_ok(r,n)){s->valid=0;setstatus(s,"State query failed.");return 0;}e=7u+le16(r+5);if(e>n)e=n;for(p=7;p+3<=e;){u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;if(p+3u+l>e)break;if(id==TLV_MODE&&l>=2)s->rat=le16(v);else if(id==TLV_LEGACY&&l==8)copy(s->legacy,v,8);else if(id==TLV_LTE&&l==8)copy(s->lte,v,8);else if(id==TLV_EXT_LTE_GET&&l==32)copy(s->extlte,v,32);else if(id==TLV_NR_SA_GET&&l==64){copy(s->sa,v,64);s->sa_present=1;}else if(id==TLV_NR_NSA_GET&&l==64){copy(s->nsa,v,64);s->nsa_present=1;}
 /* v4.3.0: TLV 0x16, "Network Selection Preference" -- SAME id and SAME
    5-byte shape (net_sel_pref:u8, mcc:u16 LE, mnc:u16 LE) on both this
    GET reply and the SET request cmd_plmn_lock_set()/cmd_plmn_lock_clear()
    build below. Guarded to length>=5 for the usual reason. */
 else if(id==TLV_NET_SEL_PREF&&l>=5){
  s->netsel_valid=1;s->net_sel_pref=v[0];s->plmn_mcc=le16(v+1);s->plmn_mnc=le16(v+3);
 }
 /* v4.3.1: TLV 0x1B, "Manual Network Selection PLMN" -- GET-only, distinct
    from TLV 0x16 above. This is the authoritative "which PLMN is actually
    locked" report, and unlike TLV 0x16's own mcc/mnc it carries
    mnc_includes_pcs_digit, needed to know whether a reported MNC value
    like 90 means 090 (3-digit) or 90 (2-digit). Value shape: mcc:u16 LE,
    mnc:u16 LE, mnc_includes_pcs_digit:bool (byte 4). */
 else if(id==TLV_MANUAL_PLMN&&l>=5){
  s->manual_plmn_valid=1;s->manual_plmn_mcc=le16(v);s->manual_plmn_mnc=le16(v+2);s->manual_plmn_pcs=v[4]?1:0;
 }
 /* Confirmed by a live capture (mode sa/nsa/both, each followed by a GET):
    on GET, id 0x2B ("NR_COMBINED" -- the same id the "nr" command uses on
    SET for a 64-byte combined band mask) is reused for a 4-byte current
    NR-mode report: value byte 0 was 0x02 after "mode sa", 0x01 after
    "mode nsa", 0x00 after "mode both" -- exactly the 0=both/1=nsa/2=sa
    encoding cmd_mode() already uses locally. Length must be 4 to avoid
    ever colliding with the 64-byte combined-mask meaning. */
 else if(id==TLV_NR_COMBINED&&l==4){
  u8 mv=v[0];
  if(mv<=2&&s->sim>=1&&s->sim<=2){s->nr_mode[s->sim-1]=(int)mv;s->nr_mode_known[s->sim-1]=1;}
 }
 p+=3u+l;}s->valid=1;return 1;}
static void mset(u8*m,u32 b);
static int mhas(const u8*m,u32 b);
static int wbit(u32 b);
static void set64(u8*p,u64 v);
static int dms_exchange(u32 node,u32 port,u8*rsp,u32 cap,u32*rn){
 struct sockaddr_qrtr a;struct timeval64 tv={3,0};u8 req[7];s64 fd,n;u16 t=txid();int skipped=0;
 fd=sc3(SYS_socket,AF_QIPCRTR,SOCK_DGRAM,0);if(fd<0)return 0;
 if(sc5(SYS_setsockopt,fd,SOL_SOCKET,SO_RCVTIMEO,(s64)&tv,sizeof(tv))<0){sc1(SYS_close,fd);return 0;}
 a.family=AF_QIPCRTR;a.pad=0;a.node=node;a.port=port;
 if(sc3(SYS_connect,fd,(s64)&a,sizeof(a))<0){sc1(SYS_close,fd);return 0;}
 if(!t)t=1;req[0]=0;put16(req+1,t);put16(req+3,DMS_MSG_GET_BANDS);put16(req+5,0);
 n=sc6(SYS_sendto,fd,(s64)req,7,0,0,0);if(n!=7){sc1(SYS_close,fd);return 0;}
 for(;;){
  n=sc6(SYS_recvfrom,fd,(s64)rsp,cap,0,0,0);if(n<0){sc1(SYS_close,fd);return 0;}
  if(n>=7&&rsp[0]==2&&le16(rsp+1)==t&&le16(rsp+3)==DMS_MSG_GET_BANDS){*rn=(u32)n;sc1(SYS_close,fd);return 1;}
  if(++skipped>=16){sc1(SYS_close,fd);return 0;}
 }
}
static int query_hardware(struct state*s){
 u32 node,port,n,p,e;u8 r[2048];
 zero(s->hw_legacy,8);zero(s->hw_lte,32);zero(s->hw_nr,64);s->hw_valid=0;
 if(!discover_service(DMS_SERVICE,&node,&port)){setstatus(s,"Hardware-band service discovery failed.");return 0;}
 if(!dms_exchange(node,port,r,sizeof(r),&n)||!result_ok(r,n)){setstatus(s,"Hardware-band query failed.");return 0;}
 e=7u+le16(r+5);if(e>n)e=n;
 for(p=7;p+3<=e;){
  u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;u32 i,count;
  if(p+3u+l>e)break;
  if(id==0x01&&l>=8)copy(s->hw_legacy,v,8);
  else if(id==0x10&&l>=8)copy(s->hw_lte,v,8);
  else if(id==0x12&&l>=2){count=le16(v);for(i=0;i<count&&2u+2u*i+1u<l;i++){u32 b=le16(v+2+2*i);if(b>=1&&b<=256)mset(s->hw_lte,b);}}
  else if(id==0x13&&l>=2){count=le16(v);for(i=0;i<count&&2u+2u*i+1u<l;i++){u32 b=le16(v+2+2*i);if(b>=1&&b<=512)mset(s->hw_nr,b);}}
  p+=3u+l;
 }
 s->hw_valid=1;return 1;
}

/* ── Cell lock GET queries (v4.1.0). Independent of query()/query_hardware()
 * above -- separate messages, separate TLV namespace (see header note). */
static int query_lte_cell_lock(struct state*s){
 u8 r[2048];u32 n,p,e;u16 res=0,err=0;
 s->lte_cell.valid=0;s->lte_cell.count=0;
 if(!exchange(s,MSG_LTE_CELL_LOCK_GET,0,0,r,sizeof(r),&n)){setstatus(s,"LTE cell-lock query failed: no reply.");return 0;}
 if(!parse_result(r,n,&res,&err)||res){setstatus(s,"LTE cell-lock query failed.");return 0;}
 e=7u+le16(r+5);if(e>n)e=n;
 for(p=7;p+3<=e;){
  u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;
  if(p+3u+l>e)break;
  if(id==TLV_LTECELL_GET_LIST&&l>=1){
   u32 c=v[0],off=1,i;
   /* Entries are 4 bytes: PCI (le16) then EARFCN (le16). EARFCN is
      genuinely truncated to 16 bits here -- see the v4.1.0 header note. */
   for(i=0;i<c&&off+4<=l&&s->lte_cell.count<LTE_CELL_LOCK_MAX;i++,off+=4){
    s->lte_cell.entries[s->lte_cell.count].pci=le16(v+off);
    s->lte_cell.entries[s->lte_cell.count].earfcn=le16(v+off+2);
    s->lte_cell.count++;
   }
  }
  p+=3u+l;
 }
 s->lte_cell.valid=1;return 1;
}
static int query_nr_cell_lock(struct state*s){
 u8 r[4096];u32 n,p,e;u16 res=0,err=0;
 zero(&s->nr_cell,sizeof(s->nr_cell));
 s->nr_cell.type=NRCELL_TYPE_UNKNOWN;
 if(!exchange(s,MSG_NR_CELL_LOCK_GET,0,0,r,sizeof(r),&n)){setstatus(s,"NR cell-lock query failed: no reply.");return 0;}
 if(!parse_result(r,n,&res,&err)||res){setstatus(s,"NR cell-lock query failed.");return 0;}
 e=7u+le16(r+5);if(e>n)e=n;
 for(p=7;p+3<=e;){
  u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;
  if(p+3u+l>e)break;
  if(id==TLV_NRCELL_GET_TYPE&&l>=4){
   s->nr_cell.type=le32(v);
  } else if(id==TLV_NRCELL_GET_PCI&&l>=74){
   s->nr_cell.have_pci=1;
   s->nr_cell.pci.pci=le16(v);
   s->nr_cell.pci.scs=le32(v+2);
   s->nr_cell.pci.arfcn=le32(v+6);
   copy(s->nr_cell.pci.band_mask,v+10,64);
  } else if(id==TLV_NRCELL_GET_ARFCN&&l>=1){
   u32 c=v[0],off=1,i;
   s->nr_cell.have_arfcn_list=1;
   for(i=0;i<c&&off+8<=l&&s->nr_cell.arfcn_count<NR_CELL_ARFCN_MAX;i++,off+=8){
    s->nr_cell.arfcn[s->nr_cell.arfcn_count].arfcn=le32(v+off);
    s->nr_cell.arfcn[s->nr_cell.arfcn_count].scs=le32(v+off+4);
    s->nr_cell.arfcn_count++;
   }
  } else if(id==TLV_NRCELL_GET_MULTI&&l>=1){
   /* Presence-only, not decoded -- matches qcom-cell-lock-test.c, which
      never parsed this TLV's contents either. */
   s->nr_cell.have_multi_marker=1;
  } else if(id==TLV_NRCELL_GET_GNB&&l>=2){
   u32 c=v[0],off=1,i;
   s->nr_cell.have_gnb=1;
   for(i=0;i<c&&off+8<=l&&s->nr_cell.gnb.count<NR_CELL_GNB_MAX;i++,off+=8){
    s->nr_cell.gnb.ids[s->nr_cell.gnb.count]=le64(v+off);
    s->nr_cell.gnb.count++;
   }
   if(off<l){s->nr_cell.gnb.have_id_bits=1;s->nr_cell.gnb.id_bits=v[off];}
  }
  p+=3u+l;
 }
 s->nr_cell.valid=1;return 1;
}

static int hw_gsm_has(const struct state*s,u32 b){u64 m=le64(s->hw_legacy);if(b==850)return(m&(1ULL<<19))!=0;if(b==900)return(m&((1ULL<<8)|(1ULL<<9)))!=0;if(b==1800)return(m&(1ULL<<7))!=0;if(b==1900)return(m&(1ULL<<21))!=0;return 0;}
static int hw_wcdma_has(const struct state*s,u32 b){int bit=wbit(b);return bit>=0&&(le64(s->hw_legacy)&(1ULL<<bit))!=0;}

/* CHANGED from v3.2: replaces print_rejected()/the inline GSM+WCDMA reject
 * blocks. Same job (record which of the requested bands weren't in the
 * hardware-supported set), different sink: instead of printing text, it
 * stores the label + the actual rejected band numbers into
 * s->last_rejected_* for the JSON "error.rejected_bands" field. */
static void set_reject_label(struct state*s,const char*label){u64 i=0;while(label[i]&&i+1<sizeof(s->last_rejected_label)){s->last_rejected_label[i]=label[i];i++;}s->last_rejected_label[i]=0;}
static void record_rejected(struct state*s,const char*label,const u32*v,int c,const u8*mask,u32 max){
 int i,n=0;set_reject_label(s,label);
 for(i=0;i<c&&n<64;i++)if(v[i]>max||!mhas(mask,v[i]))s->last_rejected_bands[n++]=v[i];
 s->last_rejected_count=n;
}
static void record_rejected_pred(struct state*s,const char*label,const u32*v,int c,int(*has)(const struct state*,u32)){
 int i,n=0;set_reject_label(s,label);
 for(i=0;i<c&&n<64;i++)if(!has(s,v[i]))s->last_rejected_bands[n++]=v[i];
 s->last_rejected_count=n;
}

static int addtlv(u8*p,int pos,u8 id,const u8*v,u16 l){int i;p[pos++]=id;put16(p+pos,l);pos+=2;for(i=0;i<l;i++)p[pos++]=v[i];return pos;}
/* CHANGED from v3.2: instead of printing "Command rejected: result=0x..
 * error=0x.." to a terminal, the exact same result/error codes are stored
 * into s->last_op for the JSON "error.result"/"error.code" fields. The
 * three-way outcome (no reply / malformed reply / modem said no) is
 * unchanged; setstatus() text is unchanged too.
 *
 * v4.1.0: split into setter_msg() (takes the QMI message id) + setter()
 * (the original band-family-only entry point, now just
 * setter_msg(s,MSG_SET,...)) so the v4.1.0 cell-lock SET functions --
 * which use their own message ids, MSG_LTE_CELL_LOCK_SET/MSG_NR_CELL_LOCK_SET
 * -- get the same result-handling/last_op/status behavior without
 * duplicating it. Every existing call site (all of which called
 * setter(), unchanged) behaves identically to before. */
static int setter_msg(struct state*s,u16 msg,const u8*p,u16 l){
 u8 r[256];u32 n;
 zero(&s->last_op,sizeof(s->last_op));
 if(!exchange(s,msg,p,l,r,sizeof(r),&n)){
  setstatus(s,"Command failed: no reply.");
  return 0;
 }
 s->last_op.have_reply=1;
 if(!parse_result(r,n,&s->last_op.result,&s->last_op.error)){
  setstatus(s,"Command failed: malformed reply (no result TLV).");
  return 0;
 }
 s->last_op.have_result=1;
 if(s->last_op.result!=0){
  setstatus(s,"Command rejected by modem.");
  return 0;
 }
 s->last_op.ok=1;
 setstatus(s,"Command accepted.");
 return 1;
}
static int setter(struct state*s,const u8*p,u16 l){return setter_msg(s,MSG_SET,p,l);}

static int sep(char c){return c==','||c==' '||c=='\t';}
static int puint(const char*s,u32*v){u32 n=0;int d=0;while(*s){if(*s<'0'||*s>'9')return 0;n=n*10u+(u32)(*s-'0');if(n>10000)return 0;s++;d++;}*v=n;return d>0;}
static int plist(char*s,u32*v,int cap,u32 min,u32 max){int c=0;char*p=s,*a;u32 x;while(*p){while(*p&&sep(*p))p++;if(!*p)break;a=p;while(*p&&!sep(*p))p++;if(*p)*p++=0;if(!puint(a,&x)||x<min||x>max||c>=cap)return -1;v[c++]=x;}return c;}
static void mset(u8*m,u32 b){u32 x=b-1;m[x/8]|=(u8)(1u<<(x%8));}
static int mhas(const u8*m,u32 b){u32 x=b-1;return (m[x/8]&(u8)(1u<<(x%8)))!=0;}
/* NR subcarrier spacing <-> the wire enum used by TLV_NRCELL_*_PCI/ARFCN.
 * The app-facing JSON fields always use plain kHz integers (15/30/60/120/
 * 240); these convert to/from the 0-4 enum the modem actually expects,
 * ported from qcom-cell-lock-test.c's scs_enum()/scs_name(). */
static int scs_enum_from_khz(u32 khz,u32*e){switch(khz){case 15:*e=0;return 1;case 30:*e=1;return 1;case 60:*e=2;return 1;case 120:*e=3;return 1;case 240:*e=4;return 1;}return 0;}
static u32 scs_khz_from_enum(u32 e){switch(e){case 0:return 15;case 1:return 30;case 2:return 60;case 3:return 120;case 4:return 240;default:return 0;}}

static int cmd_rat(struct state*s,char*a){u8 p[16],d=1,m[2];int pos=0;u16 mask=0;if(eq(a,"auto"))mask=0xFF;else{char*x=a,*z;while(*x){while(*x&&sep(*x))x++;if(!*x)break;z=x;while(*x&&!sep(*x))x++;if(*x)*x++=0;if(eq(z,"gsm"))mask|=4;else if(eq(z,"wcdma")||eq(z,"umts"))mask|=8;else if(eq(z,"lte"))mask|=16;else if(eq(z,"nr")||eq(z,"nr5g"))mask|=64;else{setstatus(s,"Invalid RAT token.");return 0;}}if(!mask)return 0;}m[0]=(u8)mask;m[1]=(u8)(mask>>8);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_MODE,m,2);return setter(s,p,(u16)pos);}
static int cmd_lte_hardware(struct state*s){
 u8 l[8],p[48],d=1;int pos=0,i,has_high=0,any=0;
 if(!s->hw_valid&&!query_hardware(s))return 0;
 for(i=0;i<32;i++)if(s->hw_lte[i]){any=1;if(i>=8)has_high=1;}
 if(!any){setstatus(s,"No hardware-supported LTE bands were reported.");return 0;}
 copy(l,s->hw_lte,8);
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 if(has_high)pos=addtlv(p,pos,TLV_EXT_LTE_SET,s->hw_lte,32);
 else pos=addtlv(p,pos,TLV_LTE,l,8);
 return setter(s,p,(u16)pos);
}
static int cmd_nr_hardware(struct state*s,u8 id){
 u8 p[80],d=1;int pos=0,i,any=0;
 if(!s->hw_valid&&!query_hardware(s))return 0;
 for(i=0;i<64;i++)if(s->hw_nr[i]){any=1;break;}
 if(!any){setstatus(s,"No hardware-supported NR bands were reported.");return 0;}
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 pos=addtlv(p,pos,id,s->hw_nr,64);
 return setter(s,p,(u16)pos);
}
static int cmd_gsm_hardware(struct state*s){
 u64 m,hm;u8 x[8],p[20],d=1;int pos=0;
 if(!s->valid)return 0;
 if(!s->hw_valid&&!query_hardware(s))return 0;
 hm=le64(s->hw_legacy);
 m=le64(s->legacy)&~((1ULL<<7)|(1ULL<<8)|(1ULL<<9)|(1ULL<<19)|(1ULL<<21));
 m|=hm&((1ULL<<7)|(1ULL<<8)|(1ULL<<9)|(1ULL<<19)|(1ULL<<21));
 set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);
 if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}
 return 0;
}
static int cmd_wcdma_hardware(struct state*s){
 u64 m,hm,wm=0;u8 x[8],p[20],d=1;int pos=0,b,i;
 if(!s->valid)return 0;
 if(!s->hw_valid&&!query_hardware(s))return 0;
 for(i=1;i<=19;i++){b=wbit((u32)i);wm|=1ULL<<b;}
 hm=le64(s->hw_legacy);m=(le64(s->legacy)&~wm)|(hm&wm);
 set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);
 if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}
 return 0;
}

static int cmd_lte(struct state*s,char*a){
 u32 v[128];int c,i,pos=0,bad=0,has_high=0;u8 l[8],e[32],p[64],d=1;
 if(eq(a,"hardware")||eq(a,"all"))return cmd_lte_hardware(s);
 if(eq(a,"none")){
  zero(l,8);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LTE,l,8);
  return setter(s,p,(u16)pos);
 }
 c=plist(a,v,128,1,256);
 if(c<=0){setstatus(s,"Invalid LTE list.");return 0;}
 if(!s->hw_valid&&!query_hardware(s))return 0;
 for(i=0;i<c;i++){
  if(!mhas(s->hw_lte,v[i]))bad=1;
  if(v[i]>64)has_high=1;
 }
 if(bad){record_rejected(s,"LTE",v,c,s->hw_lte,256);setstatus(s,"LTE command not sent.");return 0;}
 zero(l,8);zero(e,32);
 for(i=0;i<c;i++){
  mset(e,v[i]);
  if(v[i]<=64)mset(l,v[i]);
 }
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 /* Qualcomm firmware differs here. The tested modem rejects a request that
    carries both legacy LTE TLV 0x15 and extended LTE TLV 0x24 together.
    Use the known-good legacy-only packet for B1-B64. If any requested band is
    above B64, use the extended bitmap alone, which can also represent low bands. */
 if(has_high)pos=addtlv(p,pos,TLV_EXT_LTE_SET,e,32);
 else pos=addtlv(p,pos,TLV_LTE,l,8);
 return setter(s,p,(u16)pos);
}
static int cmd_nr(struct state*s,char*a,u8 id){u32 v[128];int c,i,pos=0,bad=0;u8 m[64],p[80],d=1;if(eq(a,"hardware")||eq(a,"all"))return cmd_nr_hardware(s,id);if(eq(a,"none")){zero(m,64);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,id,m,64);return setter(s,p,(u16)pos);}c=plist(a,v,128,1,512);if(c<=0){setstatus(s,"Invalid NR list.");return 0;}if(!s->hw_valid&&!query_hardware(s))return 0;for(i=0;i<c;i++)if(!mhas(s->hw_nr,v[i]))bad=1;if(bad){record_rejected(s,"NR",v,c,s->hw_nr,512);setstatus(s,"NR command not sent.");return 0;}zero(m,64);for(i=0;i<c;i++)mset(m,v[i]);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,id,m,64);return setter(s,p,(u16)pos);}
static void set64(u8*p,u64 v){int i;for(i=0;i<8;i++)p[i]=(u8)(v>>(8*i));}
static int cmd_gsm(struct state*s,char*a){u32 v[16];int c,i,pos=0,bad=0;u64 m;u8 x[8],p[20],d=1;if(eq(a,"hardware")||eq(a,"all"))return cmd_gsm_hardware(s);if(!s->valid)return 0;if(eq(a,"none")){m=le64(s->legacy)&~((1ULL<<7)|(1ULL<<8)|(1ULL<<9)|(1ULL<<19)|(1ULL<<21));set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}return 0;}c=plist(a,v,16,1,2000);if(c<=0){setstatus(s,"Invalid GSM list.");return 0;}if(!s->hw_valid&&!query_hardware(s))return 0;for(i=0;i<c;i++)if(!hw_gsm_has(s,v[i]))bad=1;if(bad){record_rejected_pred(s,"GSM",v,c,hw_gsm_has);setstatus(s,"GSM command not sent.");return 0;}m=le64(s->legacy)&~((1ULL<<7)|(1ULL<<8)|(1ULL<<9)|(1ULL<<19)|(1ULL<<21));for(i=0;i<c;i++){if(v[i]==850)m|=1ULL<<19;else if(v[i]==900)m|=(1ULL<<8)|(1ULL<<9);else if(v[i]==1800)m|=1ULL<<7;else if(v[i]==1900)m|=1ULL<<21;}set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}return 0;}
static int wbit(u32 b){switch(b){case 1:return 22;case 2:return 23;case 3:return 24;case 4:return 25;case 5:return 26;case 6:return 27;case 7:return 48;case 8:return 49;case 9:return 50;case 10:return 51;case 11:return 52;case 12:return 53;case 13:return 54;case 14:return 55;case 15:return 56;case 16:return 57;case 17:return 58;case 18:return 59;case 19:return 60;default:return -1;}}
static int cmd_wcdma(struct state*s,char*a){u32 v[32];int c,i,b,pos=0,bad=0;u64 m,clr=0;u8 x[8],p[20],d=1;if(eq(a,"hardware")||eq(a,"all"))return cmd_wcdma_hardware(s);if(!s->valid)return 0;if(eq(a,"none")){for(i=1;i<=19;i++){b=wbit((u32)i);clr|=1ULL<<b;}m=le64(s->legacy)&~clr;set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}return 0;}c=plist(a,v,32,1,19);if(c<=0){setstatus(s,"Invalid WCDMA list.");return 0;}if(!s->hw_valid&&!query_hardware(s))return 0;for(i=0;i<c;i++)if(!hw_wcdma_has(s,v[i]))bad=1;if(bad){record_rejected_pred(s,"WCDMA",v,c,hw_wcdma_has);setstatus(s,"WCDMA command not sent.");return 0;}for(i=1;i<=19;i++){b=wbit((u32)i);clr|=1ULL<<b;}m=le64(s->legacy)&~clr;for(i=0;i<c;i++){b=wbit(v[i]);m|=1ULL<<b;}set64(x,m);pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,x,8);if(setter(s,p,(u16)pos)){copy(s->legacy,x,8);return 1;}return 0;}

/* Restore every band family to the modem-reported hardware capability for
 * the currently bound SIM. GSM and WCDMA share TLV 0x12, so they must be
 * restored together in one legacy-mask write; issuing the two old helpers
 * back-to-back would let the second write rebuild from stale cached state
 * and accidentally undo the first. LTE and the three NR domains remain
 * independent SET transactions, matching the proven incremental setters.
 * Unchanged from v3.2. Note: s->diag after this call reflects only the
 * LAST of the five setter() calls below, not a full step-by-step trace --
 * see the .md spec's "Known limitations" section. */
static int cmd_reset(struct state*s){
 u8 legacy[8],p[80],d=1;int pos,i,has_lte_high=0,any_lte=0,any_nr=0;
 int skipped_sa=0,skipped_nsa=0;
 if(!s->hw_valid&&!query_hardware(s))return 0;

 /* GSM + WCDMA: the DMS legacy capability mask already contains both. */
 copy(legacy,s->hw_legacy,8);
 pos=0;pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_LEGACY,legacy,8);
 if(!setter(s,p,(u16)pos))return 0;

 /* LTE: use legacy 0x15 only when every supported band fits B1-B64;
    otherwise use the extended 0x24 bitmap alone. */
 for(i=0;i<32;i++)if(s->hw_lte[i]){any_lte=1;if(i>=8)has_lte_high=1;}
 if(!any_lte){setstatus(s,"Reset failed: no hardware LTE bands reported.");return 0;}
 pos=0;pos=addtlv(p,pos,TLV_DURATION,&d,1);
 if(has_lte_high)pos=addtlv(p,pos,TLV_EXT_LTE_SET,s->hw_lte,32);
 else pos=addtlv(p,pos,TLV_LTE,s->hw_lte,8);
 if(!setter(s,p,(u16)pos))return 0;

 for(i=0;i<64;i++)if(s->hw_nr[i]){any_nr=1;break;}

 /* Restore combined NR, then independent SA and independent NSA masks.
    Skip NR SETs entirely on devices with no NR hardware capability.
    v4.3.4: independent SA/NSA restoration is now skipped (not attempted
    at all) on a domain the capability probe already knows this device
    rejects (s->nr_cap.sa_supported/nsa_supported == 0 -- a definite,
    already-observed rejection, not just "unknown"/"never probed", which
    still fall through to attempting it as before). The combined NR mask
    just sent above is this project's established fallback for exactly
    that case -- it's what actually governs NR bands on a device without
    independent-domain support, so skipping the redundant, predictably-
    rejected independent SETs doesn't leave NR bands unrestored, it just
    stops turning a known, avoidable rejection into a hard failure of the
    whole reset after GSM/WCDMA/LTE/combined-NR had already succeeded. */
 if(any_nr){
  pos=0;pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_NR_COMBINED,s->hw_nr,64);
  if(!setter(s,p,(u16)pos))return 0;

  if(s->nr_cap.ran&&s->nr_cap.sa_supported==0){
   skipped_sa=1;
  } else {
   pos=0;pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_NR_SA_SET,s->hw_nr,64);
   if(!setter(s,p,(u16)pos))return 0;
  }
  if(s->nr_cap.ran&&s->nr_cap.nsa_supported==0){
   skipped_nsa=1;
  } else {
   pos=0;pos=addtlv(p,pos,TLV_DURATION,&d,1);pos=addtlv(p,pos,TLV_NR_NSA_SET,s->hw_nr,64);
   if(!setter(s,p,(u16)pos))return 0;
  }
 }

 if(skipped_sa||skipped_nsa){
  setstatus(s,"Band masks restored (independent NR SA/NSA skipped -- not supported on this device; combined NR mask was used instead).");
 } else {
  setstatus(s,"All band masks restored to hardware-supported bands.");
 }
 return 1;
}

/* Proven mode-only setter from the original menu. Deliberately sends no LTE
 * or NR band masks: duration (0x17) + NR operating mode (0x2E) only.
 * Unchanged from v3.2. */
static int cmd_mode(struct state*s,char*a){
 u8 p[20],d=1,m[4]={0,0,0,0};int pos=0,value;
 if(eq(a,"both"))value=0;
 else if(eq(a,"nsa"))value=1;
 else if(eq(a,"sa"))value=2;
 else{setstatus(s,"Use mode sa, mode nsa, or mode both.");return 0;}
 m[0]=(u8)value;
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 pos=addtlv(p,pos,TLV_NR_MODE,m,4);
 if(!setter(s,p,(u16)pos))return 0;
 if(s->sim>=1&&s->sim<=2){s->nr_mode[s->sim-1]=value;s->nr_mode_known[s->sim-1]=1;}
 return 1;
}

/* v4.3.0 PLMN lock -- sends TLV_DURATION (0x17) + TLV_NET_SEL_PREF (0x16,
 * net_sel_pref=0x01 MANUAL, mcc, mnc), same incremental single-purpose
 * pattern as every other setter here. Caller (do_command()) validates
 * mcc/mnc are each 0-999 before calling this; not re-validated here.
 * Updates s->netsel_valid/net_sel_pref/plmn_mcc/plmn_mnc immediately on
 * success, same reasoning as cmd_mode() above and the gsm_set/wcdma_set
 * "update state directly, skip the post-SET query" note in do_command()
 * -- an immediate GET right after a SET can race the modem and read back
 * the pre-change value; the app doesn't need to wait for that when this
 * function already knows exactly what was just written. */
static int cmd_plmn_lock_set(struct state*s,u32 mcc,u32 mnc){
 u8 p[16],d=1,v[5];int pos=0;
 v[0]=0x01;put16(v+1,(u16)mcc);put16(v+3,(u16)mnc);
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 pos=addtlv(p,pos,TLV_NET_SEL_PREF,v,5);
 if(!setter(s,p,(u16)pos))return 0;
 s->netsel_valid=1;s->net_sel_pref=0x01;s->plmn_mcc=(u16)mcc;s->plmn_mnc=(u16)mnc;
 return 1;
}
/* Clears a PLMN lock: net_sel_pref=0x00 AUTOMATIC, mcc/mnc zeroed (ignored
 * by the modem per spec when AUTOMATIC and no RAT-preference TLV 0x22 is
 * present, which this never sends). */
static int cmd_plmn_lock_clear(struct state*s){
 u8 p[16],d=1,v[5]={0,0,0,0,0};int pos=0;
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 pos=addtlv(p,pos,TLV_NET_SEL_PREF,v,5);
 if(!setter(s,p,(u16)pos))return 0;
 s->netsel_valid=1;s->net_sel_pref=0x00;s->plmn_mcc=0;s->plmn_mnc=0;
 return 1;
}

static int reopen_bound(struct state*s,int sim){
 if(s->fd>=0){sc1(SYS_close,s->fd);s->fd=-1;}
 if(!open_nas(s))return 0;
 if(!bind_sim(s,sim))return 0;
 return 1;
}

/* ── NR independent SA/NSA band-lock capability probe ────────────────────
 * Ported from qcom-band-menu-compatibility.c's probe()/query_nr_state().
 * Sends TLV_DURATION(1) + the *current* 64-byte mask (as last read back
 * via GET TLV 0x2C/0x2D) through the independent SET id (0x2F for SA,
 * 0x30 for NSA). Since the mask written back is identical to the one
 * already active, this cannot change what's locked -- a modem that
 * accepts the write just re-applies its existing state; a modem that
 * doesn't support independent locking rejects it with a TLV_RESULT
 * failure (result=1/error=1 on the device this was captured from), same
 * as any other unsupported write id. Only whether the write is *accepted*
 * is new information here.
 *
 * Deliberately uses exchange()/parse_result() directly instead of
 * setter(), because this is a diagnostic probe, not a user-issued command:
 * it must not disturb s->last_op (which build_response() uses to describe
 * the outcome of the request the app actually asked for) and it needs to
 * keep the SA and NSA wire captures separate instead of letting the
 * second probe's exchange() overwrite the first's in s->diag. */
static int nr_indep_probe_one(struct state*s,u8 set_id,const u8*mask,u16*out_result,u16*out_error,struct diag*out_diag){
 u8 p[80],r[256],d=1;u32 n;int pos=0;
 pos=addtlv(p,pos,TLV_DURATION,&d,1);
 pos=addtlv(p,pos,set_id,mask,64);
 *out_result=0;*out_error=0;
 if(!exchange(s,MSG_SET,p,(u16)pos,r,sizeof(r),&n)){*out_diag=s->diag;return -1;}
 *out_diag=s->diag;
 if(!parse_result(r,n,out_result,out_error))return -1;
 return (*out_result==0)?1:0;
}
/* Runs (or re-runs) the capability probe and stores the result in
 * s->nr_cap for serialization (see jcapability()). Requires a current,
 * valid GET snapshot to know what mask to resend -- refreshes via query()
 * first if the state isn't already valid. Returns 0 only if that GET
 * itself fails (no modem reply at all); an individual SA or NSA probe
 * being rejected, or its current mask simply not being present on this
 * device, is recorded as data, not a daemon failure. This is a real SET
 * transaction to the modem, so callers should not run it on every query
 * -- it runs once automatically at daemon startup (see run()) and
 * otherwise only on an explicit "query_nr_independent_capability" request. */
static int query_nr_independent_capability(struct state*s){
 zero(&s->nr_cap,sizeof(s->nr_cap));
 s->nr_cap.ran=1;
 if(!s->valid&&!query(s))return 0;
 s->nr_cap.sa_present=s->sa_present;
 s->nr_cap.nsa_present=s->nsa_present;
 s->nr_cap.sa_supported=s->sa_present?nr_indep_probe_one(s,TLV_NR_SA_SET,s->sa,&s->nr_cap.sa_result,&s->nr_cap.sa_error,&s->nr_cap.sa_diag):-1;
 s->nr_cap.nsa_supported=s->nsa_present?nr_indep_probe_one(s,TLV_NR_NSA_SET,s->nsa,&s->nr_cap.nsa_result,&s->nr_cap.nsa_error,&s->nr_cap.nsa_diag):-1;
 setstatus(s,"NR independent SA/NSA band-lock capability probed.");
 return 1;
}

/* ── Cell lock SET commands (v4.1.0), ported from qcom-cell-lock-test.c's
 * set_lte()/clear_lte()/nr_unlock()/nr_arfcn()/nr_pci()/nr_multi()/nr_gnb().
 * Every TLV id, length, and byte layout below is carried over unchanged --
 * see the TLV_LTECELL_ and TLV_NRCELL_ defines and the v4.1.0 header note.
 * Each function refreshes the corresponding cell-lock state (via
 * query_lte_cell_lock()/query_nr_cell_lock()) immediately after a
 * successful write, the same "update state directly, skip the generic
 * post-SET query()" pattern cmd_gsm_hardware()/cmd_wcdma_hardware() etc.
 * already use elsewhere in this file -- do_command() sets *did_set=0 for
 * all of these so serve_client() doesn't also fire the unrelated general
 * GET (message 0x0034) afterward. */
/* Value layout for TLV_LTECELL_SET_LOCK: count:u8(1) + count*[pci:u16(2),
 * earfcn:u32(4)] = 1+6*count bytes, up to LTE_CELL_LOCK_MAX entries -- see
 * the v4.4.0 header note. Locks to ANY of the listed PCIs on their
 * respective EARFCNs (typically all the same EARFCN); it is NOT a
 * frequency-only/wildcard-PCI lock -- every entry still needs a real PCI. */
#define LTECELL_MULTI_VALLEN_MAX (1u+6u*LTE_CELL_LOCK_MAX)
static int cmd_lte_cell_lock_multi_pci_set(struct state*s,u32 earfcn,const u32*pcis,u32 count){
 u8 val[LTECELL_MULTI_VALLEN_MAX],apply=1,p[3+LTECELL_MULTI_VALLEN_MAX+3];u32 i,vlen;int pos=0;
 if(count<1||count>LTE_CELL_LOCK_MAX)return 0;
 val[0]=(u8)count;vlen=1;
 for(i=0;i<count;i++){put16(val+vlen,(u16)pcis[i]);vlen+=2;put32(val+vlen,earfcn);vlen+=4;}
 pos=addtlv(p,pos,TLV_LTECELL_SET_LOCK,val,(u16)vlen);
 pos=addtlv(p,pos,TLV_LTECELL_SET_APPLY,&apply,1);
 if(!setter_msg(s,MSG_LTE_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_lte_cell_lock(s);
 return 1;
}
/* Convenience wrapper for the common single-PCI case; same TLV builder,
 * same wire bytes as before v4.4.0 for count=1 (val[0]=1,pci,earfcn --
 * byte-identical to the old hardcoded single-entry version). */
static int cmd_lte_cell_lock_set(struct state*s,u32 earfcn,u32 pci){
 return cmd_lte_cell_lock_multi_pci_set(s,earfcn,&pci,1);
}
static int cmd_lte_cell_lock_clear(struct state*s){
 u8 val=0,apply=1,p[8];int pos=0;
 pos=addtlv(p,pos,TLV_LTECELL_SET_LOCK,&val,1);
 pos=addtlv(p,pos,TLV_LTECELL_SET_APPLY,&apply,1);
 if(!setter_msg(s,MSG_LTE_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_lte_cell_lock(s);
 return 1;
}
static int cmd_nr_cell_lock_pci_set(struct state*s,u32 arfcn,u32 pci,u32 scs_enum,u32 band){
 u8 tval[4],pval[74],m[64],p[84];int pos=0;u32 x=band-1;
 zero(m,64);m[x/8]|=(u8)(1u<<(x%8));
 put32(tval,NRCELL_TYPE_PCI);
 pos=addtlv(p,pos,TLV_NRCELL_SET_TYPE,tval,4);
 put16(pval,(u16)pci);put32(pval+2,scs_enum);put32(pval+6,arfcn);copy(pval+10,m,64);
 pos=addtlv(p,pos,TLV_NRCELL_SET_PCI,pval,74);
 if(!setter_msg(s,MSG_NR_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_nr_cell_lock(s);
 return 1;
}
static int cmd_nr_cell_lock_arfcn_set(struct state*s,u32 arfcn,u32 scs_enum){
 u8 tval[4],aval[9],p[19];int pos=0;
 put32(tval,NRCELL_TYPE_ARFCN);
 pos=addtlv(p,pos,TLV_NRCELL_SET_TYPE,tval,4);
 aval[0]=1;put32(aval+1,arfcn);put32(aval+5,scs_enum);
 pos=addtlv(p,pos,TLV_NRCELL_SET_ARFCN,aval,9);
 if(!setter_msg(s,MSG_NR_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_nr_cell_lock(s);
 return 1;
}
/* Value layout: count:u8(1) + pci[]:u16*count(2 each) + scs_mask:u16(2) +
 * arfcn:u32(4) + band_mask:64B(64) = 71 + 2*count bytes, maxed out at
 * NR_CELL_MULTI_PCI_MAX entries. (mval was previously undersized here --
 * fixed to the actual worst case, not a rough estimate.) */
#define NRCELL_MULTI_VALLEN_MAX (1u+2u*NR_CELL_MULTI_PCI_MAX+2u+4u+64u)
static int cmd_nr_cell_lock_multi_pci_set(struct state*s,u32 arfcn,u32 scs_enum,u32 band,const u32*pcis,u32 count){
 u8 tval[4],mval[NRCELL_MULTI_VALLEN_MAX],p[7+3+NRCELL_MULTI_VALLEN_MAX],m[64];
 u32 x=band-1,i,vlen;u16 scs_mask=(u16)(1u<<scs_enum);int pos=0;
 if(count>NR_CELL_MULTI_PCI_MAX)return 0;
 zero(m,64);m[x/8]|=(u8)(1u<<(x%8));
 put32(tval,NRCELL_TYPE_MULTI);
 pos=addtlv(p,pos,TLV_NRCELL_SET_TYPE,tval,4);
 mval[0]=(u8)count;vlen=1;
 for(i=0;i<count;i++){put16(mval+vlen,(u16)pcis[i]);vlen+=2;}
 put16(mval+vlen,scs_mask);vlen+=2;
 put32(mval+vlen,arfcn);vlen+=4;
 copy(mval+vlen,m,64);vlen+=64;
 pos=addtlv(p,pos,TLV_NRCELL_SET_MULTI,mval,(u16)vlen);
 if(!setter_msg(s,MSG_NR_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_nr_cell_lock(s);
 return 1;
}
static int cmd_nr_cell_lock_gnb_set(struct state*s,u32 id_bits,const u64*ids,u32 count){
 u8 tval[4],gval[2+8*NR_CELL_GNB_MAX],p[7+3+2+8*NR_CELL_GNB_MAX];u32 i,vlen;int pos=0;
 if(count>NR_CELL_GNB_MAX)return 0;
 put32(tval,NRCELL_TYPE_GNB);
 pos=addtlv(p,pos,TLV_NRCELL_SET_TYPE,tval,4);
 gval[0]=(u8)count;vlen=1;
 for(i=0;i<count;i++){put64(gval+vlen,ids[i]);vlen+=8;}
 gval[vlen++]=(u8)id_bits;
 pos=addtlv(p,pos,TLV_NRCELL_SET_GNB,gval,(u16)vlen);
 if(!setter_msg(s,MSG_NR_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_nr_cell_lock(s);
 return 1;
}
/* NR cell-lock clear -- v4.1.0 idempotent-clear behavior (see the header
 * note): some/most firmware rejects a type=2 ("none") SET when there's
 * no NR cell lock active, unlike LTE clear, which always succeeds. Since
 * the requested end state is already true in that case, this reads the
 * current lock type first and, if it's already NRCELL_TYPE_NONE, skips
 * the write and reports synthetic success instead of surfacing a
 * rejection the app has no useful action for. s->last_op is left zeroed
 * in that path (no modem write happened), so build_response() correctly
 * shows "error":null rather than any stage at all, since *ok comes back
 * true. If a real SET is sent and the modem still rejects it, that
 * rejection surfaces normally via last_op/"modem_rejected", same as any
 * other command. */
static int cmd_nr_cell_lock_clear(struct state*s){
 u8 tval[4],p[7];int pos=0;
 if(!s->nr_cell.valid&&!query_nr_cell_lock(s))return 0;
 if(s->nr_cell.type==NRCELL_TYPE_NONE){
  zero(&s->last_op,sizeof(s->last_op));
  setstatus(s,"NR cell lock already clear; nothing to do.");
  return 1;
 }
 put32(tval,NRCELL_TYPE_NONE);
 pos=addtlv(p,pos,TLV_NRCELL_SET_TYPE,tval,4);
 if(!setter_msg(s,MSG_NR_CELL_LOCK_SET,p,(u16)pos))return 0;
 query_nr_cell_lock(s);
 return 1;
}

/* ── Everything below is new: JSON state/diagnostics serialization, and
 * the socket/request-dispatch/response layer. ─────────────────────────── */

static const char*tlv_name(u8 id){
 switch(id){
  case TLV_RESULT:return "RESULT";
  case TLV_MODE:return "RAT_MODE";
  case TLV_LEGACY:return "LEGACY(GSM/WCDMA)";
  case TLV_LTE:return "LTE(legacy,8B)";
  case TLV_DURATION:return "DURATION/APPLY";
  case TLV_EXT_LTE_GET:return "LTE_EXT(get,32B)";
  case TLV_EXT_LTE_SET:return "LTE_EXT(set,32B)";
  case TLV_NR_COMBINED:return "NR_COMBINED(0x2B)";
  case TLV_NR_SA_GET:return "NR_SA_GET(0x2C)";
  case TLV_NR_NSA_GET:return "NR_NSA_GET(0x2D)";
  case TLV_NR_SA_SET:return "NR_SA_SET(0x2F)";
  case TLV_NR_NSA_SET:return "NR_NSA_SET(0x30)";
  case TLV_NR_MODE:return "NR_MODE(0x2E)";
  default:return "unknown";
 }
}
static void jtlvs(char*b,u32*pos,u32 cap,const u8*r,u32 n){
 u32 p=7,e;int first=1;
 jputc(b,pos,cap,'[');
 if(n<7){jputc(b,pos,cap,']');return;}
 e=7u+le16(r+5);if(e>n)e=n;
 while(p+3<=e){
  u8 id=r[p];u16 l=le16(r+p+1);const u8*v=r+p+3;
  if(p+3u+l>e)break;
  if(!first)jputc(b,pos,cap,',');
  first=0;
  jput(b,pos,cap,"{\"id\":\"0x");
  {char h[3];h[0]="0123456789ABCDEF"[(id>>4)&0xf];h[1]="0123456789ABCDEF"[id&0xf];h[2]=0;jput(b,pos,cap,h);}
  jput(b,pos,cap,"\",\"name\":");jstr(b,pos,cap,tlv_name(id));
  jput(b,pos,cap,",\"len\":");jint(b,pos,cap,l);
  jput(b,pos,cap,",\"value_hex\":\"");jhex_bytes(b,pos,cap,v,l<64?l:64);jput(b,pos,cap,"\"");
  if(id==TLV_RESULT&&l>=4){
   u16 res=le16(v),err=le16(v+2);
   jput(b,pos,cap,",\"result\":");jint(b,pos,cap,res);
   jput(b,pos,cap,",\"error\":");jint(b,pos,cap,err);
   jput(b,pos,cap,",\"success\":");jbool(b,pos,cap,res==0);
  }
  jputc(b,pos,cap,'}');
  p+=3u+l;
 }
 jputc(b,pos,cap,']');
}
/* Serializes one raw wire capture (tx/rx hex + decoded TLVs). Shared by
 * the general per-request "diagnostics" field (jdiagnostics(), below,
 * fed by s->diag) and the capability probe's own per-domain SA/NSA
 * captures (jcap_domain(), fed by s->nr_cap.sa_diag/nsa_diag) -- both are
 * gated on verbose mode by their respective callers, not by this function. */
static void jdiag_dump(char*b,u32*pos,u32 cap,const struct diag*dg){
 if(!dg->have){jput(b,pos,cap,"null");return;}
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"msg_id\":\"0x");
 {char h[5];h[0]="0123456789ABCDEF"[(dg->msg>>12)&0xf];h[1]="0123456789ABCDEF"[(dg->msg>>8)&0xf];h[2]="0123456789ABCDEF"[(dg->msg>>4)&0xf];h[3]="0123456789ABCDEF"[dg->msg&0xf];h[4]=0;jput(b,pos,cap,h);}
 jput(b,pos,cap,"\",");
 jput(b,pos,cap,"\"request\":{\"hex\":\"");jhex_bytes(b,pos,cap,dg->tx,dg->tx_len);jput(b,pos,cap,"\",\"tlvs\":");jtlvs(b,pos,cap,dg->tx,dg->tx_len);jputc(b,pos,cap,'}');
 jput(b,pos,cap,",\"response\":");
 if(dg->rx_len){
  jput(b,pos,cap,"{\"hex\":\"");jhex_bytes(b,pos,cap,dg->rx,dg->rx_len);jput(b,pos,cap,"\",\"matched\":");jbool(b,pos,cap,dg->got_match);jput(b,pos,cap,",\"tlvs\":");jtlvs(b,pos,cap,dg->rx,dg->rx_len);jputc(b,pos,cap,'}');
 } else jput(b,pos,cap,"null");
 jput(b,pos,cap,",\"send_failed\":");jbool(b,pos,cap,dg->send_failed);
 jput(b,pos,cap,",\"timed_out\":");jbool(b,pos,cap,dg->timed_out);
 jput(b,pos,cap,",\"skipped_count\":");jint(b,pos,cap,(u32)dg->skipped_count);
 jputc(b,pos,cap,'}');
}
static void jdiagnostics(char*b,u32*pos,u32 cap,const struct state*s){
 if(!s->verbose){jput(b,pos,cap,"null");return;}
 jdiag_dump(b,pos,cap,&s->diag);
}

static void jrat(char*b,u32*pos,u32 cap,u16 m){
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"raw\":");jint(b,pos,cap,m);
 jput(b,pos,cap,",\"auto\":");jbool(b,pos,cap,m==0xFF);
 jput(b,pos,cap,",\"gsm\":");jbool(b,pos,cap,(m&4)!=0);
 jput(b,pos,cap,",\"wcdma\":");jbool(b,pos,cap,(m&8)!=0);
 jput(b,pos,cap,",\"lte\":");jbool(b,pos,cap,(m&16)!=0);
 jput(b,pos,cap,",\"nr\":");jbool(b,pos,cap,(m&64)!=0);
 jputc(b,pos,cap,'}');
}
/* Reimplements pgsm()'s bit tests from v3.2 (same 850/900/1800/1900 <->
 * bit mapping), emitting a JSON int array instead of comma-joined text. */
static void jgsm(char*b,u32*pos,u32 cap,const u8*p){
 u64 m=le64(p);int first=1;jputc(b,pos,cap,'[');
 if(m&(1ULL<<19)){jint(b,pos,cap,850);first=0;}
 if(m&((1ULL<<8)|(1ULL<<9))){if(!first)jputc(b,pos,cap,',');jint(b,pos,cap,900);first=0;}
 if(m&(1ULL<<7)){if(!first)jputc(b,pos,cap,',');jint(b,pos,cap,1800);first=0;}
 if(m&(1ULL<<21)){if(!first)jputc(b,pos,cap,',');jint(b,pos,cap,1900);first=0;}
 jputc(b,pos,cap,']');
}
/* Reimplements pwcdma()'s bit tests from v3.2 (same wbit() table), emitting
 * a JSON int array instead of comma-joined text. */
static void jwcdma(char*b,u32*pos,u32 cap,const u8*p){
 u64 m=le64(p);int first=1;jputc(b,pos,cap,'[');
#define JW(bit,band) do{if(m&(1ULL<<(bit))){if(!first)jputc(b,pos,cap,',');jint(b,pos,cap,band);first=0;}}while(0)
 JW(22,1);JW(23,2);JW(24,3);JW(25,4);JW(26,5);JW(27,6);JW(48,7);JW(49,8);JW(50,9);JW(51,10);JW(52,11);JW(53,12);JW(54,13);JW(55,14);JW(56,15);JW(57,16);JW(58,17);JW(59,18);JW(60,19);
#undef JW
 jputc(b,pos,cap,']');
}
static void jbandmask(char*b,u32*pos,u32 cap,const u8*m,u32 max){
 u32 band;int first=1;jputc(b,pos,cap,'[');
 for(band=1;band<=max;band++)if(mhas(m,band)){if(!first)jputc(b,pos,cap,',');jint(b,pos,cap,band);first=0;}
 jputc(b,pos,cap,']');
}
static void jmode(char*b,u32*pos,u32 cap,const struct state*s){
 int i=s->sim-1;
 if(i<0||i>1||!s->nr_mode_known[i]){jstr(b,pos,cap,"unknown");return;}
 if(s->nr_mode[i]==0)jstr(b,pos,cap,"both");
 else if(s->nr_mode[i]==1)jstr(b,pos,cap,"nsa");
 else if(s->nr_mode[i]==2)jstr(b,pos,cap,"sa");
 else jstr(b,pos,cap,"unknown");
}
/* Reimplements pgsm_effective()/pwcdma_effective() from v3.2: AND the
 * current legacy mask with the hardware-capability mask (when known)
 * before decoding, so a stale/over-broad legacy mask never reports a band
 * the hardware doesn't actually have. */
static void jgsm_effective(char*b,u32*pos,u32 cap,const struct state*s){
 u8 x[8];int i;
 if(!s->hw_valid){jgsm(b,pos,cap,s->legacy);return;}
 for(i=0;i<8;i++)x[i]=(u8)(s->legacy[i]&s->hw_legacy[i]);
 jgsm(b,pos,cap,x);
}
static void jwcdma_effective(char*b,u32*pos,u32 cap,const struct state*s){
 u8 x[8];int i;
 if(!s->hw_valid){jwcdma(b,pos,cap,s->legacy);return;}
 for(i=0;i<8;i++)x[i]=(u8)(s->legacy[i]&s->hw_legacy[i]);
 jwcdma(b,pos,cap,x);
}
/* present=0 means this device's last GET didn't include that domain's
 * mask at all (nothing to probe with -- genuinely unknown, not tested).
 * supported<0 means a probe was attempted but got no usable reply
 * (transport failure, not a modem decision) -- also reported as unknown
 * rather than guessed as either true or false. */
static void jcap_domain(char*b,u32*pos,u32 cap,const struct state*s,int present,int supported,u16 result,u16 error,const struct diag*dg){
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"present\":");jbool(b,pos,cap,present);
 jput(b,pos,cap,",\"supported\":");
 if(!present||supported<0)jput(b,pos,cap,"null");else jbool(b,pos,cap,supported>0);
 jput(b,pos,cap,",\"result\":");jint(b,pos,cap,result);
 jput(b,pos,cap,",\"code\":");jint(b,pos,cap,error);
 if(s->verbose){jput(b,pos,cap,",\"diagnostics\":");jdiag_dump(b,pos,cap,dg);}
 jputc(b,pos,cap,'}');
}
/* Top-level summary the app is expected to actually gate its UI on:
 * "independent_lock_supported" is true only if BOTH the SA and NSA probes
 * were run, had a mask to test, and were accepted by the modem; false
 * only if both were run and at least one was actively rejected; null in
 * every other (untested/inconclusive) case, so the app is never told
 * "false" (and doesn't hide/disable per-domain controls) on a device that
 * simply hasn't been probed yet or gave an ambiguous answer. */
static void jcapability(char*b,u32*pos,u32 cap,const struct state*s){
 const struct nr_indep_probe*c=&s->nr_cap;
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"checked\":");jbool(b,pos,cap,c->ran);
 jput(b,pos,cap,",\"sa\":");jcap_domain(b,pos,cap,s,c->sa_present,c->sa_supported,c->sa_result,c->sa_error,&c->sa_diag);
 jput(b,pos,cap,",\"nsa\":");jcap_domain(b,pos,cap,s,c->nsa_present,c->nsa_supported,c->nsa_result,c->nsa_error,&c->nsa_diag);
 jput(b,pos,cap,",\"independent_lock_supported\":");
 if(!c->ran||!c->sa_present||!c->nsa_present||c->sa_supported<0||c->nsa_supported<0)jput(b,pos,cap,"null");
 else jbool(b,pos,cap,c->sa_supported>0&&c->nsa_supported>0);
 jputc(b,pos,cap,'}');
}

/* jint()'s sibling for u64 values (gNodeB identifiers). */
static void jint64(char*b,u32*pos,u32 cap,u64 v){
 char t[21];int n=0,i;
 if(!v){jputc(b,pos,cap,'0');return;}
 while(v){t[n++]=(char)('0'+(int)(v%10));v/=10;}
 for(i=n-1;i>=0;i--)jputc(b,pos,cap,t[i]);
}
static const char*nrcell_type_name(u32 t){
 switch(t){
  case NRCELL_TYPE_PCI:return "pci";
  case NRCELL_TYPE_ARFCN:return "arfcn";
  case NRCELL_TYPE_NONE:return "none";
  case NRCELL_TYPE_MULTI:return "multi_pci";
  case NRCELL_TYPE_GNB:return "gnb_allowlist";
  default:return "unknown";
 }
}
static void jlte_cell_lock(char*b,u32*pos,u32 cap,const struct state*s){
 u32 i;
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"valid\":");jbool(b,pos,cap,s->lte_cell.valid);
 jput(b,pos,cap,",\"locks\":[");
 for(i=0;i<s->lte_cell.count;i++){
  if(i)jputc(b,pos,cap,',');
  jput(b,pos,cap,"{\"pci\":");jint(b,pos,cap,s->lte_cell.entries[i].pci);
  jput(b,pos,cap,",\"earfcn\":");jint(b,pos,cap,s->lte_cell.entries[i].earfcn);
  jputc(b,pos,cap,'}');
 }
 jput(b,pos,cap,"]}");
}
static void jnr_cell_lock(char*b,u32*pos,u32 cap,const struct state*s){
 const struct nr_cell_lock*c=&s->nr_cell;u32 i;
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"valid\":");jbool(b,pos,cap,c->valid);
 jput(b,pos,cap,",\"type\":");jstr(b,pos,cap,nrcell_type_name(c->type));
 jput(b,pos,cap,",\"type_raw\":");jint(b,pos,cap,c->type);
 jput(b,pos,cap,",\"pci_lock\":");
 if(c->have_pci){
  jputc(b,pos,cap,'{');
  jput(b,pos,cap,"\"pci\":");jint(b,pos,cap,c->pci.pci);
  jput(b,pos,cap,",\"scs_khz\":");jint(b,pos,cap,scs_khz_from_enum(c->pci.scs));
  jput(b,pos,cap,",\"arfcn\":");jint(b,pos,cap,c->pci.arfcn);
  jput(b,pos,cap,",\"bands\":");jbandmask(b,pos,cap,c->pci.band_mask,512);
  jputc(b,pos,cap,'}');
 } else jput(b,pos,cap,"null");
 jput(b,pos,cap,",\"arfcn_lock\":");
 if(c->have_arfcn_list){
  jputc(b,pos,cap,'[');
  for(i=0;i<c->arfcn_count;i++){
   if(i)jputc(b,pos,cap,',');
   jput(b,pos,cap,"{\"arfcn\":");jint(b,pos,cap,c->arfcn[i].arfcn);
   jput(b,pos,cap,",\"scs_khz\":");jint(b,pos,cap,scs_khz_from_enum(c->arfcn[i].scs));
   jputc(b,pos,cap,'}');
  }
  jputc(b,pos,cap,']');
 } else jput(b,pos,cap,"null");
 jput(b,pos,cap,",\"multi_pci_lock\":");
 if(c->have_multi_marker)jput(b,pos,cap,"{\"present\":true}");
 else jput(b,pos,cap,"null");
 jput(b,pos,cap,",\"gnb_allowlist\":");
 if(c->have_gnb){
  jputc(b,pos,cap,'{');
  jput(b,pos,cap,"\"gnb_ids\":[");
  for(i=0;i<c->gnb.count;i++){if(i)jputc(b,pos,cap,',');jint64(b,pos,cap,c->gnb.ids[i]);}
  jputc(b,pos,cap,']');
  jput(b,pos,cap,",\"id_bits\":");
  if(c->gnb.have_id_bits)jint(b,pos,cap,c->gnb.id_bits);else jput(b,pos,cap,"null");
  jputc(b,pos,cap,'}');
 } else jput(b,pos,cap,"null");
 jputc(b,pos,cap,'}');
}
/* Emits a zero-padded MNC as a JSON string, e.g. "090" vs "90", using the
 * mnc_includes_pcs_digit flag TLV 0x1B carries (TLV 0x16 has no such flag,
 * so its own mnc is only ever emitted as a plain number -- see below). */
static void jmnc_padded(char*b,u32*pos,u32 cap,u16 mnc,int pcs){
 char t[4];int n=0,width=pcs?3:2,i;u32 v=mnc;
 if(!v)t[n++]='0';else while(v){t[n++]=(char)('0'+v%10);v/=10;}
 jputc(b,pos,cap,'"');
 for(i=n;i<width;i++)jputc(b,pos,cap,'0');
 for(i=n-1;i>=0;i--)jputc(b,pos,cap,t[i]);
 jputc(b,pos,cap,'"');
}
/* v4.3.0/v4.3.1/v4.3.2. "mode"/"mode_raw"/"mcc"/"mnc" are TLV 0x16 (the
 * network selection preference itself), null until that specific TLV has
 * been seen (s->netsel_valid); "locked_plmn" is the separate, independent
 * TLV 0x1B report of which PLMN is actually locked, null until THAT
 * specific TLV has been seen (s->manual_plmn_valid).
 *
 * v4.3.2: top-level "valid" is s->netsel_valid || s->manual_plmn_valid,
 * not just s->netsel_valid as it briefly was in v4.3.0/v4.3.1. Field-
 * confirmed on a real device: some firmware never includes TLV 0x16 in a
 * GET reply at all (a "refresh" after a successful plmn_lock_set showed
 * "locked_plmn" fully populated from TLV 0x1B, while TLV 0x16 was simply
 * absent from that same reply). Gating the whole object's "valid" on 0x16
 * alone made that device's plmn_lock look entirely invalid to the app
 * even though real, useful data was sitting right there in
 * "locked_plmn" -- so don't check top-level "valid" before looking at
 * "locked_plmn"; check "locked_plmn" for null directly, independent of
 * "valid"/"mode". "valid" now only means "at least one of the two TLVs
 * showed up in the last GET", not "both did". */
static void jplmn_lock(char*b,u32*pos,u32 cap,const struct state*s){
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"valid\":");jbool(b,pos,cap,s->netsel_valid||s->manual_plmn_valid);
 if(s->netsel_valid){
  jput(b,pos,cap,",\"mode\":");
  jstr(b,pos,cap,s->net_sel_pref==0x01?"manual":(s->net_sel_pref==0x00?"automatic":"unknown"));
  jput(b,pos,cap,",\"mode_raw\":");jint(b,pos,cap,s->net_sel_pref);
  jput(b,pos,cap,",\"mcc\":");jint(b,pos,cap,s->plmn_mcc);
  jput(b,pos,cap,",\"mnc\":");jint(b,pos,cap,s->plmn_mnc);
 } else {
  jput(b,pos,cap,",\"mode\":null,\"mode_raw\":null,\"mcc\":null,\"mnc\":null");
 }
 jput(b,pos,cap,",\"locked_plmn\":");
 if(s->manual_plmn_valid){
  jputc(b,pos,cap,'{');
  jput(b,pos,cap,"\"mcc\":");jint(b,pos,cap,s->manual_plmn_mcc);
  jput(b,pos,cap,",\"mnc\":");jint(b,pos,cap,s->manual_plmn_mnc);
  jput(b,pos,cap,",\"mnc_includes_pcs_digit\":");jbool(b,pos,cap,s->manual_plmn_pcs);
  jput(b,pos,cap,",\"mnc_display\":");jmnc_padded(b,pos,cap,s->manual_plmn_mnc,s->manual_plmn_pcs);
  jputc(b,pos,cap,'}');
 } else jput(b,pos,cap,"null");
 jputc(b,pos,cap,'}');
}
static void jstate(char*b,u32*pos,u32 cap,struct state*s){
 jputc(b,pos,cap,'{');
 jput(b,pos,cap,"\"valid\":");jbool(b,pos,cap,s->valid);
 jput(b,pos,cap,",\"sim\":");jint(b,pos,cap,(u32)s->sim);
 if(s->valid){
  jput(b,pos,cap,",\"rat\":");jrat(b,pos,cap,s->rat);
  jput(b,pos,cap,",\"gsm\":");jgsm_effective(b,pos,cap,s);
  jput(b,pos,cap,",\"wcdma\":");jwcdma_effective(b,pos,cap,s);
  jput(b,pos,cap,",\"lte\":");jbandmask(b,pos,cap,s->extlte,256);
  jput(b,pos,cap,",\"nr_sa\":");jbandmask(b,pos,cap,s->sa,512);
  jput(b,pos,cap,",\"nr_nsa\":");jbandmask(b,pos,cap,s->nsa,512);
  jput(b,pos,cap,",\"nr_mode\":");jmode(b,pos,cap,s);
 } else {
  jput(b,pos,cap,",\"rat\":null,\"gsm\":null,\"wcdma\":null,\"lte\":null,\"nr_sa\":null,\"nr_nsa\":null,\"nr_mode\":null");
 }
 jput(b,pos,cap,",\"hardware\":");
 if(s->hw_valid){
  jputc(b,pos,cap,'{');
  jput(b,pos,cap,"\"gsm\":");jgsm(b,pos,cap,s->hw_legacy);
  jput(b,pos,cap,",\"wcdma\":");jwcdma(b,pos,cap,s->hw_legacy);
  jput(b,pos,cap,",\"lte\":");jbandmask(b,pos,cap,s->hw_lte,256);
  jput(b,pos,cap,",\"nr\":");jbandmask(b,pos,cap,s->hw_nr,512);
  jputc(b,pos,cap,'}');
 } else jput(b,pos,cap,"null");
 jput(b,pos,cap,",\"nr_independent_capability\":");jcapability(b,pos,cap,s);
 jput(b,pos,cap,",\"lte_cell_lock\":");jlte_cell_lock(b,pos,cap,s);
 jput(b,pos,cap,",\"nr_cell_lock\":");jnr_cell_lock(b,pos,cap,s);
 jput(b,pos,cap,",\"plmn_lock\":");jplmn_lock(b,pos,cap,s);
 jput(b,pos,cap,",\"status\":");jstr(b,pos,cap,s->status);
 jputc(b,pos,cap,'}');
}

static void set_stage(char*stage,u32 cap,const char*v){u64 i=0;while(v[i]&&i+1<cap){stage[i]=v[i];i++;}stage[i]=0;}

/* Dispatches one already-parsed request line to the matching command
 * handler. `stage` is left empty ("") when the failure (if any) should be
 * inferred from s->last_op/s->last_rejected_count by build_response()
 * (i.e. the failure happened inside one of the carried-over cmd_*
 * functions); it is set explicitly here for failures this function itself
 * detects before ever reaching modem-facing code (missing/invalid JSON
 * fields, unknown "cmd", SIM reconnect failure). See the .md spec's
 * "error.stage" reference for the full list and examples of each. */
static void do_command(struct state*s,const char*req,int*ok,int*did_set,int*shutdown_req,char*stage,u32 stage_cap){
 char cmd[32],arg[600];
 *ok=0;*did_set=0;*shutdown_req=0;stage[0]=0;
 zero(&s->last_op,sizeof(s->last_op));
 s->last_rejected_count=0;
 s->diag.have=0;

 if(!json_get_string(req,"cmd",cmd,sizeof(cmd))){setstatus(s,"Missing or invalid 'cmd' field.");set_stage(stage,stage_cap,"bad_request");return;}

 if(eq(cmd,"query")){*ok=query(s);if(!*ok)set_stage(stage,stage_cap,"daemon");return;}
 if(eq(cmd,"refresh")){
  if(!reopen_bound(s,s->sim)){s->valid=0;setstatus(s,"Refresh failed: could not reconnect.");set_stage(stage,stage_cap,"daemon");return;}
  *ok=query(s);if(!*ok)set_stage(stage,stage_cap,"daemon");
  return;
 }
 if(eq(cmd,"query_hardware")){*ok=query_hardware(s);if(!*ok)set_stage(stage,stage_cap,"daemon");return;}
 /* Runs the NR independent SA/NSA band-lock capability probe (see
    query_nr_independent_capability() above). Also runs once automatically
    at daemon startup -- this command exists so the app can re-check it
    on its own launch (per-connection) without restarting the daemon, and
    so it can force a re-check after e.g. a SIM swap if it wants one. */
 if(eq(cmd,"query_nr_independent_capability")){*ok=query_nr_independent_capability(s);if(!*ok)set_stage(stage,stage_cap,"daemon");return;}
 /* Cell lock (v4.1.0) GET refreshes -- independent of the general query()/
    query_hardware() above; see the header's v4.1.0 note. */
 if(eq(cmd,"query_lte_cell_lock")){*ok=query_lte_cell_lock(s);if(!*ok)set_stage(stage,stage_cap,"daemon");return;}
 if(eq(cmd,"query_nr_cell_lock")){*ok=query_nr_cell_lock(s);if(!*ok)set_stage(stage,stage_cap,"daemon");return;}
 /* No dedicated data beyond what's already in every response's top-level
    "version" field; this just gives the app an explicit, self-describing
    request/response round trip to check daemon compatibility with. */
 if(eq(cmd,"version")){*ok=1;setstatus(s,"qcom-bandlockd v" DAEMON_VERSION);return;}
 if(eq(cmd,"shutdown")){*ok=1;*shutdown_req=1;setstatus(s,"Daemon shutting down.");return;}
 if(eq(cmd,"verbose_set")){
  int v;
  if(!json_get_bool(req,"verbose",&v)){setstatus(s,"Missing 'verbose' boolean field.");set_stage(stage,stage_cap,"bad_request");return;}
  s->verbose=v;setstatus(s,v?"Verbose diagnostics enabled.":"Verbose diagnostics disabled.");*ok=1;return;
 }
 if(eq(cmd,"sim_set")){
  s64 sim;
  if(!json_get_int(req,"sim",&sim)||(sim!=1&&sim!=2)){setstatus(s,"Field 'sim' must be the integer 1 or 2.");set_stage(stage,stage_cap,"bad_request");return;}
   if(!reopen_bound(s,(int)sim)){s->valid=0;setstatus(s,"SIM switch failed.");set_stage(stage,stage_cap,"daemon");return;}
   *ok=query(s);if(!*ok){set_stage(stage,stage_cap,"daemon");return;}
   query_lte_cell_lock(s);
   query_nr_cell_lock(s);
   return;
 }
 if(eq(cmd,"rat_set")){
  if(!json_get_string(req,"rat",arg,sizeof(arg))){setstatus(s,"Missing 'rat' string field (\"auto\" or \"gsm,wcdma,lte,nr\").");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_rat(s,arg);*did_set=*ok;return;
 }
 if(eq(cmd,"lte_set")||eq(cmd,"nr_set")||eq(cmd,"nr_sa_set")||eq(cmd,"nr_nsa_set")||eq(cmd,"gsm_set")||eq(cmd,"wcdma_set")){
  if(!extract_bands_arg(req,arg,sizeof(arg))){setstatus(s,"Missing/invalid 'bands' field (array of integers, or \"all\"/\"hardware\"/\"none\").");set_stage(stage,stage_cap,"bad_request");return;}
  if(eq(cmd,"lte_set"))*ok=cmd_lte(s,arg);
  else if(eq(cmd,"nr_set"))*ok=cmd_nr(s,arg,TLV_NR_COMBINED);
  else if(eq(cmd,"nr_sa_set"))*ok=cmd_nr(s,arg,TLV_NR_SA_SET);
  else if(eq(cmd,"nr_nsa_set"))*ok=cmd_nr(s,arg,TLV_NR_NSA_SET);
   else if(eq(cmd,"gsm_set"))*ok=cmd_gsm(s,arg);
   else *ok=cmd_wcdma(s,arg);
   /* gsm_set/wcdma_set update s->legacy immediately after SET to avoid
      the stale-GET race (modem hasn't applied SET when query reads TLV 0x12).
      Skip the post-SET query for these two commands. */
   if(eq(cmd,"gsm_set")||eq(cmd,"wcdma_set"))*did_set=0;else *did_set=*ok;
  return;
 }
 /* Cell lock (v4.1.0) SET/CLEAR commands. All validation ranges below
    (PCI 0-503 for LTE / 0-1007 for NR, band 1-512, SCS one of 15/30/60/
    120/240 kHz, gNodeB id_bits 22-32) mirror qcom-cell-lock-test.c's own
    input validation exactly. These update lte_cell_lock/nr_cell_lock
    directly on success (see the cmd_*_cell_lock_* functions), so
    *did_set is left 0 -- the unrelated general GET (message 0x0034)
    doesn't need to fire afterward. */
 if(eq(cmd,"lte_cell_lock_set")){
  s64 earfcn=0,pci=0;
  if(!json_get_int(req,"earfcn",&earfcn)||earfcn<0){setstatus(s,"Missing/invalid 'earfcn' integer field.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"pci",&pci)||pci<0||pci>503){setstatus(s,"Field 'pci' must be an integer 0-503.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_lte_cell_lock_set(s,(u32)earfcn,(u32)pci);*did_set=0;return;
 }
 /* v4.4.0: same TLV as lte_cell_lock_set above, just with count>1. See the
    header note -- this is NOT an EARFCN-only/no-PCI lock; every PCI in the
    list is required, and the modem admits any cell matching one of them
    on the given EARFCN, not the frequency in general. */
 if(eq(cmd,"lte_cell_lock_multi_pci_set")){
  s64 earfcn=0;u32 pcis[LTE_CELL_LOCK_MAX],i;int cnt;
  if(!json_get_int(req,"earfcn",&earfcn)||earfcn<0){setstatus(s,"Missing/invalid 'earfcn' integer field.");set_stage(stage,stage_cap,"bad_request");return;}
  cnt=json_get_int_array(req,"pci_list",pcis,(int)LTE_CELL_LOCK_MAX);
  if(cnt<=0){setstatus(s,"Field 'pci_list' must be a non-empty array of integers (max 64).");set_stage(stage,stage_cap,"bad_request");return;}
  for(i=0;i<(u32)cnt;i++)if(pcis[i]>503){setstatus(s,"Field 'pci_list' entries must each be 0-503.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_lte_cell_lock_multi_pci_set(s,(u32)earfcn,pcis,(u32)cnt);*did_set=0;return;
 }
 if(eq(cmd,"lte_cell_lock_clear")){*ok=cmd_lte_cell_lock_clear(s);*did_set=0;return;}
 if(eq(cmd,"nr_cell_lock_pci_set")){
  s64 arfcn=0,pci=0,scs_khz=0,band=0;u32 e;
  if(!json_get_int(req,"arfcn",&arfcn)||arfcn<0){setstatus(s,"Missing/invalid 'arfcn' integer field.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"pci",&pci)||pci<0||pci>1007){setstatus(s,"Field 'pci' must be an integer 0-1007.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"scs_khz",&scs_khz)||!scs_enum_from_khz((u32)scs_khz,&e)){setstatus(s,"Field 'scs_khz' must be one of 15, 30, 60, 120, 240.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"band",&band)||band<1||band>512){setstatus(s,"Field 'band' must be an integer 1-512.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_nr_cell_lock_pci_set(s,(u32)arfcn,(u32)pci,e,(u32)band);*did_set=0;return;
 }
 if(eq(cmd,"nr_cell_lock_arfcn_set")){
  s64 arfcn=0,scs_khz=0;u32 e;
  if(!json_get_int(req,"arfcn",&arfcn)||arfcn<0){setstatus(s,"Missing/invalid 'arfcn' integer field.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"scs_khz",&scs_khz)||!scs_enum_from_khz((u32)scs_khz,&e)){setstatus(s,"Field 'scs_khz' must be one of 15, 30, 60, 120, 240.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_nr_cell_lock_arfcn_set(s,(u32)arfcn,e);*did_set=0;return;
 }
 if(eq(cmd,"nr_cell_lock_multi_pci_set")){
  s64 arfcn=0,scs_khz=0,band=0;u32 e,pcis[NR_CELL_MULTI_PCI_MAX],i;int cnt;
  if(!json_get_int(req,"arfcn",&arfcn)||arfcn<0){setstatus(s,"Missing/invalid 'arfcn' integer field.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"scs_khz",&scs_khz)||!scs_enum_from_khz((u32)scs_khz,&e)){setstatus(s,"Field 'scs_khz' must be one of 15, 30, 60, 120, 240.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"band",&band)||band<1||band>512){setstatus(s,"Field 'band' must be an integer 1-512.");set_stage(stage,stage_cap,"bad_request");return;}
  cnt=json_get_int_array(req,"pci_list",pcis,(int)NR_CELL_MULTI_PCI_MAX);
  if(cnt<=0){setstatus(s,"Field 'pci_list' must be a non-empty array of integers (max 64).");set_stage(stage,stage_cap,"bad_request");return;}
  for(i=0;i<(u32)cnt;i++)if(pcis[i]>1007){setstatus(s,"Field 'pci_list' entries must each be 0-1007.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_nr_cell_lock_multi_pci_set(s,(u32)arfcn,e,(u32)band,pcis,(u32)cnt);*did_set=0;return;
 }
 if(eq(cmd,"nr_cell_lock_gnb_set")){
  s64 id_bits=0;u64 ids[NR_CELL_GNB_MAX];int cnt;
  if(!json_get_int(req,"id_bits",&id_bits)||id_bits<22||id_bits>32){setstatus(s,"Field 'id_bits' must be an integer 22-32.");set_stage(stage,stage_cap,"bad_request");return;}
  cnt=json_get_int_array_u64(req,"gnb_ids",ids,(int)NR_CELL_GNB_MAX);
  if(cnt<=0){setstatus(s,"Field 'gnb_ids' must be a non-empty array of integers (max 32).");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_nr_cell_lock_gnb_set(s,(u32)id_bits,ids,(u32)cnt);*did_set=0;return;
 }
 /* No fields -- see the v4.1.0 header note / cmd_nr_cell_lock_clear() for
    the idempotent-clear behavior when no NR cell lock is currently
    active. */
 if(eq(cmd,"nr_cell_lock_clear")){*ok=cmd_nr_cell_lock_clear(s);*did_set=0;return;}
 /* v4.3.0 PLMN lock. did_set=0 for the same reason gsm_set/wcdma_set/the
    cell-lock commands above use it: cmd_plmn_lock_set()/_clear() already
    update s->netsel_valid/net_sel_pref/plmn_mcc/plmn_mnc directly on
    success, so an immediate post-SET query() would only risk racing the
    modem and reading back the pre-change value instead of adding anything. */
 if(eq(cmd,"plmn_lock_set")){
  s64 mcc=0,mnc=0;
  if(!json_get_int(req,"mcc",&mcc)||mcc<0||mcc>999){setstatus(s,"Field 'mcc' must be an integer 0-999.");set_stage(stage,stage_cap,"bad_request");return;}
  if(!json_get_int(req,"mnc",&mnc)||mnc<0||mnc>999){setstatus(s,"Field 'mnc' must be an integer 0-999.");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_plmn_lock_set(s,(u32)mcc,(u32)mnc);*did_set=0;return;
 }
 if(eq(cmd,"plmn_lock_clear")){*ok=cmd_plmn_lock_clear(s);*did_set=0;return;}
 if(eq(cmd,"mode_set")){
  if(!json_get_string(req,"mode",arg,sizeof(arg))){setstatus(s,"Missing 'mode' string field (\"sa\"/\"nsa\"/\"both\").");set_stage(stage,stage_cap,"bad_request");return;}
  *ok=cmd_mode(s,arg);*did_set=*ok;return;
 }
 if(eq(cmd,"reset")){*ok=cmd_reset(s);*did_set=*ok;return;}

 setstatus(s,"Unknown command.");set_stage(stage,stage_cap,"bad_request");
}

static void build_response(struct state*s,const char*req,int ok,const char*explicit_stage,char*out,u32 outcap){
 u32 pos=0;char cmdbuf[32];s64 idv=0;int have_id;
 have_id=json_get_int(req,"id",&idv);
 if(!json_get_string(req,"cmd",cmdbuf,sizeof(cmdbuf)))cmdbuf[0]=0;
 jputc(out,&pos,outcap,'{');
 jput(out,&pos,outcap,"\"id\":");
 if(have_id)jint(out,&pos,outcap,(u32)idv);else jput(out,&pos,outcap,"null");
 jput(out,&pos,outcap,",\"cmd\":");jstr(out,&pos,outcap,cmdbuf);
 jput(out,&pos,outcap,",\"version\":");jstr(out,&pos,outcap,DAEMON_VERSION);
 jput(out,&pos,outcap,",\"ok\":");jbool(out,&pos,outcap,ok);
 jput(out,&pos,outcap,",\"error\":");
 if(ok)jput(out,&pos,outcap,"null");
 else{
  const char*stg=explicit_stage[0]?explicit_stage:
   (s->last_rejected_count>0?"validation":
    (!s->last_op.have_reply?"transport":
     ((s->last_op.have_result&&s->last_op.result!=0)?"modem_rejected":"daemon")));
  jputc(out,&pos,outcap,'{');
  jput(out,&pos,outcap,"\"stage\":");jstr(out,&pos,outcap,stg);
  jput(out,&pos,outcap,",\"message\":");jstr(out,&pos,outcap,s->status);
  if(eq(stg,"modem_rejected")){
   jput(out,&pos,outcap,",\"result\":");jint(out,&pos,outcap,s->last_op.result);
   jput(out,&pos,outcap,",\"code\":");jint(out,&pos,outcap,s->last_op.error);
  }
  if(eq(stg,"validation")){
   int i;
   jput(out,&pos,outcap,",\"label\":");jstr(out,&pos,outcap,s->last_rejected_label);
   jput(out,&pos,outcap,",\"rejected_bands\":[");
   for(i=0;i<s->last_rejected_count;i++){if(i)jputc(out,&pos,outcap,',');jint(out,&pos,outcap,s->last_rejected_bands[i]);}
   jputc(out,&pos,outcap,']');
  }
  jputc(out,&pos,outcap,'}');
 }
 jput(out,&pos,outcap,",\"state\":");jstate(out,&pos,outcap,s);
 jput(out,&pos,outcap,",\"diagnostics\":");jdiagnostics(out,&pos,outcap,s);
 jputc(out,&pos,outcap,'}');
 if(pos<outcap)out[pos]=0;else out[outcap-1]=0;
}

/* ── Unix-domain socket transport ────────────────────────────────────── */

static int make_listen_socket(const char*name,s64*out_fd){
 s64 fd;struct sockaddr_un a;u32 nlen=(u32)slen(name),alen;
 if(nlen>106)return 0;
 fd=sc3(SYS_socket,AF_UNIX,SOCK_STREAM,0);
 if(fd<0)return 0;
 zero(&a,sizeof(a));a.family=AF_UNIX;a.path[0]=0;
 {u32 i;for(i=0;i<nlen;i++)a.path[1+i]=name[i];}
 alen=2u+1u+nlen;
 if(sc3(SYS_bind,fd,(s64)&a,(s64)alen)<0){sc1(SYS_close,fd);return 0;}
 if(sc2(SYS_listen,fd,4)<0){sc1(SYS_close,fd);return 0;}
 *out_fd=fd;return 1;
}
/* Blocks until a connection from the required peer uid arrives, silently
 * closing and retrying any connection from a different uid (or one whose
 * credentials can't be read at all). Never returns a connection that
 * hasn't been authenticated. */
static s64 accept_client(s64 listen_fd,u32 allow_uid){
 for(;;){
  s64 cfd=sc4(SYS_accept4,listen_fd,0,0,0);
  if(cfd<0)return -1;
  {
   struct ucred cr;s64 optlen=(s64)sizeof(cr);
   zero(&cr,sizeof(cr));
   if(sc5(SYS_getsockopt,cfd,SOL_SOCKET,SO_PEERCRED,(s64)&cr,(s64)&optlen)<0){sc1(SYS_close,cfd);continue;}
   if(cr.uid!=allow_uid){sc1(SYS_close,cfd);continue;}
  }
  return cfd;
 }
}
static int write_all(s64 fd,const char*b,u64 n){
 u64 off=0;
 while(off<n){s64 w=sc3(SYS_write,fd,(s64)(b+off),(s64)(n-off));if(w<=0)return 0;off+=(u64)w;}
 return 1;
}
static int write_line(s64 fd,const char*s){
 if(!write_all(fd,s,slen(s)))return 0;
 return write_all(fd,"\n",1);
}
/* Reads one NDJSON line from a connected stream socket. Unlike the old
 * TUI's readline(), there's no backspace/echo handling to do -- this is a
 * machine protocol, not a human typing at a terminal. Lines longer than
 * cap-1 are truncated (extra bytes discarded, not buffered) so a
 * pathological request can't overrun the buffer; the truncated line will
 * simply fail JSON validation downstream and come back as bad_request. */
static s64 read_line_fd(s64 fd,char*b,u64 cap){
 u64 used=0;
 if(cap<2)return -1;
 for(;;){
  char c;s64 n=sc3(SYS_read,fd,(s64)&c,1);
  if(n<0)return -1;
  if(n==0){if(used){b[used]=0;return (s64)used;}return 0;}
  if(c=='\n'){b[used]=0;return (s64)used;}
  if(c=='\r')continue;
  if(used+1<cap)b[used++]=c;
 }
}
static int serve_client(struct state*s,s64 cfd){
 static char line[8192];
 static char resp[32768];
 for(;;){
  s64 n=read_line_fd(cfd,line,sizeof(line));
  if(n<=0)return 0;
  {
   int ok=0,did_set=0,shutdown_req=0;char stage[16];
   do_command(s,line,&ok,&did_set,&shutdown_req,stage,sizeof(stage));
   if(did_set&&ok)query(s);
   build_response(s,line,ok,stage,resp,sizeof(resp));
   if(!write_line(cfd,resp))return 0;
   if(shutdown_req)return 1;
  }
 }
}

static int run(int argc,char**argv){
 struct state s;s64 listen_fd=-1;
 u32 allow_uid=0;int have_uid=0,verbose_flag=0,i;
 const char*sockname="qcom_bandlockd";

 for(i=1;i<argc;i++){
  if(eq(argv[i],"-verbose"))verbose_flag=1;
  else if(eq(argv[i],"-uid")&&i+1<argc){
   s64 v=0;int neg=0;const char*a=argv[++i];
   if(*a=='-'){neg=1;a++;}
   while(*a>='0'&&*a<='9'){v=v*10+(*a-'0');a++;}
   allow_uid=(u32)(neg?-v:v);have_uid=1;
  }
  else if(eq(argv[i],"-name")&&i+1<argc){sockname=argv[++i];}
 }
 if(!have_uid){elog("qcom-bandlockd: -uid <peer_uid> is required (refusing to start without peer authentication).\n");return 1;}

 zero(&s,sizeof(s));s.fd=-1;s.sim=1;s.verbose=verbose_flag;setstatus(&s,"Starting...");
 elog("qcom-bandlockd v" DAEMON_VERSION " starting.\n");
 if(!open_nas(&s)){elog("qcom-bandlockd: NAS discovery/open failed.\n");return 2;}
 if(!bind_sim(&s,1)){elog("qcom-bandlockd: SIM1 bind failed.\n");sc1(SYS_close,s.fd);return 3;}
 query(&s);(void)query_hardware(&s);
 /* One-time NR independent SA/NSA capability probe on daemon spawn, so the
    very first response the app gets already carries a real answer instead
    of "checked":false -- see the v4.0.4 header note and
    query_nr_independent_capability() above. */
 (void)query_nr_independent_capability(&s);
 /* Initial cell-lock reads (v4.1.0), same reasoning as above -- so the
    first response already has real "lte_cell_lock"/"nr_cell_lock" data. */
 (void)query_lte_cell_lock(&s);
 (void)query_nr_cell_lock(&s);
 setstatus(&s,"Ready.");

 if(!make_listen_socket(sockname,&listen_fd)){elog("qcom-bandlockd: failed to create/bind/listen on the abstract socket.\n");if(s.fd>=0)sc1(SYS_close,s.fd);return 4;}

 for(;;){
  s64 cfd=accept_client(listen_fd,allow_uid);
  if(cfd<0)continue;
  {int r=serve_client(&s,cfd);sc1(SYS_close,cfd);if(r)break;}
 }
 sc1(SYS_close,listen_fd);
 if(s.fd>=0)sc1(SYS_close,s.fd);
 return 0;
}
void c_start(u64*stack){int rc;int argc=(int)stack[0];char**argv=(char**)&stack[1];rc=run(argc,argv);sc1(SYS_exit,rc);for(;;){}}
__asm__(".global _start\n.type _start,%function\n_start:\nmov x0,sp\nbl c_start\nb .\n");
