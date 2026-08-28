#!/usr/bin/env python3
"""生成应用图标：微信绿圆角方块 + 白色「接听」大字 + 小听筒角标"""
from PIL import Image, ImageDraw, ImageFont
import math
import os

SRC_FONT = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(BASE, "app", "src", "main", "res")

S = 512  # 基准画布

def make_icon():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 微信绿圆角矩形背景
    d.rounded_rectangle([12, 12, S - 12, S - 12], radius=112, fill=(7, 193, 96, 255))

    # 背景装饰：右下角淡淡的白色电话弧线
    deco = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dd = ImageDraw.Draw(deco)
    bbox = (170, 210, 470, 510)
    dd.arc(bbox, start=150, end=300, fill=(255, 255, 255, 60), width=70)
    r = 40
    for ang in (150, 300):
        a = math.radians(ang)
        cx = (170 + 470) / 2 + (470 - 170) / 2 * math.cos(a)
        cy = (210 + 510) / 2 + (510 - 210) / 2 * math.sin(a)
        dd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 60))
    img = Image.alpha_composite(img, deco)

    # 白色「接」+「听」两个字（大号）
    font = ImageFont.truetype(SRC_FONT, 210)
    d = ImageDraw.Draw(img)
    for i, ch in enumerate("接听"):
        bbox2 = d.textbbox((0, 0), ch, font=font)
        w = bbox2[2] - bbox2[0]
        h = bbox2[3] - bbox2[1]
        x = 64 + i * 200 - bbox2[0]
        y = 130 - bbox2[1]
        d.text((x, y), ch, font=font, fill=(255, 255, 255, 255))

    return img

def make_preview():
    """生成一张「来电大按钮界面」的效果预览图"""
    W, H = 720, 1520
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    f_small = ImageFont.truetype(SRC_FONT, 44)
    f_big = ImageFont.truetype(SRC_FONT, 96)
    f_mid = ImageFont.truetype(SRC_FONT, 52)
    f_btn = ImageFont.truetype(SRC_FONT, 100)
    f_hint = ImageFont.truetype(SRC_FONT, 46)
    f_auto = ImageFont.truetype(SRC_FONT, 50)

    def center_text(y, s, font, fill):
        bbox = d.textbbox((0, 0), s, font=font)
        d.text(((W - (bbox[2] - bbox[0])) / 2 - bbox[0], y), s, font=font, fill=fill)

    center_text(110, "微信来电", f_small, (160, 160, 160))
    center_text(200, "大儿子", f_big, (255, 255, 255))
    center_text(340, "视频通话", f_mid, (7, 193, 96))

    # 绿色大接听按钮
    cx, cy, r = W // 2, 780, 230
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(7, 193, 96))
    bbox = d.textbbox((0, 0), "接听", font=f_btn)
    d.text((cx - (bbox[2] - bbox[0]) / 2 - bbox[0], cy - (bbox[3] - bbox[1]) / 2 - bbox[1]),
           "接听", font=f_btn, fill=(255, 255, 255))
    center_text(1050, "点这个绿色大按钮接听", f_hint, (255, 255, 255))

    # 黄色自动接听倒计时
    center_text(1160, "8 秒后自动接听", f_auto, (255, 213, 79))

    # 红色挂断按钮（较小）
    cx, cy, r = W // 2, 1360, 120
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(250, 81, 81))
    bbox = d.textbbox((0, 0), "挂断", font=f_mid)
    d.text((cx - (bbox[2] - bbox[0]) / 2 - bbox[0], cy - (bbox[3] - bbox[1]) / 2 - bbox[1]),
           "挂断", font=f_mid, fill=(255, 255, 255))
    return img

if __name__ == "__main__":
    icon = make_icon()
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        out = os.path.join(RES, "mipmap-%s" % dpi, "ic_launcher.png")
        icon.resize((px, px), Image.LANCZOS).save(out)
        print("saved", out)

    preview = make_preview()
    out = os.path.join(BASE, "docs", "preview-call-alert.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    preview.save(out)
    print("saved", out)
