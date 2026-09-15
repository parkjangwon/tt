# TT

Launch an app or shortcut with a flick of your foldable.

Open your Galaxy Fold / Pixel Fold fully, bend it past 90°, and reopen within
2 seconds — TT fires the app or app shortcut you registered.

## Features

- Hinge-angle gesture detection: open (180°) → bend past 90° → reopen within 2s
- Action: launch an app, or one of its shortcuts (YouTube Shorts, Camera Selfie, …)
- Instant-save settings; the listener survives reboot and app updates
- Live hinge-angle readout for sanity checking
- Languages: English, 한국어, 日本語, 中文 — switch in-app, applies instantly

## Requirements

- Android 11+ (API 30) foldable with a hinge angle sensor
- One-time setup: Display over other apps, Notifications, battery-optimization exemption

## Build

```sh
./gradlew :app:assembleDebug
```

Package `org.parkjw.apps.tt` · minSdk 30 · targetSdk 37

## License

MIT
