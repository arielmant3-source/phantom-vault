#!/usr/bin/env python3
"""Bouncing-ball physics melody video generator.

Renders a 1080x1920 @ 60 fps vertical video in which every collision of a
ball with its track produces an audio impulse; N hits per second fuse into
an N Hz chiptune pitch. Audio and visuals are driven by the same phase
signal, so every visible bounce is an audible click.

Usage:  python3 render.py [output.mp4]
Needs:  numpy, scipy, pycairo, opencv-python-headless, Pillow (with raqm), ffmpeg
"""
import math
import os
import subprocess
import sys
import wave
from functools import lru_cache
from multiprocessing import Pool

import cairo
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.signal import resample_poly, butter, sosfilt

# ----------------------------------------------------------------- settings
W, H, FPS = 1080, 1920, 60
DURATION = 11.0
NFRAMES = int(DURATION * FPS)
SR = 48000
OS = 4                      # audio oversampling for the naive pulse synth

YELLOW = (1.0, 0.902, 0.0)      # #FFE600
CYAN = (0.0, 1.0, 0.667)        # #00FFAA
PURPLE = (0.722, 0.161, 1.0)    # #B829FF

FONT_DIR = "/usr/share/fonts/opentype/inter"
F_BOLD = f"{FONT_DIR}/Inter-Bold.otf"
F_XBOLD = f"{FONT_DIR}/InterDisplay-Bold.otf"
F_SEMI = f"{FONT_DIR}/Inter-SemiBold.otf"
F_EMOJI = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"

TRACK_X0, TRACK_X1 = round(W * 0.175), round(W * 0.825)   # ~65% of width
CY = H // 2
GAP = 45                    # distance between the two lines of a track
LINE_W = 3
BALL_R = 7
SPACING = 90                # vertical distance between tracks in phase 2
WAVE_MAX = TRACK_X0 - 22    # max length of the left-side visualizer bar
BALL_X = (TRACK_X0 + TRACK_X1) / 2

# ----------------------------------------------------------------- score
NOTE_HZ = {
    "C3": 130.81, "E3": 164.81, "F3": 174.61, "F#3": 185.00, "G3": 196.00,
    "E4": 329.63, "G4": 392.00, "A4": 440.00, "A#4": 466.16, "B4": 493.88,
    "C5": 523.25, "C#5": 554.37, "D5": 587.33, "E5": 659.25, "G5": 783.99,
}
E8 = 0.2625                 # one eighth note
T0 = 0.30                   # first note after the click ramp
P2 = 4.50                   # start of the 3-track harmony
FINAL = 9.50                # final chord / reveal


def seq(start, items, gate):
    """items: list of (note or None, eighths). Returns note events."""
    out, t = [], start
    for name, n in items:
        if name:
            out.append((t, n * E8 * gate(n), NOTE_HZ[name]))
        t += n * E8
    return out


stacc = lambda n: 0.62 if n == 1 else (0.80 if n == 2 else 0.86)

melody = seq(T0, [("E5", 1), ("E5", 1), (None, 1), ("E5", 1), (None, 1),
                  ("C5", 1), ("E5", 2), ("G5", 4), ("G4", 4)], stacc)
melody += seq(P2, [("C5", 3), ("G4", 3), ("E4", 3), ("A4", 2), ("B4", 2),
                   ("A#4", 1), ("A4", 2), ("G4", 3)], stacc)
harmony = seq(P2, [("E5", 3), ("C5", 3), ("G4", 3), ("C5", 2), ("D5", 2),
                   ("C#5", 1), ("C5", 2), ("E5", 3)], stacc)
bass = seq(P2, [("G3", 3), ("E3", 3), ("C3", 3), ("F3", 2), ("G3", 2),
                ("F#3", 1), ("F3", 2), ("C3", 3)], stacc)
STAB = 0.30
melody.append((FINAL, STAB, NOTE_HZ["E5"]))
harmony.append((FINAL, STAB, NOTE_HZ["C5"]))
bass.append((FINAL, STAB, NOTE_HZ["G3"]))

TRACKS = [
    # name, colour, events, volume, has click ramp
    dict(name="melody", col=YELLOW, ev=melody, vol=0.50, ramp=True, y=0),
    dict(name="harmony", col=CYAN, ev=harmony, vol=0.24, ramp=False, y=-SPACING),
    dict(name="bass", col=PURPLE, ev=bass, vol=0.42, ramp=False, y=SPACING),
]

# ----------------------------------------------------------------- signals
NS = int(DURATION * SR * OS)
t_os = np.arange(NS) / (SR * OS)


def build_track_signals(tr):
    """Per-sample frequency, gate and bounce phase (in hits)."""
    f = np.zeros(NS)
    gate = np.zeros(NS)
    starts = []
    if tr["ramp"]:
        m = t_os < T0
        # exponential acceleration 15 hits/s -> first note frequency
        f[m] = 15 * (NOTE_HZ["E5"] / 15) ** ((t_os[m] / T0) ** 1.6)
        gate[m] = 1
        starts.append(0)
    for (s, d, hz) in tr["ev"]:
        a, b = int(s * SR * OS), int((s + d) * SR * OS)
        if tr["ramp"] and abs(s - T0) < 1e-6:
            pass                                    # continue ramp phase
        else:
            starts.append(a)
        f[a:b] = hz
        gate[a:b] = 1
    # integrate phase; reset to 0.5 (ball centred) at each new note
    inc = f / (SR * OS) * gate
    phase = np.cumsum(inc)
    for a in starts:
        phase[a:] += 0.5 - phase[a]
    phase[gate == 0] = 0.5
    return f, gate, phase


SIG = [build_track_signals(tr) for tr in TRACKS]


def synth():
    mix = np.zeros(NS)
    for tr, (f, gate, ph) in zip(TRACKS, SIG):
        fr = np.mod(ph, 1.0)
        fs = np.maximum(f, 1.0)
        # pulse width: 25% duty, capped to a 1.5 ms click at slow speeds
        duty = np.minimum(0.25, 0.0015 * fs)
        x = (fr < duty).astype(float) * gate
        # envelope per note: 2 ms attack, light decay for punch, 4 ms fade-out
        idx = np.flatnonzero(np.diff(np.concatenate([[0], gate, [0]])))
        e = np.zeros(NS)
        for i in range(0, len(idx), 2):
            a, b = idx[i], idx[i + 1]
            n = np.arange(b - a)
            seg = 1 - np.exp(-n / (0.002 * SR * OS))
            seg *= 0.72 + 0.28 * np.exp(-n / (0.12 * SR * OS))
            seg *= np.clip((b - a - n) / (0.004 * SR * OS), 0, 1)
            e[a:b] = seg
        x = (fr < duty).astype(float) - duty        # pulse train minus its DC
        x *= gate
        mix += tr["vol"] * x * e
    out = resample_poly(mix, 1, OS)
    sos = butter(2, 25, "highpass", fs=SR, output="sos")
    out = sosfilt(sos, out)
    sos = butter(2, 9000, "lowpass", fs=SR, output="sos")
    out = sosfilt(sos, out)
    # short room tail so the final stab rings out after the freeze
    ir_len = int(0.6 * SR)
    rng = np.random.default_rng(3)
    ir = rng.standard_normal(ir_len) * np.exp(-np.arange(ir_len) / (0.12 * SR))
    ir *= 0.025
    ir[0] = 1.0
    out = np.convolve(out, ir)[: len(out)]
    out /= np.max(np.abs(out)) + 1e-9
    out *= 0.89
    return out


# ----------------------------------------------------------------- per-frame data
def lin_interp(arr, t):
    i = np.clip(t * SR * OS, 0, NS - 1)
    i0 = np.floor(i).astype(int)
    i1 = np.minimum(i0 + 1, NS - 1)
    w = i - i0
    return arr[i0] * (1 - w) + arr[i1] * w


def hit_times(ph, gate):
    k = np.floor(ph)
    ch = np.flatnonzero((np.diff(k) != 0) & (gate[1:] > 0)) + 1
    times = ch / (SR * OS)
    side = (k[ch].astype(int) % 2)      # 1 -> hit bottom, 0 -> hit top
    return times, side


def precompute_frames():
    data = []
    for tr, (f, gate, ph) in zip(TRACKS, SIG):
        ht, hs = hit_times(ph, gate)
        amp = np.zeros(NFRAMES)
        fdisp = np.zeros(NFRAMES)
        a = 0.0
        flast = 0.0
        for i in range(NFRAMES):
            t = i / FPS
            j = min(int(t * SR * OS), NS - 1)
            g = gate[j]
            if g > 0:
                flast = f[j]
            if t >= FINAL + STAB:
                g = 0
            target = g * 1.0
            a += (target - a) * (0.6 if target > a else 0.10)
            amp[i] = a
            fdisp[i] = flast
        data.append(dict(ht=ht, hs=hs, amp=amp, fdisp=fdisp))
    return data


# ----------------------------------------------------------------- text
@lru_cache(maxsize=None)
def font(path, size):
    return ImageFont.truetype(path, size)


def emoji_img(ch, height):
    f = ImageFont.truetype(F_EMOJI, 109)
    im = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((10, 10), ch, font=f, embedded_color=True)
    im = im.crop(im.getbbox())
    s = height / im.height
    return im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)


def runs_image(runs, tracking=0):
    """runs: list of (text|PIL.Image, font_path, size, rgb). Returns RGBA."""
    pieces = []
    for text, fp, size, rgb in runs:
        if isinstance(text, Image.Image):
            pieces.append(("img", text, size))
            continue
        f = font(fp, size)
        pieces.append(("txt", text, f, rgb))
    # measure
    asc = max(p[2].getmetrics()[0] for p in pieces if p[0] == "txt")
    desc = max(p[2].getmetrics()[1] for p in pieces if p[0] == "txt")
    widths = []
    for p in pieces:
        if p[0] == "img":
            widths.append(p[1].width + p[2])     # size used as left margin
        else:
            if tracking:
                widths.append(sum(p[2].getlength(c) + tracking for c in p[1]) - tracking)
            else:
                widths.append(p[2].getlength(p[1], features=["tnum"]))
    tw = int(math.ceil(sum(widths))) + 4
    im = Image.new("RGBA", (tw, asc + desc + 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    x = 2
    for p, w in zip(pieces, widths):
        if p[0] == "img":
            em = p[1]
            im.alpha_composite(em, (int(x + p[2]), int(4 + asc * 0.5 - em.height * 0.5 + asc * 0.12)))
        else:
            col = tuple(int(c * 255) for c in p[3]) + (255,)
            if tracking:
                xx = x
                for c in p[1]:
                    d.text((xx, 4), c, font=p[2], fill=col)
                    xx += p[2].getlength(c) + tracking
            else:
                d.text((x, 4), p[1], font=p[2], fill=col, features=["tnum"])
        x += w
    return im


WHITE = (1, 1, 1)
GRAY = (0.8, 0.8, 0.8)


def build_static_text():
    title_size = 66
    l1 = runs_image([("Count the bounces", F_BOLD, title_size, WHITE),
                     (emoji_img("🔊", int(title_size * 0.95)), None, 18, None)])
    l2 = runs_image([("Can you name the ", F_BOLD, title_size, WHITE),
                     ("song", F_BOLD, title_size, YELLOW),
                     ("?", F_BOLD, title_size, WHITE)])
    sub = runs_image([("HITS PER SECOND", F_SEMI, 34, GRAY)], tracking=7)
    cta = runs_image([("Did you get it?", F_BOLD, 64, WHITE),
                      (emoji_img("🎵", 60), None, 16, None)])
    dot = (0.85, 0.85, 0.85)
    rev = runs_image([("E", F_XBOLD, 168, YELLOW), ("  •  ", F_XBOLD, 168, dot),
                      ("C", F_XBOLD, 168, CYAN), ("  •  ", F_XBOLD, 168, dot),
                      ("G", F_XBOLD, 168, PURPLE)])
    return dict(l1=l1, l2=l2, sub=sub, cta=cta, rev=rev)


NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def note_letter(hz):
    if hz < 8:
        return "–"
    n = round(12 * math.log2(hz / 440.0)) + 9     # semitones from C
    return NAMES[n % 12]


@lru_cache(maxsize=256)
def counter_img(num, letter):
    return runs_image([(f"{num}", F_XBOLD, 172, WHITE),
                       (" = ", F_XBOLD, 172, WHITE),
                       (letter, F_XBOLD, 172, YELLOW)])


# ----------------------------------------------------------------- render
def ease_out(x):
    x = min(max(x, 0.0), 1.0)
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def build_bg_gradient():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.sqrt(((xx - W / 2) / (W * 0.85)) ** 2 + ((yy - H * 0.47) / (H * 0.6)) ** 2)
    r = np.clip(r, 0, 1)[..., None]
    inner = np.array([0x1E, 0x3F, 0x99], np.float32) / 255
    outer = np.array([0x0A, 0x24, 0x72], np.float32) / 255
    return inner * (1 - r) + outer * r


G = {}


def init_worker():
    G["text"] = build_static_text()
    G["bg"] = build_bg_gradient()
    G["frames"] = precompute_frames()
    G["sig"] = SIG


def paste(canvas, im, cx, cy, alpha=1.0, scale=1.0):
    """Alpha-composite PIL RGBA image centred at (cx, cy) onto float canvas."""
    if alpha <= 0.002:
        return
    if scale != 1.0:
        im = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))),
                       Image.LANCZOS)
    a = np.asarray(im, np.float32) / 255
    x0 = int(round(cx - im.width / 2))
    y0 = int(round(cy - im.height / 2))
    xs, ys = max(0, x0), max(0, y0)
    xe, ye = min(W, x0 + im.width), min(H, y0 + im.height)
    if xe <= xs or ye <= ys:
        return
    sub = a[ys - y0:ye - y0, xs - x0:xe - x0]
    al = sub[..., 3:4] * alpha
    canvas[ys:ye, xs:xe] = canvas[ys:ye, xs:xe] * (1 - al) + sub[..., :3] * al


def ball_y_from_phase(ph, cy):
    travel = GAP / 2 - LINE_W / 2 - BALL_R
    s = np.mod(ph, 2.0)
    tri = np.where(s < 1, s, 2 - s)        # 0 at top hit, 1 at bottom hit
    return cy - travel + 2 * travel * tri


def draw_track(ctx, ti, t, cy, alpha):
    tr = TRACKS[ti]
    fr = G["frames"][ti]
    f, gate, ph = G["sig"][ti]
    col = tr["col"]
    fi = min(int(round(t * FPS)), NFRAMES - 1)
    frozen = t >= FINAL + STAB
    ytop, ybot = cy - GAP / 2, cy + GAP / 2

    # ---- left visualizer bar
    amp = fr["amp"][fi]
    fn = min(fr["fdisp"][fi] / 800.0, 1.0)
    L = WAVE_MAX * (0.12 + 0.88 * fn) * amp
    if L > 1.5:
        hmax = GAP / 2 + 12
        lam = max(7.0, 7200.0 / max(fr["fdisp"][fi], 60))
        pts = []
        n = max(8, int(L / 2))
        for k in range(n + 1):
            d = L * k / n
            taper = 1 - (d / L) ** 4.0
            wv = 0.55 + 0.45 * abs(math.sin(2 * math.pi * (d / lam) + t * 38))
            pts.append((TRACK_X0 - d, hmax * taper * wv * (0.35 + 0.65 * amp)))
        ctx.set_source_rgba(*col, 0.35 * alpha)
        ctx.move_to(TRACK_X0, cy - pts[0][1])
        for x, h in pts:
            ctx.line_to(x, cy - h)
        for x, h in reversed(pts):
            ctx.line_to(x, cy + h)
        ctx.close_path()
        ctx.fill_preserve()
        ctx.set_source_rgba(*col, 0.55 * alpha)
        ctx.set_line_width(1.5)
        ctx.stroke()

    # ---- track lines
    ctx.set_source_rgba(*col, alpha)
    for y in (ytop, ybot):
        ctx.rectangle(TRACK_X0, y - LINE_W / 2, TRACK_X1 - TRACK_X0, LINE_W)
    ctx.fill()

    # ---- impact flashes
    ht, hs = fr["ht"], fr["hs"]
    if not frozen:
        lo, hi = np.searchsorted(ht, [t - 0.15, t])
        for side, y in ((0, ytop), (1, ybot)):
            sel = ht[lo:hi][hs[lo:hi] == side]
            inten = float(np.minimum(1.0, np.sum(np.exp(-(t - sel) / 0.035)))) if len(sel) else 0.0
            if inten > 0.01:
                g = cairo.RadialGradient(BALL_X, y, 0, BALL_X, y, 46)
                g.add_color_stop_rgba(0, 1, 1, 1, 0.95 * inten * alpha)
                g.add_color_stop_rgba(0.25, *col, 0.75 * inten * alpha)
                g.add_color_stop_rgba(1, *col, 0)
                ctx.save()
                ctx.translate(BALL_X, y)
                ctx.scale(1.0, 0.32)
                ctx.translate(-BALL_X, -y)
                ctx.set_source(g)
                ctx.arc(BALL_X, y, 46, 0, 2 * math.pi)
                ctx.fill()
                ctx.restore()

    # ---- ball (motion blurred over the frame interval)
    if frozen:
        ys = np.array([float(cy)])
    else:
        ts = t - 1 / FPS + np.linspace(0, 1 / FPS, 48)
        ts = np.clip(ts, 0, DURATION)
        ys = ball_y_from_phase(lin_interp(ph, ts), cy)
    y0, y1 = float(ys.min()), float(ys.max())
    span = y1 - y0
    if span > 1.0:
        # streak: dimmer the longer it is (light spread over distance)
        a_streak = max(0.55, min(1.0, 14.0 / span + 0.45)) * alpha
        ctx.set_source_rgba(1, 1, 1, a_streak)
        ctx.set_line_width(BALL_R * 2)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        ctx.move_to(BALL_X, y0)
        ctx.line_to(BALL_X, y1)
        ctx.stroke()
        if span < GAP * 0.55:                # slow: crisp ball at the end
            ctx.set_source_rgba(1, 1, 1, alpha)
            ctx.arc(BALL_X, float(ys[-1]), BALL_R, 0, 2 * math.pi)
            ctx.fill()
    else:
        ctx.set_source_rgba(1, 1, 1, alpha)
        ctx.arc(BALL_X, float(ys[-1]), BALL_R, 0, 2 * math.pi)
        ctx.fill()


def render_frame(i):
    t = (i + 1) / FPS            # frame shows the interval ending at t
    t = min(t, DURATION)
    surf = cairo.ImageSurface(cairo.FORMAT_RGB24, W, H)
    ctx = cairo.Context(surf)
    ctx.set_source_rgb(0, 0, 0)
    ctx.paint()

    # phase-2 track expansion
    k = ease_out((t - (P2 - 0.08)) / 0.38)
    for ti in (1, 2):
        if k > 0:
            draw_track(ctx, ti, t, CY + TRACKS[ti]["y"] * k, k)
    draw_track(ctx, 0, t, CY, 1.0)

    surf.flush()
    buf = np.frombuffer(surf.get_data(), np.uint8).reshape(H, surf.get_stride() // 4, 4)
    shapes = buf[:, :W, 2::-1].astype(np.float32) / 255     # BGRA -> RGB

    # bloom
    small = cv2.resize(shapes, (W // 4, H // 4), interpolation=cv2.INTER_AREA)
    g1 = cv2.GaussianBlur(small, (0, 0), 2.5)
    g2 = cv2.GaussianBlur(small, (0, 0), 9.0)
    glow = cv2.resize(g1 * 1.1 + g2 * 1.6, (W, H), interpolation=cv2.INTER_LINEAR)

    # background
    bk = ease_in_out((t - FINAL) / 0.45)
    if bk > 0:
        canvas = G["bg"] * bk
    else:
        canvas = np.zeros((H, W, 3), np.float32)
    canvas = 1 - (1 - canvas) * (1 - shapes)                 # screen
    canvas = canvas + glow * 0.9
    np.clip(canvas, 0, 1, out=canvas)

    # text
    tx = G["text"]
    paste(canvas, tx["l1"], W / 2, H * 0.18 - 46)
    paste(canvas, tx["l2"], W / 2, H * 0.18 + 46)

    out_k = ease_in_out((t - (FINAL + 0.08)) / 0.28)
    cy_c = H * 0.72
    if out_k < 1:
        fr = G["frames"][0]
        f, gate, _ = G["sig"][0]
        j = min(int(t * SR * OS), NS - 1)
        hz = f[j] if gate[j] > 0 else 0.0
        if hz == 0:
            # hold the last frequency briefly between staccato notes
            back = int(0.10 * SR * OS)
            seg = gate[max(0, j - back):j]
            if seg.size and seg.max() > 0:
                last = np.flatnonzero(seg)[-1] + max(0, j - back)
                hz = f[last]
        num = int(round(hz))
        letter = note_letter(hz) if hz > 0 else "–"
        a = 1 - out_k
        dy = -40 * out_k
        paste(canvas, counter_img(num, letter), W / 2, cy_c + dy, a)
        paste(canvas, tx["sub"], W / 2, cy_c + 118 + dy, a)

    rk = ease_out((t - (FINAL + 0.30)) / 0.32)
    if rk > 0:
        paste(canvas, tx["rev"], W / 2, cy_c, rk, 0.86 + 0.14 * rk)
    ck = ease_out((t - (FINAL + 0.62)) / 0.35)
    if ck > 0:
        paste(canvas, tx["cta"], W / 2, cy_c + 150 + 20 * (1 - ck), ck)

    return (canvas * 255 + 0.5).astype(np.uint8).tobytes()


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    stereo = np.stack([pcm, pcm], 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(stereo.tobytes())


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "bounce_melody.mp4"
    only = os.environ.get("FRAMES")             # e.g. "0,120,300" for stills
    if only:
        init_worker()
        for s in only.split(","):
            fi = int(s)
            Image.frombytes("RGB", (W, H), render_frame(fi)).save(f"frame_{fi:04d}.png")
        return
    wav = os.path.splitext(out)[0] + ".wav"
    print("synthesising audio…", flush=True)
    write_wav(wav, synth())
    print("rendering frames…", flush=True)
    ff = subprocess.Popen([
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
        "-i", wav,
        "-c:v", "libx264", "-preset", "slow", "-crf", "15", "-pix_fmt", "yuv420p",
        "-profile:v", "high", "-movflags", "+faststart",
        "-c:a", "aac", "-b:a", "320k", "-shortest", out], stdin=subprocess.PIPE)
    with Pool(os.cpu_count(), initializer=init_worker) as pool:
        for n, fb in enumerate(pool.imap(render_frame, range(NFRAMES), chunksize=4)):
            ff.stdin.write(fb)
            if n % 60 == 0:
                print(f"  {n}/{NFRAMES}", flush=True)
    ff.stdin.close()
    ff.wait()
    os.remove(wav)
    print("done ->", out)


if __name__ == "__main__":
    main()
