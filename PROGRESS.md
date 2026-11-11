# Progress (for resuming after a shutdown)

Build: `JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleRelease`
adb: `C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe` (phone: Galaxy A55)
Repo: github.com/Elhour-Dev/kestrel (private for now; must be public for in-app updates)

## Findings
- NewPipeExtractor v0.27.1 works (Nov 2026). Stream URLs use client `c=VISIONOS`;
  plain GET + Range or `&range=` param both work. Live videos: use `hlsUrl`.

## Done
- [x] Skeleton, GPLv3, wrapper, extractor probe

## Next
- [ ] Core: models, NewPipe repo, local store, settings, SponsorBlock
- [ ] Player: ExoPlayer singleton, service, PiP, popup, gestures
- [ ] UI screens
- [ ] Account: WebView login, encrypted cookies, InnerTube
- [ ] Downloads
- [ ] Startup checks + updater
- [ ] CI workflows + smoke tests
- [ ] Install on phone, fix bugs
