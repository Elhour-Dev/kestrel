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

## Done
- [x] Full app: browse, search, channels, playlists, shorts, comments, player, background, PiP, popup,
      SponsorBlock, downloads, settings, help, onboarding, login (WebView works), InnerTube account features
- [x] Startup checks (<= ~6 s wait), self-heal, BotGuard PO tokens, multi-client backup source, updater
- [x] CI: release.yml, daily-update.yml, SmokeTest
- [x] Fresh install on phone; fixed: logo crash, foreground-service crash, HTML in comments, slow startup

## User must do
- bash tools/set-github-secrets.sh   (signing key secrets; my attempt was blocked)
- make repo public (needed for in-app updates), then run the Release workflow once
