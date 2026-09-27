# Third-party notices

Lyricota is licensed under the GNU GPL v3.0 (see `LICENSE`). It includes or uses the following
third-party software. Full license texts are bundled in `app/src/main/assets/licenses/` and shown in
the app under *About*.

## Included in the app

| Component | Copyright | License |
|---|---|---|
| AndroidX, Jetpack Compose, Material 3, Material Icons | The Android Open Source Project / Google LLC | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines | JetBrains s.r.o. and contributors | Apache License 2.0 |
| Kuromoji 0.9.0 (`kuromoji-core`, `kuromoji-ipadic`) | 2010-2015 Atilika Inc. and contributors | Apache License 2.0 (`Kuromoji-NOTICE.txt`) |
| mecab-ipadic 2.7.0 (dictionary bundled in Kuromoji) | Nara Institute of Science and Technology (NAIST) | IPADIC license (`Kuromoji-NOTICE.txt`) |
| ICU4J 75.1 | 2016-2024 Unicode, Inc. | Unicode License v3 (`ICU-LICENSE.txt`) |

## Algorithms and data formats

- **QQ Music QRC decryption** (`QrcDecrypter.kt`): Kotlin port of the modified triple-DES used by QQ Music,
  as documented by [QQMusicDecoder](https://github.com/WXRIW/QQMusicDecoder) (MIT, © 2023 WXRIW —
  `QQMusicDecoder-MIT.txt`) and [LDDC](https://github.com/chenmozhijin/LDDC) (GPL-3.0, © chenmozhijin).
  Based on the public-domain DES implementation by Brad Conte.
- **Kugou KRC** (XOR + zlib) and **NetEase eapi** (AES-128-ECB) are publicly documented formats.
- **AMLL TTML DB**: lyrics written by its contributors are released under CC0-1.0; data imported from
  other providers keeps its original terms. Lyricota downloads it on demand and does not redistribute it.
- **MusicBrainz**: core data is CC0; genres/tags are CC BY-NC-SA 3.0. Queried on demand, not redistributed.
- Notification icon: Material Icons "music_note" (Apache License 2.0).

## Test-only dependencies (not shipped in the app)

JUnit 4 (EPL 1.0), Robolectric (MIT), Roborazzi (Apache 2.0), JSON-java (Public Domain),
AndroidX Test / Compose UI Test (Apache 2.0).
