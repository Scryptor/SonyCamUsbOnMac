#!/usr/bin/env python3
"""Картинка для виртуальной камеры (scripts/fake_camera.py) и скриншотов: горное озеро на закате, нарисованное кодом —
без чужих фотографий. Пишет в scripts/assets/ кадр viewfinder.jpg и два других плана той же сцены — viewfinder-zoom.jpg
(крупно, у солнца) и viewfinder-pan.jpg (левый берег): у каждой из нескольких виртуальных камер своя картинка. Каждый —
1024×576, как превью камеры, и не больше 70 КБ, как настоящий кадр. Нужны numpy и Pillow.

  scripts/make-viewfinder-picture.py          # озеро и два других плана
  scripts/make-viewfinder-picture.py --log    # из viewfinder.jpg — viewfinder-slog3.jpg, -slog2.jpg и -hlg.jpg
  scripts/make-viewfinder-picture.py --bars   # bars.jpg — цветные полосы 75 %

--log рисует то, что камера отдаёт в превью при профиле изображения с логом (PP7 — S-Log2, PP8 и PP9 — S-Log3, PP10 —
HLG): тот же кадр, обратным путём к LUT программы (docs/superpowers/specs/2026-10-29-monitor-plus-ideas-design.md, §4).
Кадр 709 → линейный свет → отражение сцены L (100 % белого = 1, 18 % серого = 0,18) → кривая камеры → 8 бит так, как
превью ZV-E10 растягивает коды камеры: (код − 64)/955·255, коды 64…1019 → 0…255 (docs/protocol.md, «Превью в S-Log и
HLG»): чёрное S-Log3 — 8, 18 % серого — 95. Охват не пересчитывается: цвета остаются в основных цветах 709, меняется только
кривая. Все три файла — 1024×576, качество 92: scripts/fake_camera.py отдаёт их вместо viewfinder.jpg, когда в камере
выбран такой профиль.
"""
import argparse, io, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "assets")
# другие планы: (x, y) левого верхнего угла и ширина куска в долях кадра; высота — по 16:9
VIEWS = {"viewfinder.jpg": None, "viewfinder-zoom.jpg": (0.36, 0.2, 0.6), "viewfinder-pan.jpg": (0.0, 0.32, 0.6)}
W, H = 2048, 1152                     # рисуем вдвое крупнее, потом уменьшаем: края мягче
HORIZON = 0.6
SUN = (0.66, HORIZON - 0.19)
rng = np.random.default_rng(7)


def color(hex_):
    return np.array([int(hex_[i:i + 2], 16) for i in (1, 3, 5)], float)


def mix(a, b, t):
    return a + (b - a) * t


Y = np.linspace(0, 1, H)[:, None, None]
X = np.linspace(0, 1, W)[None, :, None]


def sky():
    t = np.clip(Y / HORIZON, 0, 1)
    top, middle, low = color("#1d3f73"), color("#7a93c4"), color("#f7ad6f")
    upper = mix(top, middle, np.clip(t / 0.62, 0, 1))
    lower = mix(middle, low, np.clip((t - 0.62) / 0.38, 0, 1))
    img = np.where(t < 0.62, upper, lower) * np.ones((1, W, 1))
    # солнце: мягкое свечение и яркий диск
    d = np.sqrt(((X - SUN[0]) * W / H) ** 2 + (Y - SUN[1]) ** 2)
    img += np.exp(-(d / 0.24) ** 2) * color("#ffc489") * 0.62
    img += np.exp(-(d / 0.07) ** 2) * color("#ffe2b8") * 0.45
    img = np.minimum(img, 255)
    img += np.exp(-(d / 0.03) ** 4) * (color("#fff8ea") - img) * 0.97
    # облака: вытянутые размытые пятна, подсвеченные снизу
    noise = Image.fromarray((rng.random((36, 64)) * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
    clouds = np.asarray(noise.filter(ImageFilter.GaussianBlur(44)), float)[..., None] / 255
    band = np.exp(-((Y - 0.2) / 0.08) ** 2)
    amount = np.clip((clouds - 0.53) * 4, 0, 1) * band * 0.4
    img = mix(img, mix(color("#e9d6d6"), color("#ffd9b0"), np.clip(Y / 0.35, 0, 1)), amount)
    return img


def ridge(base, height, roughness, seed):
    """Линия гребня: случайное деление отрезка пополам, высота — доля кадра."""
    r = np.random.default_rng(seed)
    n = 2049
    h = np.zeros(n)
    h[0], h[-1] = r.normal(0, 1, 2)
    step, scale = n - 1, 1.0
    while step > 1:
        half = step // 2
        for i in range(half, n - 1, step):
            h[i] = (h[i - half] + h[i + half]) / 2 + r.normal(0, scale)
        step, scale = half, scale * roughness
    h = (h - h.min()) / (h.max() - h.min())
    return base - height * np.interp(np.linspace(0, n - 1, W), np.arange(n), h)


def mountains(img):
    haze = color("#c9b6c4")
    layers = [  # дальние — светлее и голубее
        (HORIZON, 0.15, 0.55, "#9aa6c6", 0.6, 11),
        (HORIZON, 0.105, 0.58, "#66779d", 0.38, 23),
        (HORIZON + 0.004, 0.065, 0.62, "#36435f", 0.2, 37),
    ]
    for base, height, rough, hex_, fog, seed in layers:
        top = ridge(base, height, rough, seed)[None, :, None]
        inside = (Y >= top) & (Y < HORIZON + 0.005)
        depth = np.clip((Y - top) / np.maximum(HORIZON - top, 1e-3), 0, 1)
        shade = mix(color(hex_), haze, fog * depth)
        # склоны к солнцу чуть теплее
        warm = np.exp(-(((X - SUN[0]) * W / H) / 0.45) ** 2) * 0.3
        shade = mix(shade, color("#f0b48a"), warm * (1 - depth))
        img = np.where(inside, shade, img)
    return img


def water(img):
    rows = int(HORIZON * H)
    out = img.copy()
    for yy in range(rows, H):
        k = yy - rows
        src = max(0, rows - 1 - int(k * 1.04))
        # рябь: строки отражения сдвинуты синусом, дальше от берега — сильнее
        shift = int(np.sin(yy * 0.9) * (2 + k * 0.03) + np.sin(yy * 0.13) * (1 + k * 0.02))
        line = np.roll(img[src], shift, axis=0)
        out[yy] = mix(line, color("#14233c"), 0.3 + 0.35 * k / (H - rows))
    # блики солнца на воде
    y = np.arange(rows, H)
    weights = np.exp(-(y - rows) / 260)
    for _ in range(1600):
        yy = int(rng.choice(y, p=weights / weights.sum()))
        spread = 18 + (yy - rows) * 0.32
        xx = int(rng.normal(SUN[0] * W, spread))
        if 0 <= xx < W:
            length = int(2 + (yy - rows) * 0.025 + rng.random() * 5)
            span = slice(max(0, xx - length), xx + length)
            out[yy, span] = mix(out[yy, span], color("#ffe6bd"), 0.25 + 0.55 * rng.random())
    return out


def shore(picture):
    draw = ImageDraw.Draw(picture)
    dark = (12, 18, 28)
    w, h = picture.size
    # берег: низкая тёмная полоса у кромки воды слева и справа
    draw.polygon([(0, h * 0.86), (w * 0.2, h * 0.9), (w * 0.31, h), (0, h)], fill=dark)
    draw.polygon([(w, h * 0.83), (w * 0.84, h * 0.88), (w * 0.74, h), (w, h)], fill=dark)
    # ели на берегу
    for x, base, size in [(0.03, 0.9, 0.34), (0.085, 0.92, 0.26), (0.13, 0.93, 0.19), (0.175, 0.95, 0.14),
                          (0.97, 0.87, 0.36), (0.915, 0.89, 0.27), (0.865, 0.91, 0.2), (0.82, 0.93, 0.15)]:
        cx, by, s = x * w, base * h, size * h
        for tier in range(6):
            t = tier / 6
            width = s * (0.34 - 0.27 * t)
            y0 = by - s * t * 0.92
            draw.polygon([(cx - width, y0), (cx + width, y0), (cx, y0 - s * 0.32)], fill=dark)
        draw.rectangle([cx - s * 0.02, by - s * 0.05, cx + s * 0.02, by + s * 0.04], fill=dark)


def scene():
    img = water(mountains(sky()))
    # зерно и лёгкое затемнение к краям, как у объектива
    img += rng.normal(0, 2.2, img.shape)
    r = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    img *= 1 - 0.32 * r ** 2
    picture = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    shore(picture)
    os.makedirs(ASSETS, exist_ok=True)
    for name, view in VIEWS.items():
        shot = picture
        if view:
            x, y, width = view
            shot = picture.crop((int(x * W), int(y * H), int((x + width) * W), int((y + width) * H)))
        shot = shot.resize((1024, 576), Image.LANCZOS).filter(ImageFilter.UnsharpMask(1.2, 40, 2))
        for quality in range(90, 40, -4):
            buffer = io.BytesIO()
            shot.save(buffer, "JPEG", quality=quality, optimize=True)
            if buffer.tell() <= 70 * 1024:
                break
        path = os.path.join(ASSETS, name)
        open(path, "wb").write(buffer.getvalue())
        print(f"{path}: {buffer.tell() // 1024} КБ, качество {quality}")


# --- лог: кадр так, как камера отдаёт его в превью при профиле S-Log2, S-Log3 и HLG -------------------------------------

def to_linear(v):
    """Обратная кривая BT.709: значение 0–1 → линейный свет. Он и есть отражение сцены L (×1: 18 % серого = 0,18)."""
    return np.where(v < 4.5 * 0.018, v / 4.5, ((v + 0.099) / 1.099) ** (1 / 0.45))


def slog3_code(L):
    """S-Log3: отражение → код 0–1023. Выше 0,01125 — логарифм, ниже — прямая от кода 95 (L = 0 → 95, 18 % → 420)."""
    knee = 171.2102946929                                   # код на стыке прямой и логарифма
    return np.where(L >= 0.01125, 420 + 261.5 * np.log10((np.maximum(L, 0.01125) + 0.01) / 0.19),
                    95 + L * (knee - 95) / 0.01125)


def slog2_code(L):
    """S-Log2: отражение → код 0–1023. L = x·0,9·219/155; x ≥ 0 — логарифм, ниже — прямая (L = 0 → 90, 18 % → 347)."""
    x = L * 155 / (0.9 * 219)
    y = np.where(x >= 0, 0.432699 * np.log10(np.maximum(x, 0) + 0.037584) + 0.646596, 5 * x + 0.030001222851889303)
    return 64 + y * 876


def hlg_code(L):
    """HLG: отражение → код 0–1023 в «легальном» диапазоне 64–940. L = 1 (диффузный белый) — сигнал 0,75, код 721."""
    e = L * 0.2647
    signal = np.where(e <= 1 / 12, np.sqrt(3 * np.maximum(e, 0)),
                      0.17883277 * np.log(np.maximum(12 * e - 0.28466892, 1e-9)) + 0.55991073)
    return 64 + signal * 876


LOG_CURVES = {"slog3": slog3_code, "slog2": slog2_code, "hlg": hlg_code}
# превью ZV-E10 растягивает коды камеры 64…1019 на 0…255 — так измерено на камере (scripts/check_log_preview.py)
PREVIEW_BLACK, PREVIEW_SPAN = 64, 955


def preview_level(code):
    """Код камеры 0–1023 → значение канала превью 0–255."""
    return np.clip((code - PREVIEW_BLACK) / PREVIEW_SPAN * 255, 0, 255)


def log_pictures():
    """viewfinder-slog3.jpg, viewfinder-slog2.jpg и viewfinder-hlg.jpg из viewfinder.jpg: 1024×576, качество 92."""
    source = np.asarray(Image.open(os.path.join(ASSETS, "viewfinder.jpg")).convert("RGB"), float) / 255
    scene_light = to_linear(source)
    for name, curve in LOG_CURVES.items():
        code = np.clip(curve(scene_light), 0, 1023)
        picture = Image.fromarray(np.rint(preview_level(code)).astype(np.uint8))
        path = os.path.join(ASSETS, f"viewfinder-{name}.jpg")
        picture.save(path, "JPEG", quality=92, optimize=True)
        gray = np.rint(preview_level(curve(np.array(0.18))))
        print(f"{path}: {os.path.getsize(path) // 1024} КБ, 18 % серого → {int(gray)} из 255")


def bars():
    """bars.jpg: восемь вертикальных полос 75 % по 128 точек — белый, жёлтый, циан, зелёный, пурпурный, красный, синий,
    чёрный. 75 % — это 191 из 255 в каждом канале. Цветность не уменьшается (4:4:4): полосы стоят ровно на блоках JPEG."""
    colors = [(1, 1, 1), (1, 1, 0), (0, 1, 1), (0, 1, 0), (1, 0, 1), (1, 0, 0), (0, 0, 1), (0, 0, 0)]
    level = round(0.75 * 255)
    picture = np.zeros((576, 1024, 3), np.uint8)
    for i, color_ in enumerate(colors):
        picture[:, i * 128:(i + 1) * 128] = np.array(color_) * level
    path = os.path.join(ASSETS, "bars.jpg")
    Image.fromarray(picture).save(path, "JPEG", quality=100, subsampling=0, optimize=True)
    print(f"{path}: {os.path.getsize(path) // 1024} КБ, полосы {level} из 255")


def main():
    ap = argparse.ArgumentParser(description="Картинки для виртуальной камеры (scripts/fake_camera.py) в scripts/assets/")
    ap.add_argument("--log", action="store_true", help="из viewfinder.jpg — кадры S-Log3, S-Log2 и HLG (viewfinder-<имя>.jpg)")
    ap.add_argument("--bars", action="store_true", help="bars.jpg — цветные полосы 75 %%")
    a = ap.parse_args()
    if not (a.log or a.bars):
        scene()
    if a.log:
        log_pictures()
    if a.bars:
        bars()


if __name__ == "__main__":
    main()
