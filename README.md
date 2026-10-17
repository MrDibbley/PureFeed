# FaceBlockMobile

Personal Android client for m.facebook.com with ad blocking and feed filtering.
Sideload only.

## Build

Needs Android Studio (for its JDK and the Android SDK). In a terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a phone

Enable USB debugging on the phone, plug it in, then:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

- `app/src/main/java/app/faceblockmobile/web/` - WebView setup, clients, link policy
- `app/src/main/java/app/faceblockmobile/ui/` - Compose screens
- `docs/` - selector research notes
