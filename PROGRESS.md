# Progress (for resuming after a shutdown)

Build: `JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleRelease`
adb: `C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe` (phone: Galaxy A55)
Repo: github.com/Elhour-Dev/kestrel (private for now; must be public for in-app updates)

## Findings
- NewPipeExtractor v0.27.1 works (Nov 2026). Stream URLs use client `c=VISIONOS`;
  plain GET + Range or `&range=` param both work. Live videos: use `hlsUrl`.

## Done
- [x] Skeleton, GPLv3, wrapper, extractor probe
- [x] Data layer (NpRepo, InnerTube, LocalStore, SponsorBlock, Streams+fallback)
- [x] Player (YtDataSource, PlayerManager, PlaybackService, PopupService, gestures)
- [x] Downloads engine + service, Health checks, Updater
- [x] All UI screens written + resources/icon (first full compile in progress)

## Next
- [ ] Fix compile errors, build APK
- [ ] CI workflows (release.yml, daily-update.yml) + SmokeTest
- [ ] Release keystore (outside repo) + GitHub secrets
- [ ] Install on phone (fresh), test, fix crashes
- [ ] Final report
