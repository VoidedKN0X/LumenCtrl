# Security policy

Thank you for helping keep LumenCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/LumenCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of LumenCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

LumenCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for LumenCtrl to send photos, videos or their details off the phone
- Another app being able to read the library, or a photo you didn't share with it
- Location or other metadata left in an export when the export settings say it's removed
- Photos showing while the app lock is on
- Ways around the app lock, or LumenCtrl showing up in screenshots when Hide in screenshots is on
- A crafted photo, video or bundled model file that makes LumenCtrl run code or read files it shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
FD:ED:53:E2:14:DD:BA:D3:3B:B3:54:DD:81:37:99:15:5D:19:26:89:6E:F1:AB:28:8C:BD:2E:A3:3E:47:BE:81
```
