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

## Audit + playback fix (2026-11-11 evening) — DONE (build 207 = release candidate)
Tested on phone (build 201): Shorts then next video OK (video only), SponsorBlock 'Skipped Intro' OK in web mode, signed in OK. Downloads: 403 on this VPN -> clear message 'Blocked by YouTube. Try another network.'
Also fixed after review: health check only uses web player on timeout; 'blocked' message for downloads; 40 s cap + clear message when YouTube is unreachable (no VPN in China); 'Video unavailable' title; WebView renderer crash no longer kills the app.
Not tested: no-VPN timeout message on build 207 (needs VPN off), signed-out, wifi without a hijacked DNS.
Open: web page sometimes shows YouTube 'Something went wrong' mid-video on this VPN (not detected by the app); no next/prev in web notification.

Root cause (proven): YouTube blocks this VPN's IP for direct stream links. yt-dlp 2026.10.26 on the PC
(same VPN) fails with every client (bot check; web_embedded gives links but 403; HLS stuck). So no
direct-link trick works here; YouTube's own web player (WebView) is the fix. Earlier "1 MB cap" = same block.
Done (commits 7771b3c, 85042a4, e96b075 + uncommitted WebHost PAGE_JS change):
- Streams.linkWorks(url): start + past-1MB check, shared by player, Fallback and Health. Sets blockedUntil.
- Fallback: HLS-only clients now probe one segment; stop trying clients once blocked; no double Fallback run.
- Health TEST_VIDEO = M7lc1UVf-VE (audio > 1 MB; old one was too small to see the cap).
- Removed PotCapture.kt (proven useless). Media3 1.11.1, AGP 9.5.1. Zero compiler warnings.
- Web mode: WebSessionPlayer (SimpleBasePlayer) in PlaybackService -> notification + foreground;
  WebView parked 1x1 in MainActivity when no screen shows it; onWindowVisibilityChanged forced VISIBLE.
  => background play works (tested 60 s after Home), PiP works, mini player works.
- Clear error for private/paid/geo/age(signed out)/UNPLAYABLE videos (no web fallback for those).
- Shorts: observe web flag (was black). Upload date formatted. Details load after web switch.
- Switch to web: ~7 s on first video, instant after (was ~50 s).
Phone tests passed (versionCode 199): normal, long (26 min), music, age-restricted (signed in), live
(4t4AQ15yj1M), Shorts + swipe, dead stream message, PiP, background, notification.
IN PROGRESS (versionCode 200, built, not yet tested): PAGE_JS now also injected at document start
(bug: after Shorts, next video showed full YouTube page, muted -> script hadn't run).
Test: Shorts tab, swipe, then open 8aGhZQkoFbQ via intent -> must show video only, with sound.
Still to test: SponsorBlock skip in web mode (8aGhZQkoFbQ has intro 0-17 s; rewind with
KEYCODE_MEDIA_REWIND), downloads (likely blocked on this network -> check message), login.
Then: commit, push, final report (root cause, techniques, fixes, tests, what's left).

## Done
- [x] Full app: browse, search, channels, playlists, shorts, comments, player, background, PiP, popup,
      SponsorBlock, downloads, settings, help, onboarding, login (WebView works), InnerTube account features
- [x] Startup checks (<= ~6 s wait), self-heal, BotGuard PO tokens, multi-client backup source, updater
- [x] CI: release.yml, daily-update.yml, SmokeTest
- [x] Fresh install on phone; fixed: logo crash, foreground-service crash, HTML in comments, slow startup

## User must do
- bash tools/set-github-secrets.sh   (signing key secrets; my attempt was blocked)
- make repo public (needed for in-app updates), then run the Release workflow once

## New VPN server retest (2026-11-12, build 208)
- Normal, long, live, age-restricted: PLAYING (direct links still 403 here, web-player fallback used). Background 45 s: OK. SponsorBlock toast: OK.
- Downloads: still 403 on this VPN. Message shortened to "Blocked by YouTube." so it fits the list row.
- Sign-out test skipped on purpose (I can't sign back in for the user).
- Build 209: no-VPN case showed "Loading…" forever (blocking network call ignored the timeout). Fixed with `withNetTimeout` in PlayerManager (40 s, works in both the direct and web paths). Verified on phone: "The connection timed out. Check your network or VPN and try again." + Retry after ~55 s.
