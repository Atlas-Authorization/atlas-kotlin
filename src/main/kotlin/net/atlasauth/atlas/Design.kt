package net.atlasauth.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The dashboard design, as a native app sees it.
 *
 * `GET /v1/appearance` returns `design` — `null` until the app owner saves a
 * design in the dashboard — whose `resolved` member is the fully-resolved design
 * the hosted pages and web components render. This file is the platform-neutral
 * half: the model, a TOLERANT decoder (unknown keys ignored; missing, mistyped or
 * out-of-range values fall back to the Atlas defaults), the code-level override
 * ([AtlasAppearance]) and the precedence rules. `DesignCompose.kt` maps the
 * result onto Jetpack Compose.
 */

/** A closed design vocabulary: [wire] is the value the API sends. */
interface AtlasDesignToken { val wire: String }

private inline fun <reified E> parseToken(value: String?): E? where E : Enum<E>, E : AtlasDesignToken =
    enumValues<E>().firstOrNull { it.wire == value }

/** `AUTO` follows the system dark-mode setting; `LIGHT` / `DARK` force one. */
enum class AtlasColorScheme(override val wire: String) : AtlasDesignToken { AUTO("auto"), LIGHT("light"), DARK("dark") }
enum class AtlasLetterCase(override val wire: String) : AtlasDesignToken { NONE("none"), UPPERCASE("uppercase") }
enum class AtlasCardShadow(override val wire: String) : AtlasDesignToken { NONE("none"), SM("sm"), MD("md"), LG("lg"), XL("xl") }
enum class AtlasDensity(override val wire: String) : AtlasDesignToken { COMPACT("compact"), COMFORTABLE("comfortable"), SPACIOUS("spacious") }
enum class AtlasCardStyle(override val wire: String) : AtlasDesignToken { SOLID("solid"), GLASS("glass"), OUTLINE("outline"), NONE("none") }
enum class AtlasCardWidth(override val wire: String) : AtlasDesignToken { NARROW("narrow"), NORMAL("normal"), WIDE("wide") }
enum class AtlasLogoSize(override val wire: String) : AtlasDesignToken { SM("sm"), MD("md"), LG("lg") }
enum class AtlasButtonStyle(override val wire: String) : AtlasDesignToken { SOLID("solid"), OUTLINE("outline"), SOFT("soft"), GRADIENT("gradient") }
enum class AtlasSocialLayout(override val wire: String) : AtlasDesignToken { LIST("list"), GRID("grid"), ICONS("icons") }
enum class AtlasInputStyle(override val wire: String) : AtlasDesignToken { OUTLINE("outline"), FILLED("filled"), UNDERLINE("underline") }

/** The twelve palette slots, in the dashboard editor's order. */
enum class AtlasPaletteSlot(override val wire: String) : AtlasDesignToken {
    PRIMARY("primary"), PRIMARY_INK("primaryInk"), ACCENT("accent"), BACKGROUND("background"),
    SURFACE("surface"), TEXT("text"), MUTED("muted"), BORDER("border"), DANGER("danger"),
    SUCCESS("success"), INPUT_BACKGROUND("inputBackground"), INPUT_TEXT("inputText");

    internal companion object {
        /** Brand slots set in the light palette carry into dark mode (as on the web). */
        val BRAND = listOf(PRIMARY, ACCENT, DANGER, SUCCESS)
    }
}

internal object AtlasHex {
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")
    fun isValid(s: String?): Boolean = s != null && HEX.matches(s)

    /** `0xRRGGBB`, or null when not a `#rrggbb` string. */
    fun rgb(s: String?): Int? = if (isValid(s)) s!!.substring(1).toInt(16) else null

    /** Near-black or white, whichever reads on [hex] (the WCAG 0.179 split). */
    fun readableOn(hex: String): String {
        val n = rgb(hex) ?: return "#ffffff"
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        val l = 0.2126 * lin((n shr 16) and 0xff) + 0.7152 * lin((n shr 8) and 0xff) + 0.0722 * lin(n and 0xff)
        return if (l > 0.179) "#111827" else "#ffffff"
    }
}

/** One colour mode's palette: every slot is a lower-case `#rrggbb` string. */
data class AtlasPalette(val colors: Map<AtlasPaletteSlot, String>) {
    operator fun get(slot: AtlasPaletteSlot): String = colors[slot] ?: "#000000"

    /** A copy with the valid `#rrggbb` entries of [over] applied. */
    fun with(over: Map<AtlasPaletteSlot, String>): AtlasPalette =
        AtlasPalette(colors + over.filterValues { AtlasHex.isValid(it) }.mapValues { it.value.lowercase() })

    internal companion object {
        fun of(spec: String): AtlasPalette =
            AtlasPalette(AtlasPaletteSlot.values().zip(spec.split(" ")).toMap())

        fun decode(element: JsonElement?, fallback: AtlasPalette): AtlasPalette {
            val obj = element as? JsonObject ?: return fallback
            val over = AtlasPaletteSlot.values().mapNotNull { slot ->
                (obj[slot.wire] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { slot to it }
            }.toMap()
            return fallback.with(over)
        }
    }
}

/**
 * The curated font catalog (ids → family names), mirroring the dashboard's list.
 * Android cannot look a font up by family name, so the app supplies bundled (or
 * downloadable) families by catalog id — see `AtlasSignIn(fonts = …)`; anything
 * not supplied falls back to the system font of the same [Category].
 */
object AtlasFontCatalog {
    enum class Category { SANS_SERIF, SERIF, MONOSPACE }

    val families: Map<String, Pair<String?, Category>> = mapOf(
        "system" to (null to Category.SANS_SERIF),
        "system-serif" to (null to Category.SERIF),
        "system-mono" to (null to Category.MONOSPACE),
        "inter" to ("Inter" to Category.SANS_SERIF),
        "roboto" to ("Roboto" to Category.SANS_SERIF),
        "open-sans" to ("Open Sans" to Category.SANS_SERIF),
        "lato" to ("Lato" to Category.SANS_SERIF),
        "montserrat" to ("Montserrat" to Category.SANS_SERIF),
        "poppins" to ("Poppins" to Category.SANS_SERIF),
        "nunito" to ("Nunito" to Category.SANS_SERIF),
        "work-sans" to ("Work Sans" to Category.SANS_SERIF),
        "dm-sans" to ("DM Sans" to Category.SANS_SERIF),
        "manrope" to ("Manrope" to Category.SANS_SERIF),
        "source-serif" to ("Source Serif 4" to Category.SERIF),
        "merriweather" to ("Merriweather" to Category.SERIF),
        "playfair-display" to ("Playfair Display" to Category.SERIF),
        "jetbrains-mono" to ("JetBrains Mono" to Category.MONOSPACE),
        "ibm-plex-mono" to ("IBM Plex Mono" to Category.MONOSPACE),
    )

    fun family(id: String): String? = families[id]?.first
    fun category(id: String): Category = families[id]?.second ?: Category.SANS_SERIF
}

internal val TYPE_SCALES: Map<String, Double> = mapOf(
    "minor-second" to 1.067, "major-second" to 1.125, "minor-third" to 1.2, "major-third" to 1.25,
    "perfect-fourth" to 1.333, "augmented-fourth" to 1.414, "perfect-fifth" to 1.5, "golden" to 1.618,
    "minor-seventh" to 1.778, "octave" to 2.0,
)

/** A fully-resolved design: every token has a value. */
data class AtlasDesign(
    val colorScheme: AtlasColorScheme,
    val palette: AtlasPalette,
    val paletteDark: AtlasPalette,
    val typography: Typography,
    val shape: Shape,
    val cardShadow: AtlasCardShadow,
    val density: AtlasDensity,
    val card: Card,
    val logoSize: AtlasLogoSize,
    val buttonStyle: AtlasButtonStyle,
    val socialLayout: AtlasSocialLayout,
    val inputStyle: AtlasInputStyle,
    val logoUrl: String? = null,
    val logoDarkUrl: String? = null,
) {
    data class Typography(
        val font: String,
        val headingFont: String,
        /** Body text size in sp (12…20). */
        val baseSize: Int,
        val headingWeight: Int,
        val bodyWeight: Int,
        /** Letter case of BUTTON labels. */
        val letterCase: AtlasLetterCase,
        val scale: String,
    ) {
        /** Heading size = base × the type-scale ratio. */
        val headingSize: Double get() = baseSize * (TYPE_SCALES[scale] ?: 1.333)
        val smallSize: Double get() = (baseSize * 0.867 * 10).roundToInt() / 10.0
    }

    data class Shape(
        /** Card corner radius (0…32). */
        val radius: Int,
        /** Button + input corner radius; 999 = pill. */
        val controlRadius: Int,
        val borderWidth: Int,
    ) {
        val isPill: Boolean get() = controlRadius >= 999
    }

    data class Card(val style: AtlasCardStyle, val blur: Int, val width: AtlasCardWidth)

    /** Density spacing in dp: base unit, card padding, row gap, control height. */
    data class Spacing(val unit: Int, val card: Int, val gap: Int, val control: Int)

    /** A card shadow: [opacity], blur [radius] and [y] offset (dp). */
    data class Shadow(val opacity: Float, val radius: Int, val y: Int)

    /** Whether the dark palette applies, given the system setting. */
    fun usesDark(systemIsDark: Boolean): Boolean = when (colorScheme) {
        AtlasColorScheme.DARK -> true
        AtlasColorScheme.LIGHT -> false
        AtlasColorScheme.AUTO -> systemIsDark
    }

    /** The palette for the current system setting (honouring a forced scheme). */
    fun activePalette(systemIsDark: Boolean): AtlasPalette = if (usesDark(systemIsDark)) paletteDark else palette

    /** The logo for the current mode: the dark upload in dark mode, else the light one. */
    fun logo(systemIsDark: Boolean): String? =
        if (usesDark(systemIsDark)) logoDarkUrl ?: logoUrl else logoUrl ?: logoDarkUrl

    val spacing: Spacing
        get() = when (density) {
            AtlasDensity.COMPACT -> Spacing(3, 20, 10, 36)
            AtlasDensity.COMFORTABLE -> Spacing(4, 28, 14, 40)
            AtlasDensity.SPACIOUS -> Spacing(5, 36, 18, 46)
        }

    val cardMaxWidth: Int
        get() = when (card.width) { AtlasCardWidth.NARROW -> 340; AtlasCardWidth.NORMAL -> 380; AtlasCardWidth.WIDE -> 460 }

    val logoHeight: Int
        get() = when (logoSize) { AtlasLogoSize.SM -> 32; AtlasLogoSize.MD -> 48; AtlasLogoSize.LG -> 64 }

    /** The card's shadow; null for none. An outline / none card never casts one. */
    val shadow: Shadow?
        get() {
            if (card.style == AtlasCardStyle.OUTLINE || card.style == AtlasCardStyle.NONE) return null
            return when (cardShadow) {
                AtlasCardShadow.NONE -> null
                AtlasCardShadow.SM -> Shadow(0.05f, 2, 1)
                AtlasCardShadow.MD -> Shadow(0.12f, 40, 12)
                AtlasCardShadow.LG -> Shadow(0.14f, 48, 16)
                AtlasCardShadow.XL -> Shadow(0.24f, 80, 32)
            }
        }

    /** Compose elevation (dp) approximating [shadow]. */
    val elevation: Int
        get() = when (shadow?.let { cardShadow }) {
            AtlasCardShadow.SM -> 1
            AtlasCardShadow.MD -> 6
            AtlasCardShadow.LG -> 10
            AtlasCardShadow.XL -> 18
            else -> 0
        }

    /** The card border: outline cards always get one, none cards never do. */
    val cardBorderWidth: Int
        get() = when (card.style) {
            AtlasCardStyle.NONE -> 0
            AtlasCardStyle.OUTLINE -> maxOf(1, shape.borderWidth)
            else -> shape.borderWidth
        }

    companion object {
        /** The Atlas baseline (the `minimal` preset) — what a missing key resolves to. */
        val DEFAULTS = AtlasDesign(
            colorScheme = AtlasColorScheme.AUTO,
            palette = AtlasPalette.of("#18181b #ffffff #4f46e5 #fafafa #ffffff #18181b #71717a #e4e4e7 #dc2626 #15803d #ffffff #18181b"),
            paletteDark = AtlasPalette.of("#fafafa #18181b #818cf8 #09090b #18181b #fafafa #a1a1aa #3f3f46 #f87171 #4ade80 #09090b #fafafa"),
            typography = Typography("inter", "inter", 15, 600, 400, AtlasLetterCase.NONE, "perfect-fourth"),
            shape = Shape(radius = 12, controlRadius = 8, borderWidth = 1),
            cardShadow = AtlasCardShadow.MD,
            density = AtlasDensity.COMFORTABLE,
            card = Card(AtlasCardStyle.SOLID, 16, AtlasCardWidth.NORMAL),
            logoSize = AtlasLogoSize.MD,
            buttonStyle = AtlasButtonStyle.SOLID,
            socialLayout = AtlasSocialLayout.LIST,
            inputStyle = AtlasInputStyle.OUTLINE,
        )

        /**
         * Decode `design.resolved` TOLERANTLY: unknown keys are ignored, and a
         * missing, mistyped or out-of-range value falls back to [DEFAULTS] — a
         * newer server never breaks an older app.
         */
        fun fromResolved(resolved: JsonObject, logoUrl: String? = null, logoDarkUrl: String? = null): AtlasDesign {
            val d = DEFAULTS
            fun section(key: String): JsonObject = resolved[key] as? JsonObject ?: JsonObject(emptyMap())
            fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun int(o: JsonObject, key: String, range: IntRange, dflt: Int): Int {
                val p = o[key] as? JsonPrimitive ?: return dflt
                if (p.isString) return dflt
                val v = p.doubleOrNull ?: return dflt
                val i = v.toInt()
                return if (v == i.toDouble() && i in range) i else dflt
            }
            fun font(o: JsonObject, key: String, dflt: String): String =
                str(o, key)?.takeIf { AtlasFontCatalog.families.containsKey(it) } ?: dflt

            val t = section("typography")
            val bodyFont = font(t, "font", d.typography.font)
            // A design that sets `font` but no `headingFont` uses it for headings too.
            val headingFont = font(t, "headingFont", if (t.containsKey("font")) bodyFont else d.typography.headingFont)
            val s = section("shape")
            val c = section("card")
            val b = section("buttons")
            return AtlasDesign(
                colorScheme = parseToken<AtlasColorScheme>(str(resolved, "colorScheme")) ?: d.colorScheme,
                palette = AtlasPalette.decode(resolved["palette"], d.palette),
                paletteDark = AtlasPalette.decode(resolved["paletteDark"], d.paletteDark),
                typography = Typography(
                    font = bodyFont,
                    headingFont = headingFont,
                    baseSize = int(t, "baseSize", 12..20, d.typography.baseSize),
                    headingWeight = int(t, "headingWeight", 300..900, d.typography.headingWeight),
                    bodyWeight = int(t, "bodyWeight", 100..900, d.typography.bodyWeight),
                    letterCase = parseToken<AtlasLetterCase>(str(t, "letterCase")) ?: d.typography.letterCase,
                    scale = str(t, "scale")?.takeIf { TYPE_SCALES.containsKey(it) } ?: d.typography.scale,
                ),
                shape = Shape(
                    radius = int(s, "radius", 0..32, d.shape.radius),
                    controlRadius = int(s, "controlRadius", 0..999, d.shape.controlRadius),
                    borderWidth = int(s, "borderWidth", 0..3, d.shape.borderWidth),
                ),
                cardShadow = parseToken<AtlasCardShadow>(str(section("elevation"), "cardShadow")) ?: d.cardShadow,
                density = parseToken<AtlasDensity>(str(section("spacing"), "density")) ?: d.density,
                card = Card(
                    style = parseToken<AtlasCardStyle>(str(c, "style")) ?: d.card.style,
                    blur = int(c, "blur", 0..40, d.card.blur),
                    width = parseToken<AtlasCardWidth>(str(c, "width")) ?: d.card.width,
                ),
                logoSize = parseToken<AtlasLogoSize>(str(section("layout"), "logoSize")) ?: d.logoSize,
                buttonStyle = parseToken<AtlasButtonStyle>(str(b, "style")) ?: d.buttonStyle,
                socialLayout = parseToken<AtlasSocialLayout>(str(b, "socialLayout")) ?: d.socialLayout,
                inputStyle = parseToken<AtlasInputStyle>(str(section("inputs"), "style")) ?: d.inputStyle,
                logoUrl = logoUrl,
                logoDarkUrl = logoDarkUrl,
            )
        }

        /** Decode the whole `design` member of `GET /v1/appearance`; null for `null`. */
        fun fromBundle(bundle: JsonElement?): AtlasDesign? {
            val obj = bundle as? JsonObject ?: return null
            fun url(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return fromResolved(obj["resolved"] as? JsonObject ?: JsonObject(emptyMap()), url("logo_url"), url("logo_dark_url"))
        }

        /**
         * The design the native UI renders. Precedence, lowest first: Atlas
         * defaults < the dashboard design < the code-level [appearance].
         *
         * Returns null — "render exactly the pre-design look" — when there is no
         * dashboard design (or [AtlasAppearance.applyDashboardDesign] is false)
         * AND no code override.
         */
        fun resolve(dashboard: AtlasDesign?, appearance: AtlasAppearance?): AtlasDesign? {
            val base = if (appearance?.applyDashboardDesign == false) null else dashboard
            if (appearance == null || appearance.isEmpty) return base
            return appearance.applyTo(base ?: DEFAULTS)
        }
    }
}

/**
 * Code-level design overrides — the same tokens as the dashboard's design editor.
 * Every field is optional; a set field wins over the dashboard design.
 *
 * ```
 * AtlasSignIn(
 *     client = atlas,
 *     onSignedIn = { … },
 *     appearance = AtlasAppearance(
 *         palette = mapOf(AtlasPaletteSlot.PRIMARY to "#0f766e"),
 *         controlRadius = 999,          // pill buttons + inputs
 *         buttonStyle = AtlasButtonStyle.SOFT,
 *     ),
 * )
 * ```
 *
 * @param applyDashboardDesign apply the design saved in the Atlas dashboard
 *   (default true); false = only these overrides, over the Atlas defaults.
 * @param palette light-mode slot overrides (`#rrggbb`). Brand slots (primary,
 *   accent, danger, success) also apply in dark mode unless [paletteDark] sets them.
 * @param font a font catalog id (`inter`, `source-serif`, `system-mono`, …).
 */
data class AtlasAppearance(
    val applyDashboardDesign: Boolean = true,
    val colorScheme: AtlasColorScheme? = null,
    val palette: Map<AtlasPaletteSlot, String> = emptyMap(),
    val paletteDark: Map<AtlasPaletteSlot, String> = emptyMap(),
    val font: String? = null,
    val headingFont: String? = null,
    val baseSize: Int? = null,
    val headingWeight: Int? = null,
    val bodyWeight: Int? = null,
    val letterCase: AtlasLetterCase? = null,
    val scale: String? = null,
    val radius: Int? = null,
    val controlRadius: Int? = null,
    val borderWidth: Int? = null,
    val cardShadow: AtlasCardShadow? = null,
    val density: AtlasDensity? = null,
    val cardStyle: AtlasCardStyle? = null,
    val cardWidth: AtlasCardWidth? = null,
    val logoSize: AtlasLogoSize? = null,
    val buttonStyle: AtlasButtonStyle? = null,
    val socialLayout: AtlasSocialLayout? = null,
    val inputStyle: AtlasInputStyle? = null,
    val logoUrl: String? = null,
    val logoDarkUrl: String? = null,
) {
    /** True when no token is overridden ([applyDashboardDesign] is not a token). */
    val isEmpty: Boolean
        get() = this == AtlasAppearance(applyDashboardDesign = applyDashboardDesign)

    internal fun applyTo(base: AtlasDesign): AtlasDesign {
        val light = palette.filterValues { AtlasHex.isValid(it) }
        val darkOver = paletteDark.filterValues { AtlasHex.isValid(it) }.toMutableMap()
        val lightOver = light.toMutableMap()
        light[AtlasPaletteSlot.PRIMARY]?.let { p ->
            if (!light.containsKey(AtlasPaletteSlot.PRIMARY_INK)) lightOver[AtlasPaletteSlot.PRIMARY_INK] = AtlasHex.readableOn(p)
        }
        for (slot in AtlasPaletteSlot.BRAND) {
            if (!darkOver.containsKey(slot)) light[slot]?.let { darkOver[slot] = it }
        }
        darkOver[AtlasPaletteSlot.PRIMARY]?.let { p ->
            if (!darkOver.containsKey(AtlasPaletteSlot.PRIMARY_INK)) {
                darkOver[AtlasPaletteSlot.PRIMARY_INK] = light[AtlasPaletteSlot.PRIMARY_INK] ?: AtlasHex.readableOn(p)
            }
        }
        val t = base.typography
        val validFont = font?.takeIf { AtlasFontCatalog.families.containsKey(it) }
        return base.copy(
            colorScheme = colorScheme ?: base.colorScheme,
            palette = base.palette.with(lightOver),
            paletteDark = base.paletteDark.with(darkOver),
            typography = t.copy(
                font = validFont ?: t.font,
                headingFont = headingFont?.takeIf { AtlasFontCatalog.families.containsKey(it) } ?: validFont ?: t.headingFont,
                baseSize = baseSize?.takeIf { it in 12..20 } ?: t.baseSize,
                headingWeight = headingWeight?.takeIf { it in 300..900 } ?: t.headingWeight,
                bodyWeight = bodyWeight?.takeIf { it in 100..900 } ?: t.bodyWeight,
                letterCase = letterCase ?: t.letterCase,
                scale = scale?.takeIf { TYPE_SCALES.containsKey(it) } ?: t.scale,
            ),
            shape = base.shape.copy(
                radius = radius?.takeIf { it in 0..32 } ?: base.shape.radius,
                controlRadius = controlRadius?.takeIf { it in 0..999 } ?: base.shape.controlRadius,
                borderWidth = borderWidth?.takeIf { it in 0..3 } ?: base.shape.borderWidth,
            ),
            cardShadow = cardShadow ?: base.cardShadow,
            density = density ?: base.density,
            card = base.card.copy(style = cardStyle ?: base.card.style, width = cardWidth ?: base.card.width),
            logoSize = logoSize ?: base.logoSize,
            buttonStyle = buttonStyle ?: base.buttonStyle,
            socialLayout = socialLayout ?: base.socialLayout,
            inputStyle = inputStyle ?: base.inputStyle,
            logoUrl = logoUrl ?: base.logoUrl,
            logoDarkUrl = logoDarkUrl ?: base.logoDarkUrl,
        )
    }
}

/** The public appearance of an instance (`GET /v1/appearance`), as far as the native UI uses it. */
data class AtlasAppearanceInfo(
    /** The dashboard design, or null when none has been saved. */
    val design: AtlasDesign?,
    /** Enabled social provider ids (`google`, `github`, …). */
    val providers: List<String> = emptyList(),
    val applicationName: String? = null,
) {
    companion object {
        /** Decode a `GET /v1/appearance` body tolerantly. */
        fun fromJson(body: JsonObject): AtlasAppearanceInfo = AtlasAppearanceInfo(
            design = AtlasDesign.fromBundle(body["design"]?.takeIf { it !is JsonNull }),
            providers = (body["providers"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList(),
            applicationName = ((body["appearance"] as? JsonObject)?.get("applicationName") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content,
        )
    }
}

/**
 * The instance's public appearance (`GET /v1/appearance`): the dashboard design
 * (null until one is saved) and the enabled social providers.
 *
 * @param organization optional org id/slug to theme to that org's brand.
 */
suspend fun AtlasClient.appearance(organization: String? = null): AtlasAppearanceInfo {
    val path = if (organization == null) "/v1/appearance"
    else "/v1/appearance?org=" + URLEncoder.encode(organization, "UTF-8").replace("+", "%20")
    val response = requestRaw("GET", path, bodyJson = null, authenticated = false)
    val obj = try {
        Json.parseToJsonElement(response.body) as? JsonObject
    } catch (_: Throwable) {
        null
    } ?: throw AtlasException.Decoding("Could not decode the appearance response.")
    return AtlasAppearanceInfo.fromJson(obj)
}

/** Display names for provider ids. */
object AtlasProviderNames {
    private val KNOWN = mapOf(
        "github" to "GitHub", "gitlab" to "GitLab", "linkedin" to "LinkedIn", "linkedin_oidc" to "LinkedIn",
        "x" to "X", "twitter" to "X", "tiktok" to "TikTok", "hubspot" to "HubSpot",
        "huggingface" to "Hugging Face",
        "cognito" to "AWS Cognito",
        "paybin" to "Paybin",
        "railway" to "Railway",
    )

    fun name(id: String): String = KNOWN[id] ?: id.split('_', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
}
