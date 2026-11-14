"""Edit the demo film from demo/record.mjs output: intro, six numbered chapters, outro, with sound.

Usage: python3 demo/compose.py <outDir>   → <outDir>/caredrive-demo.mp4
Needs ffmpeg and Pillow. Captions are rendered as images (this ffmpeg has no drawtext).

Layout (1920x1080, light studio backdrop like the app):
  top     chapter number + title, and a second line saying what was fed in (honest about test values)
  middle  car screen (scrcpy recording, with the car's own voice and chimes) and the phone page
  bottom  what the car just said, as a subtitle pill
"""
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/caredrive-demo")
ROOT = Path(__file__).resolve().parents[2]
ASTA = ROOT / "car-app/app/src/main/res/font/asta_sans.ttf"
W, H, FPS = 1920, 1080, 30
CAR = (64, 184, 1376, 774)            # x, y, w, h
PHONE_H = 774
PHONE = (W - 64 - round(PHONE_H * 860 / 1864), 184)
INTRO, OUTRO, XF = 4.0, 5.5, 0.6     # seconds; XF = crossfade

INK, SUB, MUTE, BLUE = (25, 31, 40), (78, 89, 104), (107, 118, 132), (49, 130, 246)

# What each scenario chapter shows, and what was fed in to make it happen.
CHAPTERS = [
    ("출발 전 · 오늘 컨디션을 차로 보내요",
     "넣은 값: 수면 4시간 40분 · 에너지 점수 45 (워치 시뮬레이션)  →  오늘은 1시간마다 쉬어 가요"),
    ("주행 · 차의 실제 기어와 속도",
     "Pleos 가상 콘솔로 기어 D, 속도 0→60→80km/h를 차량 신호로 넣었어요"),
    ("긴장이 이어지면 · 차가 먼저 물어요",
     "넣은 값: 심박 112 bpm 5분 지속 (시간 감기)  ·  '좀 그래요' → 폰이 플레이리스트를 열어요"),
    ("졸음 · 핸들 신호로 알아채요",
     "차량 핸들 신호: 10초 넘게 조향 없음 → 큰 수정 조향  +  짧은 수면 = 졸음 점수 45 → '쉬어 갈게요'"),
    ("심장 이상 · 비상등 · 정차 · 연락",
     "넣은 값: 부정맥 알림 (시뮬레이션)  ·  '도움이 필요해요' → 실내 조명 빨강 · 정차(P)하면 보호자 전화 · 119 연결 준비"),
    ("맥박 없음 · 3분 안에 대답이 없으면",
     "시연을 위해 3분 타이머를 10배 빠르게 돌렸어요  ·  1분: 보호자 미리 알림  ·  3분: 보호자 전화 · 119 다이얼러 준비"),
]


def font(size, weight=700):
    f = ImageFont.truetype(str(ASTA), size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def backdrop():
    """Soft studio gradient: bright top-left falling to light grey (same as the app's light theme)."""
    small = Image.new("RGB", (192, 108))
    px = small.load()
    for y in range(108):
        for x in range(192):
            d = (((x - 50) / 192) ** 2 + ((y - 15) / 108) ** 2) ** 0.5 / 1.05
            t = min(1.0, d)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip((255, 255, 255), (217, 222, 227)))
    return small.resize((W, H), Image.BICUBIC)


def shadow(img, box, radius, blur=28, alpha=60, dy=12):
    x, y, w, h = box
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle((x, y + dy, x + w, y + h + dy), radius, fill=(0, 23, 51, alpha))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def mask(path, w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    m.save(path)


def text_w(d, s, f):
    return d.textlength(s, font=f)


# ---------------------------------------------------------------------------------------------
tl = json.loads((OUT / "timeline.json").read_text())
t0 = tl["carStart"]
dur = (tl["carEnd"] - t0) / 1000
pw = round(PHONE_H * 860 / 1864)

# Main background with soft shadows under both screens and the two small source labels.
bg = backdrop().convert("RGBA")
shadow(bg, CAR, 22)
shadow(bg, (*PHONE, pw, PHONE_H), 40)
d = ImageDraw.Draw(bg)
for i, line in enumerate(("Twelveone · 케어드라이브", "Pleos Connect 에뮬레이터 2.1.0 화면·소리 그대로 + 폰 시뮬레이터")):
    f = font(20, 600 if i == 0 else 500)
    d.text((W - 64 - text_w(d, line, f), 34 + i * 28), line, font=f, fill=MUTE if i else SUB)
bg.convert("RGB").save(OUT / "bg.png")
mask(OUT / "mask_car.png", CAR[2], CAR[3], 22)
mask(OUT / "mask_phone.png", pw, PHONE_H, 40)


def chapter_img(i, title, detail):
    img = Image.new("RGBA", (W - 128, 132), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 4, 60, 64), fill=BLUE)
    n = f"{i + 1:02d}"
    fn = font(28, 800)
    d.text((30 - text_w(d, n, fn) / 2, 15), n, font=fn, fill=(255, 255, 255))
    d.text((80, 0), title, font=font(50, 800), fill=INK)
    d.text((82, 84), detail, font=font(27, 500), fill=SUB)
    p = OUT / f"cap_ch{i}.png"
    img.save(p)
    return p


def speech_img(i, text):
    f = font(36, 700)
    tmp = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    tw = text_w(tmp, text, f)
    w, h = int(tw + 140), 76
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w - 1, h - 1), h // 2, fill=(25, 31, 40, 235))
    # Small speaker glyph: the car is speaking these exact words.
    cx, cy = 42, h // 2
    d.polygon([(cx - 14, cy - 7), (cx - 6, cy - 7), (cx + 4, cy - 15), (cx + 4, cy + 15), (cx - 6, cy + 7), (cx - 14, cy + 7)],
              fill=(144, 194, 255))
    d.arc((cx - 2, cy - 12, cx + 18, cy + 12), -50, 50, fill=(144, 194, 255), width=3)
    d.text((84, 14), text, font=f, fill=(255, 255, 255))
    p = OUT / f"cap_sp{i}.png"
    img.save(p)
    return p, w, h


# Phone frames → variable-frame-rate video aligned to the car start time.
frames = [f for f in tl["frames"] if f["t"] >= t0 - 5000]
lines = []
for i, f in enumerate(frames):
    nxt = frames[i + 1]["t"] if i + 1 < len(frames) else tl["carEnd"]
    start = max(f["t"], t0)
    if nxt <= t0:
        continue
    lines.append(f"file 'frames/{f['file']}'\nduration {(nxt - start) / 1000:.3f}")
lines.append(f"file 'frames/{frames[-1]['file']}'")
(OUT / "phone.txt").write_text("\n".join(lines) + "\n")

caps = []  # (path, start, end, x, y)
chs = tl["chapters"]
for i, c in enumerate(chs):
    end = chs[i + 1]["t"] if i + 1 < len(chs) else tl["carEnd"]
    title, detail = CHAPTERS[i] if i < len(CHAPTERS) else (c["label"], "")
    caps.append((chapter_img(i, title, detail), max(0, (c["t"] - t0) / 1000 - 0.3), (end - t0) / 1000, 64, 36))
speaks = [s for s in tl["speaks"] if s["t"] >= t0 - 1000]
for i, s in enumerate(speaks):
    nxt = speaks[i + 1]["t"] if i + 1 < len(speaks) else tl["carEnd"]
    end = min(nxt - 200, s["t"] + 6500)
    p, w, h = speech_img(i, s["text"])
    caps.append((p, max(0, (s["t"] - t0) / 1000), (end - t0) / 1000, (W - w) // 2, H - h - 22))

inputs = ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "bg.png"),
          "-i", str(OUT / "car.mkv"),
          "-f", "concat", "-safe", "0", "-i", str(OUT / "phone.txt"),
          "-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "mask_car.png"),
          "-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "mask_phone.png")]
for p, *_ in caps:
    inputs += ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(p)]
base = 5

flt = [f"[1:v]scale={CAR[2]}:{CAR[3]},setpts=PTS-STARTPTS,format=rgba[c0]",
       "[3:v]format=gray[mc]", "[c0][mc]alphamerge[car]",
       f"[2:v]scale={pw}:{PHONE_H},setpts=PTS-STARTPTS,format=rgba[p0]",
       "[4:v]format=gray[mp]", "[p0][mp]alphamerge[ph]",
       f"[0:v][car]overlay={CAR[0]}:{CAR[1]}:shortest=0[v0]",
       f"[v0][ph]overlay={PHONE[0]}:{PHONE[1]}:eof_action=repeat[v1]"]
last = "v1"
for i, (p, a, b, x, y) in enumerate(caps):
    k = base + i
    fade = (f"[{k}:v]format=rgba,fade=t=in:st={a:.2f}:d=0.35:alpha=1,"
            f"fade=t=out:st={max(a, b - 0.35):.2f}:d=0.35:alpha=1[c{i}]")
    flt.append(fade)
    flt.append(f"[{last}][c{i}]overlay={x}:{y}:enable='between(t,{a:.2f},{b:.2f})'[w{i}]")
    last = f"w{i}"
flt.append(f"[{last}]fps={FPS},format=yuv420p[vout]")
flt.append(f"[1:a]asetpts=PTS-STARTPTS,atrim=0:{dur:.2f},aresample=48000[aout]")
(OUT / "filter_main.txt").write_text(";\n".join(flt))
subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
                "-/filter_complex", str(OUT / "filter_main.txt"), "-map", "[vout]", "-map", "[aout]",
                "-t", f"{dur:.2f}", "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                "-c:a", "aac", "-b:a", "192k", str(OUT / "main.mp4")], check=True)


# ---------------------------------------------------------------------------------------------
def card(path, draw_fn):
    img = backdrop().convert("RGBA")
    draw_fn(ImageDraw.Draw(img), img)
    img.convert("RGB").save(path)


def intro(d, img):
    d.text((160, 120), "Twelveone", font=font(30, 700), fill=BLUE)
    d.text((152, 330), "케어드라이브", font=font(150, 800), fill=INK)
    d.text((160, 530), "워치가 아는 내 컨디션, 차가 먼저 챙겨요", font=font(54, 700), fill=SUB)
    d.text((160, 860), "Pleos Connect 에뮬레이터 2.1.0의 화면과 소리를 그대로 녹화했어요", font=font(28, 500), fill=MUTE)
    d.text((160, 902), "건강 데이터(수면·심박·부정맥 알림)는 폰 시뮬레이터로 넣은 시험 값이에요", font=font(28, 500), fill=MUTE)


def outro(d, img):
    d.text((152, 150), "케어드라이브", font=font(96, 800), fill=INK)
    rows = [("출발 전", "워치의 수면·에너지로 오늘의 휴식 간격을 정해요"),
            ("운전 중", "심박이 오르거나 알림이 오면 먼저 쉬어 가자고 말해요"),
            ("멈추면", "이유에 맞는 다음 행동과 보호자 알림, 119를 안내해요")]
    for i, (k, v) in enumerate(rows):
        y = 360 + i * 120
        d.rounded_rectangle((160, y, 340, y + 70), 35, fill=(232, 243, 255))
        d.text((250 - text_w(d, k, font(32, 700)) / 2, y + 14), k, font=font(32, 700), fill=BLUE)
        d.text((380, y + 10), v, font=font(44, 700), fill=INK)
    d.text((160, 790), "케어드라이브는 진단하지 않아요. 위급하면 119가 먼저예요.", font=font(30, 600), fill=SUB)
    d.text((160, 900), "github.com/Yususiwww/caredrive  ·  Twelveone", font=font(30, 600), fill=MUTE)


card(OUT / "intro.png", intro)
card(OUT / "outro.png", outro)
for name, secs in (("intro", INTRO), ("outro", OUTRO)):
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-loop", "1", "-t", f"{secs}", "-i", str(OUT / f"{name}.png"),
                    "-f", "lavfi", "-t", f"{secs}", "-i", "anullsrc=r=48000:cl=stereo",
                    "-vf", f"fps={FPS},format=yuv420p", "-c:v", "libx264", "-crf", "18",
                    "-c:a", "aac", "-shortest", str(OUT / f"{name}.mp4")], check=True)

# Crossfade intro → main → outro.
o1 = INTRO - XF
o2 = o1 + dur - XF
xf = (f"[0:v][1:v]xfade=transition=fade:duration={XF}:offset={o1:.2f}[x1];"
      f"[x1][2:v]xfade=transition=fade:duration={XF}:offset={o2:.2f}[v];"
      f"[0:a][1:a]acrossfade=d={XF}[a1];[a1][2:a]acrossfade=d={XF}[a]")
subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", str(OUT / "intro.mp4"), "-i", str(OUT / "main.mp4"), "-i", str(OUT / "outro.mp4"),
                "-filter_complex", xf, "-map", "[v]", "-map", "[a]",
                "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
                str(OUT / "caredrive-demo.mp4")], check=True)
print(OUT / "caredrive-demo.mp4", f"{INTRO + dur + OUTRO - 2 * XF:.1f}s", f"{len(caps)} captions")
