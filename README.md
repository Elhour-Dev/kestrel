# Kestrel

A free, ad-free Android video client for personal use. Kotlin + Jetpack Compose, Media3/ExoPlayer,
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) for playback (no API key) and
the [SponsorBlock](https://sponsor.ajay.app) API.

## Features
- Home/trending, search with suggestions, channels, playlists, Shorts, related videos, comments
- Subscriptions feed, history, local playlists, import/export (Kestrel, NewPipe, Google Takeout)
- Player: quality, speed, captions, audio-only, fullscreen, resume, seek/volume/brightness gestures
- Background play with notification controls, picture-in-picture, floating popup player
- No ads; SponsorBlock skipping per category
- Downloads: video or audio, chosen quality, parallel queue, pause/resume/cancel, offline playback
- Optional Google sign-in (Google's own page in a WebView; session encrypted with the Android Keystore):
  personalized feed, subscriptions, history, Watch later, playlists, likes, comments
- Startup health check that repairs itself (client refresh, cache reset, fallback client) and in-app updates

## Build
```
./gradlew assembleRelease
```
Signing uses `keystore.properties` (git-ignored) or the `KESTREL_*` environment variables.

## Automation
- `.github/workflows/release.yml`: builds, runs smoke tests, publishes a release with the APK.
- `.github/workflows/daily-update.yml`: daily NewPipeExtractor update + smoke tests; releases if green,
  opens an issue if something fails.

## License
GNU GPL v3 (see `LICENSE`). Not affiliated with YouTube or Google.
