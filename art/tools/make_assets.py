#!/usr/bin/env python3
"""Builds every shipped image from the two sources in art/source/ (Pillow only, deterministic).

    python3 art/tools/make_assets.py

Outputs: Android adaptive icon (foreground, monochrome, background color), Android splash, iOS app icon and launch
logo, the in-app title banner and the README banner. The icon is re-drawn in pixel art (downscaled, palette
reduced, upscaled without smoothing), matching the pixel-art direction of the game and staying crisp at 1024 px.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "art" / "source"
LOGO = Image.open(SRC / "kysheon-logo.jpg").convert("RGB")
POSTER = Image.open(SRC / "kysheon-poster.jpg").convert("RGB")

# Measured on the sources (see art/README.md).
EMBLEM_BOX = (394, 72, 1014, 692)       # inside the logo's golden frame, square
EMBLEM_BG = (19, 20, 24)                # logo background inside the frame
POSTER_KEEP_HEIGHT = 632                # the subtitle starts at row 638: drop it
TITLE_BOX = (170, 20, 1240, 632)        # emblem + KYSHEON lettering in the poster
APP_BG = (11, 13, 22)                   # KColors.background (#0B0D16)

PIXEL_GRID = 96                         # icon resolution in "art pixels"
PALETTE = 28                            # colors kept in the pixel-art icon


def pixel_art(image: Image.Image, grid: int, colors: int) -> Image.Image:
    small = image.resize((grid, grid), Image.Resampling.LANCZOS)
    return small.quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert("RGB")


def upscale(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.NEAREST)


def on_canvas(content: Image.Image, canvas: int, fraction: float, background) -> Image.Image:
    """Centers [content] scaled to [fraction] of a square canvas (nearest neighbour: keeps the pixels crisp)."""
    side = round(canvas * fraction)
    side -= side % content.width if side >= content.width else 0
    scaled = content.resize((side, side), Image.Resampling.NEAREST)
    out = Image.new("RGBA", (canvas, canvas), background)
    out.paste(scaled, ((canvas - side) // 2, (canvas - side) // 2))
    return out


def monochrome(image: Image.Image) -> Image.Image:
    """Android 13 themed icon: the gold strokes as white on transparent."""
    gray = image.convert("L")
    mask = gray.point(lambda v: 255 if v > 70 else 0)
    out = Image.new("RGBA", image.size, (0, 0, 0, 0))
    out.paste((255, 255, 255, 255), mask=mask)
    return out


def without_background(image: Image.Image, threshold: int = 42) -> Image.Image:
    """Splash logos float on the splash color: the emblem's dark background becomes transparent."""
    rgba = image.convert("RGBA")
    alpha = image.convert("L").point(lambda v: 0 if v < threshold else 255)
    rgba.putalpha(alpha)
    return rgba


def fade_edges(image: Image.Image, color, margin: int) -> Image.Image:
    """Blends the borders into [color] so the image sits on the app background without a visible rectangle."""
    w, h = image.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rectangle((margin, margin, w - margin, h - margin), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(margin / 2))
    return Image.composite(image, Image.new("RGB", (w, h), color), mask)


def save(image: Image.Image, path: Path, **options) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, **options)
    print(f"{path.relative_to(ROOT)}  {image.size[0]}x{image.size[1]}")


def main() -> None:
    emblem = LOGO.crop(EMBLEM_BOX)
    icon = pixel_art(emblem, PIXEL_GRID, PALETTE)

    # Android adaptive icon (108 dp, 432 px at xxxhdpi): the art stays inside the 66 dp safe zone.
    res = ROOT / "androidApp" / "src" / "main" / "res"
    save(on_canvas(icon, 432, 0.70, EMBLEM_BG + (255,)), res / "drawable-nodpi" / "ic_launcher_foreground.png")
    save(on_canvas(monochrome(icon), 432, 0.70, (0, 0, 0, 0)), res / "drawable-nodpi" / "ic_launcher_monochrome.png")
    # Splash (Android 12+ shows it in a 240 dp circle; older versions center it on the window background).
    save(on_canvas(without_background(icon), 768, 0.55, (0, 0, 0, 0)), res / "drawable-nodpi" / "splash_logo.png")

    # iOS: one 1024 px icon, opaque, iOS rounds the corners itself.
    assets = ROOT / "iosApp" / "iosApp" / "Assets.xcassets"
    save(on_canvas(icon, 1024, 0.94, EMBLEM_BG + (255,)).convert("RGB"), assets / "AppIcon.appiconset" / "AppIcon.png")
    save(on_canvas(without_background(icon), 600, 1.0, (0, 0, 0, 0)), assets / "LaunchLogo.imageset" / "LaunchLogo.png")

    # In-app title (home screen) and README banner, from the poster without its subtitle.
    title = fade_edges(POSTER.crop(TITLE_BOX), APP_BG, margin=40)
    save(title.resize((900, round(900 * title.height / title.width)), Image.Resampling.LANCZOS),
         ROOT / "app" / "src" / "commonMain" / "composeResources" / "drawable" / "title_banner.jpg", quality=88)
    save(POSTER.crop((0, 0, POSTER.width, POSTER_KEEP_HEIGHT)), ROOT / "docs" / "images" / "kysheon-banner.jpg", quality=88)


if __name__ == "__main__":
    main()
