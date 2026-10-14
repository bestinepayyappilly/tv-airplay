# CLAUDE.md

An always-on AirPlay receiver for Android TV, tuned for low-end panels. This repo is a fork of
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server). Phases and the
reasons behind them are in `PLAN.md`; read it before starting new work.

## Target device

The primary target is a OnePlus 50Y1S Pro:
- MStar `m7632` chip, Android TV 11 (API 30)
- 1.8 GB RAM; userland is **armeabi-v7a** (32-bit)
- UI plane fixed at 1080p60

Design for that. Prefer hardware decoders (`OMX.MS.AVC` / `OMX.MS.HEVC`), never advertise 4K, and keep
idle memory small.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk
git submodule update --init --depth 1          # UxPlay, OpenSSL, libplist
./gradlew assembleDebug -Pabis=armeabi-v7a
# output: app/build/outputs/apk/debug/app-debug.apk
```

- A clean build takes under a minute; OpenSSL compiles from source. `adb` is at `~/Library/Android/sdk/platform-tools/adb`; it is not on PATH.
- The NDK is pinned to `27.1.12297006` in `app/build.gradle.kts`, which is the version installed locally. Upstream uses 27.0.
- There are no unit tests. Verify changes by building, and on the TV only when the user asks.

## Layout

- `app/src/main/kotlin/com/castbay/`: Kotlin with Hilt, Compose UI and its own TV focus handling (`ui/Tv*.kt`).
  - `service/AirPlayService.kt` is the foreground service. It implements the native callbacks (`bridge/RaopCallbackHandler.kt`).
  - `discovery/Advertiser.kt` advertises `_airplay._tcp` and `_raop._tcp` through NsdManager; `service/AlwaysOn.kt` re-announces on network change and SCREEN_ON, wakes the TV and holds session locks.
  - `renderer/`: mirroring is MediaCodec, then a SurfaceTexture, then GL, then a SurfaceView. HLS goes through ExoPlayer.
- `app/src/main/cpp/`: the JNI bridge (`native_bridge.cpp`), audio (native AMediaCodec and Oboe), and `android_dnssd_shim.c`, which only stores TXT records.
  - `alac/`: software ALAC decoder (David Hammerton, MIT, hardened by besliky). Used when the hardware ALAC decoder is missing or "force software ALAC" is on.
- `app/src/main/cpp/third_party/`: git submodules. **Don't edit UxPlay in place.**
  - Add a numbered `.patch` to `app/src/main/cpp/patches/UxPlay/`.
  - The `applyUxplayPatches` Gradle task resets the touched files and reapplies every patch before CMake runs.
- `reference/`: git-ignored clones of `jqssun/android-airplay-server`, `besliky/airplay-tv` and `mazer666/PhairPlay`, kept for porting. Never import code from them without checking the license.
  - The first two are GPL-3.
  - PhairPlay is Apache-2.0, which can be used in this GPL-3 project.

## Conventions

- **License:** GPL-3.0, inherited from UxPlay. When porting code from `reference/airplay-tv`, keep its copyright notices.
- **Match the surrounding code:** Kotlin, Hilt injection, StateFlow viewmodels and Compose.
- **Upstream merges:** the remote `upstream` points at jqssun. Keep changes to upstream files small so merges stay easy, and put new behavior in new files where possible.
- **App ID:** `com.castbay` (namespace, Kotlin package and JNI symbols in `native_bridge.cpp`). Upstream merges need their `io.github.jqssun.airplay` paths remapped.

## Things that won't work (don't retry)

- **Miracast sink** in a user-installed app: `setWfdInfo` needs `CONFIGURE_WIFI_DISPLAY`. Android phones mirror through the TV's own HeyTap service instead.
- **Generic Google Cast receiver:** only certified devices get one. The TV's built-in Chromecast covers Cast.
- **DRM AirPlay content** (Apple TV+, Netflix from iOS) renders black. That is FairPlay DRM.

## Working with the TV

- Don't install to the TV or change its settings unless the user asks. They are often watching it.
- Screen-capture tooling (Argent's screen sharing) noticeably degrades playback on this TV. Read state with `adb shell dumpsys` instead, and stop capture tooling when you're done.
- The TV's IP comes from DHCP and changes. If `adb connect` fails, scan the LAN for port 5555.
