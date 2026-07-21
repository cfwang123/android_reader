# Android Book Reader

A lightweight reader: bookshelf, TXT / EPUB / MOBI / PDF, system speech (TTS) and audio export, on-device OCR, Chinese/English UI. E-books share one reading screen; PDF has its own.

[中文](README.zh.md)

## Features

| Area | Capabilities |
|------|----------------|
| **Bookshelf** | Import TXT/PDF/EPUB/MOBI (and AZW, etc.) or folders; one-level shelves; bind folders; multi-select; search; backup/restore |
| **E-books** | TXT / EPUB / MOBI: large books show the first screen quickly, then keep loading |
| **PDF** | Continuous/single page, zoom, crop, fast scroll, TOC, in-book links, TTS, scan OCR, page-range audio export |
| **TTS / export** | System speech, sentence highlight, lock-screen continue, media controls; export MP3 / M4A / WAV |
| **OCR** | Gallery or camera; runs fully on device; scanned PDFs can be recognized |
| **Other** | Portrait / landscape / auto; fullscreen; app language; keep screen on / idle timeout |

### Bookshelf

- Import files or folders; bind a folder (browse only, no copy)
- Multi-select: move / remove (does not delete source files)
- Reading history; progress and bookmarks are remembered
- **Backup / import** shelves and progress (local only)
- Settings → **Check for updates** (download install from the release page)
- Overflow: text-to-speech, OCR

### E-book reading (TXT / EPUB / MOBI)

- **Open**: large books paint the first screen first, then continue loading with a progress hint
- **Themes**: default / white / green / blue / purple / sepia / night / **custom background**
- **Fonts**: default / sans / serif / mono; **install your own font files** (long-press to remove); size and spacing
- **TXT**: auto or manual encoding; simplified↔traditional Chinese
- **EPUB / MOBI styling** (common features): bold / italic / underline / colors; block and inline images; in-book and external links; tap image → gallery
- **TOC / bookmarks / jump**; progress, battery, clock in the title bar
- **In-book search**: live results, tap to jump
- **Gestures**: side-tap page turn, scroll; back can stop TTS only
- **TTS**: sentence highlight; lock-screen / background continue (below); sleep timer
- **Export speech**: full book or line range; MP3 / M4A / WAV + bitrate

### PDF reading

- Continuous or single page; pinch-zoom; margin crop
- **Fast scroll**: right-edge thumb in continuous mode (drag to jump; shows while scrolling, hides ~1s after stop)
- **TOC** prepared in the background after open
- **Links**: page jump; external links need confirm; back / forward
- Select text when available; **OCR scanned pages** (page range, cancelable)
- **TTS / export** after text or OCR; export by page range
- Side-tap page turn; center-tap opens the menu

### TTS & export

| Item | Detail |
|------|--------|
| System TTS | Engine, language, voice, rate, highlight, sleep timer |
| Continuity | Prepares the next sentences to reduce gaps |
| Lock-screen | See below |
| Export | Build audio in chunks, then merge; prefer MP3, else M4A / WAV |
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

Large books show the first screen first. Only common styling is supported; complex layouts/tables may not match the desktop reader. DRM books will not open.

### Scanned PDF has no text

Use **OCR scanned PDF pages**; TTS/export need recognized text.

### PDF blank or squashed pages

Use the latest build; continuous mode previews while scrolling, then sharpens when idle. Reopen the book if it persists.

### MP3 unavailable

Some emulators or non-64-bit devices fall back to M4A.

### Links do nothing

PDF needs real links; EPUB needs links in the book. Use the TOC panel for plain-text contents.

## Notes

Personal/learning prototype. TTS uses the system engine; OCR never uploads images. Check third-party licenses if you redistribute.
