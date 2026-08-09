# Android Book Reader

A lightweight reader: bookshelf, TXT / EPUB / MOBI / PDF, system speech (TTS) and audio export, on-device OCR, Chinese/English UI. E-books share one reading screen; PDF has its own.

[中文](README.zh.md)

## Features

| Area | Capabilities |
|------|----------------|
| **Bookshelf** | Import TXT/PDF/EPUB/MOBI (and AZW, etc.) or folders; one-level shelves; bind folders; multi-select; search; backup/restore; reading history; long-press clear local records |
| **E-books** | TXT / EPUB / MOBI: large books show the first screen quickly, then **prefetch the rest in the background** |
| **MOBI** | Text body; **view modes**: text / single image / continuous strip; image-only books auto-enter image mode; improved UTF-8 Chinese MOBI |
| **PDF** | Continuous/single page, zoom, per-file crop, fast scroll, TOC, in-book links, TTS, tall-page tiled OCR (partial pages re-scanned), page-range audio export |
| **TTS / export** | System speech, rate control (e.g. `1×`), sentence highlight, lock-screen continue, media controls; export MP3 / M4A / WAV |
| **OCR** | Gallery or camera; on-device; scanned PDFs (long pages split into strips) |
| **Other** | UI color themes (16); **portrait / landscape lock** (menu can stay open); fullscreen; app language; keep screen on / idle screen-off; **auto-close** (idle timeout stops TTS and exits); volume-key page turn (volume while speaking) |

### Bookshelf

- Import files or folders; bind a folder (browse only, no copy)
- List style: compact rows, multi-select checkbox on the right
- Multi-select: move / remove (does not delete source files)
- **Reading history** with correct format labels (TXT / PDF / EPUB / MOBI…)
- Progress and bookmarks are remembered
- Long-press → **Details** (name, path, format, size, progress, last read, …)
- Long-press → **Clear records**: progress, bookmarks, EPUB/MOBI caches, PDF crop/OCR, etc. (shelf entry and source file kept)
- Returning from the reader **refreshes progress** on the shelf
- **Backup / import** shelves and progress (local only)
- Settings → **Check for updates** (download install from the release page)
- Settings → **Appearance → Color theme** (16 skins for chrome UI; reading page colors stay separate)
- Overflow: text-to-speech, OCR

### E-book reading (TXT / EPUB / MOBI)

- **Open**: large books paint the first screen first, then **prefetch in the background** (EPUB/MOBI); restore position without flashing page 1
- **Progress**: EPUB/MOBI show **chapter n/m + within-chapter %**; TXT shows percentage
- **TOC**: auto-detect chapter titles; if empty or wrong, **Custom TOC scan** with wildcards (`*`, `?` one char, `x`, `xxx`, `xxxx`) — e.g. `第x章 *`, `第?回 *`, `Chapter x`; **saved per book** and reopened for edit
- **Reading style**: background textures / solid color / **imported image** (full screen, adjustable opacity, solid underlay color, **stretch or fit-center** scale); text color presets + custom HSV; size and spacing; install custom fonts (long-press to remove); system default font (no bundled commercial typeface)
- **TXT**: auto or manual encoding; simplified↔traditional Chinese
- **EPUB / MOBI styling** (common features): bold / italic / underline / colors; block and inline images; in-book and external links; long-press image → gallery
- **MOBI view modes** (Style → View mode): **Text** (normal reading); **Single image** (one picture at a time, pinch-zoom, side-tap / swipe); **Continuous strip** (vertical image stream). Image-only MOBI auto-enters image mode; progress **image n / total** in image modes. **Portrait ↔ landscape** keeps zoom and horizontal pan ratio in continuous strip
- **Text selection**: long-press to select (ebooks use system long-press; **PDF needs ~1 s still hold** — any move before that is pan/scroll); **handles at both ends** to adjust; drag a handle to the top/bottom edge to auto-scroll and extend; copy / **read from here** (also jumps mid-TTS to the selection)
- **Highlights & notes** (TXT / EPUB / MOBI): **Highlight** from the selection menu; background or underline; optional note; **note bubbles** on the right; tap highlight or bubble to view/edit; **Notes** tab in the TOC sheet
- **TOC / bookmarks / jump**; battery and clock in the status bar; TOC opens scrolled to the current chapter; vertical list scroll does not steal horizontal tab swipe
- **In-book search**: live results, tap to jump
- **Gestures**: side-tap page turn, scroll; **volume keys** page turn (default on; **media volume while TTS is speaking/paused**); **left/right screen edge** (10px) adjusts font size in 0.5sp steps; back can stop TTS only
- **TTS**: rate on the control bar (e.g. `1×`); sentence highlight; lock-screen / background continue (below); sleep timer; **auto-close** (default 1 h idle → stop TTS and close app; notification / headset pause-resume resets the timer); while speaking, **read-from-selection jumps to that offset** and continues; when the bar closes, body text under it redraws immediately
- **Export speech**: full book or line range; MP3 / M4A / WAV + bitrate

### PDF reading

- Continuous or single page; pinch-zoom; **crop margins per file**
- **Progress %** = scroll position / total content height (updates while scrolling inside tall pages)
- **Fast scroll**: right-edge thumb in continuous mode (drag to jump; shows while scrolling, hides ~1s after stop)
- **TOC** prepared in the background after open
- **Links**: page jump; external links need confirm; back / forward
- Select text when available; **handles** to adjust the range; **OCR scanned pages** (tall pages tiled; **partial results re-scanned** in strips; page range, cancelable)
- **TTS / export** after text or OCR; sentence highlight follows scroll and stays above the TTS bar; **read selection** (also jumps mid-TTS to the selection start); export by page range
- Side-tap page turn; center-tap opens the menu; pinch-zoom then one-finger pan; **~1 s still long-press to select text** (move before that is pan/scroll, no fight with selection); **orientation change can keep the 2×4 menu open**; **continuous mode** keeps zoom and horizontal pan ratio when rotating

### TTS & export

| Item | Detail |
|------|--------|
| System TTS | Engine, language, voice, rate label (e.g. `1×`), highlight, sleep timer, auto-close (default 1 h), jump via read-from-selection |
| Continuity | Prepares the next sentences to reduce gaps |
| Lock-screen | See below |
| Export | Build audio in chunks, then merge; prefer MP3, else M4A / WAV; live progress dialog |
| Bitrate | 32–192 kbps for MP3/M4A |
| Standalone | Shelf ⋮ → Text to speech |

### Listening after lock / background

Goal: keep reading aloud after lock or switching apps, with notification and lock-screen controls.

**What the app does (short)**

- Shows a notification while speaking and tries to keep playback alive
- Pause / resume / prev / next sentence from the notification or lock screen
- Tries to ignore false “pause” events when the screen is off
- Bridges sentences and retries if something stalls

Leaving the reading screen does **not** stop TTS; progress can still be saved.

**If your phone still kills playback**

1. Allow notifications the first time you start TTS  
2. Settings → Apps → this app → **battery unrestricted**  
3. Lock the app in recents  
4. Install a system TTS engine and a Chinese voice pack  

**Quick test**

1. Open a book → play → confirm a “Speaking” notification  
2. Lock the screen and listen for **1+ minutes**  
3. Use notification / lock controls  

### OCR

- Shelf ⋮ → OCR  
- Fully on-device (no upload)  
- Drag to select a region; scroll the full text  

## Requirements (dev)

| Item | Value |
|------|--------|
| Language | Kotlin |
| min Android | 7.0 |
| target Android | 14 |
| Build | Gradle + Android Gradle Plugin |
| JDK | 17 |

### Local setup

1. `local.properties` with SDK path (gitignored)  
2. JDK 17  
3. `adb` on PATH  
4. Signing: `keystore.properties` + keystore (see example; do not commit secrets)  

## Quick start

```powershell
cd reader
.\gradlew.bat assembleDebug
node build.js run
node build.js devices
node build.js release
node build.js apk
```

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/reader{version}.apk
release/reader{version}.apk
```

See [CHANGELOG.md](CHANGELOG.md) for history.

## Project layout (short)

```
reader/
├── app/src/main/
│   ├── assets/ocr/     # OCR models
│   ├── java/…/         # shelf, reading, PDF, TTS, OCR, settings
│   └── res/
├── documents/
├── build.js
├── CHANGELOG.md
└── README…
```

- TXT / EPUB / MOBI → e-book reading screen  
- PDF → PDF reading screen  
- TTS → system speech + notification / lock-screen controls  

## APK size (approx.)

Mostly OCR models and libraries, then PDF support; the rest is UI and app code. A small extra for MP3 export on 64-bit phones.

## FAQ

### No TTS audio

Install a system TTS engine and a Chinese voice pack; check volume.

### TTS stops soon after lock

See **Listening after lock / background**: notifications, unrestricted battery, lock in recents. Another app may also be taking audio focus.

### Garbled TXT

Auto-detect common encodings, or set encoding in the reader.

### EPUB / MOBI slow or incomplete styling

Large books show the first screen first, then **prefetch in the background**. Only common styling is supported; complex layouts/tables may not match the desktop reader. DRM books will not open.

### MOBI garbled Chinese text

Recent builds fix UTF-8 Chinese MOBI (e.g. mislabeled encoding or damaged PalmDOC HTML). Reopen the book or clear local records if an old parse cache persists.

### No chapter list / wrong chapters

Open **TOC → Custom TOC scan**, pick a preset or enter a wildcard pattern (`第x章 *`, `001.` style `xxx. *`, etc.), tap **Apply**. Pattern is remembered for that book.

### MOBI is only images / comic

Use **Style → View mode → Single image** or **Continuous strip**, or open an image-only MOBI (auto image mode). Progress is image n / total.

### Scanned PDF has no text

Use **OCR scanned PDF pages**; tall pages are split into strips. If only the top was recognized before, OCR again with “skip done” — partial pages are re-scanned. TTS/export need recognized text.

### Select and copy text

Long-press a word (English expands to the whole word). Drag to extend, or use the **handles** after release. Drag a handle to the screen edge to scroll and extend. PDF needs extractable or OCR text; **PDF requires ~1 s still hold** to select — moving earlier pans/scrolls.

### PDF blank or squashed pages

Use the latest build; continuous mode previews while scrolling, then sharpens when idle. Reopen the book if it persists.

### MP3 unavailable

Some emulators or non-64-bit devices fall back to M4A.

### Links do nothing

PDF needs real links; EPUB needs links in the book. Use the TOC panel for plain-text contents.

## License

```
MIT License
Copyright (c) 2026 whj
```

Full text: [LICENSE](LICENSE).

- **Covered by MIT**: this project’s original Kotlin/Java source, layouts, and docs by the copyright holder  
- **Not covered by MIT**: AndroidX / Material, PdfBox-Android, TensorFlow Lite, LAME/TAndroidLame, OCR model weights, etc. — see the third-party notice at the end of `LICENSE`

## Notes

Personal/learning prototype. TTS uses the system engine; OCR never uploads images.
