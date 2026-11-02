"""把生成的图标整理成 Android 可用的资源。

做三件事：
1. 白底抠成透明（带抗锯齿羽化，别留白边）
2. 裁到内容边界后，缩进 adaptive icon 的 66dp 安全区，避免被圆角裁掉
3. 输出各密度 legacy PNG + 432px 的 adaptive 前景

用法：python tools/make_icons.py <源图> <res目录>
"""
import sys
from pathlib import Path
from PIL import Image

# adaptive icon 的规范：108dp 画布里，安全区是中心 66dp
CANVAS = 432          # xxxhdpi 的 108dp
SAFE_RATIO = 0.66     # 内容占画布比例，留出被裁的余量

# legacy launcher icon 各密度
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def white_to_alpha(img: Image.Image) -> Image.Image:
    """只抠「与边缘连通」的白色，图形内部的白色（刻度带、中心孔）要留住。

    直接按亮度阈值抠会把光圈叶片之间、刻度环内侧的白全打成透明，
    放到深色桌面上图标就碎了。所以从四角灌水，只吃外部背景。
    """
    from PIL import ImageDraw

    rgb = img.convert("RGB")
    w, h = rgb.size
    KEY = (255, 0, 255)  # 拿品红当标记色，图里不可能出现

    # 四角都灌一次，防止主体贴边导致单点灌不满
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        ImageDraw.floodfill(rgb, seed, KEY, thresh=90)

    out = rgb.convert("RGBA")
    px = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if (r, g, b) == KEY:
                px[x, y] = (255, 255, 255, 0)

    # 中心孔是封闭的纯白，单独再抠一次，让光圈中间透出来
    if px[w // 2, h // 2][:3] == (255, 255, 255):
        ImageDraw.floodfill(rgb, (w // 2, h // 2), KEY, thresh=90)
        out = rgb.convert("RGBA")
        px = out.load()
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                if (r, g, b) == KEY:
                    px[x, y] = (255, 255, 255, 0)
    return out


def trim(img: Image.Image) -> Image.Image:
    box = img.getbbox()
    return img.crop(box) if box else img


def fit_center(img: Image.Image, canvas: int, ratio: float) -> Image.Image:
    """等比缩放到 canvas*ratio，居中贴到 canvas 的透明画布上。"""
    target = int(canvas * ratio)
    w, h = img.size
    scale = target / max(w, h)
    resized = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(
        resized,
        ((canvas - resized.width) // 2, (canvas - resized.height) // 2),
        resized,
    )
    return out


def main() -> None:
    src = Path(sys.argv[1])
    res = Path(sys.argv[2])

    art = trim(white_to_alpha(Image.open(src)))  # 去白底
    print(f"内容尺寸：{art.size}")

    # 1) adaptive 前景：108dp 画布，内容占 66%
    fg = fit_center(art, CANVAS, SAFE_RATIO)
    fg_dir = res / "drawable-xxxhdpi"
    fg_dir.mkdir(parents=True, exist_ok=True)
    fg.save(fg_dir / "ic_launcher_foreground.png")
    print("写出", fg_dir / "ic_launcher_foreground.png")

    # 2) legacy 图标：白底 + 内容占 82%（老图标没有裁切，可以铺满一点）
    for name, size in DENSITIES.items():
        d = res / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        plate = Image.new("RGBA", (size, size), (255, 255, 255, 255))
        inner = fit_center(art, size, 0.82)
        plate.paste(inner, (0, 0), inner)
        plate.save(d / "ic_launcher.png")
        # round 版：圆形裁切
        mask = Image.new("L", (size, size), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        round_plate = plate.copy()
        round_plate.putalpha(mask)
        round_plate.save(d / "ic_launcher_round.png")
        print(f"写出 mipmap-{name} {size}px")

    # 3) 预览图：把成品在白底和深底各放一次，方便肉眼确认
    preview = Image.new("RGBA", (CANVAS * 2 + 72, CANVAS + 48), (250, 250, 250, 255))
    from PIL import ImageDraw
    d = ImageDraw.Draw(preview)
    d.rectangle((0, 0, CANVAS + 24, CANVAS + 48), fill=(255, 255, 255, 255))
    d.rectangle((CANVAS + 48, 0, CANVAS * 2 + 72, CANVAS + 48), fill=(28, 31, 30, 255))
    preview.paste(fg, (24, 24), fg)

    # 深底上给个绿色底盘，模拟实际桌面
    plate2 = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    dd = ImageDraw.Draw(plate2)
    dd.ellipse((0, 0, CANVAS - 1, CANVAS - 1), fill=(46, 158, 114, 255))
    white_art = fit_center(art, CANVAS, SAFE_RATIO)
    preview.paste(white_art, (CANVAS + 72, 24), white_art)
    preview.save(Path("logo-candidates") / "preview.png")
    print("写出 logo-candidates/preview.png")


if __name__ == "__main__":
    main()
