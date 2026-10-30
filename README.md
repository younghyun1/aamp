# aamp

A local-music player for Android Auto whose voice search understands albums. Say "play the album ...And Justice for All by Metallica" and it plays that album from the first track, using music files already on the phone.

Since Gemini replaced Google Assistant, voice requests reach non-partner music apps only through the Android Auto media session, and each app decides what to play. aamp exists to make that decision well. The background is in [docs/plans/2026-10-29-gemini-voice-routing.md](docs/plans/2026-10-29-gemini-voice-routing.md) and the design in [docs/plans/2026-10-29-design.md](docs/plans/2026-10-29-design.md).

## Status

First milestone code complete: playback service, MediaStore library, Android Auto browse tree and search, voice request parsing and matching, and a voice log on the phone screen. Replayed voice requests play the right queues on an Android 17 emulator; not yet tested in a car.

Music inside a folder whose name starts with a dot (for example `...And Justice for All/`) is hidden from Android's media index and therefore from aamp; rename such folders.

## Build and install

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open aamp on the phone once and allow music access, enable Unknown sources in Android Auto's developer settings, then pick aamp in Android Auto and ask Gemini to play something.

## Requirements

- Android 17 (API 37) phone with Android Auto.
- Unknown sources enabled in Android Auto's developer settings, since aamp is sideloaded.
- Music files indexed by Android's media library, readable with the audio permission.
