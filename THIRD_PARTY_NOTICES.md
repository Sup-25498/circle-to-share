# Third-party notices

Circle to Share is licensed under the GNU General Public License v3.0 or
later (see [LICENSE](LICENSE)). It is built with the open-source components
below, which keep their own licenses.

## Apache License 2.0

Full text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)

| Component | Copyright |
|---|---|
| AndroidX (Jetpack) libraries | The Android Open Source Project |
| Material Components for Android | The Android Open Source Project |
| Material Icons and Material Symbols | Google LLC |
| ZXing (`com.google.zxing:core`) | ZXing authors |
| LiteRT (TensorFlow Lite, `com.google.ai.edge.litert:litert`) | The TensorFlow Authors |
| PaddleOCR PP-OCRv5 models, converted (`app/src/main/assets/ocr/`) | PaddlePaddle Authors |
| RapidOCR (the ONNX exports of those models) | RapidOCR Authors |
| ruy (in LiteRT) | Google LLC |
| gemmlowp (in LiteRT) | The gemmlowp Authors |
| FlatBuffers (in LiteRT) | Google Inc. |
| Kotlin standard library | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | JetBrains s.r.o. and contributors |
| Guava ListenableFuture | The Guava Authors |
| Error Prone annotations | The Error Prone Authors |
| JSpecify | The JSpecify Authors |
| JetBrains Java Annotations | JetBrains s.r.o. |

The Material Icons and Symbols are in `app/src/main/res/drawable` and
`app/src/play/res/drawable`; each file's header comment names its symbol and
says where it was changed (for example redrawn on a different grid).

The OCR models in `app/src/main/assets/ocr/` are PaddleOCR's PP-OCRv5 mobile
text detection and Latin recognition models, in RapidOCR's ONNX exports,
converted to TensorFlow Lite with onnx2tf (float16 weights, fixed input
sizes); `latin_keys.txt` is the recognizer's character list. The folder's
README says how they were converted.

## Inside LiteRT

LiteRT's native library (`libtensorflowlite_jni.so`) compiles in code under
other licenses. Its LICENSE file names Caffe; the others are TensorFlow
Lite's build dependencies, found in the library. Their notices ship in the
app (`app/src/main/res/raw/notice_*.txt`) and are shown on its licenses page.

| Component | Copyright | License |
|---|---|---|
| XNNPACK | Facebook, Inc. and its affiliates; Google LLC | BSD 3-Clause |
| pthreadpool | Google LLC; Facebook Inc.; Georgia Institute of Technology | BSD 2-Clause |
| cpuinfo | Google LLC; Facebook Inc.; Georgia Institute of Technology; Marat Dukhan | BSD 2-Clause |
| Caffe (parts of TensorFlow) | The Regents of the University of California and contributors | BSD 2-Clause |
| FP16 | Facebook Inc.; Georgia Institute of Technology; Google LLC | MIT |
| FXdiv | Facebook Inc.; Marat Dukhan | MIT |
| Eigen | The Eigen authors | [MPL 2.0](LICENSES/MPL-2.0.txt); source: <https://gitlab.com/libeigen/eigen> |
| fft2d | Takuya Ooura | its own (see `app/src/main/res/raw/notice_fft2d.txt`) |

## Google Play build only

The Google Play build (`-Pplay`) adds Google Play Billing for in-app tips.
The default F-Droid / GitHub (foss) build contains none of these. Play
Billing and Play services are not open source: they are used under the
Android Software Development Kit License.

| Component | Copyright | License |
|---|---|---|
| Google Play Billing Library | Google LLC | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| Google Play services (base, basement, tasks, location, places-placereport) | Google LLC | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| Google Data Transport | Google LLC | Apache License 2.0 |
| Firebase Encoders | Google LLC | Apache License 2.0 |
| javax.inject | The JSR-330 Expert Group | Apache License 2.0 |

Google's libraries also contain open-source code compiled into them
(Protocol Buffers, Dagger, JSR 305, zlib, ICU and others). Each AAR lists
it, with its license, in `third_party_licenses.json`/`.txt`. The Play build
gathers these into the app at build time (`GooglePlayNoticesTask` in
`app/build.gradle.kts`), and the licenses page shows them under "Inside
Google's libraries".

## Test fixtures (not in the app)

`app/src/test/resources/copytext/` holds screenshots, accessibility-tree
dumps and expected Markdown of screens, for the Copy text tests. They are
NOT part of the app. Pages shown, and their licenses:

| Fixtures | Source | License |
|---|---|---|
| `ff_wiki_*` | Wikipedia, "Optical character recognition" | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), Wikipedia contributors |
| `chrome_wiki_table` | Wikipedia, "List of tallest buildings" | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), Wikipedia contributors |
| `chrome_mdn` | MDN Web Docs, "Array.prototype.map()" | [CC BY-SA 2.5](https://creativecommons.org/licenses/by-sa/2.5/), Mozilla Contributors |
| `ff_python_docs` | Python tutorial, "Data Structures" | [PSF License Version 2](https://docs.python.org/3/license.html), Python Software Foundation |
| `chrome_letter` | Einstein–Szilárd letter (1939), [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Einstein-Roosevelt-letter.png) | public domain (per Wikimedia Commons) |
| `ff_news` | A news list page written for these tests (made-up headlines and names) | GPL-3.0-or-later, as this repository |
| `chrome_error_page`, `ff_error_page` | Chrome's and Firefox's offline pages | Chromium (BSD 3-Clause), Firefox (MPL 2.0) |
| `settings_*` | Android Settings (Pixel) | Google LLC |
| `ctshare_main` | Circle to Share itself | GPL-3.0-or-later |

## Keeping this current

When dependencies change, compare against

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew -Pplay :app:dependencies --configuration playReleaseRuntimeClasspath
```

(the notices inside Google's libraries follow by themselves) and update this
file together with `components` in
`app/src/main/java/eu/woshicado/circletoshare/LicensesActivity.kt` and
`storeComponents` in `app/src/{foss,play}/java/.../StoreComponents.kt`.
