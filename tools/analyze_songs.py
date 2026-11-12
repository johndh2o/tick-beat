"""
Tick Beat song analyzer: run once over your music folder.

For every song in the folder it:
  1. converts MP3 / FLAC / OGG to WAV (the plugin plays WAV),
  2. measures the exact BPM,
  3. finds where the beats fall,
  4. quantizes it: every beat is stretched or squeezed to exactly 600 ms, so even
     songs with a live, drifting drummer land on every tick. The groove inside each
     beat is kept; only slow tempo drift is removed.
  5. writes playlist.txt.

Your original files are never changed: MP3s stay as they are, and WAVs you put in
yourself are moved to an "originals" folder before the quantized copy is written.

Songs already listed in playlist.txt are left alone, so you can run it again
after adding new songs. Use --redo to re-analyze everything.

Needs only NumPy and soundfile (no librosa/SciPy, which Windows Smart App
Control can block).

Setup (once):   python -m pip install numpy soundfile
Run:            python analyze_songs.py
                python analyze_songs.py "D:\\some\\other\\folder"
                python analyze_songs.py --no-quantize   (just measure, don't stretch)
"""

import argparse
import shutil
import sys
from pathlib import Path

try:
    import numpy as np
    import soundfile as sf
except ImportError:
    sys.exit("Missing libraries. Run:  python -m pip install numpy soundfile")

AUDIO_IN = {".mp3", ".flac", ".ogg", ".wav", ".aif", ".aiff"}
PLAYABLE = {".wav", ".aif", ".aiff"}
MAX_CHANGE = 0.04  # matches the plugin's default "Max tempo change" of 4%
HOP_SEC = 0.005  # onset envelope resolution: 5 ms
# The onset detector reacts slightly after the real attack; measured on test tracks.
DETECTOR_LAG_MS = 0.0

HEADER = """# Tick Beat playlist
# One song per line:   file name | BPM | first beat (ms)
# Written by analyze_songs.py. Edit any line by hand if a song sounds off the beat;
# re-running the script keeps your edits (use --redo to start over).
"""


def beats_per_tick(bpm):
    """Same snapping as the plugin: 100 BPM = 1 beat per tick, 200 = 2, 50 = 0.5."""
    options = [0.5, 1, 2, 4]
    return min(options, key=lambda o: abs(np.log((bpm / 100.0) / o)))


def onset_envelope(data, sr):
    """Spectral flux: how much new sound energy starts in each 5 ms step."""
    y = data.mean(axis=1)
    hop = max(1, int(round(sr * HOP_SEC)))
    n_fft = 512
    if len(y) < n_fft * 4:
        raise ValueError("song is too short")
    window = np.hanning(n_fft).astype(np.float32)
    frames = 1 + (len(y) - n_fft) // hop
    env = np.empty(frames, dtype=np.float64)
    prev = None
    block = 2048  # frames per batch, keeps memory use small
    for start in range(0, frames, block):
        stop = min(frames, start + block)
        idx = np.arange(start, stop)[:, None] * hop + np.arange(n_fft)[None, :]
        spec = np.abs(np.fft.rfft(y[idx] * window, axis=1))
        logspec = np.log1p(100.0 * spec)
        if prev is None:
            diff = np.vstack([np.zeros((1, logspec.shape[1])), np.diff(logspec, axis=0)])
        else:
            diff = np.diff(np.vstack([prev, logspec]), axis=0)
        env[start:stop] = np.maximum(diff, 0).sum(axis=1)
        prev = logspec[-1:]
    # Remove the slowly changing loudness so only the hits remain.
    w = int(0.5 / HOP_SEC)
    local = np.convolve(env, np.ones(w) / w, mode="same")
    env = np.maximum(env - local, 0)
    global LAST_KICK_ENV
    LAST_KICK_ENV = kick_envelope(y, sr, hop, frames)
    # Frame i covers audio centred at i*hop + n_fft/2.
    t0 = (n_fft / 2) / sr
    dt = hop / sr
    return env, t0, dt


LAST_KICK_ENV = None


def kick_envelope(y, sr, hop, frames):
    """How sharply the bass below 150 Hz (kick drums) rises, every 5 ms."""
    spec = np.fft.rfft(y)
    spec[np.fft.rfftfreq(len(y), 1.0 / sr) > 150.0] = 0
    bass = np.abs(np.fft.irfft(spec, n=len(y)))
    w = max(1, int(0.01 * sr))
    level = np.convolve(bass, np.ones(w) / w, mode="same")[: frames * hop : hop]
    rise = np.maximum(np.diff(level, prepend=level[0]), 0)
    out = np.zeros(frames)
    out[: len(rise)] = rise
    # Line up with the main envelope's frame timing (its frames are centred n_fft/2 later).
    return np.roll(out, -int(round(256 / hop)))


def on_the_kick(beats, times, kick_env):
    """
    The beat tracker can lock onto the off-beats ("and"s) when hi-hats or offbeat
    chords are loud. Kick drums almost always land on the beat, so compare kick
    energy on the found beats with kick energy halfway between them, and move to
    the halfway points if that's where the kicks are. Returns (beats, moved).
    """
    if kick_env is None or len(beats) < 8:
        return beats, False
    dt = times[1] - times[0]
    win = max(1, int(0.03 / dt))

    def energy(points):
        idx = np.round((points - times[0]) / dt).astype(int)
        idx = idx[(idx >= win) & (idx < len(kick_env) - win)]
        return sum(kick_env[i - win:i + win + 1].max() for i in idx) / max(1, len(idx))

    mids = (beats[:-1] + beats[1:]) / 2
    on, off = energy(beats), energy(mids)
    if off > on * 1.25:
        # Keep the same number of beats: extend by half a beat at the end.
        last = beats[-1] + (beats[-1] - beats[-2]) / 2
        return np.append(mids, last), True
    return beats, False


def fold_strength(env, times, period, bins=48):
    """How strongly the hits line up when the song is folded over `period` seconds."""
    phase = np.mod(times, period) / period
    hist = np.bincount((phase * bins).astype(np.int64) % bins, weights=env, minlength=bins)
    return hist


def peak_score(hist):
    k = np.array([1, 2, 1], dtype=float) / 4
    padded = np.concatenate([hist[-1:], hist, hist[:1]])
    smooth = np.convolve(padded, k, mode="valid")
    return (smooth.max() - smooth.mean()) / (smooth.mean() + 1e-12), smooth


def analyze(data, sr):
    """Returns (bpm, first_beat_seconds, confidence 0..1, env, times)."""
    env, t0, dt = onset_envelope(data, sr)
    times = t0 + dt * np.arange(len(env))
    # Only the strong frames matter; this makes the search much faster.
    keep = env > np.percentile(env, 80)
    e, t = env[keep], times[keep]

    def score(bpm):
        return peak_score(fold_strength(e, t, 60.0 / bpm))[0]

    # Search one octave (70-140 BPM). Every tempo has a version in this range
    # (196 -> 98, 50 -> 100), and the plugin handles those the same way.
    coarse = np.arange(70.0, 140.0, 0.25)
    s = np.array([score(b) for b in coarse])
    # Look closely around the best few candidates.
    best_bpm, best_s = None, -1.0
    for c in coarse[np.argsort(s)[-6:]]:
        for b in np.arange(c - 0.3, c + 0.3, 0.01):
            v = score(b)
            if v > best_s:
                best_s, best_bpm = v, b
    for b in np.arange(best_bpm - 0.01, best_bpm + 0.01, 0.001):
        v = score(b)
        if v > best_s:
            best_s, best_bpm = v, b
    bpm = float(best_bpm)

    # Beat position: fold the whole song over one tick-length cycle and take the
    # strongest point, refined with a parabola through the peak.
    cycle = beats_per_tick(bpm) * 60.0 / bpm
    bins = 240
    _, smooth = peak_score(fold_strength(env, times, cycle, bins))
    peak = int(np.argmax(smooth))
    a, b_, c = smooth[peak - 1], smooth[peak], smooth[(peak + 1) % bins]
    denom = a - 2 * b_ + c
    shift = 0.5 * (a - c) / denom if denom != 0 else 0.0
    first_beat = ((peak + 0.5 + shift) / bins) * cycle - DETECTOR_LAG_MS / 1000.0
    first_beat %= cycle

    confidence = float(min(1.0, best_s / 4.0))
    return bpm, first_beat, confidence, env, times


def beat_map(env, times, period, tightness=60.0):
    """
    Finds every beat in the song, following tempo drift (dynamic-programming
    beat tracking, the same method librosa uses).

    It looks for the chain of beats that lands on the strongest hits while
    keeping the gap between beats close to the song's tempo. Then each beat is
    smoothed against its neighbours (a few beats either side), so slow drift is
    followed but a drummer's small per-hit feel is not "corrected" away.
    Returns beat times in seconds.
    """
    dt = times[1] - times[0]
    P = period / dt  # beat length in frames
    oe = env / (env.std() + 1e-9)
    g = np.exp(-0.5 * (np.arange(-int(P / 8), int(P / 8) + 1) / (P / 32)) ** 2)
    local = np.convolve(oe, g, mode="same")

    n = len(local)
    lo, hi = int(round(P * 0.6)), int(round(P * 1.6))
    gaps = np.arange(hi, lo - 1, -1)  # candidate distances back to the previous beat
    penalty = -tightness * np.log(gaps / P) ** 2
    score = local.copy()
    back = np.full(n, -1)
    for i in range(lo, n):
        a = i - hi
        cand_idx = np.arange(a, i - lo + 1)
        pen = penalty
        if a < 0:
            pen = penalty[-a:]
            cand_idx = cand_idx[-a:]
        cand = score[cand_idx] + pen
        j = int(np.argmax(cand))
        if cand[j] > 0:
            score[i] = local[i] + cand[j]
            back[i] = cand_idx[j]

    # Start from the best-scoring beat near the end and walk backwards.
    tail = int(P * 2)
    i = n - tail + int(np.argmax(score[-tail:]))
    idx = []
    while i >= 0:
        idx.append(i)
        i = back[i]
    beats = times[np.array(idx[::-1])]
    if len(beats) < 16:
        raise ValueError("couldn't follow the beat")

    # Smooth: each beat = straight-line fit through its 9 neighbours.
    k = np.arange(len(beats))
    sm = np.empty_like(beats)
    for j in range(len(beats)):
        lo_j, hi_j = max(0, j - 4), min(len(beats), j + 5)
        c = np.polyfit(k[lo_j:hi_j], beats[lo_j:hi_j], 1)
        sm[j] = np.polyval(c, j)
    return sm


def render_quantized(data, sr, beats, tick=0.6):
    """
    Writes the song with beat k moved to exactly first_beat + k * 600 ms.
    Between beats the audio is evenly stretched or squeezed. Returns (audio, first_beat_seconds).
    """
    first = beats[0]
    out_pts = first + tick * np.arange(len(beats))
    in_pts = beats.astype(np.float64)
    duration = len(data) / sr
    r_first = (in_pts[1] - in_pts[0]) / tick
    r_last = (in_pts[-1] - in_pts[-2]) / tick
    # Before the first beat and after the last one, keep the nearest beat's speed.
    out_pts = np.concatenate([[0.0], out_pts, [out_pts[-1] + (duration - in_pts[-1]) / r_last]])
    in_pts = np.concatenate([[in_pts[0] - first * r_first], in_pts, [duration]])
    n_out = int(out_pts[-1] * sr)
    src_pos = np.interp(np.arange(n_out) / sr, out_pts, in_pts) * sr
    idx = np.arange(len(data))
    out = np.empty((n_out, data.shape[1]), dtype=np.float32)
    for ch in range(data.shape[1]):
        out[:, ch] = np.interp(src_pos, idx, data[:, ch], left=0.0, right=0.0)
    return out, first


def read_playlist(path):
    entries = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "|" in line:
                entries[line.split("|")[0].strip()] = line
    return entries


def main():
    # The plugin's folder; older test builds used .runelite/tick-beat.
    default = Path.home() / ".runelite" / "plugin-data" / "tick-beat"
    if not default.is_dir() and (Path.home() / ".runelite" / "tick-beat").is_dir():
        default = Path.home() / ".runelite" / "tick-beat"
    ap = argparse.ArgumentParser(description="Analyze songs for Tick Beat")
    ap.add_argument("folder", nargs="?", default=str(default))
    ap.add_argument("--redo", action="store_true", help="re-analyze songs already in playlist.txt")
    ap.add_argument("--no-quantize", action="store_true", help="measure only; don't stretch beats onto the grid")
    args = ap.parse_args()

    folder = Path(args.folder)
    if not folder.is_dir():
        sys.exit(f"Folder not found: {folder}")
    playlist = folder / "playlist.txt"
    existing = {} if args.redo else read_playlist(playlist)

    originals = folder / "originals"
    # Group files by name: "song.mp3" and "song.wav" are the same song.
    by_stem = {}
    for p in sorted(folder.iterdir()):
        if p.suffix.lower() in AUDIO_IN:
            by_stem.setdefault(p.stem, []).append(p)
    if not by_stem:
        sys.exit(f"No songs found in {folder}")

    lines, report = [], []
    for stem, files in by_stem.items():
        wav = folder / (stem + ".wav")
        if "|" in wav.name:
            report.append((wav.name, "", "", "", "rename it: file names can't contain |"))
            continue
        if wav.name in existing:
            lines.append(existing[wav.name])
            report.append((wav.name, "", "", "", "kept existing line"))
            continue

        # The untouched source: a compressed file, a WAV saved in originals/, or the WAV itself.
        compressed = [f for f in files if f.suffix.lower() not in PLAYABLE]
        backup = originals / (stem + ".wav")
        if compressed:
            source = compressed[0]
        elif backup.exists():
            source = backup
        else:
            source = files[0]

        print(f"Analyzing {wav.name} ...", flush=True)
        try:
            data, sr = sf.read(str(source), always_2d=True, dtype="float32")
            if data.shape[1] > 2:
                data = data[:, :2]
            bpm, first, conf, env, times = analyze(data, sr)
        except Exception as e:
            report.append((wav.name, "", "", "", f"skipped: {e}"))
            continue

        change = 100.0 * beats_per_tick(bpm) / bpm - 1
        playable = abs(change) <= MAX_CHANGE
        note = "ok"
        out_bpm, out_first = bpm, first
        try:
            if playable and not args.no_quantize:
                beats = beat_map(env, times, 60.0 / bpm)
                beats, moved = on_the_kick(beats, times, LAST_KICK_ENV)
                local_bpm = 60.0 / np.diff(beats)
                bpm = float(np.median(local_bpm))
                change = 100.0 * beats_per_tick(bpm) / bpm - 1
                playable = abs(change) <= MAX_CHANGE
                lo_b, hi_b = np.percentile(local_bpm, [5, 95])
                audio, out_first = render_quantized(data, sr, beats)
                out_bpm = 100.0
                note = f"quantized (tempo wandered {lo_b:.1f}-{hi_b:.1f} BPM)"
                if moved:
                    note += "; moved to the kick"
            else:
                audio = data
            if source == wav and audio is data:
                pass  # a WAV you added, left untouched
            else:
                if source == wav:
                    originals.mkdir(exist_ok=True)
                    shutil.move(str(wav), str(backup))
                sf.write(str(wav), audio, sr, subtype="PCM_16")
        except Exception as e:
            report.append((wav.name, f"{bpm:.2f}", "", "", f"couldn't write: {e}"))
            continue

        line = f"{wav.name} | {out_bpm:.3f} | {out_first * 1000:.0f}"
        if not playable:
            lines.append(f"# too far from 100 BPM ({change:+.1%} speed change): {line}")
            note = f"won't play: needs {change:+.1%} speed change"
        else:
            lines.append(line)
            if conf < 0.35:
                note += "; check by ear: weak beat"
        report.append((wav.name, f"{bpm:.2f}", f"{out_first * 1000:.0f}", f"{conf:.2f}", note))

    if playlist.exists():
        shutil.copy(playlist, folder / "playlist.backup.txt")
    playlist.write_text(HEADER + "\n" + "\n".join(lines) + "\n", encoding="utf-8")

    print()
    w = max(len(r[0]) for r in report)
    print(f"{'song'.ljust(w)}  {'BPM':>7}  {'beat ms':>7}  {'conf':>4}  result")
    for name, bpm, ms, conf, note in report:
        print(f"{name.ljust(w)}  {bpm:>7}  {ms:>7}  {conf:>4}  {note}")
    print(f"\nWrote {playlist}")
    print("In RuneLite, click Play / Stop twice in the Tick Beat panel to load the new playlist.")


if __name__ == "__main__":
    main()
