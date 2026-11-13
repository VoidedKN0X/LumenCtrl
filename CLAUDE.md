# LumenCtrl

Photo and video editor in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, one `LumenViewModel`. Non-destructive: originals stay untouched in private storage, edits are JSON sidecars, and one GLES3 fragment shader renders preview and export.

## Layout

- `model/`: `Param` (enum order = GPU packing order), `EditParams` sidecar, masks, spots, curves, presets.
- `render/Shaders.kt`: the whole develop pipeline in one fragment shader. `EditProgram` binds textures (0 source, 1 curve LUT, 2 AI mask atlas, 3 Ultra HDR gain map, 4 AI-denoised base).
- `render/EditorRenderer.kt` (live preview), `Exporter.kt` (off-screen EGL export, Ultra HDR), `VideoGl.kt` / `VideoExporter.kt` (MediaCodec video).
- `edit/`: `AutoTools`, `Segmenter` (AI masks), `Denoiser` (AI Denoise), `HdrMerge`.
- `data/`: `Catalog` (files in `filesDir/library`: `<id>.img|.vid`, `.json`, `.meta.json`, `.versions.json`, `_thumb.jpg`, `.ai-<mask>.png`, `.denoise.jpg`), `ImageLoader`, `Settings`, `CameraImport` (USB PTP/MTP).
- `ui/ctrl/`: Ctrl / Signal design system. `ui/editor/`: editor, panels, sheets, video editor.

To add a slider: add a `Param`, use `P_<key>` in `Shaders.FRAGMENT`. UI, persistence, presets, undo and export pick it up.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). The single documented exception is LiteRT (`com.google.ai.edge.litert`), the Apache 2.0 on-device ML runtime; nothing else from `com.google.*`. No WebView. Fonts and models are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Android 15+ (minSdk 35).
- Originals are never modified. Exports strip location unless the user keeps it.
- Models in `assets/models/` stay uncompressed (`noCompress "tflite"`) and are memory-mapped; record source, licence and sha256 of any new model in the README.
- Names and data from outside (shared files, USB cameras) never pick a path on the phone: files are named by LumenCtrl itself. Thumbnails and images from devices are decoded with a size check first.

## Dependency verification

There's no `gradle/verification-metadata.xml` yet. Create it with the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/LumenCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`): take the file from its log (between the BEGIN/END markers, `base64 -d | gunzip`), check it lists only Maven Central, Google's Maven and the Gradle plugin portal, and commit it. Generate it on CI, not on Windows (aapt2 is a per-OS download). After that, regenerate it the same way whenever a dependency, plugin or Gradle version changes. Never turn verification off or make it lenient.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "LumenCtrl X.Y.Z".
- Push straight to `main`; no branches or pull requests.
- Every new version needs a user-facing entry at the top of `CHANGELOG.md` (`## vX.Y.Z (date)`); CI refuses to release without one.
