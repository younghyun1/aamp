# aamp

A local-music player for Android Auto whose voice search understands albums. Say "play the album ...And Justice for All by Metallica" and it plays that album from the first track, using music files already on the phone.

Since Gemini replaced Google Assistant, voice requests reach non-partner music apps only through the Android Auto media session, and each app decides what to play. aamp exists to make that decision well. The background is in [docs/plans/2026-10-29-gemini-voice-routing.md](docs/plans/2026-10-29-gemini-voice-routing.md) and the design in [docs/plans/2026-10-29-design.md](docs/plans/2026-10-29-design.md).

## Status

Working on an Android 17 phone through the Desktop Head Unit: Gemini requests reach aamp and play the right album, including garbled ones and phrasings such as "X from Y", "Y's album X" or "X Y" without "by", plus genres ("play some jazz") and playlists ("play my road trip playlist", or just its name); Android Auto shows covers, A to Z sections, Recently added, playlists, and shuffle and repeat buttons, and in the playback view the artist and album names open the artist's albums and the album's songs; playback resumes where it stopped when Android Auto connects. On the phone, aamp is a simple player: Albums, Artists, Songs, Genres and Playlists tabs with A to Z rails, album, artist, genre and playlist pages, playlists you make and edit, play controls, and a short settings page, plus a home screen widget with the playing song and play/pause, previous and next. Not yet tested in a car.

Music inside a folder whose name starts with a dot (for example `...And Justice for All/`) is hidden from Android's media index and therefore from aamp; rename such folders.

## Build and install

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open aamp on the phone once and allow music access, plus photo access ("Allow all") if your covers are cover.jpg files next to the music. Enable Unknown sources in Android Auto's developer settings, then pick aamp in Android Auto and ask Gemini to play something.

## Requirements

- Android 17 (API 37) phone with Android Auto.
- Unknown sources enabled in Android Auto's developer settings, since aamp is sideloaded.
- Music files indexed by Android's media library, readable with the audio permission.

## License

MIT; see [LICENSE](LICENSE).
