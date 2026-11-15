# Kestrel

A free, ad-free Android video client for personal use. Kotlin + Jetpack Compose (Material 3),
Media3/ExoPlayer, [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) plus its own
InnerTube clients for playback (no API key), and the [SponsorBlock](https://sponsor.ajay.app) API.

## Features
- Home/trending, search with suggestions, channels, playlists, Shorts, related videos, comments
- Subscriptions feed, history, Watch later, local playlists, import/export (Kestrel, NewPipe, Google Takeout)
- Player: quality, speed, captions, audio-only, fullscreen, resume, seek/volume/brightness gestures
- Background play with notification controls, picture-in-picture, floating popup player
- No ads; SponsorBlock skipping per category
- Downloads: video or audio, chosen quality, parallel queue, pause/resume/cancel, offline playback
- Optional Google sign-in (Google's own page in a WebView; session encrypted with the Android Keystore):
  personalized feed, subscriptions, history, Watch later, playlists, likes, comments
- Startup health check that repairs itself (several InnerTube clients, PO tokens, signature/n solving,
  chunked requests, YouTube's web player as last resort) and in-app updates (direct APK install)
- Network settings: app-only SOCKS/HTTP proxy and DNS over HTTPS

## Code layout (`app/src/main/java/dev/kestrel/app`)
| Folder | What |
| --- | --- |
| `data/` | settings, local store, models; `newpipe/` extractor, `innertube/` account API, `streams/` stream sources, `net/` HTTP |
| `player/` | PlayerManager, playback/popup services, data source, PO tokens, JS solver, web player |
| `downloads/` | download queue and service |
| `system/` | health check, updater |
| `ui/` | Compose screens, shared components, theme |

## Build
```
./gradlew assembleRelease
```
Signing uses `keystore.properties` (git-ignored) or the `KESTREL_*` environment variables.
Tests: `./gradlew testDebugUnitTest` (live smoke tests against YouTube and SponsorBlock).

## Automation
- `ci.yml`: build, lint and unit tests on every push. Publishes nothing.
- `release.yml`: builds, runs smoke tests, publishes a release with the APK (run by hand).
- `daily-update.yml`: daily NewPipeExtractor update + smoke tests; releases if green, opens an issue
  if something fails or the stream tests were skipped by a bot check.

## Networks that YouTube blocks
Datacenter/VPN IPs often get "confirm you're not a bot" or HTTP 403 on video links. The app then falls
back to other clients and finally YouTube's own web player. The real fix is on the VPN server: send
YouTube traffic out through a clean IP (for example Cloudflare WARP over IPv6).

## License
GNU GPL v3 (see `LICENSE`). Not affiliated with YouTube or Google.
