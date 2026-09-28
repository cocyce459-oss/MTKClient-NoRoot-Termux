# Building

Two supported paths. **CI is the easiest** — it needs nothing installed locally
and produces a signed, installable APK.

## Option A: GitHub Actions (recommended)

Push to any branch, or run the workflow manually:

```bash
git push origin HEAD
# or
gh workflow run "Build APK"
```

`.github/workflows/android.yml` then:

1. installs JDK 17 and Gradle 8.9,
2. ensures `platforms;android-34` and `build-tools;34.0.0` are present,
3. runs the unit tests — including the BROM golden-vector replay,
4. vendors the MediaTek assets (cached between runs),
5. assembles a release APK,
6. uploads it as the **`MTKClient-Native-release`** artifact and writes its size
   into the job summary.

Download it with:

```bash
gh run download --name MTKClient-Native-release
```

Pushing a `v*` tag additionally publishes a GitHub Release with the APK attached.

### Signing

Without secrets, the release APK is signed with an ephemeral CI debug key. That
is **installable and fully functional** for sideloading; it simply cannot be
uploaded to Play, and reinstalling over it later requires the same key.

For a stable key, add these repository secrets:

| Secret | Contents |
|---|---|
| `MTK_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `MTK_KEYSTORE_PASSWORD` | keystore password |
| `MTK_KEY_ALIAS` | key alias |
| `MTK_KEY_PASSWORD` | key password |

Create one with:

```bash
keytool -genkeypair -v -keystore release.keystore -alias mtknative \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # paste into MTK_KEYSTORE_BASE64
```

`app/build.gradle.kts` picks the `ci` signing config up automatically when
`MTK_KEYSTORE_FILE` is set in the environment, and falls back to debug otherwise.

## Option B: Local build

Requirements: **JDK 17** and the **Android SDK** with platform 34 and build-tools
34.0.0. Android Studio handles both; from the CLI set `ANDROID_HOME` and create
`local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

### The Gradle wrapper

`gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.9, but the wrapper *jar*
is not committed (it is a binary blob). Generate it once with any Gradle 8.x:

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleRelease
```

Or skip the wrapper entirely and invoke your system Gradle:

```bash
gradle :app:assembleRelease
```

Then either install directly to an attached phone, or build an APK to copy:

```bash
gradle :app:installDebug          # needs adb + USB debugging
gradle :app:assembleRelease       # -> app/build/outputs/apk/release/app-release.apk
```

## Asset vendoring

The MediaTek binaries are **not in git**. `syncMtkAssets` stages them into
`app/build/generated/mtkAssets/` before the asset merge, and `preBuild` depends on
it. By default it downloads the upstream tarball from GitHub.

| Flag | Effect |
|---|---|
| *(none)* | download upstream `main`; bundle `payloads/` + `Loader/*.bin` |
| `-PmtkclientDir=/path/to/mtkclient` | vendor from a local checkout, no network |
| `-PmtkclientRef=<ref>` | fetch a different upstream branch |
| `-PslimAssets=true` | omit `Loader/` — BROM-only build |

```bash
gradle :app:assembleRelease -PmtkclientDir=~/src/mtkclient
gradle :app:assembleRelease -PslimAssets=true
```

`Loader/Preloader/` (838 device-specific dumps) is always excluded — nothing on
the BROM path reads it.

### Resulting sizes

Measured with deflate level 9, matching how AAPT packages assets:

| Build | Assets raw | Assets deflated | Approx. APK |
|---|---|---|---|
| Full (`payloads/` + `Loader/`) | 49.37 MB | 27.68 MB | **~30 MB** |
| Slim (`-PslimAssets=true`) | 0.13 MB | 0.09 MB | **~2–3 MB** |

Both are far inside the 100 MB target, against the ~2–4 GB a Termux install
consumes.

## Tests

```bash
gradle :app:testDebugUnitTest
```

| Suite | What it protects |
|---|---|
| `BromProtocolVectorTest` | 17 operations replayed against bytes recorded from the reference implementation — transmitted bytes, per-transfer frame boundaries, and every requested read length |
| `WireFormatTest` | big/little-endian conversions, padding, hex rendering |
| `GptParserTest` | GPT decoding at 512 and 4096 byte sectors, A/B flags, terminator handling |
| `BrlytScannerTest` | preloader container decoding and rejection of malformed input |
| `ConsoleParserTest` | tokenising, quoting, `getint()`-compatible number parsing |

Run only the protocol vectors:

```bash
gradle :app:testDebugUnitTest --tests '*BromProtocolVectorTest*'
```

### Re-recording the vectors

If upstream changes the protocol, regenerate and review the diff before accepting
it:

```bash
pip install pyusb pycryptodome pycryptodomex colorama
git clone https://github.com/bkerler/mtkclient /tmp/mtkclient
python3 tools/golden_vectors.py /tmp/mtkclient
git diff app/src/test/resources/golden_vectors.json
```

The `protocol-drift` CI job does this comparison automatically on every push.

## Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it. The app needs **no permissions**:
dumps go to app-specific external storage, and USB host access requires no
manifest permission. If the device lacks OTG/host support the UI says so rather
than failing obscurely.
