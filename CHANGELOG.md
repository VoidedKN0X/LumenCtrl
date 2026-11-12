# Changelog

What changed in each LumenCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/LumenCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
