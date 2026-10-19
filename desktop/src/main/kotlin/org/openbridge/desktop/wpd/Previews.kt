package org.openbridge.desktop.wpd

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam

/** Small JPEG previews made on the PC, for phones whose MTP gives none. */
internal object Previews {
    /** Photos larger than this aren't fetched just for a preview. */
    const val MAX_SOURCE = 20L shl 20
    private val decodable = setOf("jpg", "jpeg", "png", "gif", "bmp", "wbmp")

    fun decodable(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in decodable

    /**
     * A JPEG at most `size` pixels on its longer side, or null when the bytes aren't an image Java can read. Big photos
     * are subsampled while decoding, so a 50-megapixel JPEG never sits in memory whole.
     */
    fun scale(bytes: ByteArray, size: Int): ByteArray? {
        val image = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val longest = maxOf(reader.getWidth(0), reader.getHeight(0))
                val param: ImageReadParam = reader.defaultReadParam
                val step = (longest / (size * 2)).coerceAtLeast(1)
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                runCatching { reader.read(0, param) }.getOrNull()
            } finally { reader.dispose() }
        } ?: return null
        val ratio = minOf(1.0, size.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * ratio).toInt().coerceAtLeast(1)
        val height = (image.height * ratio).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            color = java.awt.Color.WHITE
            fillRect(0, 0, width, height)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        return ByteArrayOutputStream().also { if (!ImageIO.write(out, "jpg", it)) return null }.toByteArray()
    }

    /** The phone's own preview, shrunk when it's bigger than asked; as is when Java can't read it but it's a JPEG. */
    fun fit(bytes: ByteArray, size: Int): ByteArray? {
        val jpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val dimensions = runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                ImageIO.getImageReaders(input).asSequence().firstOrNull()?.let { reader ->
                    try { reader.input = input; maxOf(reader.getWidth(0), reader.getHeight(0)) } finally { reader.dispose() }
                }
            }
        }.getOrNull()
        return when {
            dimensions == null -> if (jpeg) bytes else null
            jpeg && dimensions <= size -> bytes
            else -> scale(bytes, size) ?: if (jpeg) bytes else null
        }
    }
}
