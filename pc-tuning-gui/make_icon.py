#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Genereert het Twizy Pit Pro app-icoon: Windows .ico + Android mipmaps."""
import os, math
from PIL import Image, ImageDraw

S = 4                      # supersampling
SZ = 1024                  # master-formaat
W = SZ * S

BG_TOP   = (13, 17, 23)    # #0d1117
BG_BOT   = (31, 38, 48)    # #1f2630
ACCENT   = (242, 183, 5)   # pit-geel
TEAL     = (45, 212, 191)  # #2dd4bf
DARK     = (5, 8, 12)

def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m

def build_master():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)

    # verticale gradient-achtergrond
    for y in range(W):
        t = y / W
        r = int(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t)
        g = int(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t)
        b = int(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t)
        dr.line([(0, y), (W, y)], fill=(r, g, b, 255))

    # ronde-vierkant masker toepassen
    mask = rounded_mask(W, int(180 * S))
    bg = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    bg.paste(img, (0, 0), mask)
    img = bg
    dr = ImageDraw.Draw(img)

    cx, cy = W // 2, int(W * 0.47)

    # snelheidsmeter-boog (teal), opening onderaan
    R = int(W * 0.34)
    bw = int(46 * S)
    box = [cx - R, cy - R, cx + R, cy + R]
    dr.arc(box, start=120, end=60, fill=(TEAL[0], TEAL[1], TEAL[2], 235), width=bw)
    # subtiele binnen-arc
    R2 = int(W * 0.34) - bw - int(10 * S)
    dr.arc([cx - R2, cy - R2, cx + R2, cy + R2], start=120, end=60,
           fill=(TEAL[0], TEAL[1], TEAL[2], 60), width=int(6 * S))

    # bliksemschicht (geel) — EV/performance
    def P(x, y): return (int(cx + x * S), int(cy + y * S))
    bolt = [P(60, -230), P(-110, 40), P(10, 40), P(-70, 250),
            P(150, -40), P(30, -40), P(120, -230)]
    dr.polygon(bolt, fill=(ACCENT[0], ACCENT[1], ACCENT[2], 255))

    # geboorde rand op de bliksem voor contrast
    dr.line(bolt + [bolt[0]], fill=(DARK[0], DARK[1], DARK[2], 90), width=int(3 * S), joint="curve")

    # geruite pit-strip onderin
    strip_y = int(W * 0.80)
    sq = int(W * 0.052)
    n = W // sq + 1
    for row in range(2):
        for i in range(n):
            if (i + row) % 2 == 0:
                x0 = i * sq
                y0 = strip_y + row * sq
                dr.rectangle([x0, y0, x0 + sq, y0 + sq],
                             fill=(230, 237, 243, 255))
    # strip binnen ronde vorm houden
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    img = out

    master = img.resize((SZ, SZ), Image.LANCZOS)
    return master

def circular(im):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
    r = im.copy(); r.putalpha(m); return r

def main():
    base = os.path.dirname(os.path.abspath(__file__))
    icon_dir = os.path.join(base, "icon")
    os.makedirs(icon_dir, exist_ok=True)
    master = build_master()
    master.save(os.path.join(icon_dir, "twizy_pitpro_1024.png"))

    # Windows .ico (multi-size)
    ico_path = os.path.join(base, "twizy_pitpro.ico")
    master.save(ico_path, sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                 (64, 64), (128, 128), (256, 256)])
    # los 256 PNG (handig voor snelkoppeling)
    master.resize((256, 256), Image.LANCZOS).save(os.path.join(icon_dir, "twizy_pitpro_256.png"))

    # Android mipmaps
    res = r"C:\Users\peter\Downloads\twizy-diag\twizy-diag\android\app\src\main\res"
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    if os.path.isdir(res):
        for d, px in dens.items():
            folder = os.path.join(res, f"mipmap-{d}")
            os.makedirs(folder, exist_ok=True)
            sq = master.resize((px, px), Image.LANCZOS)
            sq.save(os.path.join(folder, "ic_launcher.png"))
            circular(sq).save(os.path.join(folder, "ic_launcher_round.png"))
        print("ANDROID_MIPMAPS_OK")
    else:
        print("ANDROID_RES_NOT_FOUND")
    print("ICO_OK", ico_path)

if __name__ == "__main__":
    main()
