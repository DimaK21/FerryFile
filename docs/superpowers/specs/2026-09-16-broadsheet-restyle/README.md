# Handoff: FerryFile — Broadsheet restyle

Repo: `ru.kryu.ferryfile` (Kotlin + Jetpack Compose + Ktor/Netty, SAF folder access).
Target: reskin the existing two Compose screens and the served web UI. **No feature changes.**

## Overview

FerryFile ships today with the default Material 3 look (fallback purple `#6650a4` from
`ui/theme/Color.kt`, dynamic color on Android 12+) and a dark web UI (`assets/webui/style.css`).
This handoff replaces that look with **Broadsheet** — newsprint: Source Serif 4 on paper,
hierarchy from type scale and whitespace, rules only as front-page furniture (a thick 3dp rule
over a small-caps dateline row over a 1dp rule), no Material cards, no elevation.

Everything else stays: start/stop server, PIN gate, SAF folder picking, HTTPS toggle, dark theme
toggle, port field, foreground service, SSE progress, EN/RU i18n.

## About the design files

`design/FerryFile.dc.html` is a **design reference written in HTML** — a prototype of the intended
look, not production code. Open it in a browser (it needs the sibling `design/_ds/…` and
`design/*.jsx` files next to it). Recreate it in the app's own environment:

- phone screens → **Jetpack Compose** in `app/src/main/java/ru/kryu/ferryfile/ui/`
- browser pages → the existing static assets in `app/src/main/assets/webui/`
  (plain HTML/CSS/vanilla JS, no build step — keep it that way; `style.css` is a full rewrite,
  the `.html` files keep every `id` and `data-i18n` key so `app.js` needs no changes)

Fidelity: **high**. Exact values below.

## Which direction to implement

The reference shows the recreation plus three home directions. Implement **1b "Front page"** as the
spec; 1c and 1d are recorded alternates.

| id | What | Status |
|---|---|---|
| `1a` | Today's app recreated (Material 3) | Before-picture only — do not build |
| `1b` | **Home — Front page**: head pair + dateline, state as headline, address/PIN as display serif | **Build this** |
| `1c` | Home — Press proof: PIN as misregistered CMY plates, step wedge, registration target | Alternate |
| `1d` | Home — Classified column: label/value ledger rows, no display type | Alternate |
| `1e` | Home state flow: stopped → starting → running → no-Wi-Fi (RU copy) | **Build this** (states of 1b) |
| `1f` | Settings | **Build this** |
| `1g` | Browser — PIN gate + file list as ledger table | **Build this** |
| `1h` | Browser — file list as index column with leader dots | Alternate |
| `1i` | Dark ink cut (phone + browser) | **Build this** (drives the existing Dark Theme switch) |

## Design tokens

Canonical source: `design/_ds/broadsheet-…/styles.css` (`:root`). Use these, do not invent values.

### Light (default)

| Token | Value | Used for |
|---|---|---|
| `--color-bg` | `#eae9e9` | page ground (paper) |
| `--color-text` | `#201e1d` | all body and display ink, rules |
| `--color-accent` | `#201e1d` | interactive ink — **see note** |
| `--color-accent-2` | `#d6006c` | second spot colour (warnings), used sparingly |
| `--color-accent-700` | `#006786` | accent at paragraph size (contrast) |
| `--color-accent-2-700` | `#aa0b56` | warning text / destructive labels |
| `--color-neutral-300` | `#d7d3d3` | progress track |
| `--color-neutral-600` | `#7d7979` | disabled / placeholder values |
| `--color-neutral-700` | `#605d5d` | small-caps labels, captions |
| `--color-neutral-800` | `#444141` | secondary body copy |
| `--color-divider` | `#201e1d` @ 16% | hairline rules |

> **Note on the accent.** The system's shipped accent is process cyan `#0088b0` and its paper is
> `#f3f2f2`. In review the accent was set to near-black `#201e1d` and the paper to `#eae9e9` — an
> all-black-ink cut where the only colour left is the magenta of a warning. Build the black-ink
> values above; keep cyan as a one-line swap (`--color-accent`) in case it comes back.

### Dark ink cut (Dark Theme switch on)

Broadsheet defines no dark surfaces — these are proposed, derived from its ramps per its
"dark ground" note:

| Token | Value |
|---|---|
| ground | `#201e1d` |
| ink / text | `#f3f2f2` |
| surface | `#2d2b2b` |
| interactive | `#62c5ee` (`--color-accent-400`) |
| interactive text | `#99e0ff` |
| divider | `#f3f2f2` @ 22% |
| muted label | `#bab6b6` |
| secondary body | `#d7d3d3` |
| warning | `#ff90b1` |

A filled button on dark ink takes `background #62c5ee` with `#201e1d` label.

### Type — Source Serif 4 only (weights 400 / 600, plus 400 italic)

The serif is the chrome: **no sans-serif anywhere, including monospace**. Numeric strings
(address, PIN, fingerprint, sizes, dates) use the serif with `font-feature-settings: 'tnum' 1`
(Compose: `FontFeatureSetting("tnum")`).

| Role | Size / weight / tracking |
|---|---|
| Masthead "FerryFile" (home) | 28sp, 600, `-0.025em` |
| Screen title (Settings) | 24sp, 600 |
| Headline (server state) | 44sp EN / 40sp RU, 600, `-0.03em`, line-height 1.12 |
| Standfirst | 15.5sp, 400, colour `--color-neutral-800`, max measure 24em |
| Small-caps label | 10sp, 400, `letter-spacing .12em`, uppercase, colour `--color-neutral-700` |
| Dateline row | same as small-caps label |
| Address | 27sp, 600, `-0.02em`, colour `--color-accent-700`, wraps on any char |
| PIN | 58sp, 600, `letter-spacing .10em`, tnum, 3dp accent rule under it |
| Fingerprint | 12.5sp, 400, line-height 1.6, breaks on any char |
| Section heading (h4) | 19sp, 600 |
| List row value | 16sp, 400 |
| Button label | 16sp, 600 (48–52dp tall buttons) |
| Footer (version) | 11sp, uppercase, `.1em`, `--color-neutral-600` |

### Spacing / shape

Broadsheet scale (density 1.25×): `5 / 10 / 15 / 20 / 30 / 40` dp. Screen gutter **20dp**.
Radius: `--radius-sm 1` / `--radius-md 2` / `--radius-lg 4` — buttons and inputs are **2dp**,
effectively square. No shadows on either screen (the system's `--shadow-*` are for dialogs only).

Rules: head pair = 3dp solid `--color-text`, then the dateline row (5dp vertical padding),
then 1dp solid `--color-text`. Row separators inside a section = 1dp `--color-divider`;
a section's own top rule = 1dp or 2dp `--color-text`.

## Screens

### 1. Home — `ui/home/HomeScreen.kt`

Replace `Scaffold` + `TopAppBar` + `Card`s with a plain `Column` (20dp horizontal padding,
`verticalScroll`) and a bottom-pinned action. Structure top to bottom:

1. **Masthead row** — "FerryFile" 28sp/600 flush left; settings icon button on the right
   (24dp glyph, 36dp box, `--color-text`, `contentDescription` = `home_settings_content_description`).
   The current `ic_settings.xml` vector is kept as-is.
2. **Head pair** — 3dp rule; dateline row with two small-caps items:
   left `"Local Wi-Fi · Port 8080"` / `"Локальный Wi-Fi · порт 8080"`, right = the state word
   (`home_status_running` / `_starting` / `_stopped`); 1dp rule.
   The state word's colour is the whole state signal — **the green/grey 12dp dot is deleted**
   (`Color(0xFF4CAF50)` / `0xFF9E9E9E` no longer appear anywhere):
   running/starting → `--color-accent-700`; stopped → `--color-neutral-700`;
   no Wi-Fi → left item becomes `"No network"` in `--color-accent-2-700`.
3. **Headline** — 30dp above; `home_status_*` phrased as a sentence:
   "Server running" / "Server stopped" / "Starting…" (starting is set in `--color-neutral-700`).
4. **Standfirst** — one sentence, 15.5sp, `--color-neutral-800`:
   running → "Type the address into any browser on the same network, then enter the six-digit PIN."
   / "Введите адрес в браузере на том же Wi-Fi, затем шестизначный PIN."
   stopped → "Start the server to hand the address and PIN to your computer."
   / "Запустите сервер, чтобы получить адрес и PIN для компьютера."
   These are **new string resources** (the existing strings are all reused elsewhere).
5. **Connection block** (only when `isRunning && hasWifi` — replaces `ConnectionCard`, no card,
   no centring, everything flush left): label `home_open_in_browser` → address 27sp;
   20dp gap; label `home_pin` → PIN 58sp with a 3dp `--color-accent` rule beneath;
   20dp gap; when the fingerprint is non-blank, label `home_certificate_fingerprint` →
   fingerprint 12.5sp → `home_certificate_hint` 12.5sp `--color-neutral-700`.
6. **Notice block** (only when `!hasSharedFolders`) — top rule 1dp `--color-text`, bottom rule
   1dp `--color-divider`, 14dp vertical padding. Small-caps kicker "Notice" in
   `--color-accent-2-700`; `home_no_folders_shared_title` as 19sp/600;
   `home_no_folders_shared_message` 14sp; then a text button `home_add_folder` + " →",
   flush left, no padding, `--color-accent` label → navigates to Settings.
7. **No-Wi-Fi state** (`isRunning && !hasWifi`) — headline becomes
   `home_no_wifi_connection`'s first clause in `--color-accent-2-700`, standfirst is the rest of
   that string; then a block ruled 2dp `--color-accent-2-700` on top showing Address = "unavailable"
   and the PIN at 15sp. The `errorContainer` card is gone.
8. **Action, pinned to the bottom** — 1dp `--color-divider`, 14dp gap, then a full-width filled
   button, height 52dp, radius 2dp, `--color-accent` ground with `--color-bg` label,
   `home_start_server` / `home_stop_server`; `enabled = !isStarting`, and while starting the label
   is `home_status_starting` at 45% opacity (the system's disabled treatment).
   During `isStarting` a 3dp progress rule sits directly under the head pair
   (`--color-accent` on `--color-neutral-300`), indeterminate.

No behaviour changes: keep `LifecycleEventEffect(ON_RESUME) { viewModel.refresh() }` and the
existing start/stop calls exactly as they are.

### 2. Settings — `ui/settings/SettingsScreen.kt`

Same shell: back icon button (`ic_arrow_back.xml`) + title "Settings"/`settings_title` 24sp/600,
then the head pair (3dp rule, 4dp gap, 1dp rule) — no dateline row here.
Sections are separated by whitespace (30dp) and hairlines, never cards:

1. **Server port** — small-caps label `settings_server_port`; then the field: 180dp wide
   (200dp for RU), height 42dp, 1dp `--color-divider` border, radius 2dp, ground
   `--color-surface` (`#eae9e9`), value 16sp, caret `--color-accent`, numeric keyboard.
   Floating label is replaced by a 12sp caption above the box (`settings_port_hint`,
   `--color-text` @70%). Error: border and caption switch to `--color-accent-2-700` and the
   caption reads `settings_port_error`.
2. **Use HTTPS** — 1dp `--color-divider` above and below, 18dp vertical padding.
   Title `settings_use_https` 19sp/600 left, control right; `settings_use_https_description`
   13sp `--color-neutral-700` below, max measure 30em.
3. **Dark theme** — same row, title `settings_dark_theme`, no description.
4. **Storage folders** — small-caps label `settings_storage_folders` with a 1dp `--color-text`
   rule under it; one row per folder: `displayName` 16sp left, a text button
   `settings_remove` 13sp in `--color-accent-2-700` right, 1dp `--color-divider` under each row.
   Empty state: `settings_no_folders` 15sp `--color-neutral-700`.
   Then a full-width **outlined** button `settings_add_folder`, height 48dp, 1dp
   `--color-divider` border, `--color-text` label → launches the same
   `ActivityResultContracts.OpenDocumentTree()`.
5. **Footer** — `settings_version` centred, 11sp uppercase `.1em`, `--color-neutral-600`.

**The two `Switch`es become the system's segmented control.** Broadsheet has no switch;
it has `.seg`/`.seg-opt`. Build a two-option segmented control: one 1dp `--color-divider`
border, radius 2dp, a 1dp divider between halves, each half 7dp × 12dp padding, 13sp label;
the selected half fills `--color-accent` with a `--color-bg` label. Labels
`Off`/`On` and `Выкл`/`Вкл` — **new string resources**. Keep the same
`onCheckedChange` callbacks; minimum touch target 48dp tall for the pair.

### 3. Browser — PIN gate, `assets/webui/login.html` + `style.css`

Light paper (`#eae9e9`), card and border removed, block centred at `max-width: 360px`:
language toggle as a ghost text button top right → 4dp rule → dateline row
(`Local network` / `Port 8080`) → 1dp rule → `h1` "FerryFile" 40px/600 `-0.03em` →
`login.subtitle` 15px `--color-neutral-800` → field labelled `login.pin`:
full width, height 54dp, 26px/600 serif, `letter-spacing .3em`, centred, ground
`--color-surface`, 1dp `--color-divider`, radius 2dp → filled `login.unlock` button, full width,
48dp → a 12.5px `--color-neutral-700` line "Nothing leaves your Wi-Fi network." /
"Ничего не покидает вашу сеть Wi-Fi." (new i18n key).
Errors (`login.wrong_pin`, `login.too_many_attempts`, …) print as 13px
`--color-accent-2-700` text under the button with a 2dp `--color-accent-2-700` rule above —
the tinted `.message.error` box is gone.

Keep the `#login-form` / `#pin` / `#submit-btn` / `#error-msg` ids and every `data-i18n` key
so the inline script in `login.html` keeps working untouched.

### 4. Browser — file list, `assets/webui/files.html` + `style.css`

Page ground paper, content in a 34px gutter (max 900px, unchanged from `.main-content`).

- **Header** — brand 24px/600; right side: language ghost button + `files.logout` as an outlined
  button with `--color-accent-2-700` label and `--color-accent-2-300` border.
  Then 3dp rule, dateline row (`Home / Download` left, `3 shared folders · 5 items` right),
  1dp rule. Replaces the sticky dark `.app-header`.
- **Progress** (`#progress-container`) — no box. Small-caps `files.transferring` kicker in
  `--color-accent-700`, filename 22px/600 left; percentage 30px/600 tnum right with
  `11.9 MB of 18.6 MB · ETA 6s` 12px under it; then a 4dp bar, fill `--color-accent` on
  `--color-neutral-300`, `width` transition 0.3s ease (as today).
- **Toolbar row** — breadcrumb left (`files.home` link + `/` separators in
  `--color-neutral-500` + current segment 600), buttons right: `files.upload_files` outlined,
  `files.download` filled with the selection count. 2dp `--color-text` rule under the row.
- **File list** — the system's `.table`: header cells 11px uppercase `.08em`
  `--color-text` @60% over a 1dp `--color-divider`; body cells 10dp padding with a
  1dp `#201e1d` @8% bottom rule; row hover `#201e1d` @4%. Columns: 28px checkbox /
  Name / Size 120px right-aligned tnum / Modified 140px right-aligned tnum.
  Directories: name 600 followed by a `/` in `--color-neutral-600`, linked.
  Checkbox `accent-color: var(--color-accent)`, 15px.
  The 16px inline SVG folder/file icons in `app.js` (`ICON_DIR`, `ICON_FILE`) are dropped —
  the trailing `/` carries the distinction. Empty states keep `files.no_shared_folders` /
  `files.empty_folder` / `files.loading`, 15px `--color-neutral-700`, 48px vertical padding.
- **Selection bar** (`#selection-bar`) — no fill: 2dp `--color-accent-2-700` rule above,
  1dp `--color-accent-2-300` below, `files.selection_count` 14px `--color-accent-2-700` left,
  `files.download` filled + `files.clear` ghost right.
- **Drop zone** — 1dp **dashed** `--color-accent`, 26px padding, centred 14px
  `--color-accent-700` `files.drop_zone`. `.drag-over` → ground `--color-accent-100`.
- **Toast** — bottom right, paper ground, 1dp `--color-text`, radius 2dp, no shadow;
  success keeps `--color-accent-700`, error `--color-accent-2-700`.
- **Root hint** (`#root-hint`) — 13.5px `--color-neutral-700`, unchanged position.

Mobile breakpoint (`max-width: 600px`) keeps today's rules: 14px gutter, `.file-meta` hidden.

### Dark ink cut

Drive it from the existing `settings_dark_theme` preference. On Android that means a second
Compose colour set; for the web UI add a `prefers-color-scheme: dark` block (and/or a
`data-theme="dark"` attribute on `<html>`) that re-declares the `:root` variables with the dark
values in the token table — every rule above is written against the variables, so nothing else
changes. Note the black-ink accent must lift to `#62c5ee` on dark ground; near-black ink on
near-black paper is unreadable.

## Compose implementation notes

- **Source Serif 4**: bundle `SourceSerif4-Regular.ttf`, `-SemiBold.ttf`, `-Italic.ttf` in
  `res/font/` and build a `FontFamily`; set it on every `TextStyle` in `ui/theme/Type.kt`.
  Do not fall back to `FontFamily.Serif` — and delete both `FontFamily.Monospace` uses in
  `HomeScreen.kt`.
- **`Theme.kt`**: `dynamicColor` must become `false` — dynamic colour repaints the app in the
  wallpaper's palette and would destroy this identity. Rewrite `Color.kt` with the tokens above
  (the `Purple80`/`Purple40`/`Pink*` values are all dead) and map:
  `background`/`surface` → paper, `onBackground`/`onSurface` → ink, `primary` → accent,
  `onPrimary` → paper, `error` → `--color-accent-2-700`, `outline` → `--color-divider`.
  Broadsheet's tokens don't map onto M3's container roles — prefer reading colours from your own
  object over stretching `colorScheme`.
- **`Typography`**: define the roles from the type table rather than reusing
  `headlineSmall`/`displaySmall`/`labelMedium` semantics.
- Shape: `RoundedCornerShape(2.dp)` for buttons and fields; the 20dp M3 pill and the 12dp card
  radius both go.
- Rules are `Spacer(Modifier.fillMaxWidth().height(3.dp).background(ink))` — cheaper and more
  predictable than `Divider`.
- Contrast: the small-caps labels at 10sp must stay at `--color-neutral-700` or darker
  (4.5:1 on paper). Do not fade type with `alpha`.

## Assets

No new image assets. Existing vectors reused unchanged: `ic_settings.xml`, `ic_arrow_back.xml`,
`ic_stop.xml`, `ic_notification.xml`, launcher mipmaps. The system asks for Phosphor duotone icons
where new icons are needed — none are needed here. The registration target and step wedge in
direction `1c` are inline SVG/flex strips, not assets.

## New string resources

Add to `values/strings.xml` and `values-ru/strings.xml` (names suggested):

| Name | EN | RU |
|---|---|---|
| `home_dateline_network` | Local Wi-Fi · Port %1$s | Локальный Wi-Fi · порт %1$s |
| `home_running_standfirst` | Type the address into any browser on the same network, then enter the six-digit PIN. | Введите адрес в браузере на том же Wi-Fi, затем шестизначный PIN. |
| `home_stopped_standfirst` | Start the server to hand the address and PIN to your computer. | Запустите сервер, чтобы получить адрес и PIN для компьютера. |
| `home_notice_kicker` | Notice | Важно |
| `settings_switch_on` | On | Вкл |
| `settings_switch_off` | Off | Выкл |

And in `webui/i18n.js`: `login.privacy_note`, `files.dateline_shared` (`{folders} shared folders · {items} items`).

## Files in this bundle

```
design/FerryFile.dc.html    the design reference (open in a browser)
design/android-frame.jsx    device bezel used by the reference
design/browser-window.jsx   browser chrome used by the reference
design/support.js           runtime the reference needs
design/_ds/broadsheet-…/    the design system: styles.css (tokens + classes),
                            _ds_bundle.js, readme.md (its own guide)
```

Source files to change in the app:

```
app/src/main/java/ru/kryu/ferryfile/ui/theme/Color.kt        rewrite
app/src/main/java/ru/kryu/ferryfile/ui/theme/Theme.kt        rewrite (dynamicColor = false)
app/src/main/java/ru/kryu/ferryfile/ui/theme/Type.kt         rewrite (Source Serif 4)
app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt    rewrite view layer only
app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsScreen.kt  rewrite view layer only
app/src/main/res/font/                                       add Source Serif 4
app/src/main/res/values/strings.xml, values-ru/strings.xml   add rows above
app/src/main/assets/webui/style.css                          rewrite
app/src/main/assets/webui/login.html, files.html             markup tweaks, keep ids + data-i18n
app/src/main/assets/webui/i18n.js                            add two keys
```

Do **not** touch: `server/`, `service/`, `data/`, `domain/`, `di/`, `app.js`,
`HomeViewModel.kt`, `SettingsViewModel.kt`, `AppNavigation.kt`, `AndroidManifest.xml`.

## Starter prompt for Claude Code

```
Read design_handoff_broadsheet_restyle/README.md, then open
design_handoff_broadsheet_restyle/design/FerryFile.dc.html in a browser to see the target
(option 1b for Home, 1e for its states, 1f for Settings, 1g for the browser UI, 1i for dark).

Restyle FerryFile to the Broadsheet look described in that README. Visual changes only —
no new features, no changes to the Ktor server, the foreground service, the view models or
navigation. Work in this order and stop for review after each step:

1. Theme: bundle Source Serif 4 in res/font, rewrite Color.kt / Type.kt / Theme.kt to the
   README's tokens, and set dynamicColor = false.
2. HomeScreen.kt to option 1b, including the starting / no-Wi-Fi / no-folders states.
3. SettingsScreen.kt to option 1f, replacing both Switches with the segmented control.
4. assets/webui/style.css plus the markup tweaks in login.html and files.html — keep every
   element id and data-i18n key so app.js and the inline login script keep working.
5. The dark ink cut behind the existing Dark Theme preference.

Add the new string resources listed in the README to both values/ and values-ru/, and check
every screen at both languages — Russian labels run about 30% longer.
```
