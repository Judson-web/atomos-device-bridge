package dev.atomos.bridge.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CiphertextEnvelopeTest {
    @Test
    fun constructorAndGetterDefensivelyCopyCiphertext() {
        val original = byteArrayOf(1, 2, 3, 4)
        val envelope = CiphertextEnvelope(
            protocolSuite = "test-suite",
            protocolVersion = 1,
            senderDeviceId = DeviceId("device-1"),
            ciphertext = original,
            associatedDataVersion = 1
        )

        original[0] = 99
        val firstRead = envelope.ciphertext
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstRead)
        assertNotSame(firstRead, envelope.ciphertext)

        firstRead[1] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), envelope.ciphertext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCiphertext() {
        CiphertextEnvelope(
            protocolSuite = "test-suite",
            protocolVersion = 1,
            senderDeviceId = DeviceId("device-1"),
            ciphertext = byteArrayOf(),
            associatedDataVersion = 1
        )
    }
}
