# Build fitrahtube.com into landing/site/ (git-ignored). All page text lives in i18n.json
# (id -> {en, ar, nl}); edit it or index.src.html, then: python3 landing/build.py
# Publish with landing/deploy.sh. The zellige background <pattern> inside index.src.html was made
# with: python3 landing/zellige.py khatam 96 0.2
import html, json, pathlib, re, shutil
root = pathlib.Path(__file__).parent
STRINGS = json.loads((root / "i18n.json").read_text())
for key, value in STRINGS.items():
    assert set(value) == {"en", "ar", "nl"} and all(value.values()), f"{key} needs non-empty en, ar and nl"
src = (root / "index.src.html").read_text().replace("{{STRINGS}}", json.dumps(STRINGS, ensure_ascii=False, indent=2))

def fill(text):
    """Static fallback (no-JS, crawlers, thumbnails) = the English value of each id."""
    return re.sub(r"\{\{t:(\w+)\}\}", lambda m: html.escape(STRINGS[m.group(1)]["en"]), text)
# Store/chip links: bake LINKS into the static HTML so crawlers, link previews and no-JS visitors see
# the live links and labels too (the page's own script does the same at runtime).
LINKS = dict(re.findall(r'^\s*(\w+):\s*"([^"]+)"', src.split("const LINKS = {", 1)[1].split("};", 1)[0], re.M))
def bake(m):
    tag, key, live, inner = m.group(1), m.group(2), m.group(3), m.group(4)
    if key not in LINKS:
        return m.group(0)
    return f'{tag} href="{LINKS[key]}">' + inner.replace("{{t:comingSoon}}", "{{t:" + live + "}}", 1) + "</a>"
src = re.sub(r'(<a class="[^"]*" data-dl="(\w+)" data-live="(\w+)")>([\s\S]*?)</a>', bake, src)
assets = root / "assets"
top, body = src.split("<!--BODY-->\n")
meta = '''<meta name="description" content="{{t:meta}}">
<meta name="theme-color" content="#EFF4F2" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0F1512" media="(prefers-color-scheme: dark)">
<link rel="canonical" href="https://fitrahtube.com/">
<link rel="icon" type="image/png" sizes="32x32" href="favicon-32.png">
<link rel="apple-touch-icon" sizes="180x180" href="apple-touch-icon.png">
<meta property="og:type" content="website">
<meta property="og:site_name" content="FitrahTube">
<meta property="og:title" content="FitrahTube">
<meta property="og:description" content="{{t:meta}}">
<meta property="og:url" content="https://fitrahtube.com/">
<meta property="og:image" content="https://fitrahtube.com/og.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="The FitrahTube logo on green">
<meta name="twitter:card" content="summary_large_image">'''

s = root / "site"
shutil.rmtree(s, ignore_errors=True)
(s / "img").mkdir(parents=True)
head = top.replace("{{TITLE}}", "FitrahTube").replace("{{META}}", meta)
(s / "index.html").write_text(fill(
    '<!doctype html>\n<html lang="en" dir="ltr">\n<head>\n<meta charset="utf-8">\n'
    '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
    + head + "</head>\n<body>\n" + body + "</body>\n</html>\n"))
for f in ["home-en.jpg", "home-ar.jpg", "home-nl.jpg", "logo.png"]:
    shutil.copy(assets / f, s / "img" / f)
for f in ["favicon-32.png", "apple-touch-icon.png", "og.png"]:
    shutil.copy(assets / f, s / f)
(s / "robots.txt").write_text("User-agent: *\nAllow: /\n")
for p in sorted(s.rglob("*")):
    if p.is_file():
        print(f"{p.relative_to(s)}: {p.stat().st_size / 1024:.1f} KB")
