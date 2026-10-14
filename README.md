# CastBay

[![Stars](https://img.shields.io/github/stars/bestinepayyappilly/tv-airplay)](https://github.com/bestinepayyappilly/tv-airplay/stargazers)
[![GitHub](https://img.shields.io/github/downloads/bestinepayyappilly/tv-airplay/total?label=GitHub&logo=GitHub)](https://github.com/bestinepayyappilly/tv-airplay/releases)
[![license](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![build](https://img.shields.io/github/actions/workflow/status/bestinepayyappilly/tv-airplay/apk.yml?label=build)](https://github.com/bestinepayyappilly/tv-airplay/actions/workflows/apk.yml)
[![release](https://img.shields.io/github/v/release/bestinepayyappilly/tv-airplay)](https://github.com/bestinepayyappilly/tv-airplay/releases)

CastBay is an always-on AirPlay receiver for Android TV, tuned for low-end TVs. Mirror an iPhone or Mac screen,
stream music with cover art and controls, or cast a video from QuickTime, straight to the TV.

It is a fork of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server),
which is built on [UxPlay](https://github.com/FDH2/UxPlay). The reference device is a OnePlus 50Y1S Pro:
an MStar `m7632` running Android TV 11, with 1.8 GB of RAM, a 32-bit (armeabi-v7a) userland and a 1080p60 UI plane.
It runs on any Android 7.0+ device, but the tuning targets TVs like that one.

## Quickstart

1. **Download** the APK from the [latest release](https://github.com/bestinepayyappilly/tv-airplay/releases/latest), or from the [`releases/`](releases) folder:
   - [`CastBay-v1.0.0-armeabi-v7a.apk`](releases/CastBay-v1.0.0-armeabi-v7a.apk) for most Android TVs (8 MB)
   - [`CastBay-v1.0.0-universal.apk`](releases/CastBay-v1.0.0-universal.apk) if you're unsure (27 MB)
2. **Install it on the TV.** Use either:
   - adb: turn on Developer options and Network debugging on the TV, then run
     `adb connect <tv-ip>:5555 && adb install CastBay-v1.0.0-armeabi-v7a.apk`
   - a file manager or the Downloader app: open the APK on the TV and allow installs from that app.
3. **Open CastBay once.** From then on it starts with the TV and keeps running in the background.
4. **(Optional) Let it wake the TV** when a device connects, by granting "Display over other apps".
   Some TVs hide that setting; over adb it's `adb shell appops set com.castbay SYSTEM_ALERT_WINDOW allow`.
5. **Connect.** The TV appears under its own name (for example "OnePlus TV"):
   - iPhone or iPad: Control Center → Screen Mirroring, or the AirPlay button in a music or video app.
   - Mac: Control Center → Screen Mirroring, Sound output, or QuickTime's AirPlay button.

> [!WARNING]
> DRM-protected content (Apple TV+, Netflix and similar from an iPhone) shows a black screen. That is FairPlay DRM and can't be worked around.

## What this fork adds

### Always on

- Starts with the TV. It listens for `LOCKED_BOOT_COMPLETED` as well, because some TV firmware withholds `BOOT_COMPLETED` from third-party apps.
- When a sender connects, it wakes the TV from standby and brings itself to the front.
- It re-announces itself on the network after network changes and screen-on, and the receiver takes the TV's own device name (for example "OnePlus TV").

### Mirroring

- Frames go straight from the hardware decoder to the display surface. On the reference TV, frame arrival to screen dropped from about 79 ms to about 55–60 ms.
- On a TV it never advertises a resolution above the panel. A 4K mirror on a 1080p TV cost about twice the latency and stuttered.

### AirPlay 2 sessions

- HomeKit transient pairing (SRP) with ChaCha20-Poly1305 encrypted control, event, video and audio channels, so current iOS and macOS senders use their AirPlay 2 paths.
- macOS QuickTime "AirPlay video v2": a local file cast from a Mac plays through a loopback TLS-PSK proxy, with play/pause and seeking.
- Now-playing info the AirPlay 2 way: title, artist, album, artwork, progress and play state. That fixes apps such as YouTube Music, which send little or no classic DMAP metadata.

### Music

- The play/pause state follows the sender, from its own reports and, as a fallback, from the silence a paused sender streams.
- When another device takes over the TV, the device that was playing music is paused, as on an Apple TV.
- A small software ALAC decoder (David Hammerton's) replaces upstream's FFmpeg fallback.

### TV interface

- A dark glass sidebar that opens with remote focus, with the connected device pinned at its foot.
- A large-cover now-playing screen, and an ambient view (black, a slow glow in the cover's colors, the cover and the title) after a few idle seconds.
- Monochrome styling, so the artwork is the only color on screen.

### Kept from upstream

The fork keeps H.264/H.265 mirroring, AAC-ELD, AAC-LC and ALAC audio, HLS video with remote controls, picture-in-picture, an optional PIN, and resolution, frame-rate, overscan and latency settings.

## Known limits

- **One sender at a time.** A new device takes over (Settings → Connection → "Allow new connections").
- **No multi-room, grouping or AirPlay 2 buffered audio.** These need PTP clock sync on UDP ports 319/320, which an unrooted Android TV doesn't allow.
- **No "move playback here" (HomePod-style handoff) and no up-next track names.** The sender only offers those to Apple receivers.
- **On some OnePlus/HeyTap TVs the macOS Music app's own AirPlay button fails.** Its legacy sender insists on port 5000, which a system service owns. Mac system audio (Sound output → this TV) works.

## Building

```sh
git submodule update --init --depth 1        # UxPlay, OpenSSL, libplist
./gradlew assembleRelease -Pabis=armeabi-v7a  # omit -Pabis to build arm64-v8a, armeabi-v7a and x86_64
```

- **NDK:** pinned to 27.1.12297006 in `app/build.gradle.kts`. OpenSSL compiles from source, and a clean build takes about a minute.
- **Release signing:** add `storeFile`, `storePassword`, `keyAlias` and `keyPassword` to `local.properties`. Without them the release APK is unsigned.
- **UxPlay changes:** don't edit UxPlay in place. Add a numbered patch to `app/src/main/cpp/patches/UxPlay/`; the build resets the touched files and reapplies every patch before CMake runs.
- **CI** ([`.github/workflows/apk.yml`](.github/workflows/apk.yml)): every push to `main` builds an APK, and a `v*.*.*` tag publishes a release.

## Credits

This project stands on other people's work. Thank you to all of them.

### Code this app is built on or contains

| Project | Author | License | Used for |
| --- | --- | --- | --- |
| [android-airplay-server](https://github.com/jqssun/android-airplay-server) | jqssun and contributors | GPL-3.0 | The base app: UxPlay over JNI, mirroring, HLS video, native audio, Compose UI, service. Upstream contributors include Andy Bao, Cosmin Mocan, Alan, BasedLFO, TheSkyentist, c0mrrr and scavin |
| [UxPlay](https://github.com/FDH2/UxPlay) | fduncanh and contributors, building on [RPiPlay](https://github.com/FD-/RPiPlay) (Florian Draschbacher), dsafa22's AirPlay 2 mirroring work and Juho Vähä-Herttua's shairplay | GPL-3.0 | AirPlay/RAOP server, FairPlay, mirroring and audio protocol |
| [airplay-tv](https://github.com/besliky/airplay-tv) | besliky | GPL-3.0 | Always-on behavior (`AlwaysOn.kt`), mDNS advertising (`Advertiser.kt`), network monitoring (`NetworkMonitor.kt`), and the hardened ALAC decoder |
| ALAC decoder | David Hammerton | MIT | Software ALAC decoding (`cpp/alac/`) |
| [DiPlay](https://github.com/shihabal3amri/DiPlay) | Shihab | GPL-3.0 | AirPlay video v2 playback message formats (`VideoV2Session.kt`) |
| [Next Player](https://github.com/anilbeesetti/nextplayer) | Anil Beesetti | GPL-3.0 | Video player controls (via upstream) |
| [libplist](https://github.com/libimobiledevice/libplist) | libimobiledevice | LGPL-2.1 | Property lists |
| [OpenSSL](https://www.openssl.org/), built with [openssl-cmake](https://github.com/viaduck/openssl-cmake) | OpenSSL Project; viaduck | Apache-2.0; MIT | Cryptography, TLS-PSK |
| [Oboe](https://github.com/google/oboe), [AndroidX Media3/ExoPlayer](https://github.com/androidx/media), Jetpack Compose, Hilt | Google | Apache-2.0 | Audio output, video playback, UI, dependency injection |

### References consulted

These informed the protocol work; no code was copied from them unless it's listed above.

- [pyatv](https://github.com/postlund/pyatv) (MIT, Pierre Ståhl): HomeKit pairing, SRP and the encrypted-channel conventions that `hap.c` was written against.
- [shairport-sync](https://github.com/mikebrady/shairport-sync), [OwnTone](https://github.com/owntone/owntone-server) and [openairplay/airplay2-receiver](https://github.com/openairplay/airplay2-receiver): AirPlay 2 session behavior, `/command` now-playing messages, feature flags.
- [Unofficial AirPlay 2 documentation](https://emanuelecozzi.net/docs/airplay2) by Emanuele Cozzi and the [openairplay spec](https://openairplay.github.io/airplay-spec/): feature bits and message formats.
- Apple's HomeKit Accessory Protocol specification: pair-setup and pair-verify.
- [PhairPlay](https://github.com/mazer666/PhairPlay) (Apache-2.0, mazer666): studied for its multi-protocol service structure.

## License

[GPL-3.0](LICENSE), inherited from UxPlay and the upstream app. Files ported from other projects keep their original copyright notices.

---

Disclaimer: this project is not affiliated with Apple Inc. AirPlay, iPhone, Mac and QuickTime are trademarks of Apple Inc.
