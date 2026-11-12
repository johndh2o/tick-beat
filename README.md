# Tick Beat — a RuneLite plugin

Plays your own playlist with every beat locked to the OSRS game tick.

One game tick is 600 ms, so the tick runs at **100 BPM**. Songs at 100 BPM line
up one beat per tick. Songs at 200 BPM put two beats in each tick, and songs at
50 BPM put one beat on every other tick. Songs a few BPM off (97–103) are sped
up or slowed down slightly to fit.

## Run it (Mac)

1. Install **IntelliJ IDEA Community** and a **Java 11 JDK** (for example
   Eclipse Temurin 11). IntelliJ can download the JDK for you under
   *File → Project Structure → SDK*.
2. *File → Open* → choose this `tick-beat` folder. Let Gradle finish importing.
3. Open `src/test/java/com/tickbeat/TickBeatPluginTest.java` and click the green
   ▶ next to `main`. A RuneLite dev client opens with Tick Beat already
   installed. In that run configuration, add `-ea` to *VM options* and
   `--developer-mode` to *Program arguments*.
4. Log in. Open the plugin's settings (wrench icon → search "Tick Beat").

On first run the plugin creates `~/.runelite/plugin-data/tick-beat/` with a
`playlist.txt` template.

## Finding 100 BPM songs

- **Tunebat** or **GetSongBPM**: search by BPM (type 100) and filter by genre.
  Anything from 97 to 103 works.
- **Spotify**: searching "100 BPM" brings up tempo playlists.
- **SongData.io**: sorts any public Spotify playlist by BPM, so you can pull
  the 98–102 BPM songs out of your own playlists.
- Also try **50 BPM** (slow songs) and **200 BPM** (fast punk and drum & bass
  sit near there).
- Always check the BPM yourself in your DAW. Listed BPMs are often rounded,
  and they're sometimes half or double the real tempo.
- Or produce your own tracks at 100 BPM. They sync perfectly, and they're the
  only songs you could ship with the plugin.

## Add songs automatically

Put your songs (MP3, WAV, FLAC or OGG) in `~/.runelite/plugin-data/tick-beat/`, then run:

```
python -m pip install numpy soundfile
python tools/analyze_songs.py
```

For every song it:
- converts it to WAV,
- measures the tempo and finds every beat,
- **quantizes** it: each beat is stretched or squeezed to exactly 600 ms, so
  even songs with a live, drifting drummer land on every tick. Slow drift is
  removed; the small feel of individual hits is kept.
- writes `playlist.txt`.

Songs too far from 100/50/200 BPM are listed but commented out. Your originals
are never changed: MP3s stay as they are, and WAVs you added yourself are moved
to `originals/` first. Run it again after adding songs; existing lines are kept
(use `--redo` to redo everything, `--no-quantize` to only measure).

It only needs NumPy and soundfile. Windows Smart App Control can block
librosa/SciPy, so they aren't used.

## Add songs by hand

1. Put `.wav` or `.aiff` files in `~/.runelite/plugin-data/tick-beat/`. MP3s need
   converting first (e.g. `ffmpeg -i song.mp3 song.wav`, or export from your DAW).
2. Add a line to `playlist.txt` for each song:

   ```
   file name | BPM | first downbeat in ms
   my song.wav | 100 | 412
   ```

   - **BPM**: be exact. A song that is really 99.7 BPM but listed as 100 will
     still stay in sync, because the engine corrects for it, but it will be
     pushed around more.
   - **First downbeat**: how far into the file the first beat lands. Find it in
     your DAW: drop the file at bar 1, zoom in on the first kick or transient,
     and read off the time. If this is wrong, the song stays locked to the
     tick but sits between beats.
3. Turn *Play music* off and on again to reload the folder.

Files in the folder that aren't listed in `playlist.txt` play as 100 BPM with
the first beat at 0 ms.

## Side panel (music-note icon in the RuneLite sidebar)

- **Play/Stop**, **Next**, and **Flip beat**: if a song has the tick on the upbeat, press Flip beat while it plays. It moves half a beat and is saved to `playlist.txt`.
- **Music volume** slider
- **Metronome**: pick one of
  - **Game sound**: the game's own tick sound, the same one RuneLite's
    Metronome plugin plays. It fires on the raw game tick.
  - **Synced click**: a click built into the music stream. It always lands
    exactly on the music's beat. It also works with no songs loaded, as a
    plain metronome.
  - **Off**
- **Metronome volume** slider
- **Music offset** slider (−300 to +300 ms): drag it to slide the music
  against the metronome. You hear the change immediately, and it's saved when
  you let go.

### Lining the music up with the game metronome

1. Set Metronome to **Game sound** and play a song with a hard kick or snare
   on every beat.
2. Drag **Music offset** until the kick and the tick sound hit together.
   Positive values move the music later; negative values move it earlier.
3. Done. The offset is saved and applies to every song.

If you use RuneLite's own Metronome plugin, turn it off, or set this plugin's
metronome to Off, so you don't get two tick sounds.

## Settings (wrench → Tick Beat)

The same controls as the panel, plus:

| Setting | What it does |
|---|---|
| Shuffle | |
| Next song hotkey | Skip a song |
| Only play in game | Music waits until you log in and stops at the login screen (on by default) |
| Max tempo change | Songs that would need more speed change than this are skipped. Above about 3% you'll hear the pitch shift (a 6% change is one semitone) |
| Show now playing | Overlay with the song name and the current sync error |

## How the sync works

- Ticks reach the client with network jitter, so the plugin doesn't snap to
  each one. It keeps a steady 600 ms grid and pulls it slightly toward each
  tick as it arrives.
- When a song starts, its first downbeat is lined up with an upcoming tick
  (with at most one tick of silence before it).
- While the song plays, the engine compares the beat you're hearing right now
  with where the game is in the tick, and nudges playback speed by up to ±2%
  to close the gap. A big jump, such as a lag spike or reconnect, is fixed
  with a single skip.
- The **Synced click** uses the same grid as the music, so the two never drift
  apart. The **Game sound** fires on each raw tick as it arrives, so it can wander
  a few milliseconds around the music with network jitter.
- When you're logged out, the grid keeps going at 600 ms on its own.

The offline test shows how accurate this is without the game (it plays a click
track against simulated ticks with 30 ms of network jitter):

```
./gradlew syncTest
  98.0 BPM, first beat  250 ms, jitter 30 ms, offset   +0 ms -> avg off 5.0 ms, worst 12.6 ms  OK
 101.5 BPM, first beat    0 ms, jitter 30 ms, offset   +0 ms -> avg off 5.0 ms, worst 12.6 ms  OK
 196.0 BPM, first beat 1234 ms, jitter 30 ms, offset   +0 ms -> avg off 5.1 ms, worst 12.6 ms  OK
  50.8 BPM, first beat   80 ms, jitter 40 ms, offset   +0 ms -> avg off 6.7 ms, worst 16.6 ms  OK
 100.0 BPM, first beat  300 ms, jitter 30 ms, offset  +80 ms -> avg off 5.3 ms, worst 12.6 ms  OK
  99.0 BPM, first beat    0 ms, jitter 30 ms, offset -120 ms -> avg off 5.3 ms, worst 12.5 ms  OK
metronome only (no songs)                                    -> worst 0.0 ms  OK
```

## Limits

- WAV/AIFF only. Java plays these with no extra libraries. MP3 support would
  need a decoder library added to `build.gradle`.
- Speed changes use simple resampling, so the pitch moves with the tempo. Keep
  songs close to 100 BPM.
- Each song is loaded fully into memory: about 40 MB for a 4-minute stereo track.

## Plugin Hub

To publish it, push this repo to GitHub and open a PR on
`runelite/plugin-hub` that adds a file pointing at your repo and commit.
**Don't include copyrighted songs.** The plugin plays files from the user's
own folder. Songs you wrote and produced yourself are fine to share.
