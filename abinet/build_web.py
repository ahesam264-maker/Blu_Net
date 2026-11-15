"""
ساخت نسخه‌ی سریع و آفلاین UI (اجرا در GitHub Actions، قبل از patch.py):
  - Tailwind از قبل کامپایل می‌شود (به‌جای کامپایل در مرورگر موبایل)
  - React / آیکون‌ها داخل یک فایل جمع می‌شوند (بدون CDN)
  - فونت Vazirmatn داخل برنامه می‌آید
اگر هر مرحله‌ای خطا بدهد، فایل abinet/dist/index.html ساخته نمی‌شود و بیلد با UI معمولی ادامه پیدا می‌کند.
"""
import base64, glob, pathlib, re, subprocess, sys

DRY = '--dry' in sys.argv
root = pathlib.Path('abinet')
work = root / 'web'
dist = root / 'dist'
work.mkdir(exist_ok=True)
dist.mkdir(exist_ok=True)
html = (root / 'index.html').read_text(encoding='utf-8')

def run(*cmd):
    print('+', ' '.join(cmd), flush=True)
    if DRY: return
    subprocess.run(list(cmd), cwd=work, check=True)

# ---------- بخش‌های صفحه ----------
mod = re.search(r'<script type="module">(.*?)</script>', html, re.S)
cfg = re.search(r'tailwind\.config\s*=\s*(\{.*?\});?\s*</script>', html, re.S)
sty = re.search(r'<style>(.*?)</style>', html, re.S)
assert mod and cfg and sty, 'page structure changed'

(work / 'app.js').write_text(mod.group(1), encoding='utf-8')
(work / 'template.html').write_text(html, encoding='utf-8')
(work / 'tailwind.config.js').write_text(
    "module.exports = Object.assign({ content: ['./template.html'] }, " + cfg.group(1) + ");\n", encoding='utf-8')
(work / 'input.css').write_text("@tailwind base;\n@tailwind components;\n@tailwind utilities;\n" + sty.group(1), encoding='utf-8')

# ---------- ابزارها ----------
run('npm', 'init', '-y')
run('npm', 'install', '--no-audit', '--no-fund', '--legacy-peer-deps',
    'react@19.2.0', 'react-dom@19.2.0', 'lucide-react@0.462.0', 'clsx@2.1.1', 'tailwind-merge@2.5.5',
    'esbuild@0.24.2', 'tailwindcss@3.4.17', 'vazirmatn')
run('npx', 'esbuild', 'app.js', '--bundle', '--minify', '--format=iife', '--target=es2019',
    '--define:process.env.NODE_ENV="production"', '--loader:.js=jsx', '--outfile=bundle.js')
run('npx', 'tailwindcss', '-c', 'tailwind.config.js', '-i', 'input.css', '-o', 'out.css', '--minify')

if DRY:
    (work / 'bundle.js').write_text('console.log("bundle");', encoding='utf-8')
    (work / 'out.css').write_text('.x{color:red}', encoding='utf-8')

bundle = (work / 'bundle.js').read_text(encoding='utf-8').replace('</script', '<\\/script')
css = (work / 'out.css').read_text(encoding='utf-8')

# ---------- فونت ----------
font_css = ''
fonts = glob.glob(str(work / 'node_modules/vazirmatn/**/Vazirmatn*Variable*.woff2'), recursive=True)
if fonts:
    b64 = base64.b64encode(open(sorted(fonts, key=len)[0], 'rb').read()).decode()
    font_css = ("@font-face{font-family:'Vazirmatn';font-style:normal;font-weight:100 900;font-display:swap;"
                "src:url(data:font/woff2;base64," + b64 + ") format('woff2');}\n")
    print('font embedded:', len(b64) // 1024, 'KB')
else:
    print('Vazirmatn font not found; using system font')

# ---------- مونتاژ ----------
out = html
out = re.sub(r'<link[^>]*fonts\.(?:googleapis|gstatic)\.com[^>]*>\s*', '', out)
out = out.replace('<script src="https://cdn.tailwindcss.com"></script>', '')
out = re.sub(r'<script>\s*tailwind\.config.*?</script>', '', out, flags=re.S)
out = re.sub(r'<script type="importmap">.*?</script>', '', out, flags=re.S)
out = re.sub(r'<style>.*?</style>', lambda m: '<style>' + font_css + css + '</style>', out, count=1, flags=re.S)
out = re.sub(r'<script type="module">.*?</script>', lambda m: '<script>' + bundle + '</script>', out, count=1, flags=re.S)

assert 'esm.sh' not in out and 'cdn.tailwindcss.com' not in out, 'external reference left'
assert 'id="root"' in out and 'bs-splash' in out
assert len(bundle) > 100 or DRY
(dist / 'index.html').write_text(out, encoding='utf-8')
print('dist/index.html written:', len(out) // 1024, 'KB')
