# Progress (for resuming after a shutdown)

Build: `JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleRelease`
adb: `C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe` (phone: Galaxy A55)
Repo: github.com/Elhour-Dev/kestrel (private for now; must be public for in-app updates)

## Findings
- NewPipeExtractor v0.27.1 works (Nov 2026). Stream URLs use client `c=VISIONOS`;
  plain GET + Range or `&range=` param both work. Live videos: use `hlsUrl`.

## Findings (phone test, 2026-11-11)
- Phone is in China behind a VPN. YouTube blocks video from those VPN IPs: anonymous = bot check / 403,
  signed in = SABR-only (no direct links). Browsing, account, comments all work.
- App handles it: clear message, no crash. Playback code paths verified only on PC earlier (12:15) when IP was clean.

## Playback fix in progress (2026-11-11 afternoon) — CURRENT TASK
- Root bug: NewPipe's "n"/signature decoder is broken on the current player (Rhino error) -> every link 403.
  Fixed with `player/JsSolver.kt` + `assets/ejs/` (yt-dlp EJS solver, run in a hidden offline WebView).
- Signed in, MWEB client (mobile web) gives direct links here; short videos play fully on the phone.
- Remaining: bytes past ~1 MB get 403 = streaming token (pot) rejected. Our BotGuard token AND the token
  read from YouTube's own mobile player (`player/PotCapture.kt`, hidden WebView, fetch hook, 94-byte token)
  both still capped. Next phone run (APK already built, versionCode 177) logs:
  `DIAG[noPlayerPot|withPlayerPot] past1MB [asIs, noPad, pctPad, none]` -> tells if our own player-request
  token causes the cap, or the token format. Play https://www.youtube.com/watch?v=M7lc1UVf-VE and grep "DIAG".
- RESULT (16:24, versionCode 178): all past1MB = 403 (one 400), for BOTH noPlayerPot and withPlayerPot,
  all 4 formats. So token format and our player request are not the cause. Captured pot is now 128 bytes,
  but it was captured on warm-up video aqz-KE-bpKQ, not the played video.
- RESULT (16:30, versionCode 179): pot now captured on the SAME video (PotCapture.get(session, videoId)) -> still
  all 403 past 1MB. Also a 2nd small request on the same link (audio2) gets 403. Token is not the fix here.
  NEXT: WebView player fallback (auto-switch when own player gets 403).
- If nothing works: fallback plan = play through YouTube's mobile web player in a WebView (it plays fine here).
- TEMP "DIAG" logging in Streams.kt, YtDataSource.kt, PotCapture.kt must be removed before release.
- Phone screen timeout set to max for testing; restore with `adb shell settings put system screen_off_timeout 60000`
  and `adb shell svc power stayon false`.

## Done
- [x] Full app: browse, search, channels, playlists, shorts, comments, player, background, PiP, popup,
      SponsorBlock, downloads, settings, help, onboarding, login (WebView works), InnerTube account features
- [x] Startup checks (<= ~6 s wait), self-heal, BotGuard PO tokens, multi-client backup source, updater
- [x] CI: release.yml, daily-update.yml, SmokeTest
- [x] Fresh install on phone; fixed: logo crash, foreground-service crash, HTML in comments, slow startup

## User must do
- bash tools/set-github-secrets.sh   (signing key secrets; my attempt was blocked)
- make repo public (needed for in-app updates), then run the Release workflow once
