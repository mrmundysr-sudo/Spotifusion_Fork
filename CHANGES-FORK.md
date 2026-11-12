# Changes in this fork

This repository is a personal fork of **[Spotifusion](https://github.com/p1di/spotifusion)** by p1di
(upstream commit `ddb3814`, v1.1.4), a native Android app licensed under the
**GNU General Public License v3.0** (see [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), both unchanged). This fork is distributed under the
same license; its complete source code is this repository. It is a personal build, not published on Google Play.

The upstream commit history is kept in full in this repository. Every change below is also visible as a
commit, dated 2026-11-12 or later, made on top of the upstream history.

## What was changed

1. **Shared-storage export** (the only functional change). When a song or podcast episode finishes
   downloading, a second, ordinary audio file is saved in `Internal Storage/Download/Spotifusion/`
   through Android MediaStore (no storage permission needed, Android 10+). The app's own private,
   encrypted copy and everything that plays it are unchanged.
   - `data/offline/SharedAudioExporter.kt` – MediaStore insert, duplicate / overwrite protection.
   - `data/offline/AudioTagging.kt` – writes title, artist, album and cover into the file (M4A, MP3, Ogg Opus).
   - `data/offline/WebmOpusRemuxer.kt` – WebM/Opus → Ogg Opus (same Opus packets, new container).
   - `OfflineDownloader.kt`, `PodcastDownloader.kt` – one call each, just before the private copy is encrypted.
2. **Installs next to the original**: application ID `com.mrmundysr.spotifusion` (debug: `.dev` suffix),
   launcher label "Spotifusion Fork".
3. **Update check** points at this repository's releases instead of upstream's.
4. **CI**: the workflow also runs an emulator test of the export and uploads the debug APK.

Nothing else (interface, navigation, playback, lyrics, library, settings, Bluetooth / Android Auto) is touched.
