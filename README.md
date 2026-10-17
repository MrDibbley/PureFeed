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

- `app/src/main/assets/filter.js` - the feed filter engine (injected into every page)
- `rules/rules.json` - detection strings and selectors; bundled into the APK and,
  also fetched from a URL so filters can be fixed without a rebuild
- `app/src/main/assets/blocklist.txt` - default tracker domains (editable in the app)
- `app/src/main/java/app/faceblockmobile/web/` - WebView setup, clients, link policy
- `app/src/main/java/app/faceblockmobile/filter/` - injection, JS bridge, rules loading
- `app/src/main/java/app/faceblockmobile/settings/` - user settings (DataStore)
- `app/src/main/java/app/faceblockmobile/ui/` - Compose screens
- `docs/selectors.md` - how Facebook's mobile markup is structured and what each rule keys on
- `tools/selector-research/` - read-only DOM probes run against the app's WebView over USB

## Fixing a broken filter

1. Turn on debug mode (filter button, bottom right): matched posts get a coloured
   outline and a label instead of disappearing.
2. If something isn't caught, plug the phone in and open `chrome://inspect` in
   desktop Chrome (debug builds only), or use **Dump DOM** and share the file.
3. Update the strings or selectors in `rules/rules.json`. See `docs/selectors.md`.
4. **Bump `"updated"`** in the same file (e.g. `2026-10-17` → `2026-10-17.2`). The app
   ignores a downloaded file whose `updated` is older than its built-in copy.
5. Push it. If the app's **Rule updates** URL points at the raw file, e.g.
   `https://raw.githubusercontent.com/<you>/<repo>/main/rules/rules.json`,
   the phone picks it up on next launch (or tap **Check now**). No rebuild needed.
   The next APK build bundles the same file as its offline fallback.
