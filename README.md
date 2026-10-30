# aamp

A local-music player for Android Auto whose voice search understands albums. Say "play the album ...And Justice for All by Metallica" and it plays that album from the first track, using music files already on the phone.

Since Gemini replaced Google Assistant, voice requests reach non-partner music apps only through the Android Auto media session, and each app decides what to play. aamp exists to make that decision well. The background is in [docs/plans/2026-10-29-gemini-voice-routing.md](docs/plans/2026-10-29-gemini-voice-routing.md) and the design in [docs/plans/2026-10-29-design.md](docs/plans/2026-10-29-design.md).

## Status

Design only; the first milestone (playback service, library scan, Android Auto browse tree and voice log) is next.

## Requirements

- Android 17 (API 37) phone with Android Auto.
- Unknown sources enabled in Android Auto's developer settings, since aamp is sideloaded.
- Music files indexed by Android's media library, readable with the audio permission.
