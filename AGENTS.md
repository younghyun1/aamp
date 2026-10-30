# AGENTS.md

Working rules for this repository. Read [docs/plans/2026-10-29-design.md](docs/plans/2026-10-29-design.md) before changing code; it records the decisions below and the reasons for them. [docs/plans/2026-10-29-gemini-voice-routing.md](docs/plans/2026-10-29-gemini-voice-routing.md) explains how voice requests reach the app.

## Project

aamp is a local-music player for Android Auto. Its voice search turns Gemini requests into the right queue, album requests in particular. It runs on Android 17 (API 37) only, on one sideloaded device, and is written in Kotlin.

## Rules

- API 37 is the floor. No version checks below 37, no compatibility shims, no compat libraries such as AppCompat or Material.
- Performance is a requirement. Code on the Android Auto path (service start, browsing, voice requests) never loads UI classes, uses reflection, or blocks the main thread.
- Dependencies are Media3 (`media3-exoplayer`, `media3-session`), kotlinx-coroutines (core, android, guava), the AndroidX baseline profile tooling and JUnit. Anything else needs a reason recorded in the design doc first.
- Media3 session and player calls run on the main thread. Library loading, parsing and matching run on the library dispatcher.
- Every cache is bounded by entries or bytes. The library snapshot is replaced on rescan, never grown in place.
- Errors are explicit: sealed result types, no `!!`, and no exceptions escaping Media3 callbacks. `require` and `check` guard programmer errors only.
- Pure logic (normalization, parsing, scoring, snapshot encoding) uses no Android types, so it runs in JVM unit tests, and it has unit tests.
- Kotlin uses stable 2.4 language features. Experimental compiler flags need a reason recorded in the design doc.
- Comments explain what and why on their own. No ticket, milestone or plan references in code.

## Layout

```
app/src/main/kotlin/dev/cyh/aamp/
  AampApplication.kt   process-wide Library and VoiceLog
  playback/   PlaybackService, LibraryCallback, AudioPlayer
  library/    MediaStore scan, LibrarySnapshot, LibraryBuilder, Library state
  search/     TextNormalizer, MatchKey, Similarity, SearchIndex
  voice/      VoiceRequestParser, QueueResolver, VoiceLog, PlayFromSearchActivity
  browse/     media ids and the Android Auto browse items
  ui/         MainActivity
app/src/test/kotlin/dev/cyh/aamp/   JVM unit tests; TestLibrary is the shared fixture
docs/plans/                         design notes, YYYY-MM-DD-name.md
```

`artwork/` (ArtworkProvider) and snapshot persistence in `library/` are still to come.

## Toolchain

- JDK 27 (`/usr/bin/java`), Gradle through the wrapper only.
- Android SDK at `$ANDROID_HOME` (`~/Android/Sdk`). Required packages: `platforms;android-37.2` and `build-tools;37.0.0`; the Desktop Head Unit is `extras;google;auto`.
- Versions are pinned in `gradle/libs.versions.toml`. Check the latest stable release before upgrading, and update the design doc's toolchain table in the same change.

## Commands

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Aamp:V AampVoice:V AampLibrary:V
adb shell dumpsys media_session
adb shell cmd audio set-enable-hardening throw
adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -n dev.cyh.aamp/.voice.PlayFromSearchActivity --es query "album kill em all by metallica"
```

The last command replays a voice request through the same session path Android Auto uses; add hints with `--es android.intent.extra.focus vnd.android.cursor.item/album --es android.intent.extra.album "Kill 'Em All"` and similar.

Routine work uses debug builds and unit tests. Release builds, baseline profile generation and on-device benchmarks are run by the maintainer.

## Device testing

- Android Auto hides sideloaded apps until Unknown sources is enabled: Android Auto settings, tap Version repeatedly to unlock developer settings, then enable Unknown sources.
- The voice log on the phone screen, or `adb logcat -s AampVoice:V`, shows exactly what Gemini sent.
- Background audio on Android 17 fails silently. Test playback started from Android Auto with audio hardening set to `throw`.

## Docs and commits

- Design decisions live in `docs/plans/` as `YYYY-MM-DD-name.md`. When a decision changes, edit the design doc in place rather than appending dated status sections.
- Commit subjects are short and imperative; the body explains why when it is not obvious. One logical change per commit.
