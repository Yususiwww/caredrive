"""Compose the side-by-side demo video from demo/record.mjs output.

Usage: python3 demo/compose.py <outDir>   → <outDir>/caredrive-demo.mp4
Needs ffmpeg and Pillow. Captions are rendered as images (ffmpeg here has no drawtext).
"""
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/caredrive-demo")
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1920, 1080
CAR_W, CAR_H, CAR_X, CAR_Y = 1440, 810, 24, 64
PH_H, PH_X, PH_Y = 900, 1500, 64
BG = (11, 18, 32)

tl = json.loads((OUT / "timeline.json").read_text())
t0 = tl["carStart"]
dur = (tl["carEnd"] - t0) / 1000


def font(size, bold=False):
    # Face 0 = Regular, 6 = Bold in AppleSDGothicNeo.ttc
    return ImageFont.truetype(FONT, size, index=6 if bold else 0)


def caption(path, text, size, color, bold=False, width=CAR_W, height=None, icon=None):
    f = font(size, bold)
    h = height or size + 28
    img = Image.new("RGBA", (width, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x = 0
    if icon:
        d.rounded_rectangle((0, 4, size * 2 + 10, h - 4), radius=10, fill=(15, 118, 110, 255))
        d.text((10, (h - size) // 2 - 4), icon, font=font(size - 6, True), fill=(255, 255, 255))
        x = size * 2 + 26
    d.text((x, (h - size) // 2 - 4), text, font=f, fill=color)
    img.save(path)


# Static frame: titles and labels.
frame = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(frame)
d.text((CAR_X, 18), "차 화면 · Pleos Connect 에뮬레이터", font=font(28, True), fill=(203, 213, 225))
d.text((PH_X, 18), "폰 앱 (시뮬레이터)", font=font(28, True), fill=(203, 213, 225))
d.text((W - 300, H - 44), "시뮬레이션 데이터 · 데모", font=font(24), fill=(100, 116, 139))
frame.save(OUT / "bg.png")

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

# Captions: chapters (until next chapter) and car speech (until next speech, max 7 s).
caps = []
chapters = tl["chapters"]
for i, c in enumerate(chapters):
    end = chapters[i + 1]["t"] if i + 1 < len(chapters) else tl["carEnd"]
    p = OUT / f"cap_ch{i}.png"
    caption(p, c["label"], 40, (241, 245, 249), bold=True)
    caps.append((p, (c["t"] - t0) / 1000, (end - t0) / 1000, CAR_X, 892))
speaks = [s for s in tl["speaks"] if s["t"] >= t0 - 1000]
for i, s in enumerate(speaks):
    nxt = speaks[i + 1]["t"] if i + 1 < len(speaks) else tl["carEnd"]
    end = min(nxt, s["t"] + 7000)
    p = OUT / f"cap_sp{i}.png"
    caption(p, f"“{s['text']}”", 32, (153, 246, 228), icon="음성", height=56)
    caps.append((p, max(0, (s["t"] - t0) / 1000), (end - t0) / 1000, CAR_X, 966))

inputs = ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(OUT / "bg.png"),
          "-i", str(OUT / "car.mp4"),
          "-f", "concat", "-safe", "0", "-i", str(OUT / "phone.txt")]
for p, *_ in caps:
    inputs += ["-loop", "1", "-t", f"{dur:.2f}", "-i", str(p)]

flt = [f"[1:v]scale={CAR_W}:{CAR_H},setpts=PTS-STARTPTS[car]",
       f"[2:v]scale=-2:{PH_H},setpts=PTS-STARTPTS[ph]",
       "[0:v][car]overlay=%d:%d:shortest=0[v0]" % (CAR_X, CAR_Y),
       "[v0][ph]overlay=%d:%d:eof_action=repeat[v1]" % (PH_X, PH_Y)]
last = "v1"
for i, (p, a, b, x, y) in enumerate(caps):
    nxt = f"v{i + 2}"
    flt.append(f"[{last}][{i + 3}:v]overlay={x}:{y}:enable='between(t,{a:.2f},{b:.2f})'[{nxt}]")
    last = nxt
(OUT / "filter.txt").write_text(";\n".join(flt))

cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
       "-/filter_complex", str(OUT / "filter.txt"), "-map", f"[{last}]",
       "-t", f"{dur:.2f}", "-r", "30", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
       str(OUT / "caredrive-demo.mp4")]
subprocess.run(cmd, check=True, cwd=OUT)
print(OUT / "caredrive-demo.mp4", f"{dur:.1f}s", f"{len(caps)} captions")
