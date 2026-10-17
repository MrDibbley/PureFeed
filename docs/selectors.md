# m.facebook.com selector research

Findings from inspecting the **real Android WebView** running FaceBlockMobile, not
desktop Chrome emulation. Everything here is structural. No names or post content
are recorded.

| | |
|---|---|
| Date | 2026-10-17 |
| Device | Samsung SM-S938B, Android 17 |
| WebView | 155.0.8059.43 |
| User agent sent | `Mozilla/5.0 (Linux; Android 10; K) … Chrome/155.0.0.0 Mobile Safari/537.36` (Chrome's reduced UA) |
| Language | English (UK) |
| Method | WebView remote debugging over USB, read-only DOM queries (see [Re-running the research](#re-running-the-research)) |

## Why not Chrome desktop emulation

With a desktop user agent, m.facebook.com redirects to www.facebook.com. With
the app's mobile UA, Facebook serves its newer **server-driven "MContainer"
markup**. It looks nothing like the desktop site or the old `data-sigil` m-site,
so research has to happen against the app itself.

## Page model

```
html                                   ← the document scrolls (document.scrollingElement = <html>)
 └ … [data-mcomponent="MScreen"]       ← one per screen
     └ … [data-type="vscroller"]       ← THE FEED LIST (exactly one on the home feed)
          ├ top bar, tab bar, composer, stories tray   (no tracking id)
          ├ [data-tracking-duration-id] ← feed unit (post / ad / carousel)
          ├ div style="height:1px"      ← separator between units
          ├ [data-tracking-duration-id]
          └ …                           ← loading spinner at the end
```

- **There are no class names worth using.** Elements carry `class="m"` or short
  generated classes. Use `data-*` attributes instead.
- Every node is either `data-mcomponent="MContainer"` (`data-type="container"`),
  `TextArea` / `ServerTextArea` (`data-type="text"`), or `ImageArea` /
  `ServerImageArea` (`data-type="image"`).
- **Feed unit = a direct child of `[data-type="vscroller"]` that has
  `data-tracking-duration-id`.** It usually also has `data-dcm-id`. Its tracking
  id is **not** a stable post id: the same post can reappear with a different id
  after re-rendering, so don't use it as a key.
- Units are `display:flex; position:relative` with an inline
  `style="height:NNNpx"`. Nothing is absolutely positioned and there is no
  virtualisation: off-screen units keep their content. **`display:none` on a
  unit collapses it cleanly.** The 1px separator next to it is left behind,
  which is harmless.
- New units are appended to the vscroller as you scroll (29 children at start,
  85 after about 14 screens).
- `role` is sparse: `button`, `link`, `tab`, `tablist`, `img`. There is
  **no `role="article"`** and no `aria-label` on units. The useful aria-labels
  are on buttons ("See all", "See more information").

### Icon glyphs in text

Many TextAreas end with characters from Facebook's icon font. These are Unicode
Private Use Area code points, for example `U+F078B` and `U+F17E1` after "Ad".
**Strip `[\uE000-\uF8FF]` and `[\u{F0000}-\u{FFFFD}]` before comparing text.**
Glyphs are also used as **separators between label parts** (see political ads
below), so filter.js matches both the whole cleaned text and each piece between
glyphs.
Some strings also contain `U+200E` (left-to-right mark) around timestamps.

## Detection signals

"Header TextAreas" means the first few `[data-mcomponent$="TextArea"]` elements
of a unit, in document order. In practice: index 0 is the author or page name,
index 1 is the subtitle line, and index 2–3 hold the "•" and the inline action.

| Content | Signal | Where | Confidence |
|---|---|---|---|
| **Sponsored / ad** | A TextArea whose cleaned text is exactly `Ad` (raw: `Ad` + 2 icon glyphs). Its inner `<span>` is plain `Ad`, **not scrambled** as it is on desktop. | Header TextArea index 1, about 37px below the unit top | **High** (14/14 ads in sample) |
| **Political / issue ad** | Same subtitle TextArea, but three parts: `<span>Sponsored</span>`, an icon-glyph separator span, `<span>Paid for by …</span>`. Stripping glyphs glues these into "SponsoredPaid for by …", so header text is **also split at glyphs** and each part matched separately. | Header TextArea index 1, about 37px below the unit top | High (seen 2026-10-17) |
| ad, secondary | A call-to-action TextArea: `Shop now`, `Learn more` (others likely: `Sign up`, `Book now`, `Install now`, `Get offer`…) | Lower in the unit | Medium. Only use together with the label, since organic page posts can have CTAs |
| **Suggested post** (page/person you don't follow) | Header contains `•` followed by a TextArea `Follow`. That TextArea has `role="button"` and `data-action-id`. | Header TextArea index 2–3 | High |
| **Group recommendation** (post from a group you're not in) | Header contains `•` followed by a TextArea `Join` | Header TextArea index 2–3 | High |
| **Reels** | `<h2>` TextArea with text `Reels` | Unit header | High |
| **People You May Know** | TextArea `People you may know` as the unit title. Horizontal carousel with many `Add friend` buttons and a `See all` button. | Unit header | High |
| **Memories** | Not seen in this sample. Expected title text: `Memories`, `On this day`, `… years ago`. | Unit header | **Unverified**. Confirm with debug mode or a DOM dump. |
| "Suggested for you" text label | **Not present** in this feed. On mobile, suggestions show as `• Follow` / `• Join` instead. Keep the text pattern in rules.json in case it appears. | | Low |

### Outside the feed

| Element | Signal | Notes |
|---|---|---|
| **"Open app" bar** (bottom) | `[data-mcomponent="MContainer"]` with computed `position: fixed`, about 67px tall at the bottom, containing a TextArea `Open app` | Appears after you dismiss the "better on the app" pop-up. Hide the fixed container itself, not the whole screen. |
| "Get Facebook for Android / Browse faster" top bar | Not present on the logged-in feed. Seen only on the logged-out login page. | Low priority |
| `#…` "This browser is not supported" | A fixed div with 0×0 size, always present | Ignore |

### Stories tray (home feed)

A vscroller child **without** `data-tracking-duration-id`, above the first post,
containing a TextArea `Create story` and the story bubbles. It's matched by the
`stories` rule (`"kind": "section"`), which tests non-post children of the feed
list.

### Feeds (chronological feed)

- m.facebook.com has **no URL** for it. `/?sk=h_chr`, `/?filter=all&sk=h_chr`,
  `/home.php?sk=h_chr`, `/feeds/`, `/?filter=friends&sk=h_chr` and `/recent/` all
  redirect to the ranked feed (tested 2026-10-17).
- It opens from the menu: `[aria-label="Facebook menu"]` (role=button,
  `data-action-id`) → menu screen at `/bookmarks/` → a ServerTextArea `Feeds`
  whose clickable ancestor has `data-action-id`. `element.click()` on those
  ancestors works.
- On the Feeds screen the URL is still `/`. It's recognised by the nav bar title:
  `[data-testid="nav_bar_component"]` with text `Feeds`. Filter chips: `All`,
  `Favourites`, `Friends`, `Groups`, `Pages`. Posts are newest first, with the
  same unit markup, so all rules still apply.
- **Back quirk:** the scripted taps create history entries Chromium marks as
  skippable (no user activation), so `canGoBack()` is false and
  `goBackOrForward(-2)` is refused. Facebook's router also turns `history.go(-2)`
  into the menu. The app handles Back from an auto-opened Feeds by reloading `/`
  with Start on Feeds held off for that load.

### Video

Feed videos are `<video playsinline disablepictureinpicture controlslist src=…>`
inside the unit, with **no `autoplay` attribute**. Facebook starts them muted from
script with `play()`. The no-autoplay option wraps `HTMLMediaElement.prototype.play`
at document start and refuses calls without transient user activation. Verified:
with it off, the visible video played while scrolling; with it on, playback didn't
advance.

### Network: what the page loads

On the feed, every resource is first-party: `scontent-*.xx.fbcdn.net` (images),
`static.xx.fbcdn.net` and `z-m-static.xx.fbcdn.net` (JS/CSS), and `m.facebook.com`
(tested 2026-10-17). m.facebook.com's Content Security Policy refuses third-party
loads; for example, an image from `www.google.com` fires a `securitypolicyviolation` for
`img-src`. So domain blocking (`assets/blocklist.txt`) can't remove ads or
trackers from the feed, and the DOM filter has to.

## Recommended rule strategy (implemented in `rules.json`)

1. **Find units**: `[data-type="vscroller"] > [data-tracking-duration-id]`.
   Also handle nested vscrollers on other screens (groups, profiles) by
   matching any `[data-tracking-duration-id]` whose parent is a vscroller.
2. **Read header text**: the first `headerTextLimit` (5) TextAreas, cleaned of
   icon glyphs. Header-only matching avoids false positives from post bodies
   that happen to contain the word "Ad" or "Follow".
3. **Match** each rule's `headerText` list (exact, case-insensitive) or `title`
   list (h1/h2/h3 or the first TextArea) against those strings.
4. **Hide** by setting an attribute on the unit and letting one injected
   stylesheet do `display:none !important`. Facebook rewrites the inline style
   on re-render, so a stylesheet rule wins where an inline style would be
   overwritten.
5. **Re-check on mutation.** Facebook fills units in after insertion, so a
   unit that didn't match when first added must be re-tested when its subtree
   changes.

All visible strings are **English**. If the account language changes, add the
translated strings to `rules.json`.

## Re-running the research

Debug builds call `WebView.setWebContentsDebuggingEnabled(true)`.

**Option A, interactive:** plug in the phone, open the app, then open
`chrome://inspect/#devices` in desktop Chrome and click **inspect** under
FaceBlockMobile. You get full DevTools on the real WebView.

**Option B, scripted** (what was used for this document):

```bash
adb shell cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
python3 -I tools/selector-research/cdp.py 9222 tools/selector-research/units.js
```

The probes in `tools/selector-research/` only read the DOM, apart from
`scroll.js`, which scrolls. They print UI label text but reduce anything that
looks like a name or post content to its length. Don't commit their output.
