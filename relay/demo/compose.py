"""Edit the demo film from demo/record.mjs output: intro, six numbered chapters, outro, with sound.

Usage: python3 demo/compose.py <outDir>   → <outDir>/caredrive-demo.mp4
Needs ffmpeg and Pillow. Captions are rendered as images (this ffmpeg has no drawtext).

Layout (1920x1080, light studio backdrop like the app):
  top     chapter number + title, and a second line saying what was fed in (honest about test values)
  middle  car screen (scrcpy recording, with the car's own voice and chimes) and the phone page
  bottom  what the car just said, as a subtitle pill
"""
import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/caredrive-demo")
ROOT = Path(__file__).resolve().parents[2]
ASTA = ROOT / "car-app/app/src/main/res/font/asta_sans.ttf"
W, H, FPS = 1920, 1080, 30
CAR = (40, 176, 1472, 828)            # x, y, w, h
PHONE_H = 828
PHONE = (W - 40 - round(PHONE_H * 860 / 1864), 176)
INTRO, OUTRO, XF = 7.5, 7.0, 0.6     # seconds; XF = crossfade

# Dark cockpit palette, matching the app's design D.
INK, SUB, MUTE, BLUE = (242, 244, 246), (176, 184, 193), (139, 149, 161), (49, 130, 246)
OPENING = ROOT / "docs/demo/ai-scenes/opening.mp4"  # AI-made staged opening (optional)
EMERGENCY = ROOT / "docs/demo/ai-scenes/emergency.mp4"  # AI-made emergency sequence, inserted before chapter 5

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
    ("정체 · 멈춰 있으면 먼저 물어요",
     "차량 신호: 기어 D · 속도 0 · 핸들·브레이크 입력 없음 2분 (시연: 2분을 20배 빠르게)  ·  '괜찮아' → 다시 출발"),
    ("심장 이상 · 비상등 · 정차 · 연락",
     "넣은 값: 부정맥 알림 (시뮬레이션)  ·  '도움이 필요해요' → 실내 조명 빨강 · 정차(P)하면 보호자 전화 · 119 연결 준비"),
    ("맥박 없음 · 3분 안에 대답이 없으면",
     "시연을 위해 3분 타이머를 10배 빠르게 돌렸어요  ·  30초(핸들·브레이크 입력 없음): 보호자 미리 알림  ·  3분: 보호자 전화 · 119 다이얼러 준비"),
]


def font(size, weight=700):
    f = ImageFont.truetype(str(ASTA), size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def backdrop():
    """Dark radial backdrop: a faint glow at the top left falling to near-black (the app's cockpit theme)."""
    small = Image.new("RGB", (192, 108))
    px = small.load()
    for y in range(108):
        for x in range(192):
            d = (((x - 50) / 192) ** 2 + ((y - 15) / 108) ** 2) ** 0.5 / 1.05
            t = min(1.0, d)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip((30, 35, 44), (9, 11, 14)))
    return small.resize((W, H), Image.BICUBIC)


def shadow(img, box, radius, blur=28, alpha=60, dy=12):
    x, y, w, h = box
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle((x, y + dy, x + w, y + h + dy), radius, fill=(0, 0, 0, alpha * 2))
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


def media_len(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True).stdout.strip()
    return float(out or 0)


# scrcpy starts writing a few seconds before the recorder notices the file, so trim that lead-in:
# both recordings end when the scenario ends, so their extra length is the lead-in.
AUDIO = OUT / "car_audio.mka" if (OUT / "car_audio.mka").exists() else OUT / "car.mkv"
lead_v = max(0.0, media_len(OUT / "car.mkv") - dur)
lead_a = max(0.0, media_len(AUDIO) - dur)

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
    d.rounded_rectangle((0, 0, w - 1, h - 1), h // 2, fill=(242, 244, 246, 240))
    # Small speaker glyph: the car is speaking these exact words.
    cx, cy = 42, h // 2
    d.polygon([(cx - 14, cy - 7), (cx - 6, cy - 7), (cx + 4, cy - 15), (cx + 4, cy + 15), (cx - 6, cy + 7), (cx - 14, cy + 7)],
              fill=BLUE)
    d.arc((cx - 2, cy - 12, cx + 18, cy + 12), -50, 50, fill=BLUE, width=3)
    d.text((84, 14), text, font=f, fill=(25, 31, 40))
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

def event_img(i, text):
    f = font(26, 700)
    tmp = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    w, h = int(text_w(tmp, text, f) + 64), 52
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w - 1, h - 1), h // 2, fill=(49, 130, 246, 235))
    d.ellipse((18, h // 2 - 6, 30, h // 2 + 6), fill=(255, 255, 255))
    d.text((40, 9), text, font=f, fill=(255, 255, 255))
    p = OUT / f"cap_ev{i}.png"
    img.save(p)
    return p, w


# Car actions worth pointing at (navigation waypoint, ambient light), shown as a chip on the car frame.
for i, e in enumerate(tl.get("events", [])):
    p, w = event_img(i, e["label"])
    a = max(0, (e["t"] - t0) / 1000)
    caps.append((p, a, a + 4.5, CAR[0] + CAR[2] - w - 24, CAR[1] + 24))

# Playlist scene: when the car says it will play music, play two free-licence recordings for ~10 s each,
# quietly under the car's own voice. Files live in docs/demo/music (downloaded from Wikimedia Commons):
#   Gymnopédie No. 1 (Satie) — CC0;  Canon in D (Pachelbel), performed by Kevin MacLeod — CC BY 3.0.
# If they are missing, demo/music.py synthesizes the same pieces from the notes.
MUSIC_DIR = ROOT / "docs/demo/music"
RECORDINGS = [(MUSIC_DIR / "gymnopedie_cc0.ogg", 13.0, "♪ 내 플레이리스트 1 · 사티 - 짐노페디 1번"),
              (MUSIC_DIR / "canon_macleod_ccby.ogg", 0.0, "♪ 내 플레이리스트 2 · 파헬벨 - 캐논")]
MUSIC_CREDIT = "음악: Satie - Gymnopedie No.1 (CC0, Wikimedia Commons) · Pachelbel - Canon in D, Kevin MacLeod (incompetech.com), CC BY 3.0"
music = []
play = next((s_ for s_ in tl["speaks"] if "노래" in s_["text"]), None)
if play:
    if not all(f.exists() for f, _, _ in RECORDINGS):
        subprocess.run([sys.executable, str(Path(__file__).parent / "music.py"), str(OUT)], check=True,
                       capture_output=True)
        RECORDINGS = [(OUT / "gymnopedie.wav", 0.0, RECORDINGS[0][2]), (OUT / "canon.wav", 0.0, RECORDINGS[1][2])]
        MUSIC_CREDIT = "음악: Satie·Pachelbel (퍼블릭 도메인, 악보에서 합성)"
    m0 = (play["t"] - t0) / 1000 + 3.2
    for k, (wav, offset, label) in enumerate(RECORDINGS):
        start = m0 + 10.0 * k
        if start < dur - 2:
            music.append((wav, start, offset))
            p_, w_ = event_img(100 + k, label)
            caps.append((p_, start, min(dur, start + 9.5), CAR[0] + 24, CAR[1] + CAR[3] - 52 - 24))

inputs = ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "bg.png"),
          "-i", str(OUT / "car.mkv"),
          "-f", "concat", "-safe", "0", "-i", str(OUT / "phone.txt"),
          "-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "mask_car.png"),
          "-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "mask_phone.png"),
          "-i", str(AUDIO)]
for p, *_ in caps:
    inputs += ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(p)]
for wav, *_ in music:
    inputs += ["-i", str(wav)]
base = 6

flt = [f"[1:v]trim=start={lead_v:.3f},setpts=PTS-STARTPTS,scale={CAR[2]}:{CAR[3]},format=rgba[c0]",
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
car_a = (f"[5:a]atrim=start={lead_a:.3f},asetpts=PTS-STARTPTS,atrim=0:{dur:.2f},aresample=48000,"
         f"aformat=channel_layouts=stereo")
if music:
    flt.append(car_a + "[acar]")
    mix = ["[acar]"]
    for k, (wav, start, offset) in enumerate(music):
        idx = base + len(caps) + k
        ms = int(start * 1000)
        flt.append(f"[{idx}:a]aresample=48000,aformat=channel_layouts=stereo,"
                   f"atrim=start={offset}:end={offset + 10},asetpts=PTS-STARTPTS,loudnorm=I=-26:TP=-3,"
                   f"afade=t=in:d=0.8,afade=t=out:st=8.8:d=1.2,adelay={ms}|{ms}[m{k}]")
        mix.append(f"[m{k}]")
    flt.append("".join(mix) + f"amix=inputs={len(mix)}:duration=first:normalize=0[aout]")
else:
    flt.append(car_a + "[aout]")
(OUT / "filter_main.txt").write_text(";\n".join(flt))
subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
                "-/filter_complex", str(OUT / "filter_main.txt"), "-map", "[vout]", "-map", "[aout]",
                "-t", f"{dur:.2f}", "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                "-c:a", "aac", "-b:a", "192k", str(OUT / "main.mp4")], check=True)

# AI cutaways (staged, labelled): a few seconds of a matching real-life moment at the start of a chapter,
# while the narrator speaks, before returning to the screens. Files in docs/demo/ai-scenes/inserts/.
INSERTS_DIR = ROOT / "docs/demo/ai-scenes/inserts"
INSERT_FOR = [("긴장", "stress_music.mp4"), ("졸음", "drowsy.mp4"), ("정체", "jam_awake.mp4"),
              ("맥박", "guardian_sms.mp4")]
cuts = []
for c in tl["chapters"]:
    for key, fname in INSERT_FOR:
        f = INSERTS_DIR / fname
        if key in c["label"] and f.exists():
            a = max(0.0, (c["t"] - t0) / 1000) + 0.6
            cuts.append((f, a, min(4.0, media_len(f))))
if cuts:
    lab = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lab)
    ld.rounded_rectangle((48, 40, 48 + 300, 40 + 48), 24, fill=(0, 0, 0, 150))
    ld.text((70, 49), "연출 장면 · AI 생성", font=font(24, 600), fill=(242, 244, 246))
    lab.save(OUT / "insert_label.png")
    ins = ["-i", str(OUT / "main.mp4"), "-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "insert_label.png")]
    g, last_v = [], "0:v"
    for k, (f, a, d) in enumerate(cuts):
        ins += ["-i", str(f)]
        g.append(f"[{k + 2}:v]scale=1920:1080,fps={FPS},trim=0:{d:.2f},"
                 f"delogo=x=1690:y=850:w=110:h=110,fade=t=in:d=0.3:alpha=1,fade=t=out:st={d - 0.3:.2f}:d=0.3:alpha=1,"
                 f"format=rgba,setpts=PTS-STARTPTS+{a:.2f}/TB[i{k}]")
        g.append(f"[{last_v}][i{k}]overlay=0:0:eof_action=pass:enable='between(t,{a:.2f},{a + d:.2f})'[u{k}]")
        g.append(f"[u{k}][1:v]overlay=0:0:enable='between(t,{a:.2f},{a + d - 0.2:.2f})'[w{k}x]")
        last_v = f"w{k}x"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *ins, "-filter_complex", ";".join(g),
                    "-map", f"[{last_v}]", "-map", "0:a", "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                    "-pix_fmt", "yuv420p", "-c:a", "copy", str(OUT / "main_cut.mp4")], check=True)
    (OUT / "main_cut.mp4").replace(OUT / "main.mp4")


# ---------------------------------------------------------------------------------------------
def card(path, draw_fn):
    img = backdrop().convert("RGBA")
    draw_fn(ImageDraw.Draw(img), img)
    img.convert("RGB").save(path)


def intro(d, img):
    d.text((160, 120), "Twelveone", font=font(30, 700), fill=BLUE)
    d.text((160, 250), "쓰러지기 전에 세우고, 대답이 없으면 알린다.", font=font(46, 600), fill=SUB)
    d.text((152, 330), "케어드라이브", font=font(150, 800), fill=INK)
    d.text((160, 530), "차의 운전 신호에, 워치의 몸 신호를 더해요", font=font(54, 700), fill=SUB)
    d.text((160, 860), "Pleos Connect 에뮬레이터 2.1.0의 화면과 소리를 그대로 녹화했어요", font=font(28, 500), fill=MUTE)
    d.text((160, 902), "건강 데이터(수면·심박·부정맥 알림)는 폰 시뮬레이터로 넣은 시험 값이에요", font=font(28, 500), fill=MUTE)


def outro(d, img):
    d.text((152, 150), "케어드라이브", font=font(96, 800), fill=INK)
    rows = [("출발 전", "수면과 기분으로 오늘의 휴식 간격을 정해요"),
            ("운전 중", "졸음·정체·심장 이상이면 차가 먼저 물어요"),
            ("대답 없으면", "보호자에게 전화하고 119 연결을 준비해요")]
    for i, (k, v) in enumerate(rows):
        y = 360 + i * 120
        d.rounded_rectangle((160, y, 340, y + 70), 35, fill=(25, 50, 92))
        d.text((250 - text_w(d, k, font(32, 700)) / 2, y + 14), k, font=font(32, 700), fill=BLUE)
        d.text((380, y + 10), v, font=font(44, 700), fill=INK)
    d.text((160, 740), "워치 없이도: 졸음 · 정체 무응답 · 휴식 안내는 차 신호만으로 동작해요", font=font(32, 700), fill=BLUE)
    d.text((160, 800), "케어드라이브는 진단하지 않아요. 위급하면 119가 먼저예요.", font=font(30, 600), fill=SUB)
    d.text((160, 900), "Twelveone", font=font(30, 600), fill=MUTE)
    if music:
        d.text((160, 990), MUSIC_CREDIT, font=font(20, 500), fill=MUTE)


card(OUT / "intro.png", intro)
card(OUT / "outro.png", outro)
for name, secs in (("intro", INTRO), ("outro", OUTRO)):
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-loop", "1", "-t", f"{secs}", "-i", str(OUT / f"{name}.png"),
                    "-f", "lavfi", "-t", f"{secs}", "-i", "anullsrc=r=48000:cl=stereo",
                    "-vf", f"fps={FPS},format=yuv420p", "-c:v", "libx264", "-crf", "18",
                    "-c:a", "aac", "-shortest", str(OUT / f"{name}.mp4")], check=True)

# Optional AI opening: cover the generator's sparkle mark, label it as staged, then crossfade on.
parts = []
if OPENING.exists():
    lab = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lab)
    ld.rounded_rectangle((48, 40, 48 + 300, 40 + 48), 24, fill=(0, 0, 0, 150))
    ld.text((70, 49), "연출 장면 · AI 생성", font=font(24, 600), fill=(242, 244, 246))
    ld.text((64, H - 150), "운전 중, 이런 순간이 온다면", font=font(54, 800), fill=(255, 255, 255))
    lab.save(OUT / "opening_label.png")
    op_len = media_len(OPENING)
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(OPENING),
                    "-loop", "1", "-t", f"{op_len:.2f}", "-i", str(OUT / "opening_label.png"),
                    "-filter_complex",
                    "[0:v]scale=1920:1080,fps=30,delogo=x=1690:y=850:w=110:h=110:enable='lt(t,7.6)'[o];"
                    "[1:v]format=rgba,fade=t=out:st=6.6:d=0.6:alpha=1[l];"
                    "[o][l]overlay=0:0:enable='lt(t,7.4)',format=yuv420p[v];"
                    "[0:a]aresample=48000,aformat=channel_layouts=stereo[a]",
                    "-map", "[v]", "-map", "[a]", "-t", f"{op_len:.2f}", "-c:v", "libx264", "-crf", "18",
                    "-c:a", "aac", str(OUT / "opening.mp4")], check=True)
    parts.append((OUT / "opening.mp4", op_len))
parts += [(OUT / "intro.mp4", INTRO)]

# Optional AI emergency sequence (driver in pain → car sends a signal → guardian's phone → ambulance),
# cut into the demo right before the cardiac chapter (found by its label, not its position).
split = None
chs_t = [(c["t"] - t0) / 1000 for c in tl["chapters"]]
CARDIAC_I = next((i for i, c in enumerate(tl["chapters"]) if "심장" in c["label"]), None)
if EMERGENCY.exists() and CARDIAC_I is not None:
    split = max(0.0, chs_t[CARDIAC_I] - 0.4)
    em_len = media_len(EMERGENCY)
    lab = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lab)
    ld.rounded_rectangle((48, 40, 48 + 300, 40 + 48), 24, fill=(0, 0, 0, 150))
    ld.text((70, 49), "연출 장면 · AI 생성", font=font(24, 600), fill=(242, 244, 246))
    lab.save(OUT / "emergency_label.png")
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(EMERGENCY),
                    "-loop", "1", "-t", f"{em_len:.2f}", "-i", str(OUT / "emergency_label.png"),
                    "-filter_complex",
                    "[0:v]scale=1920:1080,fps=30,delogo=x=1690:y=850:w=110:h=110:enable='lt(t,9.6)'[o];"
                    "[o][1:v]overlay=0:0:enable='lt(t,14)',format=yuv420p[v];"
                    "[0:a]aresample=48000,aformat=channel_layouts=stereo[a]",
                    "-map", "[v]", "-map", "[a]", "-t", f"{em_len:.2f}", "-c:v", "libx264", "-crf", "18",
                    "-c:a", "aac", str(OUT / "emergency.mp4")], check=True)
    for name, a, b in (("main_a", 0.0, split), ("main_b", split, dur)):
        subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(OUT / "main.mp4"),
                        "-ss", f"{a:.3f}", "-t", f"{b - a:.3f}", "-c:v", "libx264", "-crf", "18",
                        "-c:a", "aac", "-b:a", "192k", str(OUT / f"{name}.mp4")], check=True)
    parts += [(OUT / "main_a.mp4", split), (OUT / "emergency.mp4", em_len), (OUT / "main_b.mp4", dur - split)]
else:
    parts += [(OUT / "main.mp4", dur)]
parts += [(OUT / "outro.mp4", OUTRO)]

# Narration (natural Korean male voice). Free Microsoft Edge neural voice via the `edge-tts` package;
# set EDGE_TTS to its executable. Lines are placed at the start of each part/chapter, before the car speaks.
# Calm documentary tone, 합니다체. Numbers are written as they should be read ("일일구", not "119").
VOICE = os.environ.get("NARRATOR_VOICE", "ko-KR-InJoonNeural")
VOICE_ARGS = os.environ.get("NARRATOR_ARGS", "--rate=-8% --pitch=-4Hz").split()  # picked by the team: sample A
NARRATION_OPENING = "운전 중 몸에 이상이 오면, 스스로 도움을 청하기 어렵습니다. 쓰러지기 전에 세우게 하고, 대답이 없으면 대신 알립니다."
NARRATION_INTRO = "케어드라이브는 차의 운전 상태를 기본으로, 워치가 있으면 몸 상태까지 함께 봅니다."
NARRATION_EMERGENCY = "위급한 순간, 차가 보낸 신호는 가족에게 먼저 닿습니다."
NARRATION_CHAPTERS = [
    "출발 전, 지난밤 수면으로 오늘의 휴식 간격을 정합니다.",
    "주행 중에는 차의 기어와 속도를 함께 읽습니다.",
    "긴장이 이어지면, 차가 먼저 묻습니다.",
    "핸들 움직임과 수면 정보를 합쳐 졸음을 알아챕니다.",
    "기어를 넣은 채 오래 멈춰 있으면, 먼저 말을 겁니다.",
    "심장 박동이 이상하면, 먼저 차를 세우도록 안내합니다.",
    "대답이 없으면 보호자에게 전화하고, 일일구 연결을 준비합니다.",
]
NARRATION_OUTRO = "케어드라이브. 몸과 차를 함께 봅니다."


def narrate(text, path):
    exe = os.environ.get("EDGE_TTS")
    if not exe:
        return None
    subprocess.run([exe, "--voice", VOICE, *VOICE_ARGS, "--text", text, "--write-media", str(path)],
                   check=True, capture_output=True)
    return path


# Crossfade every part into the next.
ins, fv, fa, off = [], [], [], 0.0
for i, (p, _) in enumerate(parts):
    ins += ["-i", str(p)]
lv, la = "0:v", "0:a"
for i in range(1, len(parts)):
    off += parts[i - 1][1] - XF
    fv.append(f"[{lv}][{i}:v]xfade=transition=fade:duration={XF}:offset={off:.2f}[x{i}]")
    fa.append(f"[{la}][{i}:a]acrossfade=d={XF}[y{i}]")
    lv, la = f"x{i}", f"y{i}"
subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *ins,
                "-filter_complex", ";".join(fv + fa), "-map", f"[{lv}]", "-map", f"[{la}]",
                "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
                str(OUT / "caredrive-demo.mp4")], check=True)
total = sum(l for _, l in parts) - XF * (len(parts) - 1)

# Absolute start of each part in the final film, then lay the narration over it.
starts, acc = [], 0.0
for _, l in parts:
    starts.append(acc)
    acc += l - XF
names = [p.stem for p, _ in parts]
cues = []
if "opening" in names:
    cues.append((starts[names.index("opening")] + 0.8, NARRATION_OPENING))
cues.append((starts[names.index("intro")] + 0.6, NARRATION_INTRO))
def film_time(t_main):
    """Position in the final film of time t_main inside the recorded demo."""
    if split is None:
        return starts[names.index("main")] + t_main
    if t_main < split:
        return starts[names.index("main_a")] + t_main
    return starts[names.index("main_b")] + (t_main - split)


if split is not None:
    cues.append((starts[names.index("emergency")] + 3.4, NARRATION_EMERGENCY))
for i, c in enumerate(tl["chapters"][: len(NARRATION_CHAPTERS)]):
    cues.append((film_time(max(0.0, (c["t"] - t0) / 1000)) + 0.2 + (0.5 if split is not None and i == CARDIAC_I else 0),
                 NARRATION_CHAPTERS[i]))
cues.append((starts[names.index("outro")] + 0.5, NARRATION_OUTRO))
files = [(narrate(text, OUT / f"narr{i}.mp3"), at) for i, (at, text) in enumerate(cues)]
files = [(f, at) for f, at in files if f]

# Never talk over the car: car speech windows in film time (chimes + voice, ~6 Korean syllables/s).
busy = sorted((film_time(max(0.0, (sp["t"] - t0) / 1000)) - 0.6,
               film_time(max(0.0, (sp["t"] - t0) / 1000)) + len(sp["text"]) / 6.0 + 1.0) for sp in speaks)
placed, kept = [], []
for f, at in sorted(files, key=lambda x: x[1]):
    d = media_len(f)
    start = at
    for _ in range(6):
        hit = next(((a, b) for a, b in busy + placed if start < b and start + d > a), None)
        if not hit:
            break
        start = hit[1] + 0.3          # wait until the car (or previous line) has finished
    else:
        print("narration dropped (no gap):", f.name)
        continue
    placed.append((start, start + d))
    kept.append((f, start))
files = kept
if files:
    final = OUT / "caredrive-demo.mp4"
    tmp = OUT / "caredrive-demo-nonarr.mp4"
    final.replace(tmp)
    ins = ["-i", str(tmp)]
    flt, labels = [], ["[0:a]volume=0.85[base]"]
    for k, (f, at) in enumerate(files):
        ins += ["-i", str(f)]
        ms = int(at * 1000)
        flt.append(f"[{k + 1}:a]aresample=48000,aformat=channel_layouts=stereo,volume=1.6,adelay={ms}|{ms}[n{k}]")
    mix = "[base]" + "".join(f"[n{k}]" for k in range(len(files)))
    graph = ";".join(labels + flt + [f"{mix}amix=inputs={len(files) + 1}:duration=first:normalize=0[a]"])
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *ins, "-filter_complex", graph,
                    "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart", str(final)], check=True)
    (OUT / "narration.json").write_text(json.dumps([{"at": round(at, 2), "text": t} for at, t in cues],
                                                   ensure_ascii=False, indent=1))
print(OUT / "caredrive-demo.mp4", f"{total:.1f}s", f"{len(caps)} captions", f"lead v={lead_v:.2f} a={lead_a:.2f}")
