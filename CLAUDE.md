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

Text the user sees lives in `res/values/strings.xml` (English) `res/values-nl/strings.xml` (Dutch, informal "je"), `res/values-fr/strings.xml` (French, "vous") and `res/values-de/strings.xml` (German, "du"); Android picks one from the phone's language or its per-app language setting (`res/xml/locales_config.xml`), there is no in-app picker and is read with `tr(R.string.key, args…)` (`AppStrings.kt`), which works in screens, the view model and the model enums alike (a new `Param` takes its label and section from there too). `MainActivity.onCreate` calls `AppStrings.init` before anything else. Every new string needs its Dutch, French and German lines too. Built-in preset names are stored in English in edit sidecars (`Preset.name`); what's shown is `Preset.label` from resources. Still in the code on purpose: number and unit formats (`%+.2f EV`, `ISO 100`), file and folder names, the brand marks "Lumen" and "CTRL", and internal error texts.

Newer parts: `model/CubeLut.kt` + `data/Luts.kt` (imported 3D LUTs, sampled on texture unit 5), `data/UserPresets.kt`, `model/Overlay.kt` + `render/OverlayPainter.kt` (text and drawing layers), `edit/Inpaint.kt` + `edit/Removals.kt` (object removal patches `<id>.rm-<rid>.png`, applied to every decoded source), `render/HeifImageWriter.kt` (HEIC/AVIF), `render/AudioMix.kt` (video music, `<id>.music-<mid>`), `data/LibraryArchive.kt` (library ZIP), `data/lock/LockVault.kt` + `ui/lock/` (the series' PIN lock, copied from SumCtrl; the view model is the `LockHost`).

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). The single documented exception is LiteRT (`com.google.ai.edge.litert`), the Apache 2.0 on-device ML runtime; nothing else from `com.google.*`. No WebView. Fonts and models are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Android 15+ (minSdk 35).
- Originals are never modified. Exports strip location unless the user keeps it.
- Models in `assets/models/` stay uncompressed (`noCompress "tflite"`) and are memory-mapped; record source, licence and sha256 of any new model in the README.
- Names and data from outside (shared files, USB cameras) never pick a path on the phone: files are named by LumenCtrl itself. Thumbnails and images from devices are decoded with a size check first.

## Build toolchain

- AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37 (Compose 1.12 needs it), `minSdk` 35, `targetSdk` 35 (on purpose: raising it changes how Android treats the app). There is no version catalog: versions are written in `build.gradle.kts` and `app/build.gradle.kts`.
- AGP 9 has built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin and no `kotlinOptions` block any more. The Kotlin version is pinned by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle.kts`; the Compose compiler plugin uses the same version. Don't re-add the Kotlin Android plugin.
- AGP 9's R8 is stricter (`-keep class X` without a member block no longer keeps the constructor; resources are shrunk together with code). Keep rules in `proguard-rules.pro` must name the members they need, and resources must be referenced through `R`, never by name.
- Any change here needs the dependency verification procedure below.

## Dependency verification

Gradle checks every library and plugin download against the sha256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency, plugin or the Gradle version changes, regenerate it with the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/LumenCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`): take the file from its log (between the BEGIN/END markers, `base64 -d | gunzip`), check the diff lists only Maven Central and Google's Maven, and commit it. Generate it on CI, not on Windows (aapt2 is a per-OS download). Never turn verification off or make it lenient.

`settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "LumenCtrl X.Y.Z".
- Push straight to `main`; no branches or pull requests.
- Every new version needs a user-facing entry at the top of `CHANGELOG.md` (`## vX.Y.Z (date)`); CI refuses to release without one.
