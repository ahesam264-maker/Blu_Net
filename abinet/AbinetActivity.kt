package com.v2ray.ang.ui

// ⚠️ این فایل به‌صورت کامل بازنویسی شد تا کدهای تکراری/ناسازگار نسخه‌های قبلی حذف شوند.
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.receiver.BluWidget3Provider
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class AbinetActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CONNECT_SLOT = "com.ahj.blunet.action.CONNECT_SLOT"
    }

    private lateinit var web: WebView
    private val rawToGuid = java.util.concurrent.ConcurrentHashMap<String, String>()

    /* ---------- پینگ گروهی ---------- */
    private class PingJob(val callId: String, val raws: List<String>)
    private val pingQueue = LinkedBlockingQueue<PingJob>()
    @Volatile private var activeBatch: MutableMap<String, Long>? = null
    private val batchLock = Object()

    /* ---------- اتصال VPN ---------- */
    private var pendingStartCallId: String? = null
    private var pendingStartGuid: String? = null
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingStartCallId
        val guid = pendingStartGuid
        pendingStartCallId = null; pendingStartGuid = null
        if (guid == null) return@registerForActivityResult
        if (r.resultCode == Activity.RESULT_OK) {
            LauncherManager.startService(this, guid)
            if (id != null) reply(id, true, "{}")
        } else if (id != null) reply(id, false, "vpn permission denied")
    }

    /* ---------- مجوز اعلان (اندروید ۱۳+) ---------- */
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /* ---------- سرعت زنده (از ترافیک واقعی همین برنامه) ---------- */
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
                if (dt > 0) evt(
                    "traffic", JSONObject()
                        .put("dl", maxOf(0.0, (rx - lastRx) * 8 / 1e6 / dt))
                        .put("ul", maxOf(0.0, (tx - lastTx) * 8 / 1e6 / dt))
                )
            }
            lastRx = rx; lastTx = tx; lastT = now
            ui.postDelayed(this, 1000)
        }
    }
    private fun startTraffic() { ui.removeCallbacks(trafficTick); lastT = 0L; ui.post(trafficTick) }
    private fun stopTraffic() { ui.removeCallbacks(trafficTick) }

    /* ---------- اسکن QR ---------- */
    private var pendingScanCallId: String? = null
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingScanCallId ?: return@registerForActivityResult
        pendingScanCallId = null
        val text = r.data?.getStringExtra("SCAN_RESULT")
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) reply(id, true, JSONObject().put("text", text).toString())
        else reply(id, false, "cancelled")
    }

    /* ---------- پشتیبان‌گیری: ذخیره در فایل ---------- */
    private var pendingBackupCallId: String? = null
    private var pendingBackupText: String = ""
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingBackupCallId ?: return@registerForActivityResult
        pendingBackupCallId = null
        if (uri == null) { reply(id, false, "cancelled"); return@registerForActivityResult }
        val text = pendingBackupText
        thread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                reply(id, true, "{}")
            } catch (e: Exception) { reply(id, false, e.message ?: "write failed") }
        }
    }

    /* ---------- پشتیبان‌گیری: خواندن از فایل ---------- */
    private var pendingOpenCallId: String? = null
    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingOpenCallId ?: return@registerForActivityResult
        pendingOpenCallId = null
        if (uri == null) { reply(id, false, "cancelled"); return@registerForActivityResult }
        thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                reply(id, true, JSONObject().put("text", text).toString())
            } catch (e: Exception) { reply(id, false, e.message ?: "read failed") }
        }
    }

    /* ---------- فایل‌های Geo: افزودن از فایل دلخواه ---------- */
    private var pendingGeoImportCallId: String? = null
    private val geoImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingGeoImportCallId ?: return@registerForActivityResult
        pendingGeoImportCallId = null
        if (uri == null) { reply(id, false, "cancelled"); return@registerForActivityResult }
        thread {
            try {
                var name = contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    val i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cur.moveToFirst(); if (i >= 0) cur.getString(i) else null
                } ?: "custom.dat"
                if (!name.endsWith(".dat")) name += ".dat"
                val dir = File(Utils.userAssetPath(this@AbinetActivity)); dir.mkdirs()
                contentResolver.openInputStream(uri)?.use { ins -> File(dir, name).outputStream().use { out -> ins.copyTo(out) } }
                reply(id, true, JSONObject().put("name", name).toString())
            } catch (e: Exception) { reply(id, false, e.message ?: "import failed") }
        }
    }

    /* ---------- دریافت پیام‌های سرویس v2rayNG ---------- */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    startTraffic(); evt("state", JSONObject().put("state", "connected"))
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    stopTraffic(); evt("state", JSONObject().put("state", "disconnected"))
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    stopTraffic()
                    evt("state", JSONObject().put("state", "error").put("message", intent.getStringExtra("content") ?: ""))
                }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> intent.serializable<RealPingResult>("content")?.let { r ->
                    synchronized(batchLock) { activeBatch?.put(r.guid, r.delayMillis) }
                }
                AppConfig.MSG_MEASURE_CONFIG_FINISH -> synchronized(batchLock) { batchLock.notifyAll() }
                AppConfig.MSG_MEASURE_DELAY_RESULT -> intent.serializable<ConnectionTestResult>("content")?.let {
                    evt(
                        "exit", JSONObject().put("ms", it.delayMillis).put("country", it.country ?: "").put("ip", it.ipAddress ?: "")
                    )
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
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), null)
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "AbinetNative")

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        thread { runCatching { SettingsManager.initAssets(this, assets) } }
        startPingWorker()
        handleWidgetIntent(intent)
        web.loadUrl("https://abinet.local/")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    /** وقتی کاربر روی یکی از سه اسلات ویجت می‌زند، همان کانفیگ وصل می‌شود. */
    private fun handleWidgetIntent(intent: Intent) {
        if (intent.action != ACTION_CONNECT_SLOT) return
        val idx = intent.getIntExtra("slot", -1)
        val raw = BluWidget3Provider.slotRaw(this, idx) ?: return
        thread {
            val guid = ensureProfile(raw) ?: return@thread
            runOnUiThread {
                pendingStartCallId = null
                pendingStartGuid = guid
                val prep = VpnService.prepare(this@AbinetActivity)
                if (prep == null) LauncherManager.startService(this@AbinetActivity, guid) else vpnPerm.launch(prep)
            }
        }
    }

    override fun onDestroy() {
        stopTraffic()
        runCatching { unregisterReceiver(receiver) }
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /* ---------- کمک‌ها ---------- */

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun reply(id: String, ok: Boolean, data: String) =
        js("window.__abiRes(${JSONObject.quote(id)},$ok,${JSONObject.quote(data)})")
    private fun evt(name: String, data: JSONObject) =
        js("window.__abiEvt(${JSONObject.quote(name)},${JSONObject.quote(data.toString())})")

    private fun iconB64(d: Drawable): String {
        val size = 40
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
        return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

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
        while (true) {
            val job = pingQueue.take()
            try {
                val guids = job.raws.map { ensureProfile(it) }
                val ids = guids.filterNotNull()
                val result = HashMap<String, Long>()
                if (ids.isNotEmpty()) {
                    synchronized(batchLock) { activeBatch = result }
                    MessageHelper.sendMsg2TestService(
                        this, TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = ids, onlyTcp = false)
                    )
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
                    val guid = ensureProfile(a.getString("raw"))
                    if (guid == null) { reply(id, false, "کانفیگ نامعتبر است"); return@thread }
                    runOnUiThread {
                        pendingStartCallId = id; pendingStartGuid = guid
                        val prep = VpnService.prepare(this@AbinetActivity)
                        if (prep == null) { LauncherManager.startService(this@AbinetActivity, guid); reply(id, true, "{}") }
                        else vpnPerm.launch(prep)
                    }
                }
                "stop" -> { LauncherManager.stopService(this@AbinetActivity); reply(id, true, "{}") }
                "testCurrent" -> {
                    MessageHelper.sendMsg2Service(this@AbinetActivity, AppConfig.MSG_MEASURE_DELAY, "")
                    reply(id, true, "{}")
                }
                "getStatus" -> {
                    val running = CoreServiceManager.isRunning()
                    reply(
                        id, true,
                        JSONObject().put("running", running).put("name", if (running) CoreServiceManager.getRunningServerName() else "").toString()
                    )
                }

                /* ---------- کلیپ‌بورد ---------- */
                "readClipboard" -> runOnUiThread {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@AbinetActivity).toString() else ""
                    reply(id, true, JSONObject().put("text", text).toString())
                }
                "writeClipboard" -> runOnUiThread {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Blu net", a.optString("text")))
                    reply(id, true, "{}")
                }

                /* ---------- اسکن QR ---------- */
                "scanQr" -> runOnUiThread {
                    pendingScanCallId = id
                    scanLauncher.launch(Intent(this@AbinetActivity, ScannerActivity::class.java))
                }

                /* ---------- تنظیمات اصلی ---------- */
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

                /* ---------- تفکیک برنامه‌ها ---------- */
                "listApps" -> thread {
                    try {
                        val pm = packageManager
                        val out = JSONArray()
                        pm.getInstalledApplications(0)
                            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                            .forEach { ai ->
                                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                                val icon = try { iconB64(pm.getApplicationIcon(ai)) } catch (e: Exception) { "" }
                                out.put(JSONObject().put("pkg", ai.packageName).put("label", pm.getApplicationLabel(ai).toString()).put("sys", sys).put("icon", icon))
                            }
                        reply(id, true, JSONObject().put("apps", out).toString())
                    } catch (e: Exception) { reply(id, false, e.message ?: "apps failed") }
                }
                "getPerApp" -> {
                    val pkgs = JSONArray()
                    (MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf<String>()).forEach { pkgs.put(it) }
                    reply(
                        id, true, JSONObject()
                            .put("enabled", MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false))
                            .put("bypass", MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false))
                            .put("pkgs", pkgs).toString()
                    )
                }
                "setPerApp" -> {
                    if (a.has("enabled")) MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, a.getBoolean("enabled"))
                    if (a.has("bypass")) MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, a.getBoolean("bypass"))
                    if (a.has("pkgs")) {
                        val j = a.getJSONArray("pkgs")
                        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, MutableList(j.length()) { j.getString(it) }.toMutableSet())
                    }
                    reply(id, true, "{}")
                }

                /* ---------- فایل‌های Geo ---------- */
                "geoList" -> thread {
                    try {
                        val dir = File(Utils.userAssetPath(this@AbinetActivity))
                        val arr = JSONArray()
                        dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                            arr.put(JSONObject().put("name", f.name).put("size", f.length()).put("modified", f.lastModified()))
                        }
                        reply(id, true, JSONObject().put("files", arr).toString())
                    } catch (e: Exception) { reply(id, false, e.message ?: "list failed") }
                }
                "geoDownload" -> thread {
                    try {
                        val name = a.getString("name"); val urlStr = a.getString("url")
                        val dir = File(Utils.userAssetPath(this@AbinetActivity)); dir.mkdirs()
                        val tmp = File(dir, "$name.tmp")
                        val c = URL(urlStr).openConnection() as HttpURLConnection
                        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                        c.inputStream.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
                        tmp.renameTo(File(dir, name))
                        reply(id, true, "{}")
                    } catch (e: Exception) { reply(id, false, e.message ?: "download failed") }
                }
                "geoDelete" -> thread {
                    try { File(Utils.userAssetPath(this@AbinetActivity), a.getString("name")).delete(); reply(id, true, "{}") }
                    catch (e: Exception) { reply(id, false, e.message ?: "delete failed") }
                }
                "geoImport" -> runOnUiThread {
                    pendingGeoImportCallId = id
                    geoImportLauncher.launch(arrayOf("*/*"))
                }

                /* ---------- ویجت ---------- */
                "setWidget" -> thread {
                    try {
                        BluWidget3Provider.saveSlots(this@AbinetActivity, a.getJSONArray("slots"))
                        runOnUiThread {
                            val mgr = AppWidgetManager.getInstance(this@AbinetActivity)
                            val ids = mgr.getAppWidgetIds(ComponentName(this@AbinetActivity, BluWidget3Provider::class.java))
                            BluWidget3Provider.updateAll(this@AbinetActivity, mgr, ids)
                        }
                        reply(id, true, "{}")
                    } catch (e: Exception) { reply(id, false, e.message ?: "widget save failed") }
                }

                /* ---------- پشتیبان‌گیری ---------- */
                "saveBackup" -> runOnUiThread {
                    pendingBackupCallId = id
                    pendingBackupText = a.getString("text")
                    saveLauncher.launch(a.optString("name", "BluNet-backup.json"))
                }
                "openBackup" -> runOnUiThread {
                    pendingOpenCallId = id
                    openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
            }
        }
    }
}
