#!/usr/bin/env python3
"""Design preview of the shipped art/orbit geometry; NOT an Android screen recording.

Requires Pillow. Run --out-dir target/splash-design; --animate also exports a
full seamless 12-second loop. Output stays outside Git by default.
"""
import argparse
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/components"
TEXT = (COMPONENTS / "SplashOrbitGeometry.kt").read_text()
ORBIT_RE = r"Orbit\(radius = ([\d.]+), tilt = ([\d.]+), roll = (-?[\d.]+), phaseOffset = ([\d.]+), turns = (-?\d+)\)"
ORBITS = [tuple(map(float, values)) for values in re.findall(ORBIT_RE, TEXT)]
assert len(ORBITS) == 3
NAVY = (1, 10, 22, 255)
GOLD = (228, 180, 90)
CORE_RADIUS = 0.15


def project(orbit, cycle, offset=0):
    radius, tilt, roll, phase, turns = orbit
    angle = 2 * math.pi * (cycle * turns + phase + offset)
    x = radius * math.cos(angle)
    y = radius * math.sin(angle) * math.cos(tilt)
    z = radius * math.sin(angle) * math.sin(tilt)
    scale = 2 / (2 - z)
    return (x * math.cos(roll) - y * math.sin(roll)) * scale, (x * math.sin(roll) + y * math.cos(roll)) * scale, z, scale


def visible(point):
    return not (point[2] < 0 and math.hypot(point[0], point[1]) < CORE_RADIUS)


def font(size, bold=False):
    path = '/usr/share/fonts/truetype/dejavu/DejaVuSans' + ('-Bold' if bold else '') + '.ttf'
    return ImageFont.truetype(path, round(size))


def make_base(scale):
    width, height = round(360 * scale), round(800 * scale)
    canvas = Image.new('RGBA', (width, height), NAVY)
    hero = min(360 - 32, 800 - 188, 520) * scale
    hero = round(hero)
    left = round((width - hero) / 2)
    top = round((height - hero) / 2 + 4 * scale)
    art = Image.open(ROOT / 'android-app/app/src/main/res/drawable-nodpi/splash_digital_core.webp').convert('RGBA')
    art = art.resize((hero, hero), Image.Resampling.LANCZOS)
    # Same 5% navy feather as the native scene, without throwing away image detail.
    mask = Image.new('L', (hero, hero))
    feather = hero * .05
    mask.putdata([round(255 * min(1, min(x, y, hero - 1 - x, hero - 1 - y) / feather)) for y in range(hero) for x in range(hero)])
    canvas.paste(art, (left, top), mask)
    draw = ImageDraw.Draw(canvas)
    draw.text((width / 2, 78 * scale), 'A P U', font=font(32 * scale, True), fill=(245, 232, 201), anchor='mm')
    draw.text((width / 2, 116 * scale), 'Цифровое ядро связи', font=font(13 * scale), fill=(145, 168, 197), anchor='mm')
    draw.text((width / 2, height - 92 * scale), 'Запуск цифрового ядра…', font=font(16 * scale, True), fill=GOLD, anchor='mm')
    draw.text((width / 2, height - 66 * scale), 'APU · сеть участников', font=font(12 * scale), fill=(145, 168, 197), anchor='mm')
    center = (left + hero / 2, top + hero / 2)
    curves = Image.new('RGBA', canvas.size)
    pen = ImageDraw.Draw(curves)
    for orbit in ORBITS:
        last = None
        for index in range(161):
            point = project(orbit, index / 160 / abs(orbit[4]))
            pos = center[0] + point[0] * hero, center[1] + point[1] * hero
            if visible(point):
                if last is not None:
                    pen.line([last, pos], fill=(*GOLD, 56), width=max(1, round(.7 * scale)))
                last = pos
            else:
                last = None
    canvas = Image.alpha_composite(canvas, curves)
    return canvas, hero, center


def frame(base, hero, center, cycle, scale):
    layer = Image.new('RGBA', base.size)
    draw = ImageDraw.Draw(layer)
    points = sorted((project(orbit, cycle, offset) for orbit in ORBITS for offset in (0, .5)), key=lambda p: p[2])
    for point in points:
        if not visible(point):
            continue
        x, y = center[0] + point[0] * hero, center[1] + point[1] * hero
        radius = 3.6 * scale * point[3]
        for ring in range(10, 0, -1):
            r = radius * (1 + 1.2 * ring / 10)
            draw.ellipse((x-r, y-r, x+r, y+r), fill=(*GOLD, round(8 * (1 - ring / 11))))
        # Analytic shading for the hard-surface spherical electron.
        min_x, max_x = math.floor(x-radius), math.ceil(x+radius)
        min_y, max_y = math.floor(y-radius), math.ceil(y+radius)
        highlight_x, highlight_y = x - radius * .34, y - radius * .34
        colours = [(255, 248, 217), (243, 206, 120), (117, 71, 22)]
        for py in range(min_y, max_y + 1):
            for px in range(min_x, max_x + 1):
                if math.hypot(px-x, py-y) > radius:
                    continue
                t = min(1, math.hypot(px-highlight_x, py-highlight_y) / (radius * 1.6))
                a, b = (colours[0], colours[1]) if t < .5 else (colours[1], colours[2])
                mix = t * 2 if t < .5 else (t - .5) * 2
                layer.putpixel((px, py), tuple(round(v + (w-v) * mix) for v, w in zip(a, b)) + (255,))
        hr = radius * .2
        hx, hy = x-radius*.3, y-radius*.3
        draw.ellipse((hx-hr, hy-hr, hx+hr, hy+hr), fill=(255, 255, 255, 230))
    return Image.alpha_composite(base, layer).convert('RGB')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out-dir', default='target/splash-design')
    parser.add_argument('--animate', action='store_true')
    args = parser.parse_args()
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    base, hero, center = make_base(3)
    frame(base, hero, center, .08, 3).save(out / 'splash-preview.png')
    if args.animate:
        base, hero, center = make_base(1)
        palette = base.convert('P', palette=Image.Palette.ADAPTIVE, colors=256)
        frames = [frame(base, hero, center, n / 240, 1).quantize(palette=palette, dither=Image.Dither.NONE) for n in range(240)]
        frames[0].save(out / 'splash-preview.gif', save_all=True, append_images=frames[1:], duration=50, loop=0, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
