# NodeLoc Android

Native Android client for [nodeloc.com](https://www.nodeloc.com) — Discourse plus
the site's own plugins (community nodes, reward, lottery, red envelope, points,
follow, custom badges, apps directory, GIFs).

Built to the specification in `android-design/` of the iOS repository, with the
SwiftUI app as the behavioural baseline: where the docs are silent, this app does
what iOS does.

## Requirements

| | |
|---|---|
| Android Gradle Plugin | 9.3.1 (built-in Kotlin) |
| Kotlin | 2.4.10 (overrides AGP's bundled 2.2.10 — see below) |
| JDK | 17+ (Android Studio's JBR works) |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |

```bash
./gradlew assembleGithubDebug      # installable debug build
./gradlew testGithubDebugUnitTest  # unit tests (HTML parser)
./gradlew lintGithubDebug          # must stay at zero errors
./gradlew assembleGithubRelease    # R8 + resource shrinking, sideloadable APK
./gradlew bundlePlayRelease        # the .aab Google Play takes
```

**Two flavours, one app.** `github` is the build distributed from the releases
topic: it carries the self-updater and `REQUEST_INSTALL_PACKAGES`. `play` is the
store build, which carries neither — Google Play forbids an app it distributes
from installing its own updates, so the difference has to be the permission and
not a runtime flag. Same `applicationId`, same features otherwise; only the
"check for updates" row differs, and in the store build it opens the listing.

Note that Play re-signs the bundle with its own key under Play App Signing, so a
sideloaded install cannot be upgraded in place by the store version, or the
reverse — the signatures do not match. That is inherent to shipping both ways.

`local.properties` needs `sdk.dir`. On a machine with no JDK on `PATH` — the
default on macOS — point `JAVA_HOME` at the one Android Studio ships:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Release signing is optional: copy
`keystore.properties.sample` to `keystore.properties` (untracked) and the release
variant signs itself; without it the same build produces an unsigned APK.

Three files are deliberately absent, each with a `.sample` beside it. A debug
build needs none of them, which is what keeps a fresh clone usable:

| File | Without it |
| --- | --- |
| `keystore.properties` | the release APK is unsigned |
| `app/google-services.json` | the Google Services plugin is not applied; no push token, and the notification poll delivers instead |
| `signup.properties` | debug builds run anywhere; a *release* build refuses, because one shipped once without the site's `invite_code` and nobody could finish signing up |

**Why Kotlin is pinned above AGP's own.** AGP 9 ships built-in Kotlin 2.2.10, but
the AndroidX and Compose artifacts this app depends on carry 2.4 metadata, which a
2.2 compiler cannot read. The root `build.gradle.kts` lifts KGP on the buildscript
classpath instead of pinning every library back to an older release.

## Layout

One Gradle module. The package tree mirrors the module split the design docs
propose, which keeps boundaries visible without paying for cross-module
configuration on every build:

```
core/design/    Nocturne tokens, component library, brand loader, settings rows
core/network/   Discourse client, cookie jar, auth, MessageBus, error mapping
core/model/     DTOs (wire) + domain models (UI)
core/html/      cooked-HTML parser and its native renderer
core/store/     repositories: site, session, inbox, preferences, push
core/util/      formatting and the shared topic → row mapper
feature/…       feed, node, post, chat, search, profile, settings, auth, media,
                apps, compose, nav
```

`ServiceLocator` is the whole dependency graph — a dozen singletons, no cycles,
readable top to bottom. A DI framework would add a build step for no gain at this
size.

## Decisions that are load-bearing

These are not preferences; each one is a bug that already happened, on iOS or
here, and the code depends on them staying true.

**Cookies belong to OkHttp.** Discourse rotates the `_t` auth cookie and only
briefly honours the old value. A pinned `Cookie` header works for minutes and then
answers `not_logged_in` on everything. `PersistentCookieJar` owns them; no code
anywhere sets that header by hand.

**The HTML parser has a depth fuse.** Block and inline parsing are mutually
recursive, and a pathological post (hundreds of nested tags) would overflow the
worker stack. Past 40 levels the subtree degrades to plain text. Real content
measures 6–15 levels. `PostHtmlParserTest` pins this, along with the tag
vocabulary real posts use.

**The reader is one `LazyColumn`.** Body, plugin cards, action bar and the entire
reply tree. The server's nested tree is flattened once into rows that carry their
own rail geometry, so no composable nests per reply level. An eager column here is
what froze specific iOS devices.

**No database.** Preferences and watermarks live in DataStore; chat snapshots and
images live in `cacheDir`. Nothing here is relational.

**`flair_url` is not always a URL.** It is either an image path or a Font Awesome
icon name. Only `/` or `http` prefixes are treated as images — otherwise every
render fires a junk request for a string like `gem`.

**Plugin widgets are second requests.** Red envelopes and lotteries have no
markdown form; the web client posts them from an `afterCreate` hook, and so does
the composer here, once the topic/post id exists.

**Video posters link by filename.** `pretty_text.rb` matches
`original_filename LIKE '<video_sha1>.%'`. The poster upload is named after the
video's SHA1 and referenced by nothing else.

**Errors are never silent and never technical.** A user-initiated action that
fails raises a toast with friendly wording (`DiscourseError.messageRes`); a failed
first load shows a retry state. Sample content is never substituted for real
content.

## Localisation

Simplified Chinese is the source language (`values-zh-rCN`), English is the
default locale (`values`). Counted nouns use `<plurals>`. The in-app "text size"
preference syncs to the account only — the app's own type scale is fixed so the
reader's dense layouts stay predictable, matching iOS.

## Push

There is no push server: Discourse only relays to its own app. Delivery is a
15-minute WorkManager poll of `notifications.json` behind a persisted watermark,
with one system channel per category so Android's own settings can mute them
separately. The first poll after enabling **only sets the baseline** — otherwise
turning push on would dump the entire unread backlog into the shade.
