package com.droiddeck.launcher.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * chroot container backend for rooted devices.
 *
 * Unlike proot's ptrace translation, a chroot is set once in the kernel and inherited by every
 * descendant, so Steam, Proton, Wine and FEX all land in the jail for free. The cost is teardown:
 * bind mounts must be unmounted and guest processes (running as root) killed before the session is
 * gone, or the next launch hits stale mounts and leftover procs -- the classic chroot-distro rot.
 *
 * That cleanup lives in assets/chroot-entry.sh, launched through su, in three layers that all run
 * as root so they keep working when the app process is gone:
 *   1. an EXIT/INT/TERM/HUP trap kills every process whose /proc/PID/root is the guest rootfs and
 *      lazily unmounts every bind, then sweeps /proc/mounts for anything still under the rootfs;
 *   2. a background watchdog notices the app died and runs the same cleanup;
 *   3. the reverse-order bind list makes parent mounts come off after their children.
 *
 * Only the narrow Steam path is supported; desktop and Flatpak install are disabled by the caller.
 */
object ChrootRuntime {
    private const val TAG = "ChrootRuntime"
    private const val BACKEND = SessionPrefs.BACKEND_CHROOT
    private const val TEMPLATE = "chroot-entry.sh"
    private const val STOP_TEMPLATE = "chroot-stop.sh"
    private val seq = AtomicInteger()

    private val SU_PATHS = listOf(
        "/debug_ramdisk/su", "/sbin/su", "/system/sbin/su",
        "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su",
    )

    fun suBinary(): String? = SU_PATHS.firstOrNull { File(it).canExecute() }

    fun available(): Boolean = suBinary() != null

    fun backendEnabled(context: Context): Boolean = SessionPrefs.runtimeBackend(context) == BACKEND

    data class Command(val argv: List<String>, val hostEnv: Map<String, String>)

    /**
     * Stage a chroot entry script and return the argv to launch it (su -c <script>).
     *
     * @param guestArgv the full guest command line exactly as proot would append it, i.e.
     *   ["/usr/bin/env", "-i", "KEY=VAL", ..., "/usr/local/bin/droiddeck-session", mode, args...]
     * @param binds bind specs from LinuxRuntime.binds() ("host:guest" or "guest"); empty/null skips
     *   mounting (a terminal piggybacks on an already-running session's mounts).
     */
    fun command(
        context: Context,
        guestArgv: List<String>,
        binds: List<String>?,
        fakeRoot: Boolean,
    ): Command {
        val su = suBinary() ?: throw IllegalStateException("no su binary found")
        val root = LinuxRuntime.rootDir(context).path
        val appPid = android.os.Process.myPid()
        val n = seq.incrementAndGet()
        val script = File(context.cacheDir, "chroot-entry-$n.sh")
        val bindsFile = File(context.cacheDir, "chroot-binds-$n.txt")
        val bindsRevFile = File(context.cacheDir, "chroot-binds-rev-$n.txt")

        val effective = binds ?: emptyList()
        bindsFile.writeText(effective.joinToString("\n") + "\n")
        bindsRevFile.writeText(effective.asReversed().joinToString("\n") + "\n")

        val quotedGuest = guestArgv.joinToString(" ") { shellQuote(it) }
        val template = context.assets.open(TEMPLATE).bufferedReader().use { it.readText() }
        val scriptText = template
            .replace("__ROOT__", root)
            .replace("__APP_PID__", appPid.toString())
            .replace("__BINDS_FILE__", bindsFile.path)
            .replace("__BINDS_REV_FILE__", bindsRevFile.path)
            .replace("__GUEST__", quotedGuest)
        script.writeText(scriptText)
        Os.chmod(script.path, 0b111101101) // 0755

        Log.i(TAG, "staged chroot entry ${script.path} (${effective.size} binds) su=$su")
        return Command(listOf(su, "-c", script.path), emptyMap())
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Force-stop the chroot session as root: kill every process whose root is the guest rootfs and
     * unmount everything under it. Idempotent, so it is safe to call after a graceful exit.
     * The app cannot signal root procs itself, so this is the guarantee against residuals.
     */
    fun stop(context: Context) {
        val su = suBinary() ?: run { Log.w(TAG, "stop: no su; cannot force-clean"); return }
        val root = LinuxRuntime.rootDir(context).path
        val n = seq.incrementAndGet()
        val script = File(context.cacheDir, "chroot-stop-$n.sh")
        val template = context.assets.open(STOP_TEMPLATE).bufferedReader().use { it.readText() }
        script.writeText(template.replace("__ROOT__", root))
        Os.chmod(script.path, 0b111101101) // 0755
        try {
            val process = ProcessBuilder(su, "-c", script.path).redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            process.waitFor()
            Log.i(TAG, "force-stopped chroot at $root")
        } catch (e: Exception) {
            Log.w(TAG, "force-stop failed", e)
        }
    }
}
