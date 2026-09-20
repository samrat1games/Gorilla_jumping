package com.phonexr.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PhoneXRInputTest {
    // Пакет в том же виде, в каком его шлёт PhoneXR: левая рука с кулаком, правая с нажатым курком.
    private val packet = "PH4 1 1 0 0 0.3400 0.5000 0.2000 0.00000 0.00000 0.00000 1.00000 0 " +
        "1 0 0 0 0.6600 0.4500 0.7000 0.00000 0.70711 0.00000 0.70711 4 1"

    @Test
    fun readsHandsGesturesAndButtons() {
        PhoneXRInput(port = 42460).use { input ->
            send(42460, packet)
            val state = requireNotNull(input.read())

            assertTrue(state.left.present)
            assertTrue(state.left.fist)
            assertEquals(.34f, state.left.x, .0001f)
            assertEquals(0, state.left.buttons)

            assertFalse(state.right.fist)
            assertTrue(state.right.isPressed(PhoneXRInput.Button.TRIGGER))
            assertEquals(.70711f, state.right.qw, .0001f)

            assertTrue(state.sixDof)
            assertFalse(state.handsOnly)
        }
    }

    @Test
    fun readsTheCurrentPh5Packet() {
        // Левая рука щипает, правая: Joy-Con со стиком вперёд и ладонью к лицу.
        val ph5 = "PH5 1 0 1 0 0.3000 0.4000 0.5000 0.00000 0.00000 0.00000 1.00000 0 0.000 0.000 " +
            "1 0 0 0 0.7000 0.4000 0.6000 0.00000 0.00000 0.00000 1.00000 4 0.000 1.000 1 " +
            "1 0 0 1"
        PhoneXRInput(port = 42462).use { input ->
            send(42462, ph5)
            val state = requireNotNull(input.read())
            assertTrue(state.left.pinch)
            assertFalse(state.left.palmToFace)
            assertTrue(state.left.index)
            assertTrue(state.right.palmToFace)
            assertEquals(1f, state.right.stickY, .0001f)
            assertTrue(state.right.isPressed(PhoneXRInput.Button.TRIGGER))
            assertTrue(state.sixDof)
        }
    }

    @Test
    fun ignoresPacketsFromAnotherProtocolVersion() {
        PhoneXRInput(port = 42461).use { input ->
            send(42461, "PH2 1 0 0 0 0.5 0.5 0.5")
            assertEquals(null, input.read())
        }
    }

    private fun send(port: Int, message: String) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use {
            it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
        }
    }
}
