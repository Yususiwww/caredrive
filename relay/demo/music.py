"""Synthesize two short public-domain pieces for the demo film (no recorded audio is used).

  gymnopedie.wav : Erik Satie, Gymnopédie No. 1 (1888), opening, ~10 s
  canon.wav      : Johann Pachelbel, Canon in D (c. 1680), opening progression, ~10 s

Both compositions are in the public domain; the sound is generated here from the notes with a soft
piano-like additive tone, so there is no third-party recording either.
Usage: python3 demo/music.py <outDir>
"""
import sys
import wave
from pathlib import Path

import numpy as np

SR = 44100
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
A4 = 440.0
NAMES = {"C": -9, "C#": -8, "Db": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1,
         "A": 0, "A#": 1, "Bb": 1, "B": 2}


def freq(note):
    name, octave = note[:-1], int(note[-1])
    return A4 * 2 ** ((NAMES[name] + 12 * (octave - 4)) / 12)


def tone(f, dur, vel=0.5):
    """Soft piano-like note: a few decaying harmonics, quick attack, long release."""
    n = int(SR * (dur + 1.2))
    t = np.arange(n) / SR
    sig = np.zeros(n)
    for k, amp in ((1, 1.0), (2, 0.35), (3, 0.12), (4, 0.06)):
        sig += amp * np.sin(2 * np.pi * f * k * t * (1 + 0.0004 * k)) * np.exp(-t * (1.6 + 0.9 * k))
    env = np.minimum(1.0, t / 0.008)
    release = np.where(t > dur, np.exp(-(t - dur) * 5.0), 1.0)
    return vel * sig * env * release


def render(events, length):
    out = np.zeros(int(SR * (length + 2)))
    for start, notes, dur, vel in events:
        for nt in notes:
            s = tone(freq(nt), dur, vel)
            i = int(start * SR)
            out[i:i + len(s)] += s[: len(out) - i]
    out = out[: int(SR * length)]
    fade = int(SR * 1.2)
    out[-fade:] *= np.linspace(1, 0, fade)
    out[: int(SR * 0.05)] *= np.linspace(0, 1, int(SR * 0.05))
    return out / (np.abs(out).max() + 1e-9) * 0.6


def save(path, mono):
    stereo = np.stack([mono, mono], axis=1)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((stereo * 32767).astype(np.int16).tobytes())


def gymnopedie():
    beat = 60 / 72  # "Lent et douloureux", 3/4
    bar = 3 * beat
    ev = []
    # Left hand: bass on beat 1, chord on beat 2, alternating Gmaj7 and Dmaj7.
    chords = [("G2", ["B3", "D4", "F#4"]), ("D2", ["A3", "C#4", "F#4"])]
    for b in range(5):
        bass, chord = chords[b % 2]
        ev.append((b * bar, [bass], bar, 0.42))
        ev.append((b * bar + beat, chord, 2 * beat, 0.24))
    # Melody enters in the second bar (opening phrase).
    mel = [(1, 1, "F#5", 1), (1, 2, "A5", 1), (2, 0, "G5", 1), (2, 1, "F#5", 1), (2, 2, "C#5", 1),
           (3, 0, "B4", 1), (3, 1, "C#5", 1), (3, 2, "D5", 1), (4, 0, "A4", 3)]
    for b, k, n, d in mel:
        ev.append((b * bar + k * beat, [n], d * beat, 0.5))
    return render(ev, 5 * bar - 0.4)


def canon():
    beat = 60 / 60
    prog = [("D3", ["F#4", "A4"]), ("A2", ["E4", "A4"]), ("B2", ["F#4", "B4"]), ("F#2", ["C#4", "F#4"]),
            ("G2", ["D4", "G4"]), ("D3", ["F#4", "A4"]), ("G2", ["D4", "G4"]), ("A2", ["E4", "A4"])]
    melody = ["F#5", "E5", "D5", "C#5", "B4", "A4", "B4", "C#5"]
    ev = []
    for i, ((bass, chord), m) in enumerate(zip(prog, melody)):
        t = i * beat * 1.25
        ev.append((t, [bass], 1.25 * beat, 0.4))
        ev.append((t, chord, 1.25 * beat, 0.18))
        ev.append((t, [m], 1.25 * beat, 0.5))
    return render(ev, 8 * 1.25 * beat + 0.8)


OUT.mkdir(parents=True, exist_ok=True)
save(OUT / "gymnopedie.wav", gymnopedie())
save(OUT / "canon.wav", canon())
print(OUT / "gymnopedie.wav", OUT / "canon.wav")
