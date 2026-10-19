package org.openbridge.desktop.wpd

import org.openbridge.desktop.Entry
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Folder sizes, search and previews over USB, against a pretend phone. */
class WpdWalkTest {
    private fun folder(id: String, name: String = id) = Entry(id, name, true, 0)
    private fun file(id: String, name: String, size: Long) = Entry(id, name, false, size)

    /** A phone that, like Android's MTP, lists DCIM inside itself and Camera twice. */
    private val tree = mapOf(
        "s1" to listOf(folder("dcim", "DCIM"), file("n", "notes.txt", 10)),
        "dcim" to listOf(folder("dcim", "DCIM"), folder("cam", "Camera"), folder("cam", "Camera")),
        "cam" to listOf(file("p1", "IMG_1.jpg", 100), file("p2", "img_2.JPG", 200), folder("s1", "loop")),
    )
    private val children: (String) -> List<Entry> = { tree[it].orEmpty() }

    @Test
    fun measureCountsEachObjectOnce() {
        val size = Walk.measure("s1", children)
        assertEquals(310, size.bytes)
        assertEquals(3, size.files)
        assertTrue(size.complete)
    }

    @Test
    fun searchFindsEachHitOnceWithItsLocation() {
        val result = Walk.search(listOf("s1" to ""), "img", children)
        assertTrue(result.complete)
        assertEquals(listOf("IMG_1.jpg" to "DCIM/Camera", "img_2.JPG" to "DCIM/Camera"), result.found.map { it.entry.name to it.location })
        val top = Walk.search(listOf("s1" to "Internal shared storage"), "camera", children)
        assertEquals(listOf("Internal shared storage/DCIM"), top.found.map { it.location })
        assertEquals(listOf("dcim"), Walk.search(listOf("s1" to ""), " dcim ", children).found.map { it.entry.id })
    }

    @Test
    fun walksStopAtTheirLimits() {
        val wide: (String) -> List<Entry> = { id ->
            if (id == "root") List(60_000) { file("f$it", "match $it", 1) } else emptyList()
        }
        val measured = Walk.measure("root", wide)
        assertFalse(measured.complete)
        assertEquals(Walk.ITEMS.toLong(), measured.files)
        val searched = Walk.search(listOf("root" to ""), "match", wide)
        assertFalse(searched.complete)
        assertEquals(Walk.HITS, searched.found.size)

        var clock = 0L
        val slow: (String) -> List<Entry> = { clock += 3_000; listOf(folder("$it/x", "x")) }
        assertFalse(Walk.measure("a", slow, now = { clock }).complete)
        assertFalse(Walk.search(listOf("a" to ""), "x", slow, now = { clock }).complete)
    }

    @Test
    fun locationsJoinWithSlashes() {
        assertEquals("DCIM", Walk.join("", "DCIM"))
        assertEquals("DCIM/Camera", Walk.join("DCIM", "Camera"))
    }

    @Test
    fun previewsAreSmallJpegs() {
        val photo = BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(photo, "png", it) }.toByteArray()
        val preview = assertNotNull(Previews.scale(png, 256))
        val image = ImageIO.read(ByteArrayInputStream(preview))
        assertEquals(256, image.width)
        assertEquals(170, image.height)
        assertEquals(0xFF.toByte(), preview[0]); assertEquals(0xD8.toByte(), preview[1])
        // A small JPEG from the phone is kept as it is.
        assertTrue(Previews.fit(preview, 256)!!.contentEquals(preview))
        assertEquals(128, ImageIO.read(ByteArrayInputStream(Previews.fit(preview, 128)!!)).width)
        assertNull(Previews.scale(byteArrayOf(1, 2, 3), 256))
        assertTrue(Previews.decodable("IMG_1.JPG"))
        assertFalse(Previews.decodable("IMG_1.HEIC"))
    }
}
