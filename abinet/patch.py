"""اجرا در GitHub Actions قبل از بیلد: فایل‌های Blu net را داخل پروژه‌ی v2rayNG می‌گذارد."""
import re, shutil, pathlib, glob

BLU_WIDGET3_PROVIDER_KT = r'''package com.v2ray.ang.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.v2ray.ang.R
import com.v2ray.ang.ui.AbinetActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * ویجت صفحه‌ی اصلی با سه کانفیگ قابل‌انتخاب. هر دکمه، AbinetActivity را با
 * اکشن CONNECT_SLOT باز می‌کند که همان کانفیگ را وصل می‌کند.
 */
class BluWidget3Provider : AppWidgetProvider() {

    companion object {
        private const val PREF = "blu_widget3"
        private const val KEY_SLOTS = "slots_json"

        fun saveSlots(context: Context, slots: JSONArray) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_SLOTS, slots.toString()).apply()
        }

        private fun loadSlots(context: Context): JSONArray =
            try {
                JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SLOTS, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }

        fun slotRaw(context: Context, index: Int): String? {
            val arr = loadSlots(context)
            if (index < 0 || index >= arr.length()) return null
            val o = arr.optJSONObject(index) ?: return null
            return o.optString("raw").takeIf { it.isNotBlank() }
        }

        fun updateAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val slots = loadSlots(context)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_blu3)
                val rowIds = intArrayOf(R.id.blu_slot_0, R.id.blu_slot_1, R.id.blu_slot_2)
                for (i in 0..2) {
                    val o = slots.optJSONObject(i)
                    val name = o?.optString("name")?.takeIf { it.isNotBlank() } ?: "—"
                    val ping = o?.optString("ping")?.takeIf { it.isNotBlank() }
                    views.setTextViewText(rowIds[i], if (ping != null) "$name  ·  $ping" else name)
                    val intent = Intent(context, AbinetActivity::class.java).apply {
                        action = AbinetActivity.ACTION_CONNECT_SLOT
                        putExtra("slot", i)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pi = PendingIntent.getActivity(
                        context, 100 + i, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    views.setOnClickPendingIntent(rowIds[i], pi)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }
}
'''

APP_NAME = 'Blu net'
APP_ID = 'com.ahj.blunet'

root = pathlib.Path('V2rayNG/app')
app = root / 'src/main'
(app / 'assets').mkdir(parents=True, exist_ok=True)
web = pathlib.Path('abinet/dist/index.html')
shutil.copy(web if web.exists() else 'abinet/index.html', app / 'assets/abinet.html')
print('UI:', 'fast offline build' if web.exists() else 'normal (CDN) build')
shutil.copy('abinet/AbinetActivity.kt', app / 'java/com/v2ray/ang/ui/AbinetActivity.kt')
(app / 'java/com/v2ray/ang/receiver').mkdir(parents=True, exist_ok=True)
(app / 'java/com/v2ray/ang/receiver/BluWidget3Provider.kt').write_text(BLU_WIDGET3_PROVIDER_KT, encoding='utf-8')

# --- منابع ویجت سه‌کانفیگی ---
(app / 'res/layout').mkdir(parents=True, exist_ok=True)
(app / 'res/layout/widget_blu3.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="8dp"
    android:background="#CC0B1220">
    <TextView android:id="@+id/blu_slot_0" style="@style/BluWidgetRow" android:text="1" />
    <TextView android:id="@+id/blu_slot_1" style="@style/BluWidgetRow" android:text="2" />
    <TextView android:id="@+id/blu_slot_2" style="@style/BluWidgetRow" android:text="3" />
</LinearLayout>
''', encoding='utf-8')
(app / 'res/values/blu_widget_styles.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="BluWidgetRow">
        <item name="android:layout_width">match_parent</item>
        <item name="android:layout_height">0dp</item>
        <item name="android:layout_weight">1</item>
        <item name="android:layout_marginBottom">4dp</item>
        <item name="android:gravity">center_vertical</item>
        <item name="android:paddingStart">14dp</item>
        <item name="android:paddingEnd">14dp</item>
        <item name="android:background">#330755D9</item>
        <item name="android:textColor">#FFFFFF</item>
        <item name="android:textSize">13sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:ellipsize">end</item>
        <item name="android:singleLine">true</item>
    </style>
</resources>
''', encoding='utf-8')
(app / 'res/xml/blu_widget3_info.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:initialLayout="@layout/widget_blu3"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen" />
''', encoding='utf-8')

# --- مانیفست: افزودن گیرنده‌ی ویجت ---
m2 = app / 'AndroidManifest.xml'
mx = m2.read_text(encoding='utf-8')
assert '</application>' in mx
widget_receiver = '''
        <receiver
            android:name=".receiver.BluWidget3Provider"
            android:exported="true">
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/blu_widget3_info" />
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
        </receiver>
    </application>'''
mx = mx.replace('</application>', widget_receiver, 1)
m2.write_text(mx, encoding='utf-8')

# --- مانیفست: صفحه‌ی اصلی = Blu net ---
m = app / 'AndroidManifest.xml'
t = m.read_text(encoding='utf-8')
s = t.index('android:name=".ui.main.MainActivity"')
a = t.rfind('<activity', 0, s)
b = t.index('</activity>', s) + len('</activity>')
blk = re.sub(r'\s*<category android:name="android\.intent\.category\.(LEANBACK_)?LAUNCHER" />', '', t[a:b])
new = f'''
        <activity
            android:name=".ui.AbinetActivity"
            android:exported="true"
            android:label="{APP_NAME}"
            android:launchMode="singleTask"
            android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''
m.write_text(t[:a] + blk + '\n' + new + t[b:], encoding='utf-8')

# --- اسم برنامه ---
for f in glob.glob(str(app / 'res/values*/strings.xml')):
    p = pathlib.Path(f)
    x = p.read_text(encoding='utf-8')
    y = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)', r'\g<1>' + APP_NAME + r'\2', x)
    if y != x: p.write_text(y, encoding='utf-8')

# --- شناسه‌ی اختصاصی (تا با v2rayNG اصلی قاطی نشود) ---
g = root / 'build.gradle.kts'
x = g.read_text(encoding='utf-8')
assert 'applicationId = "com.v2ray.ang"' in x, 'applicationId not found'
g.write_text(x.replace('applicationId = "com.v2ray.ang"', f'applicationId = "{APP_ID}"'), encoding='utf-8')

# شناسه عوض شد ولی هسته همچنان Xray است
u = app / 'java/com/v2ray/ang/util/Utils.kt'
x = u.read_text(encoding='utf-8')
y = x.replace('BuildConfig.APPLICATION_ID.startsWith("com.v2ray.ang")', 'true')
assert y != x, 'isXray not patched'
u.write_text(y, encoding='utf-8')

# --- آیکون اعلان: «B» سفید (فقط شکل، بدون متن) ---
STAT = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"
        android:pathData="M6,4H12A3.3,3.3 0 0 1 12,10.6A4.7,4.7 0 0 1 12,20H6Z M9.2,6.4H12A1.5,1.5 0 0 1 12,9.4H9.2Z M9.2,11.8H12A2.9,2.9 0 0 1 12,17.6H9.2Z"/>
</vector>
"""
for name in ('ic_stat_name', 'ic_stat_proxy', 'ic_stat_direct'):
    for f in glob.glob(str(app / f'res/drawable*/{name}.png')):
        pathlib.Path(f).unlink()
    (app / 'res/drawable').mkdir(exist_ok=True)
    (app / f'res/drawable/{name}.xml').write_text(STAT, encoding='utf-8')

# --- آیکون برنامه: اگر abinet/icon.png آپلود شده باشد ---
icon = pathlib.Path('abinet/icon.png')
if icon.exists():
    from PIL import Image, ImageDraw
    im = Image.open(icon).convert('RGBA')
    px = im.getpixel((2, 2))
    bg = '#%02X%02X%02X' % px[:3] if px[3] > 240 else '#0B1220'
    sizes = {'mdpi': (48, 108), 'hdpi': (72, 162), 'xhdpi': (96, 216), 'xxhdpi': (144, 324), 'xxxhdpi': (192, 432)}
    for d, (leg, fg) in sizes.items():
        folder = app / f'res/mipmap-{d}'
        folder.mkdir(parents=True, exist_ok=True)
        sq = im.resize((leg, leg), Image.LANCZOS)
        sq.save(folder / 'ic_launcher.png')
        mask = Image.new('L', (leg, leg), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, leg - 1, leg - 1), fill=255)
        rd = Image.new('RGBA', (leg, leg), (0, 0, 0, 0))
        rd.paste(sq, (0, 0), mask)
        rd.save(folder / 'ic_launcher_round.png')
        canvas = Image.new('RGBA', (fg, fg), (0, 0, 0, 0))
        inner = int(fg * 0.66)
        small = im.resize((inner, inner), Image.LANCZOS)
        canvas.paste(small, ((fg - inner) // 2, (fg - inner) // 2), small)
        canvas.save(folder / 'ic_launcher_foreground.png')
    (app / 'res/values/ic_launcher_background.xml').write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{bg}</color>\n</resources>\n', encoding='utf-8')
    ADAPT = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for n in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        (app / 'res/mipmap-anydpi-v26' / n).write_text(ADAPT, encoding='utf-8')
    print('custom app icon applied')

# --- حذف پیام «شروع خدمات» هنگام روشن شدن ---
lm = app / 'java/com/v2ray/ang/core/LauncherManager.kt'
x = lm.read_text(encoding='utf-8')
y = x.replace('context.toast(R.string.toast_services_start)', '// (toast removed)')
assert y != x, 'start toast not found'
lm.write_text(y, encoding='utf-8')

print('Blu net patch applied')
