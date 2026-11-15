package com.v2ray.ang.ui

// ⚠️ بدون کامپایل نوشته شده؛ اگر بیلد ارور داد، متن ارور را بفرست.
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class AbinetActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val rawToGuid = ConcurrentHashMap<String, String>()

    // پینگ‌ها یکی‌یکی (دسته‌ای) انجام می‌شوند
    private class PingJob(val callId: String, val raws: List<String>)
    private val pingQueue = LinkedBlockingQueue<PingJob>()
    @Volatile private var activeBatch: MutableMap<String, Long>? = null
    private val batchLock = Object()

    // سرعت زنده: بایت‌های واقعی شبکه‌ی همین برنامه (ترافیک تونل)
    private val ui = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private val trafficTick = object : Runnable {
        override fun run() {
            val uid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            val now = System.currentTimeMillis()
            if (lastT != 0L && rx >= 0 && tx >= 0) {
                val dt = (now - lastT) / 1000.0
                if (dt > 0) evt("traffic", JSONObject()
                    .put("dl", maxOf(0.0, (rx - lastRx) * 8 / 1e6 / dt))
                    .put("ul", maxOf(0.0, (tx - lastTx) * 8 / 1e6 / dt)))
            }
            lastRx = rx; lastTx = tx; lastT = now
            ui.postDelayed(this, 1000)
        }
    }
    private fun startTraffic() { ui.removeCallbacks(trafficTick); lastT = 0L; ui.post(trafficTick) }
    private fun stopTraffic() { ui.removeCallbacks(trafficTick) }

    // بدون این مجوز (اندروید ۱۳+) اعلان اتصال نمایش داده نمی‌شود
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingStartCallId: String? = null
    private var pendingStartGuid: String? = null

    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingStartCallId ?: return@registerForActivityResult
        if (r.resultCode == Activity.RESULT_OK) { LauncherManager.startService(this, pendingStartGuid); reply(id, true, "{}") }
        else reply(id, false, "دسترسی VPN داده نشد")
        pendingStartCallId = null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> { startTraffic(); evt("state", JSONObject().put("state", "connected")) }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> { stopTraffic(); evt("state", JSONObject().put("state", "disconnected")) }
                AppConfig.MSG_STATE_START_FAILURE -> { stopTraffic(); evt("state", JSONObject().put("state", "error").put("message", intent.getStringExtra("content") ?: "")) }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> intent.serializable<RealPingResult>("content")?.let { r ->
                    synchronized(batchLock) { activeBatch?.put(r.guid, r.delayMillis) }
                }
                AppConfig.MSG_MEASURE_CONFIG_FINISH -> synchronized(batchLock) { batchLock.notifyAll() }
                AppConfig.MSG_MEASURE_DELAY_RESULT -> intent.serializable<ConnectionTestResult>("content")?.let {
                    evt("exit", JSONObject().put("ms", it.delayMillis).put("country", it.country ?: "").put("ip", it.ipAddress ?: ""))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.WHITE)
        setContentView(web)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // فایل UI از assets با آدرس https://abinet.local/ سرو می‌شود
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.url.host != "abinet.local") return null
                return try {
                    WebResourceResponse("text/html", "utf-8", assets.open("abinet.html"))
                } catch (e: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), null) }
            }
        }
        web.addJavascriptInterface(Bridge(), "AbinetNative")
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        thread { runCatching { SettingsManager.initAssets(this, assets) } }
        startPingWorker()
        web.loadUrl("https://abinet.local/")
    }

    override fun onDestroy() {
        stopTraffic()
        runCatching { unregisterReceiver(receiver) }
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    /* ---------- کمک‌ها ---------- */

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun reply(id: String, ok: Boolean, data: String) =
        js("window.__abiRes(${JSONObject.quote(id)},$ok,${JSONObject.quote(data)})")
    private fun evt(name: String, data: JSONObject) =
        js("window.__abiEvt(${JSONObject.quote(name)},${JSONObject.quote(data.toString())})")

    /** لینک vless/vmess/... → guid داخل v2rayNG (در صورت نبودن، وارد می‌شود) */
    private fun ensureProfile(raw: String): String? {
        rawToGuid[raw]?.let { if (MmkvManager.decodeServerConfig(it) != null) return it }
        val before = MmkvManager.decodeAllServerList().toSet()
        AngConfigManager.importBatchConfig(raw, "", true)
        val guid = MmkvManager.decodeAllServerList().firstOrNull { it !in before } ?: return null
        rawToGuid[raw] = guid
        return guid
    }

    private fun startPingWorker() = thread(isDaemon = true) {
        runCatching { SettingsManager.initAssets(this, assets) }
        while (true) {
            val job = pingQueue.take()
            try {
                val guids = job.raws.map { ensureProfile(it) }
                val ids = guids.filterNotNull()
                val result = HashMap<String, Long>()
                if (ids.isNotEmpty()) {
                    synchronized(batchLock) { activeBatch = result }
                    MessageHelper.sendMsg2TestService(this, TestServiceMessage(
                        key = AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = ids, onlyTcp = false))
                    synchronized(batchLock) {
                        val end = System.currentTimeMillis() + 30_000
                        while (result.size < ids.size && System.currentTimeMillis() < end) batchLock.wait(500)
                        activeBatch = null
                    }
                }
                val arr = JSONArray()
                guids.forEach { g -> arr.put(g?.let { result[it] }?.takeIf { it > 0 } ?: -1) }
                reply(job.callId, true, JSONObject().put("pings", arr).toString())
            } catch (e: Exception) { reply(job.callId, false, e.message ?: "ping failed") }
        }
    }

    /* ---------- پل JS ↔ Kotlin ---------- */
    inner class Bridge {
        @JavascriptInterface
        fun call(id: String, method: String, argsJson: String) {
            val a = JSONObject(argsJson)
            when (method) {
                "ping" -> {
                    val raws = a.getJSONArray("raws").let { j -> List(j.length()) { j.getString(it) } }
                    pingQueue.put(PingJob(id, raws))
                }
                "fetchSub" -> thread {
                    try {
                        val c = URL(a.getString("url")).openConnection() as HttpURLConnection
                        c.connectTimeout = 15000; c.readTimeout = 20000
                        c.setRequestProperty("User-Agent", "v2rayNG")
                        val text = c.inputStream.bufferedReader().use { it.readText() }
                        reply(id, true, JSONObject().put("text", text).toString())
                    } catch (e: Exception) { reply(id, false, e.message ?: "fetch failed") }
                }
                "start" -> thread {
                    runCatching { SettingsManager.initAssets(this@AbinetActivity, assets) }
                    val guid = ensureProfile(a.getString("raw"))
                    if (guid == null) { reply(id, false, "کانفیگ نامعتبر است"); return@thread }
                    runOnUiThread {
                        pendingStartCallId = id; pendingStartGuid = guid
                        val prep = VpnService.prepare(this@AbinetActivity)
                        if (prep == null) { LauncherManager.startService(this@AbinetActivity, guid); reply(id, true, "{}") }
                        else vpnPerm.launch(prep)
                    }
                }
                "getSettings" -> {
                    val defs = a.getJSONObject("defaults"); val out = JSONObject()
                    defs.keys().forEach { k ->
                        val d = defs.get(k)
                        if (d is Boolean) out.put(k, MmkvManager.decodeSettingsBool(k, d))
                        else out.put(k, MmkvManager.decodeSettingsString(k, d.toString()) ?: "")
                    }
                    reply(id, true, JSONObject().put("values", out).toString())
                }
                "setSetting" -> {
                    val k = a.getString("key")
                    if (a.optString("type") == "bool") MmkvManager.encodeSettings(k, a.optBoolean("value"))
                    else MmkvManager.encodeSettings(k, a.optString("value"))
                    reply(id, true, "{}")
                }
                "resetSettings", "applyDefaults" -> {
                    val vals = a.getJSONObject(if (method == "applyDefaults") "values" else "defaults")
                    vals.keys().forEach { k ->
                        val d = vals.get(k)
                        if (d is Boolean) MmkvManager.encodeSettings(k, d) else MmkvManager.encodeSettings(k, d.toString())
                    }
                    reply(id, true, "{}")
                }
                "stop" -> { LauncherManager.stopService(this@AbinetActivity); reply(id, true, "{}") }
                "testCurrent" -> {
                    MessageHelper.sendMsg2Service(this@AbinetActivity, AppConfig.MSG_MEASURE_DELAY, "")
                    reply(id, true, "{}")
                }
            }
        }
    }
}
