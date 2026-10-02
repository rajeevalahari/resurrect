package com.example.resurrect

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Bridges the Flutter UI to the on-device ADB loopback.
 *
 * Channel: `com.example.resurrect/adb`
 * Connection methods: pair, connect, connectAuto, isConnected, disconnect, isPaired,
 *   plus overlay/Settings helpers for the floating pair window.
 * Feature methods: apkInfo(path), installApk(path) — the latter streams the APK through
 *   `cmd package install --bypass-low-target-sdk-block` to resurrect low-target apps.
 */
class MainActivity : FlutterActivity() {

    private val channelName = "com.example.resurrect/adb"
    private val host = "127.0.0.1"

    private val executor = Executors.newSingleThreadExecutor()
    private val readExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        try {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not install Conscrypt provider: ${e.message}")
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, AdbEvents.CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    AdbEvents.sink = events
                }

                override fun onCancel(arguments: Any?) {
                    AdbEvents.sink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "pair" -> {
                        val port = call.argument<Int>("port")
                        val code = call.argument<String>("code")
                        if (port == null || code == null) {
                            result.error("ARGS", "port and code are required", null)
                        } else {
                            runInBackground(result) { pair(port, code) }
                        }
                    }

                    "connect" -> {
                        val port = call.argument<Int>("port")
                        if (port == null) {
                            result.error("ARGS", "port is required", null)
                        } else {
                            runInBackground(result) { connect(port) }
                        }
                    }

                    "connectAuto" -> runInBackground(result) { connectAuto() }

                    "isPaired" -> result.success(isPaired())

                    "isConnected" -> runInBackground(result) {
                        AdbConnectionManager.getInstance(applicationContext).isConnected
                    }

                    "disconnect" -> runInBackground(result) {
                        AdbConnectionManager.getInstance(applicationContext).disconnect()
                        true
                    }

                    "hasOverlayPermission" ->
                        result.success(Settings.canDrawOverlays(this))

                    "requestOverlayPermission" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        result.success(null)
                    }

                    "openDeveloperOptions" -> {
                        val opened = runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.isSuccess
                        if (!opened) {
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        result.success(opened)
                    }

                    "showFloatingWindow" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            result.error("NO_OVERLAY", "Overlay permission not granted", null)
                        } else {
                            FloatingWindowController.show(applicationContext)
                            result.success(true)
                        }
                    }

                    "hideFloatingWindow" -> {
                        FloatingWindowController.hide()
                        result.success(true)
                    }

                    "prepareSelection" -> {
                        val paths = call.argument<List<String>>("paths")
                        if (paths == null) {
                            result.error("ARGS", "paths is required", null)
                        } else {
                            runInBackground(result) { prepareSelection(paths) }
                        }
                    }

                    "installApks" -> {
                        val paths = call.argument<List<String>>("paths")
                        if (paths == null) {
                            result.error("ARGS", "paths is required", null)
                        } else {
                            runInBackground(result) { installApks(paths) }
                        }
                    }

                    "readCrashLog" -> runInBackground(result) { readCrashLog() }

                    else -> result.notImplemented()
                }
            }
    }

    /** Runs [block] off the UI thread and posts success/error back on the UI thread. */
    private fun runInBackground(result: MethodChannel.Result, block: () -> Any?) {
        executor.execute {
            val outcome = runCatching(block)
            mainHandler.post {
                outcome
                    .onSuccess { result.success(it) }
                    .onFailure { e ->
                        Log.e(TAG, "ADB op failed", e)
                        result.error(
                            "ADB_ERROR",
                            e.message ?: e.toString(),
                            Log.getStackTraceString(e),
                        )
                    }
            }
        }
    }

    // ---- connection ---------------------------------------------------------

    private fun pair(port: Int, code: String): Boolean =
        AdbConnectionManager.getInstance(applicationContext).pair(host, port, code)
            .also { if (it) markPaired() }

    private fun connect(port: Int): Boolean =
        AdbConnectionManager.getInstance(applicationContext).connect(host, port)
            .also { if (it) markPaired() }

    private fun connectAuto(): Boolean =
        AdbConnectionManager.getInstance(applicationContext)
            .autoConnect(applicationContext, 25_000L)
            .also { if (it) markPaired() }

    private fun isPaired(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    private fun markPaired() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, true).apply()
    }

    // ---- APK resurrection (split-aware) -------------------------------------

    // Split-name ABI tokens, most-specific first so substring matching is unambiguous.
    private val abiTokens =
        listOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips")

    /**
     * Expands the user's picks (raw .apk files and/or .apks/.xapk/.apkm/.zip bundles)
     * into the exact set of APKs to install, picks the one ABI split matching this
     * device, reads base-APK metadata, and returns it (plus the resolved paths) for the
     * UI to confirm before installing.
     */
    private fun prepareSelection(paths: List<String>): Map<String, Any?> {
        val collected = extractAndCollect(paths)
        val selected = selectSplits(collected)

        val basePath = selected.firstOrNull { isBaseApk(it) } ?: selected.firstOrNull()
        val info = basePath?.let { packageManager.getPackageArchiveInfo(it, 0) }
        val appInfo = info?.applicationInfo?.apply {
            sourceDir = basePath
            publicSourceDir = basePath
        }
        val label = appInfo?.let {
            runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull()
        }

        return mapOf(
            "label" to (label ?: info?.packageName ?: "APK"),
            "packageName" to (info?.packageName ?: "unknown"),
            "versionName" to (info?.versionName ?: ""),
            "targetSdk" to (appInfo?.targetSdkVersion ?: 0),
            "minSdk" to (appInfo?.minSdkVersion ?: 0),
            "totalSize" to selected.sumOf { File(it).length() },
            "parts" to selected.size,
            "hasNativeLibs" to hasNativeLibs(selected),
            "resolvedPaths" to selected,
        )
    }

    /**
     * Installs [paths] (base + splits) atomically in a single package-install session
     * via the ADB `exec:` service, with `--bypass-low-target-sdk-block` on Android 14+.
     */
    private fun installApks(paths: List<String>): String {
        val files = paths.map(::File).filter { it.exists() }
        if (files.isEmpty()) return "Failure (no APKs found)"

        val totalSize = files.sumOf { it.length() }
        val options = buildString {
            append("-r -d")
            if (Build.VERSION.SDK_INT >= 34) append(" --bypass-low-target-sdk-block")
        }

        val createOut = runExec("cmd package install-create $options -S $totalSize", 15_000L)
        val sessionId = Regex("""\[(\d+)\]""").find(createOut)?.groupValues?.get(1)
            ?: return "Failure (install-create: ${createOut.ifBlank { "no session id" }})"

        files.forEachIndexed { index, file ->
            val splitName = "${index}_${file.name.replace(' ', '_')}"
            val writeOut = runExecStream(
                "cmd package install-write -S ${file.length()} $sessionId $splitName -",
                file,
                180_000L,
            )
            if (!writeOut.contains("Success", ignoreCase = true)) {
                runExec("cmd package install-abandon $sessionId", 8_000L)
                return "Failure (writing ${file.name}: ${writeOut.ifBlank { "no response" }})"
            }
        }

        val commitOut = runExec("cmd package install-commit $sessionId", 90_000L)
        val basePkg = files.map { it.path }.firstOrNull { isBaseApk(it) }
            ?.let { packageManager.getPackageArchiveInfo(it, 0)?.packageName }
        val ok = commitOut.contains("Success", ignoreCase = true) ||
            (basePkg != null && isPackageInstalled(basePkg))
        return if (ok) "Success" else commitOut.ifBlank { "Failure (commit: no response)" }
    }

    /** Unzips any bundle picks into cache; passes raw .apk picks through unchanged. */
    private fun extractAndCollect(paths: List<String>): List<String> {
        val outDir = File(cacheDir, "resurrect_splits").apply {
            deleteRecursively()
            mkdirs()
        }
        val result = mutableListOf<String>()
        var index = 0
        for (path in paths) {
            val file = File(path)
            val name = file.name.lowercase()
            val isBundle = name.endsWith(".apks") || name.endsWith(".xapk") ||
                name.endsWith(".apkm") || name.endsWith(".zip")
            if (isBundle) {
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".apk")) {
                            val out = File(outDir, "${index++}_${File(entry.name).name}")
                            out.outputStream().use { zip.copyTo(it) }
                            result.add(out.absolutePath)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } else if (name.endsWith(".apk")) {
                result.add(path)
            }
        }
        return result
    }

    /** Keeps base + density/language/feature splits, plus only the device-matching ABI split. */
    private fun selectSplits(apkPaths: List<String>): List<String> {
        if (apkPaths.size <= 1) return apkPaths
        val abiSplits = LinkedHashMap<String, String>() // abi token -> path
        val others = mutableListOf<String>()
        for (path in apkPaths) {
            val abi = abiOf(File(path).name)
            if (abi != null) abiSplits[abi] = path else others.add(path)
        }
        if (abiSplits.isEmpty()) return apkPaths
        val deviceTokens = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }
        val chosen = deviceTokens.firstNotNullOfOrNull { abiSplits[it] }
        return if (chosen != null) others + chosen else others
    }

    private fun abiOf(fileName: String): String? {
        val lower = fileName.lowercase()
        return abiTokens.firstOrNull { lower.contains(it) }
    }

    private fun isBaseApk(path: String): Boolean {
        val info = packageManager.getPackageArchiveInfo(path, 0) ?: return false
        return info.packageName != null && info.splitNames.isNullOrEmpty()
    }

    private fun hasNativeLibs(paths: List<String>): Boolean {
        for (path in paths) {
            runCatching {
                ZipFile(path).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) return true
                    }
                }
            }
        }
        return false
    }

    private fun runExec(command: String, timeoutMs: Long): String =
        runExecStream(command, null, timeoutMs)

    /** Opens an `exec:` stream for [command], optionally streams [stdin] into it, reads output. */
    private fun runExecStream(command: String, stdin: File?, timeoutMs: Long): String {
        val manager = AdbConnectionManager.getInstance(applicationContext)
        val stream = manager.openStream("exec:$command")
        if (stdin != null) {
            stdin.inputStream().use { input ->
                val out = stream.openOutputStream()
                input.copyTo(out, DEFAULT_BUFFER_SIZE)
                out.flush()
            }
        }
        val reader = readExecutor.submit<String> {
            stream.openInputStream().bufferedReader().readText().trim()
        }
        return try {
            reader.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            ""
        } catch (e: Exception) {
            ""
        } finally {
            reader.cancel(true)
            runCatching { stream.close() }
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Dumps the crash logcat buffer over ADB (the shell user has READ_LOGS even though
     * this app's own process does not), so the user can see why a resurrected app
     * force-closes.
     */
    private fun readCrashLog(): String {
        val manager = AdbConnectionManager.getInstance(applicationContext)
        val stream = manager.openStream("shell:logcat -d -b crash -t 600")
        val reader = readExecutor.submit<String> {
            stream.openInputStream().bufferedReader().readText().trim()
        }
        val out = try {
            reader.get(12, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ""
        } finally {
            reader.cancel(true)
            runCatching { stream.close() }
        }
        return out.ifBlank {
            "No crash entries captured.\n\nOpen the installed app to reproduce the " +
                "force-close, then tap this again right away."
        }
    }

    companion object {
        private const val TAG = "Resurrect"
        const val PREFS = "resurrect_prefs"
        const val KEY_PAIRED = "paired"
    }
}
