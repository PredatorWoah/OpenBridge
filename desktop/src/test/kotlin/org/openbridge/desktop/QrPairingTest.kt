package org.openbridge.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pairing by QR code (OBP3) against a phone that speaks it exactly as docs/protocol.md says. */
class QrPairingTest {
    private val phone = FakePhone()

    @AfterTest fun stop() = phone.close()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The reference vector: K = 16 bytes of 04, F, Nm and Np = 32 bytes of 01, 02 and 03. */
    @Test
    fun matchesTheReferenceVector() {
        val key = ByteArray(16) { 4 }
        val f = ByteArray(32) { 1 }; val nm = ByteArray(32) { 2 }; val np = ByteArray(32) { 3 }
        assertEquals("aa955a6147859395f4e5c691978f1bd5956f86fff65b5b16a308c8ab7cd718e9", hex(QrPairing.phoneProof(key, f, nm, np)))
        assertEquals("d0cefd86bda6b8324bfacfe9e84f88d0cac0f0ac1e70cb808b4efa4a549c698e", hex(QrPairing.computerProof(key, f, nm, np)))
        assertEquals("openbridge://pair?k=BAQEBAQEBAQEBAQEBAQEBA&n=Aditya%27s%20MacBook%20Air", QrPairing.link(key, "Aditya's MacBook Air"))
    }

    @Test
    fun theCodeCarriesTheNameEncodedAndCut() {
        val key = ByteArray(16)
        assertEquals("openbridge://pair?k=AAAAAAAAAAAAAAAAAAAAAA&n=%E5%B0%8F%E6%98%8E%E7%9A%84%E7%94%B5%E8%84%91%20%26%20a%2Bb%3Dc", QrPairing.link(key, "小明的电脑 & a+b=c"))
        // 43 three-byte characters is 129 bytes: cut to 42, never in the middle of one.
        val long = "长".repeat(43)
        assertEquals("长".repeat(42), QrPairing.cut(long))
        assertEquals(126, QrPairing.cut(long).toByteArray().size)
        assertEquals("a".repeat(128), QrPairing.cut("a".repeat(200)))
        // Fresh codes have fresh keys of 16 bytes, unpadded base64url (22 characters).
        val one = QrCode.fresh("PC", now = 0); val two = QrCode.fresh("PC", now = 0)
        assertEquals(16, one.key.size)
        assertTrue(!one.key.contentEquals(two.key))
        assertEquals(22, one.text.substringAfter("k=").substringBefore('&').length)
        assertEquals(120, one.secondsLeft(0)); assertEquals(1, one.secondsLeft(119_500)); assertTrue(one.expired(120_000))
    }

    @Test
    fun pairsWithThePhoneThatScannedTheCode() {
        val code = QrCode.fresh("Test PC")
        phone.qrKey = code.key
        val answer = BridgeClient.pairByQr("127.0.0.1", phone.port, code.key, Transport.FAST_USB, name = "Test PC")
        val client = assertIs<QrAnswer.Paired>(answer).client
        assertContentEquals(phone.fingerprint, client.fingerprint)
        assertContentEquals(phone.secret, client.secret)
        assertEquals(listOf("Test PC"), phone.qrNames)
        assertNull(phone.qrKey)   // used once, then forgotten
        // The new pairing works for requests, pinned to the certificate it saw.
        assertEquals(24, client.info().revision)
        // Used: the phone holds no key any more.
        assertIs<QrAnswer.NotYet>(BridgeClient.pairByQr("127.0.0.1", phone.port, code.key, Transport.FAST_USB))
    }

    @Test
    fun waitsWhileThePhoneHasNotScanned() {
        val answer = assertIs<QrAnswer.NotYet>(BridgeClient.pairByQr("127.0.0.1", phone.port, QrPairing.newKey(), Transport.FAST_USB))
        assertEquals("Scan the code on the computer first", answer.message)
        assertTrue(!answer.old)
    }

    @Test
    fun aPhoneBefore130NeedsTheSixDigitCode() {
        FakePhone(revision = 23).use { old ->
            val answer = assertIs<QrAnswer.NotYet>(BridgeClient.pairByQr("127.0.0.1", old.port, QrPairing.newKey(), Transport.FAST_USB))
            assertTrue(answer.old)
        }
    }

    @Test
    fun aWrongPhoneProofIsNeverAnswered() {
        // Another key: another phone, or something in between relaying with its own certificate.
        phone.qrKey = QrPairing.newKey()
        phone.qrWrongProof = true
        val error = assertFailsWith<BridgeException> { BridgeClient.pairByQr("127.0.0.1", phone.port, phone.qrKey!!, Transport.FAST_USB) }
        assertEquals(S.pairingTampered, error.message)
        Thread.sleep(200)
        assertTrue(phone.qrProofs.isEmpty(), "the computer's proof must not be sent")
    }

    @Test
    fun theWrongKeyIsRefusedAndForgottenAfterThreeTries() {
        val right = QrPairing.newKey()
        phone.qrKey = right
        // The phone proves with its key; a computer with another key can't check that proof and stops, sending nothing.
        repeat(2) { assertFailsWith<BridgeException> { BridgeClient.pairByQr("127.0.0.1", phone.port, QrPairing.newKey(), Transport.FAST_USB) } }
        assertTrue(phone.qrProofs.isEmpty())
        assertContentEquals(right, phone.qrKey)
        assertIs<QrAnswer.Paired>(BridgeClient.pairByQr("127.0.0.1", phone.port, right, Transport.FAST_USB))
    }

    @Test
    fun looksEverywhereOnceEach() {
        val links = listOf(UsbLink("A", "Pixel 8", UsbLink.State.READY, 5001, ByteArray(32)), UsbLink("B", "SM-S918B", UsbLink.State.UNAUTHORIZED))
        val nearby = listOf(NearbyPhone("Pixel 8", "192.168.1.40", "41987", "aa"), NearbyPhone("Elsewhere", "8.8.8.8", "41987"),
            NearbyPhone(S.hotspotPhone, "192.168.43.1", "41987", "bb"))
        val targets = qrTargets(nearby, "192.168.43.1", links)
        assertEquals(listOf("127.0.0.1:5001", "192.168.1.40:41987", "192.168.43.1:41987", "192.168.43.1:$HOTSPOT_PORT"), targets.map { it.key })
        assertEquals(Transport.FAST_USB, targets[0].transport)
        assertEquals("Pixel 8", targets[1].name)
        assertNull(targets[2].name)
        assertEquals(emptyList(), qrTargets(emptyList(), null, emptyList()))
    }
}
