# Broadsheet Restyle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reskin FerryFile's two Compose screens (Home, Settings) and the served web UI (login, files) from default Material 3 to the "Broadsheet" newsprint look, with zero behavior/feature changes.

**Architecture:** Introduce a small `BroadsheetColors`/`BroadsheetType` token layer in `ui/theme/` (read directly by screens instead of stretching Material3's `colorScheme`/`typography` roles), bundle Source Serif 4, then rewrite the two screens' view layers and the web UI's `style.css` + two HTML files against those tokens. No ViewModel, navigation, server, or `app.js` changes.

**Tech Stack:** Kotlin + Jetpack Compose + Material3 (Android side), vanilla HTML/CSS/JS with no build step (web UI side).

**Spec:** `docs/superpowers/specs/2026-09-16-broadsheet-restyle/README.md` (the design handoff doc — copied verbatim from the design package). The rendered reference is `docs/superpowers/specs/2026-09-16-broadsheet-restyle/design/FerryFile.dc.html` (open it in a browser; it needs its sibling `_ds/` and `.jsx`/`.js` files next to it — e.g. `cd` into that `design/` folder and run `python3 -m http.server 8901`, then visit `http://localhost:8901/FerryFile.dc.html`). Canonical token source: `docs/superpowers/specs/2026-09-16-broadsheet-restyle/design/_ds/broadsheet-.../styles.css`.

## Global Constraints

- **Visual changes only.** No new features, no server/service/data/domain/di changes, no navigation changes.
- **Do not touch:** `server/`, `service/`, `data/`, `domain/`, `di/`, `app.js`, `HomeViewModel.kt`, `SettingsViewModel.kt`, `AppNavigation.kt`, `AndroidManifest.xml`.
- **Direction to build:** Home = 1b ("Front page") + its state flow 1e (stopped/starting/running/no-Wi-Fi); Settings = 1f; Browser = 1g (ledger table) + dark ink cut 1i. Directions 1c/1d/1h are recorded alternates — do not build them.
- **Type:** Source Serif 4 only, weights 400/600 + 400 italic. No sans-serif anywhere, including monospace — delete both `FontFamily.Monospace` uses in the current `HomeScreen.kt`.
- **Numeric strings** (address, PIN, fingerprint, sizes, dates) get tabular figures: Compose `TextStyle(fontFeatureSettings = "tnum")`, CSS `font-feature-settings: 'tnum' 1`.
- **Shape:** buttons/fields/inputs are `RoundedCornerShape(2.dp)` / `border-radius: 2px` — effectively square. No shadows, no Material cards, no elevation.
- **Spacing scale:** 5 / 10 / 15 / 20 / 30 / 40 dp. Screen gutter 20dp.
- **dynamicColor must be false** — the app's identity is fixed ink/paper, not wallpaper-derived color.
- **Contrast:** small-caps labels at 10sp/11px must stay at `--color-neutral-700` (light) / the muted-label dark token or darker — never faded with alpha.
- Run every step in the order below; each task's own build/verification step must pass before moving to the next.

### Design tokens (canonical — copied from the spec, do not invent values)

| Token | Light | Dark | Notes |
|---|---|---|---|
| bg / ground (paper) | `#eae9e9` | `#201e1d` | |
| surface | `#eae9e9` | `#2d2b2b` | same as bg in light (no cards) |
| text / ink | `#201e1d` | `#f3f2f2` | |
| accent (interactive) | `#201e1d` | `#62c5ee` | filled button ground |
| accent-2 (warning, raw) | `#d6006c` | `#ff90b1` | |
| accent-700 (accent at paragraph size) | `#006786` | `#99e0ff` | "interactive text" in dark |
| accent-2-700 (warning text) | `#aa0b56` | `#ff90b1` | dark collapses accent-2/accent-2-700 into one |
| accent-2-300 (warning border tint) | `#ffc0d0` (real ramp step) | derived: `color-mix(in srgb, var(--color-accent-2) 35%, transparent)` | web only |
| accent-100 (hover/drag wash) | `#e9f8ff` (real ramp step) | derived: `color-mix(in srgb, var(--color-accent) 12%, transparent)` | web only |
| neutral-300 (progress track) | `#d7d3d3` | derived: `#2d2b2b` (= surface) | not given for dark |
| neutral-500 (breadcrumb separators, web only) | `#9b9797` (real ramp step) | derived: `#928e8e` | not given for dark |
| neutral-600 (disabled/placeholder) | `#7d7979` | derived: `#bab6b6` (= muted label) | dark collapses 600/700 |
| neutral-700 (small-caps labels/captions) | `#605d5d` | `#bab6b6` ("muted label") | |
| neutral-800 (secondary body) | `#444141` | `#d7d3d3` ("secondary body") | |
| divider | `#201e1d` @16% | `#f3f2f2` @22% | web: `color-mix(in srgb, var(--color-text) 16%/22%, transparent)` |

Type scale, per-role sizes/weights/tracking, and every string's exact copy are in the spec README — read the relevant section before each task rather than re-deriving values.

### Deviations from the design reference (judgment calls made while reconciling the spec with the do-not-touch list — flag any of these you'd rather have handled differently before continuing)

1. File list icons are hidden via CSS (`.file-icon { display: none }`), not removed from `app.js`'s `ICON_DIR`/`ICON_FILE` constants — `app.js` is off-limits.
2. Directory trailing "/" is added via a CSS `::after` on `.file-name-btn.dir`, not by editing `app.js`'s `nameBtn.textContent` assignment.
3. The file list's Size/Modified is rendered as **one** right-aligned tabular column (header label "Size · Modified"), not two independent 120px/140px columns — `app.js` emits a single combined `.file-meta` span; splitting it needs an `app.js` change.
4. The files page header's dateline row shows a static "Local network / Port ####" pair (tiny inline script reading `window.location.port`, mirroring the login page) instead of a live "N shared folders · N items" count — that count needs new `app.js` logic. The existing interactive `#breadcrumb` (restyled, left in place) still shows the real current path just below the header.
5. The Home screen's dateline shows the port only while running (parsed from the already-exposed `HomeUiState.url`); it reads "Local Wi-Fi · Port —" while stopped/starting, since `HomeUiState` doesn't carry the configured port and `HomeViewModel.kt` is on the do-not-touch list.
6. The progress block shows one 22px/600 `#progress-filename` (it already doubles as the "Transferring…" placeholder and the live filename inside `app.js`) instead of a separate small-caps "Transferring" kicker above a distinct filename node — there's no second DOM node to hold a kicker without touching `app.js`.
7. The toolbar keeps only the upload button; the filled Download button stays inside the selection bar (shown only once something's selected), instead of always-visible in the toolbar — an always-visible toolbar button would silently no-op with nothing selected, since `app.js` wires no enabled/disabled or live-count logic for it.
8. New string/i18n keys added beyond the spec's "suggested" table: Android `home_headline_running`, `home_headline_stopped`, `home_starting_standfirst`, `home_address_label`, `home_address_unavailable`, `home_dateline_no_network`; web `common.dateline_network`, `common.dateline_port`, `files.col_name`, `files.col_meta`. Needed to render states 1e/1g faithfully — the spec table is explicitly "suggested," not exhaustive.
9. Fonts are self-hosted (WOFF2 under `assets/webui/fonts/`, TTF under `res/font/`) rather than loaded from Google Fonts, since the web UI is served over a LAN link that may have no internet path — matches the product's own "nothing leaves your Wi-Fi network" copy.

## File Structure

```
app/src/main/res/font/                                  new: 3 Source Serif 4 TTFs
app/src/main/assets/webui/fonts/                         new: 3 Source Serif 4 WOFF2s
docs/licenses/SourceSerif4-OFL.txt                       new: font license text
app/src/main/java/.../ui/theme/Color.kt                  rewrite: BroadsheetColors tokens
app/src/main/java/.../ui/theme/Type.kt                   rewrite: SourceSerif4 FontFamily + BroadsheetType
app/src/main/java/.../ui/theme/Theme.kt                  rewrite: drop dynamic color, add BroadsheetTheme
app/src/main/java/.../MainActivity.kt                    trim: drop dynamicColor arg
app/src/main/res/values/strings.xml                      add ~12 new keys
app/src/main/res/values-ru/strings.xml                   add the same 12 keys, RU copy
app/src/main/java/.../ui/home/HomeScreen.kt               rewrite: view layer only
app/src/main/java/.../ui/settings/SettingsScreen.kt       rewrite: view layer only + SegmentedToggle
app/src/main/assets/webui/style.css                       rewrite: full Broadsheet system, light+dark
app/src/main/assets/webui/login.html                      markup tweak + 1 new inline script block
app/src/main/assets/webui/files.html                      markup tweak + 1 new inline script block
app/src/main/assets/webui/i18n.js                         add ~6 new keys (both languages)
```

---

## Task 1: Bundle Source Serif 4

**Files:**
- Create: `app/src/main/res/font/source_serif_4_regular.ttf`
- Create: `app/src/main/res/font/source_serif_4_semibold.ttf`
- Create: `app/src/main/res/font/source_serif_4_italic.ttf`
- Create: `app/src/main/assets/webui/fonts/source-serif-4-regular.woff2`
- Create: `app/src/main/assets/webui/fonts/source-serif-4-semibold.woff2`
- Create: `app/src/main/assets/webui/fonts/source-serif-4-italic.woff2`
- Create: `docs/licenses/SourceSerif4-OFL.txt`

**Interfaces:**
- Produces: three Android font resources (`R.font.source_serif_4_regular`, `R.font.source_serif_4_semibold`, `R.font.source_serif_4_italic`) that Task 2's `Type.kt` builds a `FontFamily` from. Three static web font files at `/webui/fonts/*.woff2` that Task 6's `style.css` `@font-face` rules reference by exact path.

Source: the official Adobe Fonts GitHub release `adobe-fonts/source-serif` tag `4.005R` (SIL Open Font License 1.1). Verified filenames inside each archive (flat `TTF/` folder, no Caption/Display/Subhead/SmText suffix = the default "Text" optical size):
- `TTF/SourceSerif4-Regular.ttf`, `TTF/SourceSerif4-Semibold.ttf`, `TTF/SourceSerif4-It.ttf`
- Same names with a trailing `.woff2` in the WOFF2 release.

- [ ] **Step 1: Download the two release archives into the scratch directory**

```bash
cd /tmp && mkdir -p broadsheet-fonts && cd broadsheet-fonts
curl -sL -o desktop.zip "https://github.com/adobe-fonts/source-serif/releases/download/4.005R/source-serif-4.005_Desktop.zip"
curl -sL -o woff2.zip "https://github.com/adobe-fonts/source-serif/releases/download/4.005R/source-serif-4.005_WOFF2.zip"
ls -la desktop.zip woff2.zip
```

Expected: both files present, `desktop.zip` ~17MB, `woff2.zip` ~11MB.

- [ ] **Step 2: Extract just the three weights needed, into the project**

```bash
cd /tmp/broadsheet-fonts
mkdir -p extracted
unzip -q -o desktop.zip -d extracted
unzip -q -o woff2.zip -d extracted

mkdir -p /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/res/font
mkdir -p /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui/fonts

cp "extracted/source-serif-4.005_Desktop/TTF/SourceSerif4-Regular.ttf" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/res/font/source_serif_4_regular.ttf
cp "extracted/source-serif-4.005_Desktop/TTF/SourceSerif4-Semibold.ttf" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/res/font/source_serif_4_semibold.ttf
cp "extracted/source-serif-4.005_Desktop/TTF/SourceSerif4-It.ttf" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/res/font/source_serif_4_italic.ttf

cp "extracted/source-serif-4.005_WOFF2/TTF/SourceSerif4-Regular.ttf.woff2" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui/fonts/source-serif-4-regular.woff2
cp "extracted/source-serif-4.005_WOFF2/TTF/SourceSerif4-Semibold.ttf.woff2" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui/fonts/source-serif-4-semibold.woff2
cp "extracted/source-serif-4.005_WOFF2/TTF/SourceSerif4-It.ttf.woff2" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui/fonts/source-serif-4-italic.woff2

cp "extracted/source-serif-4.005_Desktop/OFL.txt" \
   /Users/dimak21/AndroidStudioProjects/FerryFile/docs/licenses/SourceSerif4-OFL.txt 2>/dev/null || \
   find extracted -iname "OFL.txt" -exec cp {} /Users/dimak21/AndroidStudioProjects/FerryFile/docs/licenses/SourceSerif4-OFL.txt \; -quit
```

- [ ] **Step 3: Verify the 7 files landed and Android's font-resource filename rule is satisfied**

```bash
ls -la /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/res/font/
ls -la /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui/fonts/
ls -la /Users/dimak21/AndroidStudioProjects/FerryFile/docs/licenses/SourceSerif4-OFL.txt
```

Expected: 3 `.ttf` files (each 180–280KB) whose names match `^[a-z0-9_]+\.ttf$` (Android resource names must be lowercase letters/digits/underscore only — `source_serif_4_regular.ttf` etc. already satisfy this), 3 `.woff2` files (each 55–85KB), and the license file.

- [ ] **Step 4: Clean up the scratch download**

```bash
rm -rf /tmp/broadsheet-fonts
```

- [ ] **Step 5: Commit**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile
git add app/src/main/res/font app/src/main/assets/webui/fonts docs/licenses/SourceSerif4-OFL.txt
git commit -m "$(cat <<'EOF'
Bundle Source Serif 4 for the Broadsheet restyle

Android TTFs under res/font/, web WOFF2s under assets/webui/fonts/, both
from the official adobe-fonts/source-serif 4.005R release (SIL OFL 1.1).
Self-hosted rather than loaded from Google Fonts so the web UI keeps
working over a LAN link with no internet path.
EOF
)"
```

---

## Task 2: Theme rewrite — Color.kt, Type.kt, Theme.kt, MainActivity.kt

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/theme/Color.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/theme/Type.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/theme/Theme.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/MainActivity.kt:25`

**Interfaces:**
- Produces: `BroadsheetColors` (data class: `bg, surface, text, accent, accent2, accent700, accent2700, neutral300, neutral600, neutral700, neutral800, divider: Color`), `LightBroadsheetColors`/`DarkBroadsheetColors` (`BroadsheetColors` instances), `BroadsheetTheme.colors: BroadsheetColors` (`@Composable` accessor), `SourceSerif4: FontFamily`, `BroadsheetType` (object with `masthead, screenTitle, standfirst, smallCapsLabel, address, pin, pinSmall, fingerprint, sectionHeading, listRowValue, buttonLabel, footer, caption: TextStyle` and `@Composable fun headline(): TextStyle`), `FerryFileTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`.
- Consumes: nothing from earlier tasks (this is the foundation Tasks 4–8 build on).

- [ ] **Step 1: Rewrite `Color.kt`**

```kotlin
package ru.kryu.ferryfile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Broadsheet design tokens — see docs/superpowers/specs/2026-09-16-broadsheet-restyle/README.md
 * for the light values (copied from the design system's styles.css) and the dark ink cut,
 * which the spec calls a proposal, not a shipped token set.
 */
data class BroadsheetColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val accent2: Color,
    val accent700: Color,
    val accent2700: Color,
    val neutral300: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val divider: Color
)

val LightBroadsheetColors = BroadsheetColors(
    bg = Color(0xFFEAE9E9),
    surface = Color(0xFFEAE9E9),
    text = Color(0xFF201E1D),
    accent = Color(0xFF201E1D),
    accent2 = Color(0xFFD6006C),
    accent700 = Color(0xFF006786),
    accent2700 = Color(0xFFAA0B56),
    neutral300 = Color(0xFFD7D3D3),
    neutral600 = Color(0xFF7D7979),
    neutral700 = Color(0xFF605D5D),
    neutral800 = Color(0xFF444141),
    divider = Color(0xFF201E1D).copy(alpha = 0.16f)
)

// Dark ink cut: Broadsheet ships no dark surfaces. neutral300 and neutral600 are derived
// (collapsed onto the nearest tone the spec does give) rather than taken from the system.
val DarkBroadsheetColors = BroadsheetColors(
    bg = Color(0xFF201E1D),
    surface = Color(0xFF2D2B2B),
    text = Color(0xFFF3F2F2),
    accent = Color(0xFF62C5EE),
    accent2 = Color(0xFFFF90B1),
    accent700 = Color(0xFF99E0FF),
    accent2700 = Color(0xFFFF90B1),
    neutral300 = Color(0xFF2D2B2B),
    neutral600 = Color(0xFFBAB6B6),
    neutral700 = Color(0xFFBAB6B6),
    neutral800 = Color(0xFFD7D3D3),
    divider = Color(0xFFF3F2F2).copy(alpha = 0.22f)
)
```

- [ ] **Step 2: Rewrite `Type.kt`**

```kotlin
package ru.kryu.ferryfile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.kryu.ferryfile.R

val SourceSerif4 = FontFamily(
    Font(R.font.source_serif_4_regular, FontWeight.Normal),
    Font(R.font.source_serif_4_semibold, FontWeight.SemiBold),
    Font(R.font.source_serif_4_italic, FontWeight.Normal, FontStyle.Italic)
)

private const val TabularNumbers = "tnum"

/** Type roles from the Broadsheet spec's type table — named per role, not stretched onto
 * Material3's headlineSmall/displaySmall/labelMedium semantics. Color is applied by callers. */
object BroadsheetType {
    val masthead = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.025).em
    )

    val screenTitle = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    )

    // 44sp EN / 40sp RU per the spec — Russian labels run longer, so the headline steps down.
    @Composable
    fun headline(): TextStyle {
        val isRussian = LocalConfiguration.current.locales[0].language == "ru"
        val size = if (isRussian) 40.sp else 44.sp
        return TextStyle(
            fontFamily = SourceSerif4,
            fontWeight = FontWeight.SemiBold,
            fontSize = size,
            letterSpacing = (-0.03).em,
            lineHeight = size * 1.12f
        )
    }

    val standfirst = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp
    )

    val smallCapsLabel = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.12.em
    )

    val address = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNumbers
    )

    val pin = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 58.sp,
        letterSpacing = 0.10.em,
        fontFeatureSettings = TabularNumbers
    )

    // Used in the no-Wi-Fi block, where the PIN is shown de-emphasized at 15sp.
    val pinSmall = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.10.em,
        fontFeatureSettings = TabularNumbers
    )

    val fingerprint = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TabularNumbers
    )

    val sectionHeading = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp
    )

    val listRowValue = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    )

    val buttonLabel = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    )

    val footer = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.1.em
    )

    val caption = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )
}

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
)
```

- [ ] **Step 3: Rewrite `Theme.kt`**

```kotlin
package ru.kryu.ferryfile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBroadsheetColors = staticCompositionLocalOf { LightBroadsheetColors }

object BroadsheetTheme {
    val colors: BroadsheetColors
        @Composable get() = LocalBroadsheetColors.current
}

@Composable
fun FerryFileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val broadsheetColors = if (darkTheme) DarkBroadsheetColors else LightBroadsheetColors

    // Broadsheet's tokens don't map onto M3's container roles, so this scheme only backs
    // the handful of stock Material3 components still in use (OutlinedButton, BasicTextField
    // cursor default, etc). Screens read BroadsheetTheme.colors directly for everything else.
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = broadsheetColors.accent,
            onPrimary = broadsheetColors.bg,
            background = broadsheetColors.bg,
            onBackground = broadsheetColors.text,
            surface = broadsheetColors.surface,
            onSurface = broadsheetColors.text,
            error = broadsheetColors.accent2700,
            outline = broadsheetColors.divider
        )
    } else {
        lightColorScheme(
            primary = broadsheetColors.accent,
            onPrimary = broadsheetColors.bg,
            background = broadsheetColors.bg,
            onBackground = broadsheetColors.text,
            surface = broadsheetColors.surface,
            onSurface = broadsheetColors.text,
            error = broadsheetColors.accent2700,
            outline = broadsheetColors.divider
        )
    }

    CompositionLocalProvider(LocalBroadsheetColors provides broadsheetColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
```

- [ ] **Step 4: Trim `MainActivity.kt:25`** — `dynamicColor` no longer exists on `FerryFileTheme`, so drop the argument.

Change:
```kotlin
            FerryFileTheme(darkTheme = darkTheme, dynamicColor = false) {
```
to:
```kotlin
            FerryFileTheme(darkTheme = darkTheme) {
```

- [ ] **Step 5: Compile check**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. (HomeScreen.kt/SettingsScreen.kt still reference the old `MaterialTheme.colorScheme`/`MaterialTheme.typography` roles and `FontFamily.Monospace` at this point — that's fine, they still compile against the old Material3 APIs; Tasks 4–5 replace them.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ru/kryu/ferryfile/ui/theme app/src/main/java/ru/kryu/ferryfile/MainActivity.kt
git commit -m "$(cat <<'EOF'
Rewrite theme tokens for the Broadsheet restyle

Color.kt/Type.kt/Theme.kt now expose BroadsheetColors/BroadsheetType,
read directly by screens instead of stretching Material3's colorScheme/
typography roles (the spec's own recommendation, since Broadsheet's
tokens don't map onto M3's container roles). dynamicColor is gone.
EOF
)"
```

---

## Task 3: New string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Produces: the string resource names Task 4/5 reference below.

- [ ] **Step 1: Add to `app/src/main/res/values/strings.xml`**, before the closing `</resources>`:

```xml
    <string name="home_dateline_network">Local Wi-Fi · Port %1$s</string>
    <string name="home_dateline_no_network">No network</string>
    <string name="home_headline_running">Server running</string>
    <string name="home_headline_stopped">Server stopped</string>
    <string name="home_running_standfirst">Type the address into any browser on the same network, then enter the six-digit PIN.</string>
    <string name="home_stopped_standfirst">Start the server to hand the address and PIN to your computer.</string>
    <string name="home_starting_standfirst">Bringing the server up and issuing a PIN.</string>
    <string name="home_notice_kicker">Notice</string>
    <string name="home_address_label">Address</string>
    <string name="home_address_unavailable">unavailable</string>
    <string name="settings_switch_on">On</string>
    <string name="settings_switch_off">Off</string>
```

- [ ] **Step 2: Add to `app/src/main/res/values-ru/strings.xml`**, before the closing `</resources>`:

```xml
    <string name="home_dateline_network">Локальный Wi-Fi · порт %1$s</string>
    <string name="home_dateline_no_network">Нет сети</string>
    <string name="home_headline_running">Сервер работает</string>
    <string name="home_headline_stopped">Сервер остановлен</string>
    <string name="home_running_standfirst">Введите адрес в браузере на том же Wi-Fi, затем шестизначный PIN.</string>
    <string name="home_stopped_standfirst">Запустите сервер, чтобы получить адрес и PIN для компьютера.</string>
    <string name="home_starting_standfirst">Поднимаем сервер и выдаём PIN.</string>
    <string name="home_notice_kicker">Важно</string>
    <string name="home_address_label">Адрес</string>
    <string name="home_address_unavailable">недоступен</string>
    <string name="settings_switch_on">Вкл</string>
    <string name="settings_switch_off">Выкл</string>
```

- [ ] **Step 3: Verify both XML files are well-formed**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile
python3 -c "import xml.dom.minidom as m; m.parse('app/src/main/res/values/strings.xml'); print('EN ok')"
python3 -c "import xml.dom.minidom as m; m.parse('app/src/main/res/values-ru/strings.xml'); print('RU ok')"
```

Expected: `EN ok` / `RU ok`, no parse errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "$(cat <<'EOF'
Add Home/Settings string resources for the Broadsheet restyle

12 new keys (EN+RU): the dateline network/no-network labels, the
sentence-form running/stopped headlines and their standfirsts, the
notice kicker, the no-Wi-Fi address label, and the segmented-control
On/Off labels. Beyond the spec's own "suggested" 6 — see plan's
Deviations list, item 8.
EOF
)"
```

---

## Task 4: HomeScreen.kt rewrite

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt` (full rewrite; the `ConnectionCard` private composable is deleted, folded inline)

**Interfaces:**
- Consumes: `BroadsheetTheme.colors: BroadsheetColors`, `BroadsheetType.{masthead,headline(),standfirst,smallCapsLabel,address,pin,pinSmall,fingerprint,sectionHeading,buttonLabel}` (Task 2); `HomeViewModel`/`HomeUiState` **unchanged** (`isRunning, isStarting, url, pin, certificateFingerprint, hasWifi, hasSharedFolders: Boolean/String`); string resources from Task 3 plus the existing `home_*` keys.
- Produces: `HomeScreen(viewModel: HomeViewModel = hiltViewModel(), onNavigateToSettings: () -> Unit)` — same signature as today, called unchanged from `AppNavigation.kt`.

- [ ] **Step 1: Replace the full file contents**

```kotlin
package ru.kryu.ferryfile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.ui.theme.BroadsheetTheme
import ru.kryu.ferryfile.ui.theme.BroadsheetType

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BroadsheetTheme.colors

    // Re-read the server state every time this screen comes back to the foreground, not just
    // once per composition: the server can be stopped from outside the app (the notification's
    // Stop action stops the service while the activity is merely paused, not recreated), and
    // ServerRepository.refresh() is what notices that and republishes Stopped — but only if
    // something actually calls it. ON_RESUME also fires on first display, so this still covers
    // the initial load LaunchedEffect(Unit) used to handle.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        // Отступы системных панелей уже заданы в AppNavigation, Scaffold их не добавляет.
        contentWindowInsets = WindowInsets(0),
        containerColor = colors.bg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Masthead row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.brand_name),
                        style = BroadsheetType.masthead,
                        color = colors.text
                    )
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.home_settings_content_description),
                            tint = colors.text,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val isNoWifi = uiState.isRunning && !uiState.hasWifi
                // Only known while running: HomeUiState carries the live server URL, not the
                // configured port (HomeViewModel.kt is off-limits for this restyle).
                val port = remember(uiState.url) {
                    Regex(""":(\d+)$""").find(uiState.url)?.groupValues?.get(1)
                }

                // Head pair: thick rule, dateline row, thin rule.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colors.text)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isNoWifi) {
                            stringResource(R.string.home_dateline_no_network)
                        } else {
                            stringResource(R.string.home_dateline_network, port ?: "—")
                        }.uppercase(),
                        style = BroadsheetType.smallCapsLabel,
                        color = if (isNoWifi) colors.accent2700 else colors.neutral700
                    )
                    Text(
                        text = when {
                            uiState.isStarting -> stringResource(R.string.home_status_starting)
                            uiState.isRunning -> stringResource(R.string.home_status_running)
                            else -> stringResource(R.string.home_status_stopped)
                        }.uppercase(),
                        style = BroadsheetType.smallCapsLabel,
                        color = if (uiState.isRunning || uiState.isStarting) colors.accent700 else colors.neutral700
                    )
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.text)
                )

                if (uiState.isStarting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = colors.accent,
                        trackColor = colors.neutral300
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Headline + standfirst
                if (isNoWifi) {
                    val fullText = stringResource(R.string.home_no_wifi_connection)
                    val dashIndex = fullText.indexOf('—')
                    val headlineText = if (dashIndex >= 0) fullText.substring(0, dashIndex).trim() else fullText
                    val standfirstText = if (dashIndex >= 0) fullText.substring(dashIndex + 1).trim() else ""

                    Text(text = headlineText, style = BroadsheetType.headline(), color = colors.accent2700)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = standfirstText, style = BroadsheetType.standfirst, color = colors.accent2700)
                } else {
                    val headlineText = when {
                        uiState.isStarting -> stringResource(R.string.home_status_starting)
                        uiState.isRunning -> stringResource(R.string.home_headline_running)
                        else -> stringResource(R.string.home_headline_stopped)
                    }
                    Text(
                        text = headlineText,
                        style = BroadsheetType.headline(),
                        color = if (uiState.isStarting) colors.neutral700 else colors.text
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val standfirstText = when {
                        uiState.isStarting -> stringResource(R.string.home_starting_standfirst)
                        uiState.isRunning -> stringResource(R.string.home_running_standfirst)
                        else -> stringResource(R.string.home_stopped_standfirst)
                    }
                    Text(text = standfirstText, style = BroadsheetType.standfirst, color = colors.neutral800)
                }

                // Connection block (running + Wi-Fi) / no-Wi-Fi block (running, no Wi-Fi)
                if (uiState.isRunning) {
                    Spacer(modifier = Modifier.height(30.dp))
                    if (uiState.hasWifi) {
                        Text(
                            text = stringResource(R.string.home_open_in_browser),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.url, style = BroadsheetType.address, color = colors.accent700)

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = stringResource(R.string.home_pin),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.pin, style = BroadsheetType.pin, color = colors.text)
                        Spacer(modifier = Modifier.height(6.dp))
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(colors.accent)
                        )

                        if (uiState.certificateFingerprint.isNotBlank()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = stringResource(R.string.home_certificate_fingerprint),
                                style = BroadsheetType.smallCapsLabel,
                                color = colors.neutral700
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.certificateFingerprint,
                                style = BroadsheetType.fingerprint,
                                color = colors.text
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.home_certificate_hint),
                                style = BroadsheetType.fingerprint,
                                color = colors.neutral700
                            )
                        }
                    } else {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(colors.accent2700)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.home_address_label),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_address_unavailable),
                            style = BroadsheetType.address,
                            color = colors.neutral600
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.home_pin),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.pin, style = BroadsheetType.pinSmall, color = colors.text)
                    }
                }

                // Notice block
                if (!uiState.hasSharedFolders) {
                    Spacer(modifier = Modifier.height(30.dp))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.text)
                    )
                    Column(modifier = Modifier.padding(vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.home_notice_kicker).uppercase(),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.accent2700
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.home_no_folders_shared_title),
                            style = BroadsheetType.sectionHeading,
                            color = colors.text
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_no_folders_shared_message),
                            style = BroadsheetType.listRowValue,
                            color = colors.text
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.home_add_folder) + " →",
                            style = BroadsheetType.buttonLabel,
                            color = colors.accent,
                            modifier = Modifier.clickable(onClick = onNavigateToSettings)
                        )
                    }
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.divider)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Action, pinned to the bottom
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { if (uiState.isRunning) viewModel.onStopClicked() else viewModel.onStartClicked() },
                enabled = !uiState.isStarting,
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.bg,
                    disabledContainerColor = colors.accent,
                    disabledContentColor = colors.bg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                val runningLabel = if (uiState.isRunning) {
                    stringResource(R.string.home_stop_server)
                } else {
                    stringResource(R.string.home_start_server)
                }
                val displayLabel = if (uiState.isStarting) stringResource(R.string.home_status_starting) else runningLabel
                Text(
                    text = displayLabel,
                    style = BroadsheetType.buttonLabel,
                    modifier = if (uiState.isStarting) Modifier.alpha(0.45f) else Modifier
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
```

- [ ] **Step 2: Compile check**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
Restyle HomeScreen to Broadsheet direction 1b + state flow 1e

Plain Column, no Scaffold TopAppBar/Card/Switch. Head pair (thick rule
/ dateline / thin rule) replaces the top app bar; the running/starting/
stopped/no-Wi-Fi/no-folders states are now typographic (headline +
standfirst + small-caps dateline), the green/grey status dot is gone.
View layer only — HomeViewModel.kt and its uiState shape are unchanged.
EOF
)"
```

---

## Task 5: SettingsScreen.kt rewrite

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsScreen.kt` (full rewrite; adds two new private composables `SegmentedToggle`/`SegmentOption`)

**Interfaces:**
- Consumes: `BroadsheetTheme.colors: BroadsheetColors`, `BroadsheetType.{screenTitle,smallCapsLabel,sectionHeading,listRowValue,buttonLabel,caption}`, `SourceSerif4: FontFamily` (Task 2); `SettingsViewModel`/`SettingsUiState` **unchanged** (`port: Int, portError: Boolean, darkTheme: Boolean, useHttps: Boolean, sharedFolders: List<SharedFolder>`); string resources from Task 3 plus existing `settings_*` keys.
- Produces: `SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), onNavigateBack: () -> Unit)` — same signature as today.

- [ ] **Step 1: Replace the full file contents**

```kotlin
package ru.kryu.ferryfile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kryu.ferryfile.BuildConfig
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.ui.theme.BroadsheetColors
import ru.kryu.ferryfile.ui.theme.BroadsheetTheme
import ru.kryu.ferryfile.ui.theme.BroadsheetType
import ru.kryu.ferryfile.ui.theme.SourceSerif4

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BroadsheetTheme.colors
    val isRussian = LocalConfiguration.current.locales[0].language == "ru"

    var portText by remember(uiState.port) { mutableStateOf(uiState.port.toString()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderPicked(uri.toString())
        }
    }

    Scaffold(
        // Отступы системных панелей уже заданы в AppNavigation, Scaffold их не добавляет.
        contentWindowInsets = WindowInsets(0),
        containerColor = colors.bg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.settings_back_content_description),
                            tint = colors.text,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.settings_title), style = BroadsheetType.screenTitle, color = colors.text)
                }

                Spacer(modifier = Modifier.height(20.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(3.dp).background(colors.text))
                Spacer(modifier = Modifier.height(4.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.text))

                Spacer(modifier = Modifier.height(30.dp))

                // Server port
                Text(
                    text = stringResource(R.string.settings_server_port).uppercase(),
                    style = BroadsheetType.smallCapsLabel,
                    color = colors.neutral700
                )
                Spacer(modifier = Modifier.height(15.dp))

                val portFieldWidth = if (isRussian) 200.dp else 180.dp
                val portBorderColor = if (uiState.portError) colors.accent2700 else colors.divider
                val portCaptionColor = if (uiState.portError) colors.accent2700 else colors.text.copy(alpha = 0.7f)
                Text(
                    text = if (uiState.portError) {
                        stringResource(R.string.settings_port_error)
                    } else {
                        stringResource(R.string.settings_port_hint)
                    },
                    style = BroadsheetType.caption,
                    color = portCaptionColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                BasicTextField(
                    value = portText,
                    onValueChange = { newValue ->
                        portText = newValue
                        viewModel.onPortChanged(newValue)
                    },
                    textStyle = BroadsheetType.listRowValue.copy(color = colors.text),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(portFieldWidth)
                        .height(42.dp)
                        .border(1.dp, portBorderColor, RoundedCornerShape(2.dp)),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(30.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Spacer(modifier = Modifier.height(18.dp))

                // Use HTTPS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(text = stringResource(R.string.settings_use_https), style = BroadsheetType.sectionHeading, color = colors.text)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_use_https_description),
                            fontFamily = SourceSerif4,
                            fontSize = 13.sp,
                            color = colors.neutral700
                        )
                    }
                    SegmentedToggle(checked = uiState.useHttps, onCheckedChange = { viewModel.onHttpsChanged(it) })
                }

                Spacer(modifier = Modifier.height(18.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                Spacer(modifier = Modifier.height(30.dp))

                // Dark theme
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.settings_dark_theme), style = BroadsheetType.sectionHeading, color = colors.text)
                    SegmentedToggle(checked = uiState.darkTheme, onCheckedChange = { viewModel.onDarkThemeChanged(it) })
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Storage folders
                Text(
                    text = stringResource(R.string.settings_storage_folders).uppercase(),
                    style = BroadsheetType.smallCapsLabel,
                    color = colors.neutral700
                )
                Spacer(modifier = Modifier.height(4.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.text))

                if (uiState.sharedFolders.isEmpty()) {
                    Spacer(modifier = Modifier.height(15.dp))
                    Text(
                        text = stringResource(R.string.settings_no_folders),
                        style = BroadsheetType.listRowValue.copy(fontSize = 15.sp),
                        color = colors.neutral700
                    )
                } else {
                    uiState.sharedFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = folder.displayName,
                                style = BroadsheetType.listRowValue,
                                color = colors.text,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.settings_remove),
                                fontFamily = SourceSerif4,
                                fontSize = 13.sp,
                                color = colors.accent2700,
                                modifier = Modifier.clickable { viewModel.onFolderRemoved(folder.uri) }
                            )
                        }
                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, colors.divider),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(text = stringResource(R.string.settings_add_folder), style = BroadsheetType.buttonLabel)
                }
            }

            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME).uppercase(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                fontFamily = SourceSerif4,
                fontSize = 11.sp,
                letterSpacing = 0.1.em,
                color = colors.neutral600
            )
        }
    }
}

// Broadsheet has no Switch; it has a two-option segmented control (Off/On).
@Composable
private fun SegmentedToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BroadsheetTheme.colors
    Row(
        modifier = modifier
            .height(48.dp)
            .border(1.dp, colors.divider, RoundedCornerShape(2.dp))
    ) {
        SegmentOption(
            label = stringResource(R.string.settings_switch_off),
            selected = !checked,
            onClick = { onCheckedChange(false) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.divider)
        )
        SegmentOption(
            label = stringResource(R.string.settings_switch_on),
            selected = checked,
            onClick = { onCheckedChange(true) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: BroadsheetColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = SourceSerif4,
            fontSize = 13.sp,
            color = if (selected) colors.bg else colors.text
        )
    }
}
```

- [ ] **Step 2: Compile check**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsScreen.kt
git commit -m "$(cat <<'EOF'
Restyle SettingsScreen to Broadsheet direction 1f

Head pair replaces the top app bar, sections separated by whitespace
and hairlines instead of cards. Both Switches become a new
SegmentedToggle (Off/On) — Broadsheet has no switch component. Port
field is a bordered BasicTextField with a caption above it instead of
a Material floating label. View layer only — SettingsViewModel.kt and
its uiState shape are unchanged.
EOF
)"
```

---

## Task 6: Web UI style.css rewrite (light + dark)

**Files:**
- Modify: `app/src/main/assets/webui/style.css` (full rewrite)

**Interfaces:**
- Consumes: nothing Kotlin-side. Must keep every class name `app.js` sets via `className`/`classList`/`querySelectorAll` unchanged: `file-item`, `dir`, `file-icon`, `file-name-btn`, `file-meta`, `file-checkbox`, `file-download-btn`, `breadcrumb-item`, `breadcrumb-sep`, `breadcrumb-current`, `visible`, `drag-over`, `toast`/`error`/`success`. Must keep every element `id` `app.js` looks up by `getElementById` (unchanged from today — see Task 8's file list).
- Produces: the `.page`, `.login-block`, `.dateline-rule`, `.dateline-row`, `.ghost-btn`, `.btn-filled`, `.btn-outline`, `.btn-outline-danger`, `.pin-input`, `.error-msg`, `.privacy-note`, `.app-header`, `.header-top`, `.file-list-head` classes Tasks 7–8's HTML references.

- [ ] **Step 1: Replace the full file contents**

```css
/* FerryFile Web UI — Broadsheet */

*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

/* Author rules like .btn-filled{display:inline-flex} otherwise outrank the UA [hidden] default. */
[hidden] {
  display: none !important;
}

@font-face {
  font-family: 'Source Serif 4';
  src: url('/webui/fonts/source-serif-4-regular.woff2') format('woff2');
  font-weight: 400;
  font-style: normal;
  font-display: swap;
}

@font-face {
  font-family: 'Source Serif 4';
  src: url('/webui/fonts/source-serif-4-semibold.woff2') format('woff2');
  font-weight: 600;
  font-style: normal;
  font-display: swap;
}

@font-face {
  font-family: 'Source Serif 4';
  src: url('/webui/fonts/source-serif-4-italic.woff2') format('woff2');
  font-weight: 400;
  font-style: italic;
  font-display: swap;
}

:root {
  --color-bg: #eae9e9;
  --color-surface: #eae9e9;
  --color-text: #201e1d;
  --color-accent: #201e1d;
  --color-accent-2: #d6006c;
  --color-accent-700: #006786;
  --color-accent-2-700: #aa0b56;
  --color-accent-2-300: #ffc0d0;
  --color-accent-100: #e9f8ff;
  --color-neutral-300: #d7d3d3;
  --color-neutral-500: #9b9797;
  --color-neutral-600: #7d7979;
  --color-neutral-700: #605d5d;
  --color-neutral-800: #444141;
  /* Divider and the file-list hover/rule washes are ink-relative (color-mix against
     --color-text), so they auto-adapt under the dark override below without repeating. */
  --color-divider: color-mix(in srgb, var(--color-text) 16%, transparent);
  --radius-md: 2px;
}

@media (prefers-color-scheme: dark) {
  :root {
    --color-bg: #201e1d;
    --color-surface: #2d2b2b;
    --color-text: #f3f2f2;
    --color-accent: #62c5ee;
    --color-accent-2: #ff90b1;
    --color-accent-700: #99e0ff;
    --color-accent-2-700: #ff90b1;
    --color-accent-2-300: color-mix(in srgb, var(--color-accent-2) 35%, transparent);
    --color-accent-100: color-mix(in srgb, var(--color-accent) 12%, transparent);
    --color-neutral-300: #2d2b2b;
    --color-neutral-500: #928e8e;
    --color-neutral-600: #bab6b6;
    --color-neutral-700: #bab6b6;
    --color-neutral-800: #d7d3d3;
  }
}

body {
  font-family: 'Source Serif 4', Georgia, 'Times New Roman', serif;
  background: var(--color-bg);
  color: var(--color-text);
  min-height: 100vh;
  line-height: 1.5;
}

/* ── Furniture: dateline rules/rows, reused on the login and files pages ── */

.dateline-rule {
  height: 1px;
  background: var(--color-text);
}

.dateline-rule-thick {
  height: 3px;
}

.dateline-row {
  display: flex;
  justify-content: space-between;
  padding: 5px 0;
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-neutral-700);
}

.ghost-btn {
  background: none;
  border: none;
  font: inherit;
  font-size: 13px;
  color: var(--color-accent);
  cursor: pointer;
  padding: 4px 0;
}

.ghost-btn:hover {
  text-decoration: underline;
}

/* ── Buttons ── */

.btn-filled,
.btn-outline,
.btn-outline-danger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 48px;
  padding: 0 20px;
  border-radius: var(--radius-md);
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  border: 1px solid transparent;
}

.btn-filled {
  background: var(--color-accent);
  color: var(--color-bg);
  border-color: var(--color-accent);
}

.btn-filled:hover {
  opacity: 0.9;
}

.btn-outline {
  background: transparent;
  color: var(--color-text);
  border-color: var(--color-divider);
}

.btn-outline:hover {
  background: color-mix(in srgb, var(--color-text) 6%, transparent);
}

.btn-outline-danger {
  background: transparent;
  color: var(--color-accent-2-700);
  border-color: var(--color-accent-2-300);
  height: 40px;
  padding: 0 16px;
  font-size: 14px;
}

.btn-outline-danger:hover {
  background: color-mix(in srgb, var(--color-accent-2-700) 8%, transparent);
}

button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ── Page shell ── */

.page {
  display: flex;
  justify-content: center;
  padding: 40px 16px;
  min-height: 100vh;
}

/* ── Login page ── */

.login-block {
  width: 100%;
  max-width: 360px;
}

.login-top {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.login-block h1 {
  font-size: 40px;
  font-weight: 600;
  letter-spacing: -0.03em;
  margin: 24px 0 8px;
}

.login-block .subtitle {
  font-size: 15px;
  color: var(--color-neutral-800);
  margin-bottom: 28px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-neutral-700);
  margin-bottom: 8px;
}

.pin-input {
  width: 100%;
  height: 54px;
  background: var(--color-surface);
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-family: inherit;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 0.3em;
  text-align: center;
  outline: none;
}

.pin-input:focus {
  border-color: var(--color-accent);
}

.error-msg {
  display: none;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 2px solid var(--color-accent-2-700);
  font-size: 13px;
  color: var(--color-accent-2-700);
}

.error-msg.visible {
  display: block;
}

.privacy-note {
  margin-top: 24px;
  font-size: 12.5px;
  color: var(--color-neutral-700);
}

/* ── Files page: header ── */

.app-header {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 34px 0;
}

.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 16px;
}

.app-header .brand {
  font-size: 24px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.main-content {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 34px;
}

/* ── Progress (see plan Deviations item 6: one filename node, not a separate kicker) ── */

.progress-container {
  display: none;
  margin-bottom: 30px;
}

.progress-container.visible {
  display: block;
}

.progress-label {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
}

#progress-filename {
  font-size: 22px;
  font-weight: 600;
}

#progress-pct {
  font-size: 30px;
  font-weight: 600;
  font-feature-settings: 'tnum' 1;
}

.progress-details {
  font-size: 12px;
  color: var(--color-neutral-700);
  margin-bottom: 10px;
}

.progress-bar {
  height: 4px;
  background: var(--color-neutral-300);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--color-accent);
  width: 0%;
  transition: width 0.3s ease;
}

/* ── Breadcrumb (existing interactive nav — the current-location line) ── */

.breadcrumb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 15px;
  margin-bottom: 16px;
}

.breadcrumb-item {
  background: none;
  border: none;
  font: inherit;
  color: var(--color-accent-700);
  cursor: pointer;
  padding: 0;
}

.breadcrumb-item:hover {
  text-decoration: underline;
}

.breadcrumb-sep {
  color: var(--color-neutral-500);
}

.breadcrumb-current {
  font-weight: 600;
}

/* ── Toolbar ── */

.toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

#upload-label {
  cursor: pointer;
}

.root-hint {
  margin: 0 0 16px;
  font-size: 13.5px;
  color: var(--color-neutral-700);
}

/* ── File list: the system's table ── */

.file-list-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 0 8px;
  border-bottom: 1px solid var(--color-divider);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: color-mix(in srgb, var(--color-text) 60%, transparent);
}

.file-list-head .col-check {
  width: 28px;
  flex-shrink: 0;
}

.file-list-head .col-name {
  flex: 1;
}

.file-list-head .col-meta {
  flex-shrink: 0;
  min-width: 220px;
  text-align: right;
}

.file-list {
  margin-top: 20px;
}

.file-list-empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--color-neutral-700);
  font-size: 15px;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid color-mix(in srgb, var(--color-text) 8%, transparent);
}

.file-item:hover {
  background: color-mix(in srgb, var(--color-text) 4%, transparent);
}

/* Directory/file distinction is carried by weight + the trailing "/" below; the JS-drawn
   16px SVGs are hidden rather than removed from app.js (see plan Deviations item 1). */
.file-icon {
  display: none;
}

.file-checkbox {
  width: 15px;
  height: 15px;
  flex: none;
  accent-color: var(--color-accent);
  cursor: pointer;
}

.file-name-btn {
  background: none;
  border: none;
  font: inherit;
  font-size: 16px;
  color: var(--color-text);
  text-align: left;
  cursor: pointer;
  flex: 1;
  min-width: 0;
  padding: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-name-btn:hover {
  text-decoration: underline;
}

.file-name-btn.dir {
  font-weight: 600;
  color: var(--color-accent-700);
}

/* See plan Deviations item 2: the trailing "/" is CSS furniture, not an app.js edit. */
.file-name-btn.dir::after {
  content: '/';
  font-weight: 400;
  color: var(--color-neutral-600);
}

/* One combined, right-aligned tabular column — see plan Deviations item 3. */
.file-meta {
  font-size: 14px;
  font-feature-settings: 'tnum' 1;
  color: var(--color-neutral-700);
  flex-shrink: 0;
  min-width: 220px;
  text-align: right;
  white-space: nowrap;
}

.file-download-btn {
  background: none;
  border: none;
  font: inherit;
  font-size: 13px;
  color: var(--color-accent-700);
  cursor: pointer;
  padding: 0;
}

.file-download-btn:hover {
  text-decoration: underline;
}

/* ── Drop zone ── */

.drop-zone {
  border: 1px dashed var(--color-accent);
  border-radius: var(--radius-md);
  padding: 26px;
  text-align: center;
  font-size: 14px;
  color: var(--color-accent-700);
  margin-top: 20px;
  cursor: pointer;
  outline: none;
}

.drop-zone:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}

.drop-zone.drag-over {
  background: var(--color-accent-100);
}

/* ── Selection bar ── */

.selection-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  margin-bottom: 12px;
  border-top: 2px solid var(--color-accent-2-700);
  border-bottom: 1px solid var(--color-accent-2-300);
}

#selection-count {
  font-size: 14px;
  color: var(--color-accent-2-700);
}

.selection-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

/* ── Toast ── */

.toast {
  position: fixed;
  bottom: 24px;
  right: 24px;
  background: var(--color-bg);
  border: 1px solid var(--color-text);
  border-radius: var(--radius-md);
  padding: 12px 18px;
  font-size: 14px;
  max-width: 320px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.2s, transform 0.2s;
  pointer-events: none;
  z-index: 100;
}

.toast.visible {
  opacity: 1;
  transform: translateY(0);
}

.toast.error {
  color: var(--color-accent-2-700);
}

.toast.success {
  color: var(--color-accent-700);
}

/* ── Responsive ── */

@media (max-width: 600px) {
  .app-header {
    padding: 16px 14px 0;
  }

  .main-content {
    padding: 16px 14px;
  }

  .file-meta,
  .file-list-head .col-meta {
    display: none;
  }

  .login-block h1 {
    font-size: 32px;
  }
}
```

- [ ] **Step 2: Visual sanity check with the current (pre-Task-7/8) markup** — the class renames won't be wired into the HTML until Tasks 7–8, so this step only confirms the CSS itself parses and the font files load without 404s once referenced. Serve the assets directory and check the network tab:

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui && python3 -m http.server 8902 &
sleep 1
curl -s -o /dev/null -w "style.css: %{http_code}\n" http://localhost:8902/style.css
curl -s -o /dev/null -w "font regular: %{http_code}\n" http://localhost:8902/fonts/source-serif-4-regular.woff2
curl -s -o /dev/null -w "font semibold: %{http_code}\n" http://localhost:8902/fonts/source-serif-4-semibold.woff2
curl -s -o /dev/null -w "font italic: %{http_code}\n" http://localhost:8902/fonts/source-serif-4-italic.woff2
kill %1
```

Expected: all four `200`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/webui/style.css
git commit -m "$(cat <<'EOF'
Rewrite web UI style.css for the Broadsheet restyle

Full rewrite: light + dark (prefers-color-scheme) tokens, self-hosted
Source Serif 4 via @font-face, the login/files pages' dateline
furniture, and the file list restyled as the system's table. Keeps
every class name and id app.js sets or looks up unchanged.
EOF
)"
```

---

## Task 7: Web UI login.html + i18n.js (login + shared keys)

**Files:**
- Modify: `app/src/main/assets/webui/login.html`
- Modify: `app/src/main/assets/webui/i18n.js`

**Interfaces:**
- Consumes: `.page`, `.login-block`, `.login-top`, `.ghost-btn`, `.dateline-rule`/`.dateline-rule-thick`/`.dateline-row`, `.pin-input`, `.btn-filled`, `.error-msg` classes from Task 6.
- Produces: `common.dateline_network`, `common.dateline_port`, `login.privacy_note` i18n keys that Task 8's files.html also reads (`common.*`).

- [ ] **Step 1: Add the `common` object and `login.privacy_note` to both languages in `i18n.js`.** In the `en` block, insert a `common` entry right after `brand: 'FerryFile',` (before `language: {`):

```js
      common: {
        dateline_network: 'Local network',
        dateline_port: 'Port {port}'
      },
```

and inside the existing `login: { ... }` object, add after `network_error: 'Network error, please try again'` (adjust the preceding line's trailing comma):

```js
        network_error: 'Network error, please try again',
        privacy_note: 'Nothing leaves your Wi-Fi network.'
```

Then in the `ru` block, insert after `brand: 'FerryFile',`:

```js
      common: {
        dateline_network: 'Локальная сеть',
        dateline_port: 'Порт {port}'
      },
```

and inside `ru`'s `login: { ... }`, add after `network_error: 'Ошибка сети, попробуйте ещё раз'`:

```js
        network_error: 'Ошибка сети, попробуйте ещё раз',
        privacy_note: 'Ничего не покидает вашу сеть Wi-Fi.'
```

- [ ] **Step 2: Replace the full contents of `login.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title data-i18n="login.title"></title>
  <link rel="stylesheet" href="/webui/style.css" />
</head>
<body>
  <div class="page">
    <div class="login-block">
      <div class="login-top">
        <button
          type="button"
          class="ghost-btn language-toggle"
          id="language-toggle"
          data-i18n="language.switch_to"
          data-i18n-aria-label="language.switch_aria_label"
        ></button>
      </div>

      <div class="dateline-rule dateline-rule-thick"></div>
      <div class="dateline-row">
        <span data-i18n="common.dateline_network"></span>
        <span id="dateline-port"></span>
      </div>
      <div class="dateline-rule"></div>

      <h1 data-i18n="brand"></h1>
      <p class="subtitle" data-i18n="login.subtitle"></p>

      <form id="login-form">
        <div class="form-group">
          <label for="pin" data-i18n="login.pin"></label>
          <input
            id="pin"
            type="text"
            inputmode="numeric"
            pattern="[0-9]*"
            maxlength="6"
            autocomplete="off"
            autofocus
            required
            class="pin-input"
          />
        </div>

        <button type="submit" class="btn-filled" id="submit-btn" data-i18n="login.unlock"></button>
      </form>

      <div id="error-msg" class="error-msg" role="alert"></div>

      <p class="privacy-note" data-i18n="login.privacy_note"></p>
    </div>
  </div>

  <script src="/webui/i18n.js"></script>
  <script>
    (function () {
      var i18n = window.FerryFileI18n;
      var portEl = document.getElementById('dateline-port');

      function renderPort() {
        var port = window.location.port || (window.location.protocol === 'https:' ? '443' : '80');
        portEl.textContent = i18n.t('common.dateline_port', { port: port });
      }

      renderPort();
      document.addEventListener('ferryfile-language-change', renderPort);
    })();
  </script>
  <script>
    (function () {
      var i18n = window.FerryFileI18n;
      var form = document.getElementById('login-form');
      var pinInput = document.getElementById('pin');
      var submitBtn = document.getElementById('submit-btn');
      var errorMsg = document.getElementById('error-msg');
      var errorKey = null;

      function showError(key) {
        errorKey = key;
        errorMsg.textContent = i18n.t(key);
        errorMsg.classList.add('visible');
      }

      function clearError() {
        errorKey = null;
        errorMsg.textContent = '';
        errorMsg.classList.remove('visible');
      }

      pinInput.addEventListener('input', function () {
        pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 6);
      });

      form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearError();

        var pin = pinInput.value;
        if (pin.length !== 6) {
          showError('login.pin_length');
          return;
        }

        submitBtn.disabled = true;
        submitBtn.textContent = i18n.t('login.checking');

        fetch('/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pin: pin })
        })
          .then(function (res) {
            if (res.ok) {
              window.location.href = '/files';
              return;
            }
            if (res.status === 429) {
              showError('login.too_many_attempts');
            } else if (res.status === 401) {
              showError('login.wrong_pin');
              pinInput.value = '';
              pinInput.focus();
            } else {
              showError('login.unexpected_error');
            }
            submitBtn.disabled = false;
            submitBtn.textContent = i18n.t('login.unlock');
          })
          .catch(function () {
            showError('login.network_error');
            submitBtn.disabled = false;
            submitBtn.textContent = i18n.t('login.unlock');
          });
      });

      document.addEventListener('ferryfile-language-change', function () {
        if (errorKey) errorMsg.textContent = i18n.t(errorKey);
        submitBtn.textContent = submitBtn.disabled
          ? i18n.t('login.checking')
          : i18n.t('login.unlock');
      });
    })();
  </script>
</body>
</html>
```

Note: the second inline `<script>` block (the form submit handler) is copied **byte-for-byte unchanged** from the current file — only a new, separate script block was added above it for the dateline port text. This is deliberate: the spec says this script must keep working untouched.

- [ ] **Step 3: Visual check in a browser**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui && python3 -m http.server 8902 &
sleep 1
```

Then use the claude-in-chrome tool: navigate to `http://localhost:8902/login.html`, screenshot it, and confirm: paper background, serif type throughout (no sans-serif fallback glyphs visible), the dateline row reads "LOCAL NETWORK" / "PORT 8902", the PIN field is a bordered box with wide letter-spacing, the button is solid near-black with light label. Then toggle the OS/browser to dark mode (or use the browser devtools' "Emulate CSS prefers-color-scheme: dark") and confirm the ground flips to near-black with light-blue accent. Kill the server after (`kill %1`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/webui/login.html app/src/main/assets/webui/i18n.js
git commit -m "$(cat <<'EOF'
Restyle the login page to Broadsheet direction 1g

Card/border removed, dateline furniture added above the masthead (a
tiny new inline script reads window.location.port so it doesn't need
a server-side template), privacy note added below the button. The
existing PIN-submit inline script is untouched.
EOF
)"
```

---

## Task 8: Web UI files.html + i18n.js (files-page keys)

**Files:**
- Modify: `app/src/main/assets/webui/files.html`
- Modify: `app/src/main/assets/webui/i18n.js`

**Interfaces:**
- Consumes: `.app-header`, `.header-top`, `.dateline-rule`/`.dateline-row`, `.btn-outline`, `.btn-outline-danger`, `.btn-filled`, `.ghost-btn`, `.file-list-head`, `common.dateline_network`/`common.dateline_port` from Tasks 6–7.
- Produces: `files.dateline_shared`, `files.col_name`, `files.col_meta` i18n keys (the first is added per the spec table but intentionally left unwired — see plan Deviations item 4/8).

- [ ] **Step 1: Add three keys to `i18n.js`'s existing `files: { ... }` objects.** In the `en` block, add after `transfer_error: 'Transfer error'` (adjust that line's trailing comma):

```js
        transfer_error: 'Transfer error',
        dateline_shared: '{folders} shared folders · {items} items',
        col_name: 'Name',
        col_meta: 'Size · Modified'
```

In the `ru` block, add after `transfer_error: 'Ошибка передачи'`:

```js
        transfer_error: 'Ошибка передачи',
        dateline_shared: '{folders} общих папок · {items} элементов',
        col_name: 'Имя',
        col_meta: 'Размер · Изменён'
```

- [ ] **Step 2: Replace the full contents of `files.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title data-i18n="files.title"></title>
  <link rel="stylesheet" href="/webui/style.css" />
</head>
<body>
  <header class="app-header">
    <div class="header-top">
      <span class="brand" data-i18n="brand"></span>
      <div class="header-actions">
        <button
          type="button"
          class="ghost-btn language-toggle"
          id="language-toggle"
          data-i18n="language.switch_to"
          data-i18n-aria-label="language.switch_aria_label"
        ></button>
        <button class="btn-outline-danger" id="logout-btn" data-i18n="files.logout"></button>
      </div>
    </div>
    <div class="dateline-rule dateline-rule-thick"></div>
    <div class="dateline-row">
      <span data-i18n="common.dateline_network"></span>
      <span id="dateline-port"></span>
    </div>
    <div class="dateline-rule"></div>
  </header>

  <main class="main-content">
    <!-- Progress bar (hidden until transfer starts) -->
    <div class="progress-container" id="progress-container">
      <div class="progress-label">
        <span id="progress-filename"></span>
        <span id="progress-pct">0%</span>
      </div>
      <div class="progress-bar">
        <div class="progress-fill" id="progress-fill"></div>
      </div>
      <div class="progress-details" id="progress-details"></div>
    </div>

    <!-- Breadcrumb -->
    <nav
      class="breadcrumb"
      id="breadcrumb"
      aria-label=""
      data-i18n-aria-label="files.file_path"
    ></nav>

    <!-- Toolbar -->
    <div class="toolbar">
      <label class="btn-outline" id="upload-label">
        <span data-i18n="files.upload_files"></span>
        <input type="file" id="upload-input" multiple style="display:none" />
      </label>
    </div>

    <p class="root-hint" id="root-hint" hidden>
      <span data-i18n="files.root_hint"></span>
    </p>

    <div class="selection-bar" id="selection-bar" hidden>
      <span id="selection-count"></span>
      <div class="selection-actions">
        <button class="btn-filled" id="download-selected" data-i18n="files.download"></button>
        <button class="ghost-btn" id="clear-selection" data-i18n="files.clear"></button>
      </div>
    </div>

    <!-- File list header row -->
    <div class="file-list-head" id="file-list-head">
      <span class="col-check"></span>
      <span class="col-name" data-i18n="files.col_name"></span>
      <span class="col-meta" data-i18n="files.col_meta"></span>
    </div>

    <!-- File listing -->
    <div class="file-list" id="file-list">
      <div class="file-list-empty" id="file-list-empty" data-i18n="files.loading"></div>
    </div>

    <!-- Drag-and-drop zone -->
    <div class="drop-zone" id="drop-zone" role="button" tabindex="0">
      <span data-i18n="files.drop_zone"></span>
    </div>
  </main>

  <!-- Toast notification -->
  <div class="toast" id="toast" role="status" aria-live="polite"></div>

  <script src="/webui/i18n.js"></script>
  <script>
    (function () {
      var i18n = window.FerryFileI18n;
      var portEl = document.getElementById('dateline-port');

      function renderPort() {
        var port = window.location.port || (window.location.protocol === 'https:' ? '443' : '80');
        portEl.textContent = i18n.t('common.dateline_port', { port: port });
      }

      renderPort();
      document.addEventListener('ferryfile-language-change', renderPort);
    })();
  </script>
  <script src="/webui/app.js"></script>
</body>
</html>
```

Every element `id` `app.js` looks up (`breadcrumb`, `file-list`, `file-list-empty`, `drop-zone`, `upload-input`, `root-hint`, `logout-btn`, `toast`, `progress-container`, `progress-filename`, `progress-pct`, `progress-fill`, `progress-details`, `selection-bar`, `selection-count`, `download-selected`, `clear-selection`, `upload-label`) is unchanged from today's file — only classes changed and the file-list-head/dateline markup was added around them.

- [ ] **Step 3: Visual check in a browser**

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile/app/src/main/assets/webui && python3 -m http.server 8902 &
sleep 1
```

Navigate to `http://localhost:8902/files.html` with the claude-in-chrome tool and screenshot it. `app.js` will fail its `fetch('/api/list?path=/')` call (no real server here) and the empty-state text will read "Failed to load folder contents" — that's expected and fine; the goal of this check is purely visual: paper background, serif headline "FerryFile", the header's dateline row showing "LOCAL NETWORK" / "PORT 8902", the file-list-head row with uppercase "NAME"/"SIZE · MODIFIED" over a hairline, the dashed drop zone. Toggle dark mode the same way as Task 7's check and confirm the dark ink cut. Kill the server after (`kill %1`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/webui/files.html app/src/main/assets/webui/i18n.js
git commit -m "$(cat <<'EOF'
Restyle the files page to Broadsheet direction 1g

Sticky dark header replaced with the dateline furniture (brand +
outline/ghost/danger buttons, then a static "Local network / Port"
line — see plan Deviations item 4 for why it isn't a live folder/item
count). File list gets a static header row (Name / Size · Modified).
Every id app.js looks up is unchanged.
EOF
)"
```

---

## Task 9: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Full Gradle test suite** — confirms the 115 existing unit tests (data/domain/server layers, untouched by this restyle) still pass:

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile && ./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no test failures.

- [ ] **Step 2: Full debug assembly** — catches any resource-linking issue (e.g. a font filename AAPT rejects) that a Kotlin-only compile wouldn't:

```bash
cd /Users/dimak21/AndroidStudioProjects/FerryFile && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and drive the app on a connected device/emulator.** Use the `run` skill (or `mobile` MCP tools directly if no project-specific run skill is found) to install the debug APK, launch it, and check, in **both English and Russian** (switch the device/emulator language, or use "Change app language" from the app's system settings entry) and **both Dark Theme positions** (Settings → Dark Theme):
  - Home, stopped: masthead, head pair with "LOCAL WI-FI · PORT —" / "STOPPED", headline "Server stopped" / "Сервер остановлен", stopped standfirst, no colored status dot anywhere.
  - Home, running (start the server from the UI): dateline right reads "RUNNING" in accent-700, address/PIN/rule/fingerprint block renders, standfirst reads the running copy.
  - Home, starting: tap Start and, during the brief starting window, confirm the 3dp progress rule under the head pair and the 45%-opacity button label.
  - Home, no shared folders (remove all folders in Settings first): the ruled notice block with "Notice"/"Важно" kicker and the "Add a folder →" link, which must navigate to Settings when tapped.
  - Home, no Wi-Fi (turn off Wi-Fi with the server running, or airplane mode): headline/standfirst in warning color, the 2dp ruled address/PIN block reading "unavailable".
  - Settings: head pair with no dateline row, the port field with caption-above styling and numeric keyboard, both segmented controls (HTTPS, Dark Theme) responding to taps and reflecting the persisted value after leaving and re-entering the screen, folder list with working Remove, outlined Add Folder button opening the SAF picker.
  - Confirm Russian labels don't clip or overlap anywhere (Settings' port field should visibly widen; Home's headline should visibly shrink to 40sp).

- [ ] **Step 4: Drive the web UI over the LAN.** With the server running (HTTP, to avoid a self-signed cert prompt) and the device and this machine on the same Wi-Fi, open `http://<device-ip>:<port>/login` in a real browser (or `adb reverse tcp:<port> tcp:<port>` and use `http://localhost:<port>/login` from this machine), enter the PIN shown on the Home screen, and confirm: dateline network/port line, PIN field styling, then on `/files`: dateline header, breadcrumb, file list header row, a directory row showing the trailing "/", uploading a file and watching the progress block render, selecting files and confirming the selection bar. Repeat with the browser's dark mode toggled.

- [ ] **Step 5: Record the result** — if everything in Steps 1–4 passes, this task needs no code changes and no commit. If any check fails, fix it as a normal edit within the relevant earlier task's file, re-run that task's compile/verification step, then re-run this task's Steps 1–4 before considering the branch done.
