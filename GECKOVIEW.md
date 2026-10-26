# WallPanel with GeckoView

This branch swaps WallPanel's system WebView for GeckoView (Mozilla's embeddable Firefox
engine), so the dashboard renders with a current browser engine on devices whose WebView
can't be updated, such as Fire OS 7 tablets that are stuck on Chromium 84.

It installs as `com.thanksmister.iot.wallpanel.gecko`, separately from the stock app.

## What changed

- `BrowserActivityNative` uses GeckoView. Playlists, reconnect and reload, the progress bar,
  touch-to-wake and the settings button all behave as before.
- MQTT/REST `eval` commands go through a bundled extension in `assets/wallpanel-bridge/`,
  because GeckoView has no `evaluateJavascript`.
- The build was moved to Gradle 8.14, AGP 8.13, Kotlin 2.3 and compileSdk 36, which current
  GeckoView needs. Kotlin synthetics were replaced with `findViewById` properties.
- Firebase (analytics and crash reporting) was removed.
- Builds are arm64 only, minSdk 26 (Android 8.0).

Known limitation: GeckoView has no "ignore SSL errors" switch, so a self-signed HTTPS
dashboard shows an error page. Plain `http://` or a valid certificate works.

## Build

You need JDK 17 and the Android SDK (command-line tools are enough; Android Studio isn't
required). With Homebrew:

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-36" "build-tools;36.0.0"
```

`local.properties` (not committed) must exist in the project root:

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
code=1234
hassUrl=
broker=
brokerPass=
brokerUsername=
```

Then build and install the **release** variant:

```sh
./gradlew assembleProdRelease
adb install -r WallPanelApp/build/outputs/apk/prod/release/WallPanelApp-prod-release.apk
```

Don't install `prodDebug` on a real panel. In debug builds, `BrowserActivityNative`
overwrites the saved dashboard URL, MQTT login and settings code on every launch.

## Signing

Release builds are signed with a private release key when `local.properties` has these
keys (the keystore itself must stay outside the repo and is never committed):

```
releaseStoreFile=/path/to/wallpanel-release.keystore
releaseStorePassword=...
releaseKeyAlias=wallpanel-gecko
releaseKeyPassword=...
```

Create one with:

```sh
keytool -genkeypair -keystore ~/.android/wallpanel-release.keystore -storetype PKCS12 \
  -alias wallpanel-gecko -keyalg RSA -keysize 2048 -validity 10000
```

Without those keys the build falls back to the Android debug key, so anyone can still build
from source. A device only updates in place when the new APK is signed with the same key as
the installed one, so **back up the release keystore and its password**. If they are lost,
users have to uninstall the app and set it up again. Switching from a debug-signed build to a
release-signed one needs the same uninstall and reinstall, once.

## Updating the engine

GeckoView gets security fixes with each Firefox release. To update, change
`versions.geckoview` in the root `build.gradle` to a newer version from
https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/ and rebuild.
Newer GeckoView releases may need newer Kotlin, AndroidX and compileSdk versions (see the
artifact's `.pom`). 153.x is the Firefox ESR line, which keeps getting security fixes without
breaking API changes.
