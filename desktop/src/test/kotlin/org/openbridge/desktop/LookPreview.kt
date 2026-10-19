package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.openbridge.ui.*
import java.io.File
import kotlin.test.Test

class LookPreview {
    @Test fun render() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return)
        out.mkdirs()
        for (look in Look.entries) for ((name, connected, energy) in listOf(Triple("idle", false, 0f), Triple("on", true, 1f))) {
            val engine = SunEngine()
            var frame = SunEngine.Frame()
            repeat(if (connected) 240 else 30) { frame = engine.step(1.0 / 60, connected, connected, false) }
            Palette.look = look
            val scene = ImageComposeScene(400, 820, Density(2f)) {
                Backdrop(Modifier.fillMaxSize(), energy = energy, level = if (connected) 0.4f else 0f, still = true) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawHero(engine, frame, Offset(size.width / 2, size.height * 0.42f), size.width * 0.27f, 140f)
                    }
                }
            }
            val image = scene.render(0)
            File(out, "${look.id}-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
