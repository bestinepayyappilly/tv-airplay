# tv-airplay plan

An always-on AirPlay receiver for Android TV, built for low-end panels like the OnePlus 50Y1S Pro
(MStar `m7632`, Android TV 11, 1.8 GB RAM, armeabi-v7a userland, 1080p60-only UI plane).

This repo is a fork of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
(remote `upstream`), so UxPlay fixes can be merged later. License: GPL-3.0 (inherited from UxPlay).

## Sources

| Project | License | What we take |
|---|---|---|
| jqssun/android-airplay-server | GPL-3.0 | Base app: UxPlay 1.74 core over JNI, mirroring (H.264/H.265), HLS video, native audio, PiP, Compose UI, foreground service, boot receiver |
| besliky/airplay-tv | GPL-3.0 | Always-on behaviour and low-RAM tuning (see Phase 2) and its small Apple ALAC decoder |
| mazer666/PhairPlay | Apache-2.0 | Multi-protocol service pattern only. Its Miracast is a stub that needs system APIs, its Cast needs a registered Cast Connect App ID, and it has no DLNA |

Clones of all three are in `reference/`, which is git-ignored.

## Android phones (no extra app on the phone)

- **Google Cast**: the TV's built-in Chromecast (`com.google.android.apps.mediashell`). Third-party apps can't receive Cast.
- **Screen mirroring (Miracast)**: re-enable the TV's own HeyTap service
  (`cmd package install-existing com.heytap.optvmiracastservice com.heytap.olink com.heytap.ars.sink`).
  A user-installed app can't be a Miracast sink because `setWfdInfo` needs `CONFIGURE_WIFI_DISPLAY`.
- **DLNA renderer**: optional Phase 4, which we would write ourselves.

## Phases

1. **Base builds** for armeabi-v7a, unchanged from upstream.
2. **Always-on**, ported from besliky:
   - Bring `MirrorActivity` to the front from the service using the `SYSTEM_ALERT_WINDOW` background-start exemption, with a full-screen-intent fallback. On this TV the overlay permission can be granted with `appops set <pkg> SYSTEM_ALERT_WINDOW allow`.
   - Wake with `ACQUIRE_CAUSES_WAKEUP` so the TV powers on over CEC.
   - Re-announce mDNS on network change (debounced) and on `SCREEN_ON`.
   - Re-register NSD by unregistering, waiting, then registering, to avoid "Name (2)".
   - Handle `MY_PACKAGE_REPLACED`.
   - Take wake and Wi-Fi locks only during a session.
3. **Low-RAM tuning**:
   - Replace the FFmpeg ALAC fallback (about 143 MB of source) with the Apple ALAC decoder.
   - Prefer hardware and low-latency decoders, and cap the advertised resolution to the panel (no 4K on a 1080p UI plane).
   - Keep the decoder alive with an `AImageReader` sink when there is no surface.
   - Cap the frame cache, add `onTrimMemory` handling, and keep the idle footprint small.
4. **Optional DLNA MediaRenderer** (SSDP, AVTransport, RenderingControl) for Android phone video push.

## Known limits

- DRM content (Apple TV+, Netflix from iPhone) shows black. That is FairPlay DRM and can't be fixed.
- One sender at a time.
