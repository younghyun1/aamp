# Gemini voice routing to music apps

Why "play the album ...And Justice for All by Metallica" stopped working with local music after Gemini replaced Google Assistant, and which path aamp depends on. Findings as of 2026-10-29.

## What changed

Google began removing Google Assistant from Android phones on 2026-09-04 and removed the option to switch back. Phone-projected Android Auto uses Gemini as its assistant from then on.

Google Assistant reached third-party music apps in two ways: App Actions (`actions.intent.PLAY_MEDIA`, delivered as a `MEDIA_PLAY_FROM_SEARCH` intent) on the phone, and the Android Auto media session in the car. Gemini does not read App Actions at all.

## The two routes Gemini uses

**Partner route (phone and car).** Gemini resolves the request itself and sends it to a Connected App. Google's help page names iHeartRadio, Pandora and Spotify; YouTube Music has its own integration. The list lives on Google's side, and no manifest entry, capability declaration or session flag adds an app to it. AppFunctions, the successor developer API, is in a private preview for selected apps. Local files can never use this route.

**Android Auto route (car only).** Android Auto hands the spoken request to the media app that owns the active session by calling `playFromSearch(query, extras)` on it. Google's Android Auto media documentation, updated 2026-10-23, says this guidance applies to Gemini when it is the default assistant. The extras may carry structured hints:

| Key | Meaning |
|---|---|
| `android.intent.extra.focus` | Request type: `vnd.android.cursor.item/album`, `.../artist`, `.../audio` (song), `.../genre`, `.../playlist`, or `vnd.android.cursor.item/*` (any) |
| `android.intent.extra.album` | Album name |
| `android.intent.extra.artist` | Artist name |
| `android.intent.extra.title` | Song title |
| `android.intent.extra.genre` | Genre |
| `android.intent.extra.playlist` | Playlist |

From there the app alone decides what plays. The route only works if the session advertises `ACTION_PLAY_FROM_SEARCH` in its current playback state, including when idle; otherwise Gemini answers that it cannot control media apps. Music Assistant hit exactly this and fixed it in its app in September 2026.

## Observed: what Gemini actually sends

Measured on a Samsung SM-S948U running Android 17, through the Desktop Head Unit, on 2026-10-29.

- **Caller and target.** The Google app (`com.google.android.googlequicksearchbox`) calls `playFromSearch` on the session of the currently active media app; the Android Auto host does not relay it. The system log shows `tempAllowlistTargetPkgIfPossible callingPackage:com.google.android.googlequicksearchbox targetPackage:<app> reason:MediaSessionRecord:playFromSearch [WIU]`.
- **Which app.** The active media app, not one named in the request. Android Auto resumes the last-used media app when it connects, so that app receives requests until another one plays.
- **While-in-use.** The `[WIU]` grant gives the target app while-in-use status for the request, so Android 17's background audio hardening does not block playback started by voice.
- **Hints.** None useful. For "play album And Justice For All by Metallica" the extras were only `android.intent.extra.focus=vnd.android.cursor.item/*`, `android.intent.extra.REFERRER_NAME` and `android.intent.extra.user_query_language`. No album, artist or title extras.
- **Query text.** Rewritten by Gemini: "play album" was removed, and recognition through the head unit's microphone produced "And Just For All My Intelligents".

This explains Poweramp: it received the bare name without any album marker and searched songs first, finding the title track "...And Justice for All".

## Consequences for aamp

- aamp must be the active media app in Android Auto for voice requests to reach it.
- Matching is entirely aamp's job, from text alone: the word "album" never arrives, artist names may be misheard, and "by" may be heard as "my". An album that matches as well as its same-named title track has to win.
- Gemini on the phone, outside the car, will not route to aamp; nothing in the app can change that.
- The session must keep advertising play-from-search in every state. Media3 does this whenever the player allows `COMMAND_SET_MEDIA_ITEM` (checked in the Media3 1.11.1 source, `MediaSessionLegacyStub`).

## Sources

- [Android Developers: Google Assistant and media apps](https://developer.android.com/media/implement/assistant)
- [Android Developers: Support voice actions (cars)](https://developer.android.com/training/cars/media/voice-actions)
- [Gemini Help: Play music from streaming apps](https://support.google.com/gemini/answer/15300097?hl=en&co=GENIE.Platform%3DAndroid)
- [Android Developers: AppFunctions overview](https://developer.android.com/ai/appfunctions)
- [music-assistant/mobile-app#827](https://github.com/music-assistant/mobile-app/issues/827), [music-assistant/mobile-app#969](https://github.com/music-assistant/mobile-app/pull/969) and its [Android Auto notes](https://github.com/music-assistant/mobile-app/blob/main/docs/ANDROID-AUTO.md)
- [Android Headlines: Google Assistant gone from Android phones](https://www.androidheadlines.com/2026/09/google-assistant-killed-android-gemini.html)
- [XDA: Poweramp with Gemini on Android Auto](https://www.xda-developers.com/used-poweramp-and-youtube-music-side-by-side-on-android-auto-for-month-one-clearly-won/)
