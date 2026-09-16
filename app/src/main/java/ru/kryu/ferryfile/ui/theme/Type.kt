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
