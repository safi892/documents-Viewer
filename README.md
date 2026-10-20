# Universal Reader

An Android document reader that opens a wide range of file formats entirely on-device.
Nothing is uploaded: parsing and rendering happen locally in the app process.

## Features

- **PDF** — native rasteriser, pinch-to-zoom with multi-step re-rasterisation, no blur at high zoom.
- **Office** — DOCX, XLSX, PPTX and legacy DOC/XLS/PPT, plus OpenDocument (ODT/ODS/ODP/ODG), rendered through a WebView-based engine with secure JS settings.
- **EPUB** — reflowable books with cover pages and chapter navigation.
- **Comics** — CBZ archives, page by page.
- **Text & markup** — TXT, MD, RTF, HTML, XML, JSON, CSV, TSV, YAML with syntax-aware rendering.
- **Images** — JPG, PNG, WEBP, GIF, HEIC and friends, with pinch-zoom and pan.
- **Recent library** — on-device Room database of recently opened documents, with a reopening cache so a second visit is instant.
- **Dark theme** — first-class dark mode; documents stay on light paper for legibility (algorithmic darkening is explicitly disabled for rendered documents).

## Architecture

```
app/src/main/java/com/secure/reader
├── core/            → design system, database (Room), file cache
├── features/
│   ├── library/     → recents list, view model, repository
│   └── reader/      → routing, view model, per-format viewers and engines
└── App.kt           → single-activity navigation (library ⇄ reader)
app/src/main/assets
├── engines/         → vendored JS office/EPUB bundles
└── renderers/       → first-party decoders + HTML/CSS harness
```

- UI: Jetpack Compose (Material 3), single activity, `SharedTransitionLayout` for the row → title hero.
- Persistence: Room; document bytes are streamed into an app-private cache keyed by content hash.
- Rendering: PDF uses a native page bitmap pipeline with a byte-budgeted bitmap pool; Office/EPUB/comic formats render in a hardened WebView.
- Asynchrony: all decoding and file I/O on `Dispatchers.IO`; engines expose progress so the UI can show real state instead of a spinner.

## Build

Requires JDK 17 and the Android SDK (compileSdk 37, minSdk 24).

```sh
./gradlew :app:assembleDebug
```

Install on a connected device:

```sh
./gradlew :app:installDebug
```

## Tests

```sh
./gradlew :app:testDebugUnitTest          # JVM unit tests
node tools/verify-decoders.mjs            # decoder fixtures, no device required
```
