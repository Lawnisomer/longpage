"""Create a browser preview directly from the shipping Android vector paths."""
from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / 'app/src/main/res'
out = root / 'build/branding'
out.mkdir(parents=True, exist_ok=True)
android = '{http://schemas.android.com/apk/res/android}'

def paths(filename, ink=None):
    vector = ET.parse(res / 'drawable' / filename).getroot()
    result = []
    for path in vector.findall('path'):
        attrs = {'d': path.get(android + 'pathData')}
        for native, svg in [('fillColor', 'fill'), ('strokeColor', 'stroke'),
                            ('strokeWidth', 'stroke-width'), ('strokeLineCap', 'stroke-linecap'),
                            ('strokeLineJoin', 'stroke-linejoin'), ('fillType', 'fill-rule')]:
            value = path.get(android + native)
            if value is None:
                continue
            if value == '#00000000':
                value = 'none'
            elif value == '#FFFFFF' and ink:
                value = ink
            elif value == 'evenOdd':
                value = 'evenodd'
            attrs[svg] = value
        result.append('<path ' + ' '.join(f'{key}="{escape(value)}"' for key, value in attrs.items()) + '/>')
    return ''.join(result)

foreground = paths('ic_launcher_foreground.xml')
monochrome = paths('ic_launcher_foreground.xml', '#111111')
tile = paths('ic_longpage_tile.xml', '#111111')

def icon(identity, shape='round', size=192, themed=False):
    mask = ('<circle cx="54" cy="54" r="36"/>' if shape == 'circle' else
            '<rect x="18" y="18" width="72" height="72" rx="18"/>')
    fill = '#E5E5E5' if themed else '#111111'
    art = monochrome if themed else foreground
    return (f'<svg width="{size}" height="{size}" viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg">'
            f'<defs><clipPath id="{identity}">{mask}</clipPath></defs><g clip-path="url(#{identity})">'
            f'<rect width="108" height="108" fill="{fill}"/>{art}</g></svg>')

cards = ''.join(f'<article>{icon(key, shape, themed=theme)}<h2>{label}</h2><p>{caption}</p></article>'
                for key, shape, theme, label, caption in [
                    ('circle', 'circle', False, '圆形', '自适应桌面图标'),
                    ('round', 'round', False, '圆角方形', '自适应桌面图标'),
                    ('mono', 'round', True, '主题单色', '透明镂空，随系统着色')])
small = (icon('small48', size=48) + '<span>48 px</span>' + icon('small24', size=24) + '<span>24 px</span>'
         + f'<svg width="24" height="24" viewBox="0 0 24 24">{tile}</svg><span>快捷设置 · 24 dp</span>')
html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>长页 · 图标预览</title>
<style>*{box-sizing:border-box}body{margin:0;background:#F6F6F3;color:#111;font-family:"Microsoft YaHei",sans-serif}
main{width:1120px;height:790px;padding:48px 64px}header{display:flex;align-items:flex-start;justify-content:space-between}
h1{font-size:42px;letter-spacing:3px;margin:0 0 12px;font-weight:500}header p{font-size:18px;color:#626262;margin:0}
.version{font-size:12px;letter-spacing:2px;margin-top:12px;color:#777}.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:24px;margin-top:42px}
article{text-align:center;background:#FFF;border:1px solid #E4E4E0;border-radius:16px;padding:32px 12px 24px}
h2{font-size:17px;font-weight:500;margin:22px 0 8px}article p{font-size:13px;color:#777;margin:0}
.small{display:flex;align-items:center;gap:20px;border-top:1px solid #DCDCD6;margin-top:34px;padding-top:26px}
.small span{font-size:13px;color:#666;margin-right:36px}.footer{margin-top:28px;font-size:13px;color:#777;letter-spacing:1px}</style>
<main><header><div><h1>长页</h1><p>打开想要留存的竖向长页。</p></div><div class="version">ICON / 0.1.3</div></header>
<section class="cards">CARDS</section><section class="small">SMALL</section>
<p class="footer">黑白几何 · 纵向纸页与取景边角 · 由应用原生矢量资源生成</p></main></html>'''
(out / 'icon-preview.html').write_text(html.replace('CARDS', cards).replace('SMALL', small), encoding='utf-8')
(out / 'longpage-icon.svg').write_text(icon('export', size=512), encoding='utf-8')
print(out / 'icon-preview.html')
