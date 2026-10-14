package net.atlasauth.atlas

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DesignTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val fullResolved = """
        {"version":1,"preset":"midnight","colorScheme":"dark",
         "palette":{"primary":"#0F766E","primaryInk":"#ffffff","accent":"#f59e0b","background":"#f8fafc","surface":"#ffffff","text":"#0f172a","muted":"#64748b","border":"#e2e8f0","danger":"#b91c1c","success":"#15803d","inputBackground":"#ffffff","inputText":"#0f172a"},
         "paletteDark":{"primary":"#2dd4bf","primaryInk":"#042f2e","accent":"#fbbf24","background":"#020617","surface":"#0f172a","text":"#f8fafc","muted":"#94a3b8","border":"#1e293b","danger":"#f87171","success":"#4ade80","inputBackground":"#020617","inputText":"#f8fafc"},
         "typography":{"font":"source-serif","headingFont":"playfair-display","baseSize":17,"headingWeight":700,"bodyWeight":300,"letterCase":"uppercase","scale":"golden"},
         "shape":{"radius":20,"controlRadius":999,"borderWidth":2},
         "elevation":{"cardShadow":"xl"},"spacing":{"density":"spacious"},
         "card":{"style":"glass","blur":24,"width":"wide"},
         "layout":{"position":"split","logoSize":"lg"},
         "buttons":{"style":"gradient","socialLayout":"icons"},"inputs":{"style":"underline"},
         "background":{},"backgroundDark":{},"logo":{}}
    """.trimIndent()

    // ---- decoding ----

    @Test
    fun decodesAFullResolvedDesign() {
        val d = AtlasDesign.fromResolved(obj(fullResolved))
        assertEquals(AtlasColorScheme.DARK, d.colorScheme)
        assertEquals("#0f766e", d.palette[AtlasPaletteSlot.PRIMARY])
        assertEquals("#042f2e", d.paletteDark[AtlasPaletteSlot.PRIMARY_INK])
        assertEquals("source-serif", d.typography.font)
        assertEquals("playfair-display", d.typography.headingFont)
        assertEquals(17, d.typography.baseSize)
        assertEquals(700, d.typography.headingWeight)
        assertEquals(300, d.typography.bodyWeight)
        assertEquals(AtlasLetterCase.UPPERCASE, d.typography.letterCase)
        assertEquals(17 * 1.618, d.typography.headingSize, 0.001)
        assertEquals(20, d.shape.radius)
        assertTrue(d.shape.isPill)
        assertEquals(2, d.shape.borderWidth)
        assertEquals(AtlasCardShadow.XL, d.cardShadow)
        assertEquals(46, d.spacing.control)
        assertEquals(AtlasCardStyle.GLASS, d.card.style)
        assertEquals(460, d.cardMaxWidth)
        assertEquals(64, d.logoHeight)
        assertEquals(AtlasButtonStyle.GRADIENT, d.buttonStyle)
        assertEquals(AtlasSocialLayout.ICONS, d.socialLayout)
        assertEquals(AtlasInputStyle.UNDERLINE, d.inputStyle)
    }

    @Test
    fun partialDesignFillsDefaultsAndIgnoresUnknownKeys() {
        val d = AtlasDesign.fromResolved(
            obj(
                """{"palette":{"primary":"#123456","bogus":"#ffffff"},
                   "typography":{"font":"roboto","baseSize":99,"letterCase":"shouty"},
                   "shape":{"radius":"12px"},"buttons":{"style":"neon"},"futureSection":{"x":1}}""",
            ),
        )
        val defaults = AtlasDesign.DEFAULTS
        assertEquals("#123456", d.palette[AtlasPaletteSlot.PRIMARY])
        assertEquals(defaults.palette[AtlasPaletteSlot.SURFACE], d.palette[AtlasPaletteSlot.SURFACE])
        assertEquals(defaults.paletteDark, d.paletteDark)
        assertEquals("roboto", d.typography.font)
        assertEquals("roboto", d.typography.headingFont)
        assertEquals(15, d.typography.baseSize)
        assertEquals(AtlasLetterCase.NONE, d.typography.letterCase)
        assertEquals(12, d.shape.radius)
        assertEquals(AtlasButtonStyle.SOLID, d.buttonStyle)
    }

    @Test
    fun emptyResolvedIsTheDefaults() {
        assertEquals(AtlasDesign.DEFAULTS, AtlasDesign.fromResolved(JsonObject(emptyMap())))
    }

    @Test
    fun nullDesignDecodesToNull() {
        val info = AtlasAppearanceInfo.fromJson(obj("""{"object":"appearance","design":null,"providers":["google","github"]}"""))
        assertNull(info.design)
        assertEquals(listOf("google", "github"), info.providers)
        assertNull(AtlasAppearanceInfo.fromJson(obj("""{"object":"appearance"}""")).design)
    }

    @Test
    fun bundleCarriesLogoUrls() {
        val d = AtlasDesign.fromBundle(
            obj("""{"resolved":{},"logo_url":"https://cdn.example.com/l.png","logo_dark_url":"https://cdn.example.com/d.png"}"""),
        )!!
        assertEquals("https://cdn.example.com/l.png", d.logo(systemIsDark = false))
        assertEquals("https://cdn.example.com/d.png", d.logo(systemIsDark = true))
    }

    // ---- colours + dark mode ----

    @Test
    fun autoSchemeFollowsTheSystemAndForcedSchemesDoNot() {
        val d = AtlasDesign.fromResolved(obj("""{"palette":{"primary":"#111111"},"paletteDark":{"primary":"#eeeeee"}}"""))
        assertEquals("#111111", d.activePalette(systemIsDark = false)[AtlasPaletteSlot.PRIMARY])
        assertEquals("#eeeeee", d.activePalette(systemIsDark = true)[AtlasPaletteSlot.PRIMARY])
        assertEquals(d.paletteDark, d.copy(colorScheme = AtlasColorScheme.DARK).activePalette(systemIsDark = false))
        assertEquals(d.palette, d.copy(colorScheme = AtlasColorScheme.LIGHT).activePalette(systemIsDark = true))
    }

    @Test
    fun hexMapsToComposeColors() {
        assertEquals(Color(0xFFFF8000), atlasColor("#ff8000"))
        assertEquals(Color.Transparent, atlasColor("orange"))
        val dark = AtlasStyle(AtlasDesign.DEFAULTS, systemIsDark = true)
        assertEquals(atlasColor("#09090b"), dark.color(AtlasPaletteSlot.BACKGROUND))
        assertEquals("#111827", AtlasHex.readableOn("#ffffff"))
        assertEquals("#ffffff", AtlasHex.readableOn("#18181b"))
    }

    @Test
    fun shadowAndBorderFollowCardStyle() {
        val d = AtlasDesign.DEFAULTS
        assertEquals(12, d.shadow?.y)
        assertNull(d.copy(card = d.card.copy(style = AtlasCardStyle.OUTLINE)).shadow)
        assertEquals(1, d.copy(card = d.card.copy(style = AtlasCardStyle.OUTLINE)).cardBorderWidth)
        assertEquals(0, d.copy(card = d.card.copy(style = AtlasCardStyle.NONE)).cardBorderWidth)
    }

    @Test
    fun fontCatalog() {
        assertEquals("Source Serif 4", AtlasFontCatalog.family("source-serif"))
        assertNull(AtlasFontCatalog.family("system"))
        assertEquals(AtlasFontCatalog.Category.MONOSPACE, AtlasFontCatalog.category("jetbrains-mono"))
    }

    // ---- precedence ----

    @Test
    fun noDesignAndNoOverrideMeansTheUnchangedLook() {
        assertNull(AtlasDesign.resolve(null, null))
        assertNull(AtlasDesign.resolve(null, AtlasAppearance()))
    }

    @Test
    fun dashboardDesignAppliesWithoutAnOverride() {
        val dash = AtlasDesign.fromResolved(obj(fullResolved))
        assertEquals(dash, AtlasDesign.resolve(dash, null))
        assertEquals(dash, AtlasDesign.resolve(dash, AtlasAppearance()))
    }

    @Test
    fun codeOverrideWinsOverTheDashboard() {
        val dash = AtlasDesign.fromResolved(obj(fullResolved))
        val d = AtlasDesign.resolve(
            dash,
            AtlasAppearance(
                palette = mapOf(AtlasPaletteSlot.PRIMARY to "#FF0000"),
                controlRadius = 4,
                buttonStyle = AtlasButtonStyle.OUTLINE,
                colorScheme = AtlasColorScheme.LIGHT,
            ),
        )!!
        assertEquals("#ff0000", d.palette[AtlasPaletteSlot.PRIMARY])
        assertEquals("#111827", d.palette[AtlasPaletteSlot.PRIMARY_INK])
        assertEquals("#ff0000", d.paletteDark[AtlasPaletteSlot.PRIMARY])
        assertEquals(4, d.shape.controlRadius)
        assertEquals(AtlasButtonStyle.OUTLINE, d.buttonStyle)
        assertEquals(AtlasColorScheme.LIGHT, d.colorScheme)
        assertEquals("source-serif", d.typography.font)
        assertEquals(AtlasInputStyle.UNDERLINE, d.inputStyle)
    }

    @Test
    fun overrideAloneLayersOverTheDefaultsAndOptOutWorks() {
        val d = AtlasDesign.resolve(null, AtlasAppearance(density = AtlasDensity.COMPACT))!!
        assertEquals(AtlasDensity.COMPACT, d.density)
        assertEquals(AtlasDesign.DEFAULTS.palette, d.palette)

        val dash = AtlasDesign.fromResolved(obj(fullResolved))
        assertNull(AtlasDesign.resolve(dash, AtlasAppearance(applyDashboardDesign = false)))
        assertEquals(
            AtlasDesign.DEFAULTS.palette,
            AtlasDesign.resolve(dash, AtlasAppearance(applyDashboardDesign = false, inputStyle = AtlasInputStyle.FILLED))!!.palette,
        )
    }

    @Test
    fun invalidOverrideValuesAreIgnored() {
        val d = AtlasDesign.resolve(
            null,
            AtlasAppearance(palette = mapOf(AtlasPaletteSlot.PRIMARY to "blue"), baseSize = 400, font = "comic-sans"),
        )!!
        assertEquals(AtlasDesign.DEFAULTS.palette[AtlasPaletteSlot.PRIMARY], d.palette[AtlasPaletteSlot.PRIMARY])
        assertEquals(15, d.typography.baseSize)
        assertEquals("inter", d.typography.font)
    }

    // ---- fetching ----

    private fun makeClient(): AtlasClient = AtlasClient(
        publishableKey = "pk_test_123",
        frontendApi = server.url("/").toString(),
        tokenStore = InMemoryTokenStore(),
        httpClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
    )

    @Test
    fun fetchesAppearanceWithThePublishableKey() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"appearance","design":{"resolved":{"buttons":{"style":"soft"}},"logo_url":null},"providers":["google"]}"""),
        )
        val info = makeClient().appearance(organization = "acme co")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/appearance?org=acme%20co", request.path)
        assertEquals("pk_test_123", request.getHeader("x-publishable-key"))
        assertEquals(AtlasButtonStyle.SOFT, info.design?.buttonStyle)
        assertNull(info.design?.logoUrl)
        assertEquals(listOf("google"), info.providers)
    }

    @Test
    fun fetchWithNullDesign() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"appearance","design":null,"providers":[]}"""))
        assertNull(makeClient().appearance().design)
        assertEquals("/v1/appearance", server.takeRequest().path)
    }

    // ---- EMAIL_DELIVERY_NOT_CONFIGURED ----

    private val message = "This app can't send email right now. Try another sign-in method or contact the app owner."
    private val undeliverable =
        """{"errors":[{"code":"EMAIL_DELIVERY_NOT_CONFIGURED","message":"$message","meta":{"fix":"Configure an email provider first."}}]}"""

    @Test
    fun emailDeliveryErrorIsTyped() {
        val e = parseErrorEnvelope(Json { ignoreUnknownKeys = true }, 422, undeliverable)
        assertEquals(422, e.status)
        assertEquals(AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED, e.knownCode)
        assertTrue(e.isEmailDeliveryNotConfigured)
        assertEquals(message, e.message)
        val other = parseErrorEnvelope(Json { ignoreUnknownKeys = true }, 400, """{"errors":[{"code":"SOMETHING_NEW","message":"x"}]}""")
        assertNull(other.knownCode)
        assertFalse(other.isEmailDeliveryNotConfigured)
    }

    @Test
    fun signInShowsTheServerMessageAndWithdrawsEmail() {
        val view = signInErrorView(parseErrorEnvelope(Json { ignoreUnknownKeys = true }, 422, undeliverable))
        assertEquals(message, view.message)
        assertEquals(AtlasErrorCode.EMAIL_DELIVERY_NOT_CONFIGURED, view.code)
        assertTrue(view.emailUnavailable)
        assertFalse(signInErrorView(AtlasException.Transport("offline")).emailUnavailable)
    }

    @Test
    fun emailCodeRequestSurfacesTheTypedError() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"sia_1","status":"needs_first_factor"}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody(undeliverable))
        val flow = makeClient().signInFlow()
        flow.create("a@example.com")
        try {
            flow.prepareEmailCode()
            throw AssertionError("expected a refusal")
        } catch (e: AtlasException) {
            assertTrue(e.isEmailDeliveryNotConfigured)
            assertEquals(message, e.message)
        }
    }
}
