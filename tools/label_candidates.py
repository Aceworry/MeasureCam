"""给 Logo 候选图加编号，并在下面附一行小尺寸实拍效果。

挑图标时最容易踩的坑是：大图上很好看，缩到桌面上糊成一团。
所以这张图除了编号，还把每个方案压到 48px 排了一行，模拟真实桌面大小。
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

SHEET = "logo-candidates/App_icon_design_sheet__exactly_2026-10-29T12-09-51.png"
OUT = "logo-candidates/候选编号.png"

# 6 个图标在 1536x1024 上的中心位置（2 行 3 列）
CENTERS = [(294, 260), (768, 260), (1243, 260), (294, 707), (768, 707), (1243, 707)]
CELL = 340  # 图标大致边长，用来裁小图


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in ("C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def main() -> None:
    im = Image.open(SHEET).convert("RGB")
    W, H = im.size

    # 底部留一条放小尺寸预览
    strip_h = 150
    canvas = Image.new("RGB", (W, H + strip_h), (245, 246, 245))
    canvas.paste(im, (0, 0))
    d = ImageDraw.Draw(canvas)

    num_font = load_font(46)
    tip_font = load_font(22)

    for i, (cx, cy) in enumerate(CENTERS, start=1):
        # 编号圆标：压在图标右下角，不挡设计
        bx, by, r = cx + 128, cy + 128, 34
        d.ellipse((bx - r, by - r, bx + r, by + r), fill=(46, 158, 114))
        d.ellipse((bx - r, by - r, bx + r, by + r), outline=(255, 255, 255), width=4)
        t = str(i)
        bb = d.textbbox((0, 0), t, font=num_font)
        d.text(
            (bx - (bb[2] - bb[0]) / 2, by - (bb[3] - bb[1]) / 2 - bb[1]),
            t, font=num_font, fill=(255, 255, 255),
        )

        # 小尺寸实拍：裁一块压到 48px，再放大回去看重采样后的样子
        box = (cx - CELL // 2, cy - CELL // 2, cx + CELL // 2, cy + CELL // 2)
        crop = im.crop(box)
        small = crop.resize((48, 48), Image.LANCZOS).resize((96, 96), Image.NEAREST)
        # 左侧留 300px 放说明文字，剩下的宽度平均分给 6 个缩略图
        slot = (W - 300) / 6
        px = int(300 + (i - 0.5) * slot - 48)
        canvas.paste(small, (px, H + 30))

    d.text((24, H + 44), "48px 实际大小", font=tip_font, fill=(120, 126, 122))
    d.text((24, H + 76), "缩到桌面还认得出，才算合格", font=tip_font, fill=(160, 166, 162))

    canvas.save(OUT)
    print("写出", OUT, canvas.size)


if __name__ == "__main__":
    main()
