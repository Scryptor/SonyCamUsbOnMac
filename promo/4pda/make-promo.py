import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

S, OUT = sys.argv[1], sys.argv[2]
W = 3000
SC = 0.70                     # масштаб скриншота 2560x1600
TW, TH = int(2560 * SC), int(1600 * SC)
TX, TY = (W - TW) // 2, 290   # где скриншот на холсте
H = TY + TH + 190
BEZ = 26

def font(size, weight="Regular"):
    f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f

# фон: тёмный вертикальный градиент с лёгким синим отливом
bg = Image.new("RGB", (W, H))
px = bg.load()
for y in range(H):
    t = y / H
    c = (int(14 + 10 * t), int(18 + 12 * t), int(32 + 18 * t))
    for x in range(W):
        px[x, y] = c
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((W // 2 - 1100, TY - 200, W // 2 + 1100, TY + TH + 300), fill=(60, 110, 220, 70))
glow = glow.filter(ImageFilter.GaussianBlur(220))
bg = Image.alpha_composite(bg.convert("RGBA"), glow)

# тень и корпус планшета
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle((TX - BEZ, TY - BEZ + 30, TX + TW + BEZ, TY + TH + BEZ + 30), 70, fill=(0, 0, 0, 170))
bg = Image.alpha_composite(bg, shadow.filter(ImageFilter.GaussianBlur(40)))
d = ImageDraw.Draw(bg)
d.rounded_rectangle((TX - BEZ, TY - BEZ, TX + TW + BEZ, TY + TH + BEZ), 64, fill=(22, 23, 27), outline=(70, 74, 84), width=3)

shot = Image.open(f"{S}/hero-raw.png").convert("RGB").resize((TW, TH), Image.LANCZOS)
mask = Image.new("L", (TW, TH), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, TW, TH), 40, fill=255)
bg.paste(shot, (TX, TY), mask)
d = ImageDraw.Draw(bg)

# заголовок
title, sub = "Sony Cam Viewer", "Живое видео и управление камерой Sony с Android-планшета и Mac — по USB-C или по Wi-Fi"
ft, fs = font(96, "Bold"), font(40)
d.text((W // 2, 120), title, font=ft, fill=(255, 255, 255), anchor="mm")
d.text((W // 2, 210), sub, font=fs, fill=(170, 180, 200), anchor="mm")
foot = "Бесплатно, без рекламы и регистрации  ·  github.com/Scryptor/SonyCamViewer"
d.text((W // 2, H - 70), foot, font=font(34), fill=(140, 150, 170), anchor="mm")

ACC = (64, 156, 255)
fh, fb = font(34, "Semibold"), font(27)

def P(x, y):  # точка скриншота -> холст
    return TX + x * SC, TY + y * SC

def badge(cx, cy, n, r=26):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ACC, outline=(255, 255, 255), width=4)
    d.text((cx, cy + 1), str(n), font=font(30, "Bold"), fill=(255, 255, 255), anchor="mm")

ITEMS = [
    ("Пресеты", "свои для фото и видео,\nприменяются одной кнопкой", (462, 50)),
    ("До 4 камер сразу", "живые плитки по Wi-Fi, переход\nна другую — без переподключения", (470, 280)),
    ("Связанные камеры", "повторяют за главной\nнастройки, пресеты и запись", (470, 787)),
    ("Как на дисплее камеры", "выдержка, диафрагма, ISO, ББ,\nэкспокоррекция, фокус, режим\nзаписи; нажатие — меню Fn", (440, 1491)),
    ("Строка состояния", "REC с таймером, связь, к/с,\nкарта, заряд камеры и планшета", (776, 79)),
    ("Фото · Видео · S&Q", "переключатель режима,\nкак кнопка MODE на камере", (2335, 608)),
    ("Гистограмма", "тени, полутона, света,\nпровалы и пересвет в процентах", (1619, 986)),
    ("Запись на всех камерах", "одна кнопка; красная рамка\nи REC, как у монитора", (2320, 1088)),
]
rows = 4
step = TH / rows
for i, (head, body, pt) in enumerate(ITEMS):
    n = i + 1
    left = i < rows
    ty = TY + 30 + (i % rows) * step
    bx = 70 if left else TX + TW + BEZ + 70
    badge(bx + 26, ty + 22, n)
    d.text((bx + 72, ty), head, font=fh, fill=(255, 255, 255))
    d.multiline_text((bx + 72, ty + 50), body, font=fb, fill=(175, 185, 205), spacing=8)
    badge(*P(*pt), n)

bg.convert("RGB").save(f"{OUT}/sony-cam-viewer-4pda.png", optimize=True)
bg.convert("RGB").save(f"{OUT}/sony-cam-viewer-4pda.jpg", quality=92, subsampling=0)
