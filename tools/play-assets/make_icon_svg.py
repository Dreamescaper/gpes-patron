#!/usr/bin/env python3
"""Builds docs/play/assets/icon-store.svg (and icon-full.svg) from the app's adaptive-icon vector drawables, so the Play Store icon
is the same artwork as the launcher icon. Usage: python3 tools/play-assets/make_icon_svg.py  (run from the repository root)."""
import re
from pathlib import Path

RES = Path("app/src/main/res")
OUT = Path("docs/play/assets")


def attr(tag, name):
    m = re.search(r'android:%s="([^"]*)"' % name, tag)
    return m.group(1) if m else None


def color(v):
    if v is None or v.startswith("@"):
        return "none"
    v = v.lstrip("#")
    if len(v) == 8:  # AARRGGBB
        a, rgb = int(v[:2], 16) / 255, v[2:]
        return "#" + rgb if a == 1 else "#%s" % rgb
    return "#" + v


def vector_to_svg_body(xml):
    group = re.search(r"<group([^>]*)>", xml)
    paths = []
    for m in re.finditer(r"<path\b[^>]*?/>", xml, re.S):
        t = m.group(0)
        fill, stroke = color(attr(t, "fillColor")), color(attr(t, "strokeColor"))
        d = " ".join(attr(t, "pathData").split())
        extra = ""
        if stroke != "none":
            extra = ' stroke="%s" stroke-width="%s" stroke-linecap="%s" stroke-linejoin="%s"' % (
                stroke, attr(t, "strokeWidth"), attr(t, "strokeLineCap") or "butt", attr(t, "strokeLineJoin") or "miter")
        rule = ' fill-rule="evenodd"' if attr(t, "fillType") == "evenOdd" else ""
        paths.append('<path d="%s" fill="%s"%s%s/>' % (d, fill, rule, extra))
    body = "\n  ".join(paths)
    if group:
        g = group.group(1)
        s, px, py = attr(g, "scaleX"), attr(g, "pivotX"), attr(g, "pivotY")
        body = '<g transform="translate(%s %s) scale(%s) translate(-%s -%s)">\n  %s\n  </g>' % (px, py, s, px, py, body)
    return body


bg = re.search(r'name="ic_launcher_background">([^<]*)<', (RES / "values/colors.xml").read_text()).group(1)
art = vector_to_svg_body((RES / "drawable/ic_launcher_foreground.xml").read_text())
OUT.mkdir(parents=True, exist_ok=True)
# The feature graphic wants the dog at one fixed size whatever the launcher scaling is, so its crop follows the group's scale
# (0.82 was the size when the graphic was designed): a tighter window around the centre makes the artwork look as before.
scale = float(re.search(r'android:scaleX="([^"]*)"', (RES / "drawable/ic_launcher_foreground.xml").read_text()).group(1))
half = 84 * scale / 0.82 / 2
feature_box = "%.2f %.2f %.2f %.2f" % (54 - half, 54 - half, 2 * half, 2 * half)
for name, box in (("icon-full.svg", "0 0 108 108"), ("icon-store.svg", "12 12 84 84"), ("icon-feature.svg", feature_box)):
    (OUT / name).write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="%s">\n  <rect x="0" y="0" width="108" height="108" fill="%s"/>\n  %s\n</svg>\n' % (box, bg, art))
    print("wrote", OUT / name)
