package dev.qcom.bandmenu

import com.topjohnwu.superuser.Shell

/**
 * Some root solutions' SELinux policy doesn't let apps connect to a root
 * process's socket (KernelSU on the Xperia 1 IV; a OnePlus whose kernel can't
 * go permissive): connect() fails with "Permission denied" while the daemon or
 * DIAG helper runs. This adds only `allow untrusted_app <root domain>
 * unix_stream_socket connectto`, live until reboot, through the root
 * solution's own policy tool. Both root processes accept only this app's uid.
 */
object RootSocketRule {
    /** SELinux domains root processes run in: "su" on KernelSU, "magisk" on
     *  Magisk (Magisk 26 on the OnePlus Nord: the "su" rule is accepted but
     *  doesn't apply). */
    private val ROOT_DOMAINS = listOf("su", "magisk")

    /** True if a policy tool accepted the rule for at least one domain. */
    fun allow(tag: String): Boolean {
        val added = ROOT_DOMAINS.filter { domain ->
            val rule = "allow untrusted_app $domain unix_stream_socket connectto"
            // A subshell: libsu's root shell is persistent, so a bare exit would end it.
            Shell.cmd(
                "(for t in ksud /data/adb/ksud /data/adb/ksu/bin/ksud; do " +
                    "command -v \$t >/dev/null 2>&1 && \$t sepolicy patch '$rule' >/dev/null 2>&1 && exit 0; done; " +
                "for t in magiskpolicy /data/adb/magisk/magiskpolicy; do " +
                    "command -v \$t >/dev/null 2>&1 && \$t --live '$rule' >/dev/null 2>&1 && exit 0; done; exit 1)"
            ).exec().isSuccess
        }
        AppLog.i(tag, "SELinux connectto rule: ${if (added.isEmpty()) "no tool accepted it" else "added for $added"}")
        return added.isNotEmpty()
    }
}
