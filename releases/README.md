# Releases

Signed CastBay builds. The same files, plus every future version, are on the
[GitHub Releases page](https://github.com/bestinepayyappilly/tv-airplay/releases).

| File | For | Size |
|---|---|---|
| `CastBay-v1.0.0-armeabi-v7a.apk` | Most Android TVs and TV boxes (32-bit ARM), including low-end panels | 8.3 MB |
| `CastBay-v1.0.0-universal.apk` | Any device: 64-bit ARM, 32-bit ARM and x86_64 (emulators) | 27 MB |

If you're unsure, use the universal APK: it works everywhere, only larger.

Both are signed with the CastBay release key
(certificate SHA-256 `c9c0a07e54da17865f8e30f27b49c1c20f75d7a9e7184f5499ed46ec7924dad0`), so later
versions install over them. Checksums are in [`SHA256SUMS`](SHA256SUMS):

```sh
shasum -a 256 -c SHA256SUMS
```
