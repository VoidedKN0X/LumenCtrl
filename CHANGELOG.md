# Changelog

What changed in each LumenCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/LumenCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

### Fixed

- **German mask messages keep their capital letters.** "Select: sky", "Finding sky on this phone", "Add sky mask" and "No sky found in this photo" put the mask name in lower case, which reads wrong in German, where nouns are always capitalised. German now keeps the capital; the other languages are unchanged. Not run on a phone.

### Removed

- **No more lines in Android's log.** LumenCtrl wrote timing and detail lines to the phone's system log after AI denoise, an AI mask, removing an object and an HDR merge, while denoising (patch size and count) and while finding a mask (what the model found), plus a warning when a song for a video couldn't be read or the music mix failed. All nine log lines are gone, and so is the switched-off timer for how long each preview frame takes to draw. Nothing else changes: the messages you see on screen, the time shown after AI denoise, and what happens when a song can't be read or the music mix fails stay exactly the same.
- **Log lines are stripped from the release build.** The release build already removed debug, verbose and info log lines; it now also removes warning, error and other log lines, so anything a library writes to Android's log, or a log line that sneaks in later, doesn't end up in the app you install.

## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Added

- **LumenCtrl in French and German, following the phone's language.** Every screen, slider, preset name, setting and message now also has a French translation (polite "vous", as Android itself uses) and a German one (informal "du", as modern Android apps use). Android picks French or German when your phone is set to that language; otherwise LumenCtrl stays in English, or Dutch as before. LumenCtrl now also tells Android which languages it has (English, Dutch, French, German), so you can choose one for LumenCtrl alone in Android's per-app language setting. Built-in presets are shown with French or German names but edits still store the English names, so a photo keeps its preset whatever language the phone uses. Product and model names (AI Denoise, NAFNet, U-Net, Ultra HDR, LUT), units, folder names and the app name stay as they are.

### Project

- `CLAUDE.md` now lists the French and German string files next to the Dutch one, and says every new string needs a Dutch, French and German line.

## v0.3.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

Your library, edits, masks, AI masks, versions and settings from 0.2.0 open unchanged: everything new (LUTs, layers, removals, music) is stored as extra fields in the same files, which older versions simply ignore. If you used the old app lock, see Security below: you'll be asked to choose an app PIN.

### New

- **"Edit in LumenCtrl" from your gallery.** Gallery apps now offer LumenCtrl when you tap Edit (or "Edit with") on a photo or video. LumenCtrl copies the item into its library and opens it straight in the editor; with the app lock on, it waits until you unlock. The gallery's own file is never changed: your edit is kept in LumenCtrl and saved to the gallery through Export, as with any other photo. Built and checked by CI only; not yet tried on a phone.
- **Your own presets.** In Presets, **Save current look** keeps the open photo's look (all the global sliders, the curves and the LUT) as a preset of your own. Crop, straighten, the blur focus point, AI Denoise, masks and spots belong to one photo and are not saved in it. Your presets appear under **Your presets**, with the same amount slider as the built-in looks (0 to 200%), and can be renamed or deleted; a photo that used a deleted preset keeps its look. They're kept in LumenCtrl's private storage (`presets.json`).
- **LUT import.** In Edit > Color, **Import .cube** reads a 3D LUT from a colour-grading app or LUT pack (Adobe/Resolve `.cube` format, sizes 2 to 96, with its input domain) and puts it on the photo or video, with an amount slider. LUTs are stored once in LumenCtrl and listed there for every photo; they can be renamed or deleted (edits that used a deleted LUT then render without it). The LUT is applied after colour grading and before vignette and grain, in the same shader for preview, thumbnails, photo export and video export. 1D LUTs aren't supported and are refused with a message. Edits saved by earlier versions open unchanged; an edit with a LUT opened in an older LumenCtrl shows without the LUT.
- **HEIC and AVIF export.** The export sheet now offers HEIC and AVIF next to JPEG, PNG and WebP, for single photos and batches, with the same quality slider. LumenCtrl writes them with the phone's own encoders and Android's HEIF writer, in 512-pixel tiles joined into one image (the way Android's own HEIF tools do it), so there is no new library. HEIC uses the HEVC encoder every phone has; AVIF needs an AV1 encoder, which many phones don't have, so AVIF only appears where there is one (the sheet says so otherwise). These files are saved **without any metadata**: no camera details, date, location or edit record, and Ultra HDR stays JPEG-only. Built and checked by CI only: the encoders differ per phone, so try both formats on yours and open the results in your gallery.
- **Text and drawing layers.** A new **Text & draw** tool in the photo editor. **Text:** add a line of text (up to 200 characters), drag it into place, and set its size, one of eight colours and an optional background box; tap a text to select it, change or delete it. **Draw:** draw freehand with one finger in a chosen colour and line width; every line is its own layer, so Undo takes away the last one, and **Clear drawing** removes them all. Layers are stored in the edit (non-destructive, with undo, history and versions), positioned on the cropped picture, and drawn with the same code on screen and into exports, thumbnails, slideshows and collages, in the bundled Space Grotesk font. They're for photos; the video editor doesn't have them. Up to 100 layers per photo.
- **Object removal.** Heal has a new **Remove object** mode: paint over something you don't want (a passer-by, a sign, a wire, a bin), a little past its edges, and tap **Remove**. LumenCtrl fills the area in from the rest of the photo, on the phone, with no AI model and nothing downloaded: a content-aware fill (PatchMatch on a coarse-to-fine pyramid, written for LumenCtrl) copies matching texture from around the object. Each removal is part of the edit: it shows in the history, Undo takes it away, and **Undo last removal** does the same from the panel. The fill is stored as a small picture beside the photo and laid over the original whenever the photo is shown or exported; the original itself is never changed, and heal spots, masks, AI masks, AI Denoise and every slider then work on the cleaned-up picture. It works best against grass, sky, water, walls and other plain or repeating backgrounds; big objects in busy scenes can leave visible repeats, and the filled area is worked out at preview resolution (up to 3072 px), so it can look a little softer than the rest in a full-size export of a very large photo. For photos; up to 24 removals per photo. Built and checked by CI only; the fill was tested on a computer with test pictures, not yet on a phone.
- **Video crop with a frame you can drag.** The video editor's Crop tab now shows the whole frame with the same crop box as the photo editor: drag its corners and edges, or drag inside it to move it, with or without an aspect lock. Rotate and flip were already there; the crop is applied in the preview and in the export.
- **Music for videos.** In the video editor's Video tab, **Add music** picks a song or sound file from your phone (anything Android can play: MP3, AAC/M4A, Ogg, Opus, FLAC, WAV). LumenCtrl keeps its own copy beside the video, plays it along in the preview, and mixes it into the export: it starts with the clip (after the trim), has a volume slider, and fades out over the last 1.5 seconds. The clip's own sound stays in the mix unless you turn Sound off or change the speed, in which case you hear only the music. In a reel, each clip plays its own music. Mixing and AAC encoding happen on the phone with Android's own codecs, before the picture is rendered. Change or remove the music at any time; it's part of the edit, with undo.
- **Library export and import.** **Settings > Library backup** has **Export**, which writes your whole library to one ZIP file wherever you choose: every original photo and video as imported, edits, saved versions, ratings, flags, albums, AI masks, denoised copies, object-removal patches, video music, your presets and your LUTs. **Import** first reads the file and shows what it would add (new photos and videos, albums, presets, LUTs and the size) before anything happens. It only adds what's missing: photos you already have are skipped, albums, presets and LUTs are added by their id, and nothing in your library is replaced or deleted. Only file names LumenCtrl itself uses are read from the ZIP (anything else, such as a path with "../", is ignored), everything is unpacked to a private folder first and only moved into the library once the whole file was read, and the import stops before it would fill the phone. The ZIP isn't encrypted, so keep it somewhere private: it contains your originals with their metadata.
- **Settings export and import.** **Settings > Settings file** exports your settings to a small JSON file (Hide in screenshots, Hide in recent apps, accent colour and the export defaults: format, quality, size, metadata, watermark text, edit record, Ultra HDR) and imports one. Before importing, LumenCtrl lists every setting that would change, with its current and new value, and asks. The app lock and its PIN are never in the file, and an import never turns the app lock off.

### Security

- **The app lock is now the same PIN lock as in every Ctrl app.** Until now LumenCtrl's lock used the phone's own screen lock or a strong biometric. It now uses its own **app PIN** (at least 6 digits, separate from the phone's PIN, never stored: it unlocks a key protected by the phone's secure hardware), with optional **fingerprint unlock** (strong biometrics only; the PIN is still needed after a restart and every 72 hours), a growing wait after wrong PINs, **Change PIN**, and a **Lock again** choice (as soon as you leave, after 1, 5 or 15 minutes, after an hour, or only on restart; 1 minute, as before, until you choose). The picker, camera and share sheet LumenCtrl opens itself don't count as leaving. While a lock or PIN screen shows, the window is always hidden from screenshots and other apps' overlays, and with the lock on the recent-apps card is always blank. **Forgot PIN?** deletes what the lock protects (your library, presets and LUTs, not your exports or the gallery's originals), resets the settings and turns the lock off. Photos shared in or opened from a gallery while locked still wait until you unlock. **If you had the old lock on,** LumenCtrl asks for your screen lock once more after this update and then asks you to choose an app PIN; cancelling turns the lock off. The lock code is copied from SumCtrl and adapted to LumenCtrl's look.
- The AndroidX Biometric library is no longer used (the lock uses Android's own BiometricPrompt), so it was removed from the build. LumenCtrl now declares the fingerprint permission (`USE_BIOMETRIC`) itself; it used to come in through that library, so the app asks for nothing new.
- **New permission: hide other apps' floating windows** (`HIDE_OVERLAY_WINDOWS`, granted at install without a prompt). The shared app lock uses it, as in the other Ctrl apps, to hide chat heads and other overlays over the lock and PIN screens while they show, so nothing can cover or imitate them. Overlays work as usual everywhere else in LumenCtrl.

### Language

- **LumenCtrl now speaks Dutch.** Every screen, menu, message, slider and screen-reader label has a Dutch translation (informal "je"), and LumenCtrl follows your phone's language: Dutch when the phone is set to Dutch, English otherwise. There's no language setting in the app. Technical names and units (HDR, LUT, RAW, EV, MB) and the app name stay as they are. The names of the built-in presets are now translated on screen too; edits keep storing their English names, so existing edits open exactly as before. The last few lines that mixed text with numbers in the code (the library size in Settings, the Ultra HDR source line and the AI Denoise progress line) now come from the text resources as well. If you switch the phone's language while LumenCtrl is open, some labels (slider and menu names) follow after the app is restarted.

### Project

- **Checksum list regenerated without the old entries.** `gradle/verification-metadata.xml` was regenerated on CI with the fixed workflow and committed exactly as printed (its SHA-256 matches the log). It now lists only the 345 components the build downloads today; the leftovers from earlier library and build-tool versions are gone.
- **The checksum list no longer collects old entries.** The hand-started workflow that regenerates `gradle/verification-metadata.xml` used to add the new checksums to the existing file, so entries for library versions that are no longer used stayed in it. It now clears the old entries first and writes only what the build uses, and prints the file's SHA-256 so the copy that gets committed can be checked against the log. Nothing in the app changes.

## v0.2.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there.

### Performance

- **Rating, flagging and album changes are quicker in a large library.** After every change (a star rating, a flag, adding to or removing from an album, an import, a delete) LumenCtrl lists the library again, and for that it used to open and read one small file per photo, every time: thousands of file reads for one star in a big library. The facts of each photo are now kept in memory after the first read and replaced whenever LumenCtrl writes them, so listing the library again only looks at which files exist. The files on the phone are written exactly as before and remain the source of truth: the memory copy starts empty every time the app starts.
- Keeping your selection after such a change no longer compares every selected photo with every photo in the library.

### Maintenance

- **The app's text moved out of the code.** The words on the screens, in menus, messages, the edit history and screen-reader labels (some 440 pieces of text: slider and section names, filter and sort names, buttons, hints, error messages) were written straight into the code; they now live in the app's text resources (`strings.xml`), where they can be reviewed and, later, translated. The wording is the same, character for character, and numbers inside a sentence ("Imported 3 of 5") are filled in the same way as before. Left in the code on purpose: the names of the presets (they are stored in your edits), number and unit formats such as "+0.50 EV", file and folder names, the "Lumen" and "CTRL" marks, and five lines that mix text with a number format (the library size in Settings, the photo size and file size in Info, the "Ultra HDR source" line and the AI Denoise progress line).
- Removed checks for Android versions LumenCtrl can't be installed on (it needs Android 15): the "is this Android 14?" test before HDR preview and Ultra HDR, the Android 13 test before hiding the app in the recent-apps overview, and the Android 11 test before WebP export. Nothing changes on the phone.

### Security

- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Builds are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. Nothing in the app changed for this. The release job itself has not run with the new workflow yet; the next version is its first use.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache, and the Gradle plugin portal is no longer a plugin source.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (reproduced on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the weekly code scan start from a clean Gradle home, and `settings.gradle.kts` lists only Maven Central and Google's Maven for plugins; everything LumenCtrl's build needs is there. A build takes a minute or two longer. Dependency verification itself is unchanged and as strict as before.

### Changed

- **Newer build tools and libraries; nothing is meant to change in how LumenCtrl looks or works.** LumenCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.7.3 → 9.5.1, Gradle 8.11.1 → 9.9.2, Kotlin 2.0.21 → 2.4.21 (with its Compose compiler plugin) and Compose BOM 2024.10.01 → 2026.09.00 (Compose 1.7 → 1.12.1, Material 3 1.3 → 1.4.0). The libraries move to the versions the newest Ctrl apps use: AndroidX Activity 1.9.3 → 1.13.0, Core 1.15.0 → 1.18.0, Lifecycle 2.8.7 → 2.10.0 and ExifInterface 1.3.7 → 1.4.1. Material icons are now pinned to 1.7.8, the version the app already used. Biometric (1.1.0) and LiteRT (1.4.0) are unchanged. No library was added. This is a large jump for the interface library (five Compose releases), so the screens need a look on a phone before the next release.
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 35 to 37. The Android versions LumenCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 35), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. LumenCtrl's own keep rules are unchanged. The newer R8 removes and renames more than the old one, so AI masks and AI Denoise (LiteRT), saved edits and export settings should be tried on a phone before the next release.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

## v0.1.3 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Security

- **Every download the build uses is now checked against a recorded checksum.** LumenCtrl was the only Ctrl app without Gradle dependency verification. `gradle/verification-metadata.xml` now lists the SHA-256 of every library and build plugin (351 components), generated on CI by the `verification-metadata.yml` workflow and committed unchanged, so a build fails if any download differs from what was recorded. Nothing in the app itself changes.

## v0.1.2 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Performance

- **The library opens faster after a restart.** Photos you haven't edited had no stored thumbnail, so the grid decoded every full-size original again each time the app started. LumenCtrl now keeps a small copy (384 px) of each unedited photo in its private cache and shows that. The copies are made the first time a photo appears in the grid, are removed when you delete the photo, and are simply made again if Android empties the cache. Photos with transparency or an Ultra HDR gain map, and videos, are still read from the original so they look exactly as before. Your originals and edits are not touched and nothing in the library folder changes.

### Privacy

- **Shared photos wait for the app lock.** When another app shared photos into LumenCtrl while the app lock was showing, they were imported straight away, behind the lock screen. They are now imported after you unlock. Without the app lock, or when the app is already unlocked, nothing changes.
- **The app lock only accepts strong biometrics.** The lock used to accept any biometric Android offers, including weaker ones such as camera-only face unlock on many phones. It now accepts only what Android classes as strong (Class 3: fingerprint, and face unlock on phones with a secure face sensor), the same as the other Ctrl apps. The phone's screen lock (PIN, pattern or password) still works as before. If your phone's only biometric is a weaker one, the unlock prompt now asks for your screen lock instead.

### Maintenance

- The app name, the share sheet title, the unlock prompt title and the screen-reader labels of the collage and HDR previews moved from the code into the app's text resources. The words are the same. (Most other text in LumenCtrl is still written in the code.)
- Removed a check for Android versions older than 12 in USB camera import; LumenCtrl needs Android 15.

## v0.1.1 (2026-11-13)

A security and privacy update. Editing, import and export work as before.

### Security

- **Safer import from USB cameras.** The file names a camera reports are no longer used to name files on the phone; LumenCtrl picks its own name and keeps only the extension. A faulty or hostile USB device can no longer use a name with "../" in it to write outside the import folder.
- **Camera thumbnails are checked before they're shown.** Thumbnails from a USB camera are measured first and decoded at a small size, so a broken or oversized one can't use up the phone's memory.
- **Shares and "Open with" only bring in what the other app can read.** When another app shares a photo or opens one with LumenCtrl, Android now checks that app was allowed to read it, so no app can make LumenCtrl import files on its behalf.
- **Checked build tools.** The build now checks the Gradle download against its published checksum, so a tampered build tool can't slip into a release.

### Privacy

- **No debug logging in the app.** Release builds now strip debug, verbose and info log messages from the app and the libraries it uses (including the on-device AI runtime), so nothing about your photos can end up in the phone's system log.
- **One permission fewer.** The old "use fingerprint hardware" permission, which only Android 8 and older use, is gone. The app lock's fingerprint unlock works as before through Android's biometric prompt.

## v0.1.0 (2026-11-12)

The first release of LumenCtrl: a private, offline photo and video editor.

### New

- **Non-destructive editing.** Originals are never changed; every edit is a small settings file next to the photo, so you can undo, compare, reset or go back to any saved version.
- **Light, Curve, Color, Mixer, Grading, Effects, Detail, Optics and Blur** tools, with a live histogram, on-device Auto tone and a white-balance eyedropper.
- **12 presets** with an amount slider, plus copy and paste of edits between photos.
- **Masks:** linear, radial, luminance range and color range, each with 10 local sliders.
- **Offline AI masks:** Subject, Background, People, Face skin, Body skin, Hair, Clothes and Sky, worked out on the phone.
- **Offline AI Denoise:** Quality or Fast, run once per photo, then blended in with an Amount slider.
- **Heal and clone** spots, **crop** with aspect locks, rotate, flip, auto-straighten and perspective.
- **HDR:** edit in HDR, view it on HDR screens, export Ultra HDR JPEGs, and merge 2 to 7 bracketed shots into one HDR photo.
- **Video editing:** the same looks on videos, plus trim, mute and speed, and reels made from several clips.
- **Library:** search, filters, ratings, pick and reject flags, albums, slideshow and collage.
- **Import** from the Photo Picker, the camera, share and "Open with", or straight from a camera over USB.
- **Export** to JPEG, PNG or WebP with size and quality, a metadata policy (location removed by default), an optional watermark and an optional edit record.
- **Privacy:** no internet permission, app lock, Hide in screenshots and Hide in recent apps.
