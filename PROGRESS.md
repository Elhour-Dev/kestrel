# Progress (for resuming after a shutdown)

Build: `JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleRelease`
adb: `C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe` (phone: Galaxy A55)
Repo: github.com/Elhour-Dev/kestrel (private for now; must be public for in-app updates)


## Quality fix (2026-11-16) — DONE, commit 004cff0 (build 258, local only)
- Cause: web mode (used whenever links are blocked) hid the Quality chip and ignored setQuality.
- Fix: WebHost reads levels via movie_player.getAvailableQualityLevels() and applies setPlaybackQualityRange (works on m.youtube.com, tested). Default quality applied at web start. Chip moved after Dislike.
- Phone tests: direct 720p->360p (decoder 640x360), web 720p->240p (current=small). Default 720 applied in web for normal video and Shorts.
- Regression pass OK: startup, home, search+suggestions, channel, playback, SponsorBlock skip, comments, auto-PiP, PiP return, background play, mini player, library, history, watch later, downloads screen, subscriptions, settings, Shorts.
- Seen once, not reproduced: playback stopped after PiP->return->comments->back x2; a Short hung ~2 min when the network switched to blocked links.

## UI redesign "Plover" (plan approved 2026-11-16) — branch ui-redesign
Plan file: ~/.claude/plans/pasted-content-id-915c-first-use-cheeky-hellman.md. Dark only. Keep applicationId dev.kestrel.app.
Install test builds with -PversionCode=258+ and adb install -r (never uninstall / pm clear).
Web-mode testing: build with -PforceWeb=true (never ship that build).

Done on ui-redesign (all phone-checked): 0be1b5b name/icon/dark theme, 4ab16f6 shell (top tabs, icon bottom bar),
6e714fc cards/feeds/Shorts shelf, b669ac3 forceWeb switch.
Next: player in 4 commits (Compose overlay on PlayerView useController=false + webPlayer; gear sheet with
quality/speed/captions/audio track/audio only/popup/PiP/sleep/loop; gestures ported into overlay pointerInput;
watch-page restyle). Test each in direct, web, local file, live, PiP, fullscreen.
Not yet phone-tested: 46ed38c drift retry (hard to force).

### Reference: YouTube Android app on the phone (2026-11-16)
- Top bar: logo left; bell (red count badge) + search right. Under it text tabs: Home | Subs | Music | Live | More (underline on active). More = list: Movies & TV, Charts, Gaming, ...
- Bottom bar: icons only, no labels: Home, Shorts, Create (+), You (account avatar).
- Feed card: full-bleed 16:9 thumbnail, duration pill bottom-right; under it avatar + 2-line title + "channel  ▷1.2M  5y ago" + "•••". Skeleton placeholders while loading.
- Watch page: 1-line title (fades out), "@handle  88K likes  1.2M views  5y ago  more"; row: channel avatar, subscribe/bell pill, then icon-only Like, Dislike, Share, Ask(AI), •••; Comments card "Comments 2.9K" with a carousel; then related feed.
- Player controls: top: chevron (minimize), autoplay toggle pill (center), cast, CC, gear. Middle: prev / play-pause / next in dim circles. Bottom-left time pill "1:28 / 12:57", bottom-right fullscreen; red progress bar with dot on the bottom edge.
- Gear sheet: Quality "Auto (144p)", Playback speed, Captions, Lock screen, On-the-go, More -> Loop video, Ambient mode, Stable volume, Sleep timer, Watch in VR, Help.
- Quality sheet: Auto (recommended) / Higher picture quality / Data saver / Advanced (specific resolution).
- Mini player: floating 16:9 card bottom-right with play + close over it, above the bottom bar.
- You tab: Accounts chip, bell, search, settings; avatar + name + @handle; "View channel" button; History row (horizontal cards); Library chips (Recents, Downloaded, Playlists, Music) + playlist rows (Liked videos, Watch later, ...).
- Notifications: "Important" + "Today" sections, rows with avatar, text, time, thumbnail, •••.
- Shorts: full-screen; top right search + •••; right column: like(heart)+count, comments+count, Save, Share, Ask, Remix, sound disc; bottom: avatar @handle + Subscribe pill, title.
- Search: back, field, voice; suggestion rows with a fill-in arrow.

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
- Removed PotCapture.kt (proven useless). Media3 1.11.1, AGP 9.5.1. Zero compiler warnings (again after 2026-11-15 fixes).
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
- Build 211: web-player mid-video error ("Something went wrong") is now detected (page script looks for YouTube's player-error element -> `WebHost.onError`). PlayerManager reloads once at the last good position, then shows "YouTube's player stopped with an error. Try again or switch VPN server." + Retry. Verified on phone: reload at ~2 min, error screen at ~3.5 min on this VPN (the VPN itself still breaks the stream). Cosmetic: the controls overlay can show behind the error text.

## Session 2026-11-15 (overhaul)
- Backup tag: backup-before-overhaul-2026-11-15 (pushed).
- Audit: build OK, 12 compiler warnings, 61 lint warnings (0 errors). Stream/download smoke tests were skipped (bot check).
- Root cause proven from the VPS itself: YouTube bot-blocks the VPS IP (and the second VPS). PO-token provider did not help; no IPv6.
- Fix (on the VPS, not in the app): YouTube domains now leave the VPS through Cloudflare WARP; everything else unchanged.
  Notes + undo script are on the VPS in /root/warp (README.txt, rollback.sh). Verified: full 18.5 MB download from the PC through the VPN.
- WARP egress is IPv6 only (WARP's IPv4 is bot-flagged too; mixed v4/v6 broke links). Inbound sniffing turns phone IP destinations into domains.
- App fixes (builds 214-224, installed in place): Samsung TV client (no PO token needed) first; link check uses the player's 1 MB ranges;
  "blocked" only when every source refuses; live checks one HLS segment; titles from oEmbed; next/prev in web notification;
  skeleton loading; opaque player error; Settings > Network (app proxy + DoH); Home hides removed kiosks (Trending, podcasts);
  12 compiler warnings fixed; CI workflow (was failing: setup-android 'tools', android-37.0 name, gradlew not executable).
- Phone tests 2026-11-15 (build 222-224, VPN on): normal, long (26 min), live, Shorts+swipe, search+suggestions, comments+replies,
  subscriptions, Watch later, like/unlike, quality switch, audio-only, fullscreen rotation, PiP, background (screen off),
  notification pause/play, SponsorBlock intro skip, downloads (video+audio, parallel, resume, offline play), fresh install (debug app) signed out. No crashes/ANRs.
- Not tested: posting/deleting comments (needs user OK), popup (needs "Appear on top" permission), updater (repo private),
  low-network throttling, age-restricted this round. Seen twice right after install: first link on cold start not opened (not reproducible).
- Round 2 (builds 225-228): 403 = LinksRefusedException; network marked blocked only when every source is refused or bot-checked;
  fallback stops after 3 refusals/bot checks; web page bot-check detected (clear message instead of black video);
  light-theme status bar icons fixed; avatar button labelled; daily job won't release when stream tests were skipped;
  relaunch no longer replays an old link. VPS: /root/warp/apply_warp.py re-applies (idempotent), working copies in /root/warp/working.
- Tests on 225-228: normal, long, live (ANDROID_VR), music (NewPipe after TV 403s), age-restricted signed in (web player), 720p download+merge,
  light theme, DoH on (debug app), blocked IP via SOCKS tunnel + app proxy (debug app): clear bot-check message, 6 s on 2nd video, ~40 s on 1st.
- Round 3 (build 229): signed-out client order TV, ANDROID_VR, IOS first (early stop no longer skips them); signed-in loop never stops early;
  bot-check text checked before the player-error box and only while the video hasn't loaded; notification gets the fallback title;
  Recents relaunch doesn't replay a link; DoH bootstrap 3-5 s timeout; daily job holds back (no commit, no release) an extractor bump when stream tests were skipped.
- Known: right after `adb install -r` + force-stop, the first link opened can play the previously opened video (seen 3x; dev-only path).
- Build 230: confirm dialog before deleting a comment/reply. Phone-tested: post, reply, delete, Cancel keeps it, Delete removes it (all on the user's account, test comments removed).

## Session 2026-11-15/16 (fix list: controls, likes, description, ads, collabs, downloads, subscribe)
- Watch page details come from YouTube's `next` data right away: likes, description, views/date, avatar, subscribers, related,
  collaborators (dialog lists every channel; 2nd channel opens). Controls show on start; web player has its own controls and strips/skips ads
  (ad fields removed from player JSON, ad hosts/paths get 204, skip/mute/jump when an ad shows).
- Downloads: 1 MB ranges like the player, fresh links once on 403, reuse the playing video's links.
- Subscribe sends YouTube's params and syncs. Bell removed: YouTube answers 400 for every variant of the notification call.
- Links open through LinkActivity: first link after a stop/update now opens that video (5/5 cold starts on build 242).
- Links: preferred source gets 2.5 s, then the other starts too (HedgeTest, 9 offline tests). "Unavailable" from NewPipe ends it at once.
- Start: links skip the startup wait (checks run 20 s later); normal start waits max 2.5 s (was 6 s). Cold launch 292 ms.
- New: "Copy error details" on error screens, Settings > Share app log (no cookies), Back up/Restore now include settings (tested on debug app).
- Network 2026-11-16 ~15:05+: phone VPN slow (1.5 s per TCP connect, links 16-29 s, earlier 4.8 s). Phone v2rayNG uses "vpnizer Auto"
  (same VPS as the PC); its "Tokyo-Trojan/VLESS" profiles point at an old IP and fail (-1 ms). PC uses Tokyo-Hy2. Not changed (ask user).
- Account side effects of tests: test videos went into watch history; Kowski and KSI subscriptions from subscribe tests may need removing.
- Later 2026-11-16 (builds 249-255): checker review fixed: signed-in private/members-only no longer cut off by NewPipe's error;
  web controls Activity leak; collab dialog per video. Cancelled backup clients now stop. Links with t=/start= start there (LinkStartTest).
  Phone (debug app via PC's Tokyo-Hy2 over adb reverse): t=60 and t=98 start right; SponsorBlock skipped 1:46-3:02 natively.
  Slowness root cause: China -> VPS link today (VPS -> YouTube 0.05 s; NewPipe getInfo 11-14 s from both phone and PC).
- Not done / limits: update prompt not shown in link-opened sessions; ad baseline not possible (YouTube served no ad data to this
  network signed out); web overlay takes all taps (in-page prompts like a content warning can't be tapped).
- Builds 256-257 (installed): controls now hide 3 s after playback starts (were stuck on). Checker round 2 fixed: solver timeout no longer
  aborts the backup loop; link start reset per link, ignored for live, capped. Release tests: 6/6 cold links (VIEW, SEND, t=) land on
  the player; Subscribe with params -> Google says subscribed=true on another video of the channel; unsubscribed again (test undone).
  Download not re-tested on release: network marked blocked (30 min) after all sources refused one video.
