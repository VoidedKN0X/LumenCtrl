# Changelog

What changed in each LumenCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/LumenCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Built and checked by CI only: nothing here had been run on a phone when it was written.

### Performance

- **Rating, flagging and album changes are quicker in a large library.** After every change (a star rating, a flag, adding to or removing from an album, an import, a delete) LumenCtrl lists the library again, and for that it used to open and read one small file per photo, every time: thousands of file reads for one star in a big library. The facts of each photo are now kept in memory after the first read and replaced whenever LumenCtrl writes them, so listing the library again only looks at which files exist. The files on the phone are written exactly as before and remain the source of truth: the memory copy starts empty every time the app starts.
- Keeping your selection after such a change no longer compares every selected photo with every photo in the library.

### Maintenance

- **The app's text moved out of the code.** The words on the screens, in menus, messages, the edit history and screen-reader labels (some 440 pieces of text: slider and section names, filter and sort names, buttons, hints, error messages) were written straight into the code; they now live in the app's text resources (`strings.xml`), where they can be reviewed and, later, translated. The wording is the same, character for character, and numbers inside a sentence ("Imported 3 of 5") are filled in the same way as before. Left in the code on purpose: the names of the presets (they are stored in your edits), number and unit formats such as "+0.50 EV", file and folder names, the "Lumen" and "CTRL" marks, and five lines that mix text with a number format (the library size in Settings, the photo size and file size in Info, the "Ultra HDR source" line and the AI Denoise progress line).
- Removed checks for Android versions LumenCtrl can't be installed on (it needs Android 15): the "is this Android 14?" test before HDR preview and Ultra HDR, the Android 13 test before hiding the app in the recent-apps overview, and the Android 11 test before WebP export. Nothing changes on the phone.

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
