package org.openbridge.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** A screen in a given state, as the user would see it. */
class Shot(val name: String, val model: (Look) -> AppModel, val tab: Tab = Tab.HOME, val page: Page = Page.NONE, val sheet: Boolean = false)

private val base = { look: Look -> Fixtures.model(look) }

/** Every screen and its key states. */
val shots = listOf(
    Shot("01-home-idle", { base(it).copy(update = UpdateUi(version = "1.2.0", page = "https://example.org")) }),
    Shot("02-home-starting", { base(it).copy(share = ShareUi(Phase.STARTING)) }),
    Shot("03-home-sharing-waiting", { base(it).copy(share = ShareUi(Phase.WAITING, endpoints = Fixtures.endpoints, cable = Cable.CHARGING, usbDebugging = true)) }),
    Shot("04-home-connected", { base(it).copy(share = ShareUi(Phase.CONNECTED, computer = Fixtures.MAC, transport = Via.WIFI, endpoints = Fixtures.endpoints,
        ), outbox = Fixtures.outbox, collecting = 0.62f) }),
    Shot("05-pairing-code", { base(it).copy(share = ShareUi(Phase.WAITING, endpoints = Fixtures.endpoints), pairing = PairingUi("482913", Fixtures.MAC)) }),
    Shot("06-activity", { base(it).copy(outbox = Fixtures.outbox) }, Tab.ACTIVITY),
    Shot("07-activity-empty", { base(it).copy(events = emptyList()) }, Tab.ACTIVITY),
    Shot("08-texts", { base(it).copy(outbox = OutboxUi(listOf(PhoneOutbox.Item("t", "Text", 20, org.openbridge.protocol.Outbox.TEXT)))) }, Tab.TEXTS),
    Shot("09-texts-empty", { base(it).copy(texts = emptyList()) }, Tab.TEXTS),
    Shot("10-settings", { base(it).copy(update = UpdateUi(upToDate = true)) }, Tab.SETTINGS),
    Shot("11-settings-trusted", { base(it).copy(share = ShareUi(Phase.CONNECTED, computer = Fixtures.MAC, transport = Via.WIFI)) }, Tab.SETTINGS, Page.TRUSTED),
    Shot("12-settings-notifications", base, Tab.SETTINGS, Page.NOTIFICATIONS),
    Shot("13-settings-notifications-off", { base(it).copy(mirror = MirrorUi()) }, Tab.SETTINGS, Page.NOTIFICATIONS),
    Shot("14-settings-hotspot", base, Tab.SETTINGS, Page.HOTSPOT),
    Shot("15-settings-hotspot-on", { base(it).copy(share = ShareUi(Phase.WAITING), hotspot = HotspotUi("AndroidShare_4821", "k7 mq 2x 9p",
        served = Endpoint(Via.HOTSPOT, "192.168.49.1", 41986))) }, Tab.SETTINGS, Page.HOTSPOT),
    Shot("16-settings-help", base, Tab.SETTINGS, Page.HELP),
    Shot("17-share-sheet", base, sheet = true),
    Shot("18-qr-scanner", { base(it).copy(share = ShareUi(Phase.WAITING, endpoints = Fixtures.endpoints), qr = QrUi(scanning = true)) }),
    Shot("19-qr-ready", { base(it).copy(share = ShareUi(Phase.WAITING, endpoints = Fixtures.endpoints),
        qr = QrUi(waitingFor = Fixtures.MAC, expiresAt = Fixtures.now + 94_000)) }),
    Shot("20-qr-paired", { base(it).copy(share = ShareUi(Phase.CONNECTED, computer = Fixtures.MAC, transport = Via.WIFI, endpoints = Fixtures.endpoints),
        qr = QrUi(paired = Fixtures.MAC)) }),
    Shot("21-qr-expired", { base(it).copy(share = ShareUi(Phase.WAITING, endpoints = Fixtures.endpoints),
        qr = QrUi(problem = QrProblem.EXPIRED, problemComputer = Fixtures.MAC)) }),
)

/** Sets the screen size, font scale, language and look before the activity starts. */
class Setup(private val qualifiers: String, private val locale: Locale, private val look: Look, private val fontScale: Float = 1f) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(fontScale)
            Locale.setDefault(locale)
            Palette.look = look
            base.evaluate()
        }
    }
}

abstract class Gallery(qualifiers: String, locale: Locale, private val look: Look, fontScale: Float = 1f) {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(Setup(qualifiers, locale, look, fontScale)).around(compose)

    /** Draws each of [list] in turn and saves it under [folder]. */
    protected fun render(folder: String, list: List<Shot>, rtl: Boolean = false) {
        var current by mutableStateOf(list.first())
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(frozen = true), LocalCameraFeed provides FakeCamera,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                OpenBridgeTheme {
                    val shot = current
                    key(shot.name) {
                        // Share › OpenBridge opens over whatever app shared; the home screen stands in for it.
                        if (shot.sheet) Box(Modifier.fillMaxSize()) {
                            App(shot.model(look), FakeActions)
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                            SendingSheet(2, 5)
                        }
                        else App(shot.model(look), FakeActions, shot.tab, shot.page)
                    }
                }
            }
        }
        for (shot in list) {
            current = shot
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("screenshots.dir")}/$folder/${shot.name}.png")
        }
    }
}

private val sizes = mapOf("small" to "w360dp-h640dp-xhdpi", "big" to "w412dp-h915dp-xhdpi")
private val languages = mapOf("en" to Locale.US, "zh" to Locale.SIMPLIFIED_CHINESE)

/** Every screen, in every look, in English and Chinese, on a small and a big phone. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class EveryScreen(private val look: Look, private val language: String, private val size: String) :
    Gallery((if (language == "zh") "zh-rCN-" else "en-rUS-") + sizes.getValue(size), languages.getValue(language), look) {

    @Test fun gallery() = render("${look.id}/$language-$size", shots)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun cases() = Look.entries.flatMap { look -> languages.keys.flatMap { lang -> sizes.keys.map { size -> arrayOf(look, lang, size) } } }
    }
}

private val largeText = listOf("01-home-idle", "04-home-connected", "05-pairing-code", "06-activity", "08-texts", "10-settings",
    "12-settings-notifications", "15-settings-hotspot-on", "18-qr-scanner", "19-qr-ready", "20-qr-paired")

/** The text at 200%: nothing may be cut off. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class LargeText(private val language: String) :
    Gallery((if (language == "zh") "zh-rCN-" else "en-rUS-") + sizes.getValue("small"), languages.getValue(language), Look.DUSK, fontScale = 2f) {

    @Test fun gallery() = render("font-2x/$language-small", shots.filter { it.name in largeText })

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(arrayOf("en"), arrayOf("zh"))
    }
}

/** Right to left: the layout mirrors and nothing overlaps. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class RightToLeft(private val look: Look) : Gallery("en-rUS-" + sizes.getValue("big"), Locale.US, look) {
    @Test fun gallery() = render("rtl/${look.id}", shots.filter { it.name in setOf("04-home-connected", "06-activity", "08-texts", "10-settings") }, rtl = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(arrayOf(Look.DUSK))
    }
}
