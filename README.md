# PureFeed

An Android app for m.facebook.com with ad blocking and FB Purity-style feed
filtering. It wraps the mobile website in a WebView and hides what you don't want
to see. Sideload only; not on the Play Store.

> **Not affiliated with, endorsed by or connected to Meta Platforms or Facebook.**
> "Facebook" is a trademark of Meta. Third-party clients are against Facebook's
> terms of service. Use at your own risk. PureFeed only changes what your phone
> displays: it doesn't change your Facebook settings or post anything, but it does
> open the Feeds screen for you by tapping the menu (see "Start on Feeds").

## What it does

- **Hides:** sponsored posts (including "Paid for by" political ads), suggested
  posts from pages you don't follow, group suggestions, Reels, People You May Know,
  the Stories tray and the "Open app" nag bar. Memories hiding is also available but
  not yet verified.
- **Your own filters:** keywords or phrases (whole-word), muted pages and people,
  and custom CSS.
- **Start on Feeds:** opens Facebook's chronological "Feeds" view (newest first)
  instead of the ranked feed, optionally filtered to Friends, Favourites, Groups or
  Pages. Back takes you to the normal feed.
- **No video autoplay:** videos only play when you tap them.
- **Debug mode:** outlines matched posts in a colour per rule instead of hiding them.
- **Remote rules:** detection rules are downloaded from this repo
  ([`rules/rules.json`](rules/rules.json)) on launch, so broken filters can be fixed
  without a new APK.
- Persistent login, photo upload, pull to refresh, full-screen video, dark mode
  following the system, and external links opened in your browser.

**Limitations:** the detection strings are **English**. With Facebook set to
another language most filters won't match until translations are added to
`rules.json`. Facebook changes its markup often; expect occasional breakage.

## Install

Needs **Android 8.0 or newer**.

1. Download `PureFeed-<version>.apk` from the
   [latest release](https://github.com/MrDibbley/PureFeed/releases/latest).
2. **Verify it** (below). Recommended, since you're installing outside the Play Store.
3. Open the APK on your phone and allow installing from that source when asked.
4. Log in to Facebook in the app as usual.

### Verifying a download

Each release lists two SHA-256 values and includes a `SHA256SUMS.txt` file.

**1. The APK file hash** shows the file wasn't corrupted or swapped:

```bash
shasum -a 256 PureFeed-1.0.0.apk
```

(`sha256sum` on Linux; `Get-FileHash PureFeed-1.0.0.apk` in Windows PowerShell.)
The output must match the value in the release notes and `SHA256SUMS.txt`.

**2. The signing certificate fingerprint** shows who built it. It's the same for
every release, so it also proves an update came from the same place:

```bash
apksigner verify --print-certs PureFeed-1.0.0.apk
```

(`apksigner` is in the Android SDK's `build-tools` folder.) The
`Signer #1 certificate SHA-256 digest` must be:

```
6a:d2:81:ff:b9:d0:7d:a2:32:0a:ac:6a:fa:89:d6:15:be:29:bc:ce:52:16:66:49:0e:c6:71:81:3f:fd:3b:88
```

(`apksigner` prints it without colons: `6ad281ffb9d07da2320aac6afa89d615be29bcce521666490ec671813ffd3b88`.)

Android itself refuses to install an update signed with a different key.

## Building it yourself

Needs Android Studio (for its JDK and the Android SDK). In a terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Debug builds install
as **PureFeed debug** (`app.purefeed.debug`) alongside the release app. They also
allow `chrome://inspect` on the WebView, for selector research.

Install over USB with debugging enabled:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release builds (maintainer)

Release builds are signed with a key kept **outside** the repo, at
`~/keys/purefeed-release.jks`. Its password is stored in the macOS Keychain, not in a
file. One-time setup:

```bash
mkdir -p ~/keys && keytool -genkeypair -v -keystore ~/keys/purefeed-release.jks -storetype PKCS12 -alias purefeed -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PureFeed"
```

```bash
security add-generic-password -a "$USER" -s purefeed-release-key -w
```

Both commands prompt for the password; use the same one for both. **Back up the
`.jks` file and the password.** Without them no update can ever be installed over
an existing PureFeed.

Then: bump `versionCode` and `versionName` in `app/build.gradle.kts`, and run
`./gradlew assembleRelease`. The signed APK is
`app/build/outputs/apk/release/app-release.apk`. Without the key file you get an
unsigned APK instead.

## Layout

- `app/src/main/assets/filter.js` - the feed filter engine (injected into every page)
- `rules/rules.json` - detection strings and selectors; bundled into the APK and
  also fetched from GitHub so filters can be fixed without a rebuild
- `app/src/main/assets/blocklist.txt` - default tracker domains (editable in the app)
- `app/src/main/java/app/purefeed/web/` - WebView setup, clients, link policy
- `app/src/main/java/app/purefeed/filter/` - injection, JS bridge, rules loading
- `app/src/main/java/app/purefeed/settings/` - user settings (DataStore)
- `app/src/main/java/app/purefeed/ui/` - Compose screens
- `docs/selectors.md` - how Facebook's mobile markup is structured and what each rule keys on
- `tools/selector-research/` - read-only DOM probes run against the app's WebView over USB

## Fixing a broken filter

1. Turn on debug mode (filter button, bottom right): matched posts get a coloured
   outline and a label instead of disappearing.
2. If something isn't caught, plug the phone in and open `chrome://inspect` in
   desktop Chrome (debug builds only), or use **Dump DOM** and share the file.
   Dumps contain the posts on screen, so share them privately.
3. Update the strings or selectors in `rules/rules.json`. See `docs/selectors.md`.
4. **Bump `"updated"`** in the same file (e.g. `2026-10-17` → `2026-10-17.2`). The app
   ignores a downloaded file whose `updated` is older than its built-in copy.
5. Push it. The app's **Rule updates** URL defaults to
   `https://raw.githubusercontent.com/MrDibbley/PureFeed/main/rules/rules.json`,
   so phones pick it up on next launch (or tap **Check now**). No rebuild needed.
   GitHub caches raw files for up to 5 minutes, so allow that long after pushing.
   The next APK build bundles the same file as its offline fallback.

FB Purity (fbpurity.com) inspired the feature set. None of its code is used.
