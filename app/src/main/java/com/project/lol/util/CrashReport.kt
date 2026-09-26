package com.project.lol.util

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.webkit.WebViewCompat
import com.project.lol.BuildConfig
import com.project.lol.proxy.LocalProxyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CrashReport {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun build(context: Context, thread: Thread, throwable: Throwable, logs: List<String>): String {
        val sb = StringBuilder()
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()

        sb.appendLine("spotilol crash report")
        sb.appendLine("time: ${timeFmt.format(Date())}  tz: ${zone.id} (${offsetLabel(zone)})")
        sb.appendLine()
        appendCrash(sb, thread, throwable)
        appendApp(sb, context)
        appendDevice(sb)
        appendRegion(sb, context, locale, zone)
        appendNetwork(sb, context)
        appendMemory(sb, context)
        appendStorage(sb, context)
        appendWebView(sb, context)
        appendSession(sb, context)
        sb.appendLine()
        sb.appendLine("[ logs ] (${logs.size} lines)")
        if (logs.isEmpty()) {
            sb.appendLine("(empty)")
        } else {
            logs.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    private fun appendCrash(sb: StringBuilder, thread: Thread, throwable: Throwable) {
        sb.appendLine("[ crash ]")
        sb.appendLine("thread: ${thread.name} (id=${thread.id}, prio=${thread.priority})")
        sb.appendLine("onMainThread: ${Looper.myLooper() == Looper.getMainLooper()}")
        sb.appendLine("type: ${throwable.javaClass.name}")
        sb.appendLine("message: ${throwable.message ?: "none"}")
        var cause = throwable.cause
        var depth = 1
        while (cause != null && depth <= 6) {
            sb.appendLine("cause[$depth]: ${cause.javaClass.name}: ${cause.message ?: "none"}")
            cause = cause.cause
            depth++
        }
        sb.appendLine("suppressed: ${throwable.suppressed.size}")
        sb.appendLine()
        sb.appendLine("stackTrace:")
        sb.appendLine(throwable.stackTraceToString())
        sb.appendLine()
    }

    private fun appendApp(sb: StringBuilder, context: Context) {
        sb.appendLine("[ app ]")
        sb.appendLine("package: ${context.packageName}")
        sb.appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("build: ${BuildConfig.BUILD_TYPE}  debuggable: ${BuildConfig.DEBUG}")
        sb.appendLine("pid: ${Process.myPid()}  threads: ${Thread.activeCount()}")
        sb.appendLine("uptime: ${uptimeLabel()}")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("installed: ${timeFmt.format(Date(info.firstInstallTime))}")
            sb.appendLine("updated: ${timeFmt.format(Date(info.lastUpdateTime))}")
        }
        sb.appendLine()
    }

    private fun appendDevice(sb: StringBuilder) {
        sb.appendLine("[ device ]")
        sb.appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("product: ${Build.PRODUCT}  device: ${Build.DEVICE}  board: ${Build.BOARD}")
        sb.appendLine("hardware: ${Build.HARDWARE}  brand: ${Build.BRAND}")
        sb.appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        sb.appendLine("securityPatch: ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine("buildId: ${Build.DISPLAY} / ${Build.ID}")
        sb.appendLine("emulator: ${isEmulator()}")
        sb.appendLine()
    }

    private fun appendRegion(sb: StringBuilder, context: Context, locale: Locale, zone: TimeZone) {
        sb.appendLine("[ region ]")
        sb.appendLine("locale: ${locale.toLanguageTag()} (${locale.displayName})")
        sb.appendLine("country: ${locale.country.ifBlank { "?" }} ${locale.displayCountry}")
        sb.appendLine("timezone: ${zone.id} (${offsetLabel(zone)})")
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                sb.appendLine("simCountry: ${tm.simCountryIso.ifBlank { "?" }}")
                sb.appendLine("networkCountry: ${tm.networkCountryIso.ifBlank { "?" }}")
                sb.appendLine("carrier: ${tm.simOperatorName.ifBlank { tm.networkOperatorName }}")
            }
        }
        sb.appendLine()
    }

    private fun appendNetwork(sb: StringBuilder, context: Context) {
        sb.appendLine("[ network ]")
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            sb.appendLine("transport: ${transportLabel(caps)}")
            sb.appendLine("validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
            sb.appendLine("metered: ${cm?.isActiveNetworkMetered == true}")
            if (caps != null) {
                sb.appendLine("bandwidth: down=${caps.linkDownstreamBandwidthKbps}kbps up=${caps.linkUpstreamBandwidthKbps}kbps")
            }
        }
        val prefs = context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
        sb.appendLine("connectionMode: ${prefs.getString("ConnectionMode", "normal")}")
        sb.appendLine("localProxy: running=${LocalProxyManager.isRunning} port=${LocalProxyManager.port}")
        sb.appendLine()
    }

    private fun appendMemory(sb: StringBuilder, context: Context) {
        sb.appendLine("[ memory ]")
        runCatching {
            val runtime = Runtime.getRuntime()
            sb.appendLine("heap: max=${mb(runtime.maxMemory())} used=${mb(runtime.totalMemory() - runtime.freeMemory())} free=${mb(runtime.freeMemory())}")
            sb.appendLine("nativeHeap: ${mb(Debug.getNativeHeapAllocatedSize())}")
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            sb.appendLine("device: total=${mb(info.totalMem)} avail=${mb(info.availMem)} lowMemory=${info.lowMemory} threshold=${mb(info.threshold)}")
        }
        sb.appendLine()
    }

    private fun appendStorage(sb: StringBuilder, context: Context) {
        sb.appendLine("[ storage ]")
        runCatching {
            val stat = StatFs(context.filesDir.path)
            sb.appendLine("data: total=${gb(stat.blockCountLong * stat.blockSizeLong)} free=${gb(stat.availableBlocksLong * stat.blockSizeLong)}")
        }
        sb.appendLine("filesDir: ${context.filesDir.absolutePath}")
        sb.appendLine()
    }

    private fun appendWebView(sb: StringBuilder, context: Context) {
        sb.appendLine("[ webview ]")
        runCatching {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            sb.appendLine("package: ${pkg?.packageName ?: "unknown"}")
            sb.appendLine("version: ${pkg?.versionName ?: "unknown"}")
        }
        sb.appendLine()
    }

    private fun appendSession(sb: StringBuilder, context: Context) {
        val prefs = context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
        sb.appendLine("[ session ]")
        sb.appendLine("keys: ${prefs.all.size}")
        sb.appendLine("loggedIn: ${prefs.getBoolean("LoggedIn", false)}")
        sb.appendLine("serviceOn: ${prefs.getBoolean("ServiceOn", true)}")
        sb.appendLine("playerMode: ${prefs.getString("PlayerMode", "spotilol")}")
        sb.appendLine("aPlayMode: ${prefs.getString("APlayMode", "disabled")}")
        sb.appendLine("amoled: ${prefs.getBoolean("AmoledTheme", false)}")
        sb.appendLine("powerSave: ${prefs.getBoolean("PowerSave", false)}")
        sb.appendLine("blockServiceWorker: ${prefs.getBoolean("BlockServiceWorker", true)}")
        sb.appendLine("lyricsStyle: ${prefs.getString("LyricsStyle", "default")}")
        sb.appendLine("logging: ${Logger.isEnabled()}")
        sb.appendLine()
    }

    private fun transportLabel(caps: NetworkCapabilities?): String {
        if (caps == null) return "none"
        val types = ArrayList<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("wifi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("vpn")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types.add("bluetooth")
        return if (types.isEmpty()) "other" else types.joinToString("+")
    }

    private fun uptimeLabel(): String {
        val ms = SystemClock.elapsedRealtime()
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        return "${hours}h ${minutes}m ${seconds}s"
    }

    private fun offsetLabel(zone: TimeZone): String {
        val total = zone.rawOffset / 60_000
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return "GMT$sign${abs / 60}:${(abs % 60).toString().padStart(2, '0')}"
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    private fun mb(bytes: Long): String = "${bytes / 1024 / 1024}MB"

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.2fGB", bytes.toDouble() / 1024 / 1024 / 1024)
}
