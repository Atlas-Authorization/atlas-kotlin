package net.atlasauth.atlas

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/*
 * Mapping an AtlasDesign onto Jetpack Compose. Used by `AtlasSignIn` when a
 * design applies (a dashboard design was saved, or the app passed an
 * `AtlasAppearance`). With no design the composables keep their original
 * Material 3 defaults, untouched.
 */

/** A Compose colour from `#rrggbb`; transparent when not a hex colour. */
fun atlasColor(hex: String): Color = AtlasHex.rgb(hex)?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Transparent

/**
 * Everything a composable needs to draw one design in one colour mode.
 *
 * @param fonts font families the app provides, keyed by catalog id (`"inter"`,
 *   `"source-serif"`, …). A font not supplied falls back to the system family of
 *   the same category.
 */
class AtlasStyle(
    val design: AtlasDesign,
    systemIsDark: Boolean,
    private val fonts: Map<String, FontFamily> = emptyMap(),
) {
    val palette: AtlasPalette = design.activePalette(systemIsDark)
    val isDark: Boolean = design.usesDark(systemIsDark)

    fun color(slot: AtlasPaletteSlot): Color = atlasColor(palette[slot])

    fun family(id: String): FontFamily = fonts[id] ?: when (AtlasFontCatalog.category(id)) {
        AtlasFontCatalog.Category.SERIF -> FontFamily.Serif
        AtlasFontCatalog.Category.MONOSPACE -> FontFamily.Monospace
        AtlasFontCatalog.Category.SANS_SERIF -> FontFamily.SansSerif
    }

    private val t get() = design.typography
    val body: TextStyle
        get() = TextStyle(fontFamily = family(t.font), fontSize = t.baseSize.sp, fontWeight = FontWeight(t.bodyWeight), color = color(AtlasPaletteSlot.TEXT))
    val small: TextStyle
        get() = body.copy(fontSize = t.smallSize.sp, color = color(AtlasPaletteSlot.MUTED))
    val heading: TextStyle
        get() = TextStyle(fontFamily = family(t.headingFont), fontSize = t.headingSize.sp, fontWeight = FontWeight(t.headingWeight), color = color(AtlasPaletteSlot.TEXT))
    val button: TextStyle
        get() = body.copy(
            fontWeight = FontWeight(maxOf(500, t.bodyWeight)),
            letterSpacing = if (t.letterCase == AtlasLetterCase.UPPERCASE) 0.04.em else 0.em,
        )

    fun buttonLabel(label: String): String = if (t.letterCase == AtlasLetterCase.UPPERCASE) label.uppercase() else label

    val controlHeight get() = design.spacing.control.dp
    /** Control corner radius; a pill (999) becomes half the control height. */
    val controlShape get() = RoundedCornerShape(minOf(design.shape.controlRadius, design.spacing.control / 2).dp)
    val borderWidth get() = design.shape.borderWidth.dp
}

/** The card around the sign-in form: surface, radius, border, shadow, padding, width. */
@Composable
internal fun AtlasCard(style: AtlasStyle, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val d = style.design
    val shape = RoundedCornerShape(d.shape.radius.dp)
    val surface = when (d.card.style) {
        AtlasCardStyle.NONE -> Color.Transparent
        // No backdrop blur on Android's Compose; a translucent surface stands in.
        AtlasCardStyle.GLASS -> style.color(AtlasPaletteSlot.SURFACE).copy(alpha = if (style.isDark) 0.6f else 0.72f)
        else -> style.color(AtlasPaletteSlot.SURFACE)
    }
    var m = modifier.widthIn(max = d.cardMaxWidth.dp).fillMaxWidth()
    if (d.elevation > 0) m = m.shadow(d.elevation.dp, shape)
    m = m.clip(shape).background(surface, shape)
    if (d.cardBorderWidth > 0) m = m.border(d.cardBorderWidth.dp, style.color(AtlasPaletteSlot.BORDER), shape)
    Box(m.padding(if (d.card.style == AtlasCardStyle.NONE) 0.dp else d.spacing.card.dp)) { content() }
}

/** The primary call-to-action, in the design's button style (solid / outline / soft / gradient). */
@Composable
internal fun AtlasPrimaryButton(style: AtlasStyle, label: String, enabled: Boolean, onClick: () -> Unit) {
    val primary = style.color(AtlasPaletteSlot.PRIMARY)
    val shape = style.controlShape
    var m = Modifier.fillMaxWidth().heightIn(min = style.controlHeight).clip(shape)
    val textColor: Color
    when (style.design.buttonStyle) {
        AtlasButtonStyle.SOLID -> { m = m.background(primary, shape); textColor = style.color(AtlasPaletteSlot.PRIMARY_INK) }
        AtlasButtonStyle.OUTLINE -> {
            m = m.border(maxOf(1, style.design.shape.borderWidth).dp, primary, shape); textColor = primary
        }
        AtlasButtonStyle.SOFT -> { m = m.background(primary.copy(alpha = 0.14f), shape); textColor = primary }
        AtlasButtonStyle.GRADIENT -> {
            m = m.background(Brush.linearGradient(listOf(primary, style.color(AtlasPaletteSlot.ACCENT))), shape)
            textColor = style.color(AtlasPaletteSlot.PRIMARY_INK)
        }
    }
    Box(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = (style.design.spacing.unit * 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(style.buttonLabel(label), style = style.button.copy(color = if (enabled) textColor else textColor.copy(alpha = 0.6f)))
    }
}

/** A secondary (bordered, text-coloured) button — "email me a code", social buttons. */
@Composable
internal fun AtlasSecondaryButton(
    style: AtlasStyle,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val shape = style.controlShape
    Box(
        modifier.heightIn(min = style.controlHeight)
            .clip(shape)
            .background(style.color(AtlasPaletteSlot.SURFACE), shape)
            .border(maxOf(1, style.design.shape.borderWidth).dp, style.color(AtlasPaletteSlot.BORDER), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = (style.design.spacing.unit * 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            style.buttonLabel(label),
            style = style.small.copy(color = style.color(AtlasPaletteSlot.TEXT), fontWeight = FontWeight.Medium),
        )
    }
}

/** A labelled text input in the design's input style (outline / filled / underline). */
@Composable
internal fun AtlasTextInput(
    style: AtlasStyle,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val shape = style.controlShape
    val inputText = style.color(AtlasPaletteSlot.INPUT_TEXT)
    val pad = (style.design.spacing.unit * 3).dp
    Column(verticalArrangement = Arrangement.spacedBy((style.design.spacing.unit * 1.5).dp)) {
        Text(label, style = style.small.copy(color = style.color(AtlasPaletteSlot.TEXT)))
        var m = Modifier.fillMaxWidth().heightIn(min = style.controlHeight)
        m = when (style.design.inputStyle) {
            AtlasInputStyle.OUTLINE -> {
                var o = m.clip(shape).background(style.color(AtlasPaletteSlot.INPUT_BACKGROUND), shape)
                if (style.design.shape.borderWidth > 0) o = o.border(style.borderWidth, style.color(AtlasPaletteSlot.BORDER), shape)
                o.padding(horizontal = pad)
            }
            // Text colour washed 6% over the input background, no border (as on the web).
            AtlasInputStyle.FILLED -> m.clip(shape)
                .background(style.color(AtlasPaletteSlot.INPUT_BACKGROUND), shape)
                .background(style.color(AtlasPaletteSlot.TEXT).copy(alpha = 0.06f), shape)
                .padding(horizontal = pad)
            AtlasInputStyle.UNDERLINE -> m
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.body.copy(color = inputText),
            cursorBrush = SolidColor(style.color(AtlasPaletteSlot.PRIMARY)),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = m.semantics { contentDescription = label },
            decorationBox = { inner ->
                Column(Modifier.fillMaxWidth().heightIn(min = style.controlHeight), verticalArrangement = Arrangement.Center) {
                    inner()
                    if (style.design.inputStyle == AtlasInputStyle.UNDERLINE) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 6.dp)
                                .height(maxOf(1, style.design.shape.borderWidth).dp)
                                .background(style.color(AtlasPaletteSlot.BORDER)),
                        )
                    }
                }
            },
        )
    }
}

/**
 * Social sign-in buttons laid out per the design (`LIST` / `GRID` / `ICONS`). The
 * composable only reports the tapped provider id; start OAuth yourself with
 * [AtlasClient.oauthAuthorizeUrl] + a Custom Tab.
 */
@Composable
fun AtlasSocialButtons(
    providers: List<String>,
    onSelect: (String) -> Unit,
    design: AtlasDesign = AtlasDesign.DEFAULTS,
    systemIsDark: Boolean = false,
    fonts: Map<String, FontFamily> = emptyMap(),
) {
    val style = AtlasStyle(design, systemIsDark, fonts)
    val gap = (design.spacing.unit * 2).dp
    when (design.socialLayout) {
        AtlasSocialLayout.LIST -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            providers.forEach { id ->
                AtlasSecondaryButton(style, "Continue with ${AtlasProviderNames.name(id)}", enabled = true) { onSelect(id) }
            }
        }
        AtlasSocialLayout.GRID -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            providers.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { id ->
                        AtlasSecondaryButton(style, AtlasProviderNames.name(id), enabled = true, modifier = Modifier.weight(1f)) { onSelect(id) }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        AtlasSocialLayout.ICONS -> Row(
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            providers.forEach { id ->
                val name = AtlasProviderNames.name(id)
                AtlasSecondaryButton(
                    style,
                    name.take(1),
                    enabled = true,
                    modifier = Modifier.semantics { contentDescription = "Continue with $name" },
                ) { onSelect(id) }
            }
        }
    }
}

/** The design's logo (light or dark), loaded from its URL; nothing while loading or on failure. */
@Composable
internal fun AtlasLogo(url: String, height: Int) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(height.dp))
        }
    }
}
