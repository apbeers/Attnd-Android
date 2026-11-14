#!/usr/bin/env python3
"""
Turn raw emulator screenshots into Play Store listing images.

    compose_screenshots.py --raw build/screenshots/raw --captions store/en-US/captions.txt --out build/store

Writes, for play_publish.py --listing:
    <out>/phoneScreenshots/NN_<screen>.png   2160×3840 RGB: brand gradient, caption, framed screenshot
    <out>/featureGraphic/feature_graphic.png 1024×500 RGB: gradient, icon, "Attnd" wordmark, tagline, map crop
    <out>/icon/icon.png                      512×512 32-bit: the launcher logo on the brand purple

Captions come from captions.txt (`screen=caption`, one per line, in listing order); each needs
<raw>/<screen>.png. Requires Pillow.
"""

import argparse
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

BRAND_DARK = (0x43, 0x1F, 0x86)
BRAND_LIGHT = (0x7B, 0x4F, 0xD6)
BEZEL = (0x1A, 0x10, 0x2E)
WHITE = (255, 255, 255)

SCREENSHOT_SIZE = (2160, 3840)  # 9:16, Play's maximum side
FEATURE_SIZE = (1024, 500)
ICON_SIZE = 512
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024

FONT_CANDIDATES = [
    '/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf',
    '/usr/share/fonts/opentype/noto/NotoSans-Bold.otf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf',
    '/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf',
]
FONT_REGULAR_CANDIDATES = [
    '/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf',
    '/usr/share/fonts/opentype/noto/NotoSans-Regular.otf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
]


def font(size, bold=True):
    for path in FONT_CANDIDATES if bold else FONT_REGULAR_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    print(f'warning: no TrueType font found, using Pillow\'s default at {size}px', file=sys.stderr)
    return ImageFont.load_default(size)


def gradient(size, start, end, horizontal=False):
    """A linear gradient from [start] to [end], top→bottom (or left→right)."""
    ramp = Image.linear_gradient('L')  # 256×256, black at the top
    if horizontal:
        ramp = ramp.rotate(90)  # counter-clockwise: black on the left
    mask = ramp.resize(size, Image.Resampling.BICUBIC)
    return Image.composite(Image.new('RGB', size, end), Image.new('RGB', size, start), mask)


def glow(canvas, center, radius, opacity):
    """A soft white light spot, for depth behind the caption or the wordmark."""
    layer = Image.new('L', canvas.size, 0)
    x, y = center
    ImageDraw.Draw(layer).ellipse((x - radius, y - radius, x + radius, y + radius), fill=int(255 * opacity))
    layer = layer.filter(ImageFilter.GaussianBlur(radius // 2))
    canvas.paste(Image.new('RGB', canvas.size, WHITE), (0, 0), layer)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def drop_shadow(canvas, box, radius, offset, blur, opacity):
    shadow = Image.new('L', canvas.size, 0)
    x0, y0, x1, y1 = box
    ImageDraw.Draw(shadow).rounded_rectangle(
        (x0, y0 + offset, x1, y1 + offset), radius=radius, fill=int(255 * opacity))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.paste(Image.new('RGB', canvas.size, (0x12, 0x06, 0x2A)), (0, 0), shadow)


def framed(screen, width):
    """[screen] scaled to [width] (bezel included) with a thin dark bezel and rounded corners: (RGB, mask)."""
    bezel = max(8, width // 70)
    inner_w = width - 2 * bezel
    inner_h = round(screen.height * inner_w / screen.width)
    inner = screen.convert('RGB').resize((inner_w, inner_h), Image.Resampling.LANCZOS)
    outer_radius = width // 11
    inner_radius = outer_radius - bezel
    frame = Image.new('RGB', (width, inner_h + 2 * bezel), BEZEL)
    frame.paste(inner, (bezel, bezel), rounded_mask(inner.size, inner_radius))
    return frame, rounded_mask(frame.size, outer_radius)


def wrap(draw, text, fnt, max_width):
    """Greedy word wrap: the lines of [text] that fit [max_width] in [fnt]."""
    lines, line = [], ''
    for word in text.split():
        candidate = f'{line} {word}'.strip()
        if not line or draw.textlength(candidate, font=fnt) <= max_width:
            line = candidate
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def balanced(draw, text, fnt, max_width):
    """Two-line captions split near the middle instead of leaving one orphaned word."""
    lines = wrap(draw, text, fnt, max_width)
    if len(lines) != 2:
        return lines
    words = text.split()
    best = lines
    for i in range(1, len(words)):
        first, second = ' '.join(words[:i]), ' '.join(words[i:])
        widths = [draw.textlength(first, font=fnt), draw.textlength(second, font=fnt)]
        if max(widths) <= max_width:
            spread = abs(widths[0] - widths[1])
            best_widths = [draw.textlength(line, font=fnt) for line in best]
            if spread < abs(best_widths[0] - best_widths[1]):
                best = [first, second]
    return best


def caption_layout(draw, text, max_width, start_size, min_size):
    """The largest font size (down to [min_size]) at which [text] fits in two lines."""
    size = start_size
    while True:
        fnt = font(size)
        lines = balanced(draw, text, fnt, max_width)
        if len(lines) <= 2 and all(draw.textlength(line, font=fnt) <= max_width for line in lines):
            return fnt, lines
        if size <= min_size:
            sys.exit(f'Caption too long for two lines: "{text}" (shorten it in captions.txt)')
        size -= 6


def draw_centered_lines(draw, lines, fnt, center_x, top, line_gap, fill):
    ascent, descent = fnt.getmetrics()
    line_height = ascent + descent
    for i, line in enumerate(lines):
        draw.text((center_x, top + i * (line_height + line_gap)), line, font=fnt, fill=fill, anchor='ma')
    return len(lines) * line_height + (len(lines) - 1) * line_gap


def phone_screenshot(raw, caption):
    width, height = SCREENSHOT_SIZE
    canvas = gradient(SCREENSHOT_SIZE, BRAND_DARK, BRAND_LIGHT)
    glow(canvas, (width // 2, 420), 900, 0.10)
    draw = ImageDraw.Draw(canvas)

    # Caption block: up to two bold white lines, vertically centred in the top band.
    band_top, band_bottom = 140, 720
    fnt, lines = caption_layout(draw, caption, width - 2 * 170, start_size=168, min_size=110)
    ascent, descent = fnt.getmetrics()
    gap = int((ascent + descent) * 0.12)
    block = len(lines) * (ascent + descent) + (len(lines) - 1) * gap
    draw_centered_lines(draw, lines, fnt, width // 2, band_top + (band_bottom - band_top - block) // 2, gap, WHITE)

    # The screenshot, framed, filling the rest above a bottom margin.
    top, bottom_margin = 800, 150
    phone_h_max = height - top - bottom_margin
    phone_w = min(width - 2 * 260, round(phone_h_max * raw.width / raw.height))
    phone, mask = framed(raw, phone_w)
    if phone.height > phone_h_max:  # unusually tall raw screenshot: shrink to fit
        phone_w = round(phone_w * phone_h_max / phone.height)
        phone, mask = framed(raw, phone_w)
    x = (width - phone.width) // 2
    radius = phone.width // 11
    drop_shadow(canvas, (x, top, x + phone.width, top + phone.height), radius, offset=36, blur=60, opacity=0.55)
    canvas.paste(phone, (x, top), mask)
    return canvas


def feature_graphic(icon, map_raw, tagline):
    width, height = FEATURE_SIZE
    canvas = gradient(FEATURE_SIZE, BRAND_DARK, BRAND_LIGHT, horizontal=True)
    glow(canvas, (250, 250), 300, 0.08)
    draw = ImageDraw.Draw(canvas)

    # Phone crop on the right, bleeding off the bottom edge.
    text_right = width - 60
    if map_raw is not None:
        phone, mask = framed(map_raw, 300)
        x, y = width - phone.width - 70, 56
        drop_shadow(canvas, (x, y, x + phone.width, y + phone.height), phone.width // 11, 16, 24, 0.5)
        canvas.paste(phone, (x, y), mask)
        text_right = x - 40

    # Icon + wordmark + tagline on the left.
    left = 64
    icon_size = 112
    tile = icon.convert('RGB').resize((icon_size, icon_size), Image.Resampling.LANCZOS)
    wordmark = font(128)
    tag_font = font(36, bold=False)
    tag_lines = wrap(draw, tagline, tag_font, text_right - left)[:2]
    word_ascent, word_descent = wordmark.getmetrics()
    tag_ascent, tag_descent = tag_font.getmetrics()
    block = max(icon_size, word_ascent + word_descent) + 26 + len(tag_lines) * (tag_ascent + tag_descent + 6)
    top = (height - block) // 2
    canvas.paste(tile, (left, top + (word_ascent + word_descent - icon_size) // 2), rounded_mask(tile.size, 26))
    draw.text((left + icon_size + 28, top), 'Attnd', font=wordmark, fill=WHITE)
    y = top + max(icon_size, word_ascent + word_descent) + 26
    for line in tag_lines:
        draw.text((left, y), line, font=tag_font, fill=(0xEE, 0xE6, 0xFF))
        y += tag_ascent + tag_descent + 6
    return canvas


def find_icon_source(res_dir):
    """
    The launcher logo: the adaptive icon's foreground bitmap when it is a full-bleed PNG
    (mipmap-anydpi-v26/ic_launcher.xml → foreground → layer-list → @drawable/<name>), else the
    highest-resolution legacy mipmap ic_launcher.png.
    """
    res = Path(res_dir)
    candidates = []
    adaptive = res / 'mipmap-anydpi-v26' / 'ic_launcher.xml'
    if adaptive.exists():
        fg = re.search(r'<foreground[^>]*android:drawable="@(\w+)/(\w+)"', adaptive.read_text())
        if fg:
            kind, name = fg.groups()
            for xml in res.glob(f'{kind}*/{name}.xml'):
                candidates += [p for ref in re.findall(r'android:drawable="@drawable/(\w+)"', xml.read_text())
                               for p in res.glob(f'drawable*/{ref}.png')]
            candidates += list(res.glob(f'{kind}*/{name}.png'))
    candidates += list(res.glob('mipmap-*/ic_launcher.png'))
    if not candidates:
        sys.exit(f'No launcher icon PNG found under {res}')
    return max(candidates, key=lambda p: Image.open(p).size[0] * Image.open(p).size[1])


def hires_icon(source):
    """512×512 32-bit PNG: the logo on the brand purple (padded to a square, transparency filled)."""
    logo = Image.open(source).convert('RGBA')
    side = max(logo.size)
    square = Image.new('RGBA', (side, side), BRAND_DARK + (255,))
    square.alpha_composite(logo, ((side - logo.width) // 2, (side - logo.height) // 2))
    return square.resize((ICON_SIZE, ICON_SIZE), Image.Resampling.LANCZOS)


def read_captions(path):
    captions = []
    for number, line in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        screen, sep, caption = line.partition('=')
        if not sep or not screen.strip() or not caption.strip():
            sys.exit(f'{path}:{number}: expected screen=caption')
        captions.append((screen.strip(), caption.strip()))
    if not 2 <= len(captions) <= 8:
        sys.exit(f'{path}: {len(captions)} screenshots listed (Play allows 2–8 phone screenshots)')
    return captions


def save_png(image, path, mode='RGB'):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.convert(mode).save(path, 'PNG', optimize=True)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--raw', default='build/screenshots/raw', help='raw screenshots: <screen>.png')
    parser.add_argument('--captions', default='store/en-US/captions.txt')
    parser.add_argument('--out', default='build/store')
    parser.add_argument('--res', default='app/src/main/res', help='Android resources (launcher icon)')
    parser.add_argument('--tagline', default='Event maps, schedules and live updates')
    parser.add_argument('--feature-screen', default='map', help='raw screenshot shown in the feature graphic')
    args = parser.parse_args()

    raw_dir, out = Path(args.raw), Path(args.out)
    captions = read_captions(args.captions)
    missing = [screen for screen, _ in captions if not (raw_dir / f'{screen}.png').exists()]
    if missing:
        sys.exit(f'Missing raw screenshots in {raw_dir}: {", ".join(missing)}')

    for kind in ('phoneScreenshots', 'featureGraphic', 'icon'):
        for old in (out / kind).glob('*.png'):
            old.unlink()

    for index, (screen, caption) in enumerate(captions, 1):
        raw = Image.open(raw_dir / f'{screen}.png')
        low, high = raw.convert('L').getextrema()
        if high - low < 8:
            sys.exit(f'{raw_dir / f"{screen}.png"} is a single flat colour (the app was not on screen)')
        image = phone_screenshot(raw, caption)
        assert image.size == SCREENSHOT_SIZE
        path = save_png(image, out / 'phoneScreenshots' / f'{index:02d}_{screen}.png')
        if path.stat().st_size > MAX_SCREENSHOT_BYTES:
            sys.exit(f'{path} is {path.stat().st_size} bytes; Play allows {MAX_SCREENSHOT_BYTES}')
        print(f'{path}  "{caption}"')

    icon_source = find_icon_source(args.res)
    icon = hires_icon(icon_source)
    print(save_png(icon, out / 'icon' / 'icon.png', mode='RGBA'), f'(from {icon_source})')

    feature_raw = raw_dir / f'{args.feature_screen}.png'
    feature = feature_graphic(icon, Image.open(feature_raw) if feature_raw.exists() else None, args.tagline)
    assert feature.size == FEATURE_SIZE
    print(save_png(feature, out / 'featureGraphic' / 'feature_graphic.png'))


if __name__ == '__main__':
    main()
