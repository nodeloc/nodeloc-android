# Working in this repository

NodeLoc Android — see `README.md` for the build and the architecture. This file
covers what an agent needs to know before changing code.

## Reference material

The specification lives in the iOS repository at `D:\nodeloc_ios\android-design\`
(overview, design system, architecture, API, per-screen specs, platform mapping,
pitfalls, roadmap). The SwiftUI sources in `D:\nodeloc_ios\nodeloc\` are the
behavioural baseline — when a detail is unclear, read the Swift, not a guess.

## Conventions

- **Comments explain why, never what.** The codebase is dense with decisions that
  look arbitrary until you know the backend quirk behind them; those are the
  comments worth writing. Do not narrate the code.
- **Design tokens only.** Colours come from `Nocturne`, spacing from `Space`,
  radii from `Radius`, type from `Type`. No literal colours or `sp` sizes in
  feature code, and no Material default palette. Colours and type hold to this;
  spacing does not, and the gap is not drift to be swept up. `Space` is a
  2.8-based scale (2.8 / 5.6 / 8.4 / 11.2 / 16.8 / 22.4) plus `page` 16 and
  `card` 14, while roughly 450 `.dp` literals in feature code sit on a 2-based
  one (4 / 6 / 8 / 10 / 12 / 16 / 24 / 32). The two do not line up: mapping a
  literal to its nearest token moves it — `8.dp` to `Space.s3` is +0.4 — so a
  blanket conversion silently re-lays-out every screen. Only exact,
  semantically matching values have been converted (`horizontal = 16.dp` where
  it really is the page margin, `14.dp` where it really is a card inset).
  Reconciling the two scales needs the iOS spec and a device pass; until then,
  add a token before reaching for a literal, and leave the existing literals
  alone.
- **Strings are resources.** Chinese in `values-zh-rCN`, English in `values`,
  identical keys and placeholder order. Non-Compose code resolves through
  `ToastCenter.show(resId, …)` or an injected `Context`.
- **Errors** map through `DiscourseError.messageRes`. A screen never shows a
  status code, and a user-initiated failure never passes silently.
- **Optimistic writes roll back.** Like, bookmark, join, follow and notification
  level all flip the UI first and restore the previous value on failure.
- **Cancellation is not failure.** `runCatching` catches `Throwable`, so a job
  cancelled by a newer request reports itself as an error — "couldn't load", or
  "login failed" for a user who merely navigated away. Wherever the result
  reaches UI state, a toast or a rendered list, use `runCatchingCancellable`, or
  open a broad `catch` with `rethrowIfCancellation()`. Both are in `core/util`.
  A catch that cleans up and rethrows needs neither, and must not have them:
  guarding before the cleanup is what leaves it undone.

## Before changing these, read the comment above them

`PersistentCookieJar` (token rotation) · `PostHtmlParser.MAX_NESTING_DEPTH`
(stack overflow) · the reader's single `LazyColumn` (layout freeze) ·
`flairImageUrl` (junk requests) · the red-envelope/lottery second request
(no markup exists) · `uploadVideoPoster`'s filename (the only link to the video) ·
the push watermark baseline (backlog flood) · the `github`/`play` flavour split
(a store build that self-updates is a policy violation, so nothing about the
updater or `REQUEST_INSTALL_PACKAGES` may move back into `src/main`).

## Verification

```bash
./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubDebug
```

Lint is expected to stay at **zero errors**. The parser tests are the regression
net for the riskiest component — extend them when you touch it.

## Backend reality checks

Discourse's own documentation is not authoritative for this deployment. Before
modelling a new endpoint, fetch the real response and check the field types:
Rails serialises several `user_option` enums as strings, `has_more` arrives as
both a bool and 0/1, `trust_levels` is a name→id map, and `apps/directory.json`
returns a bare array. One wrong type takes down the entire response, so DTOs are
optional-by-default with `ignoreUnknownKeys`.
