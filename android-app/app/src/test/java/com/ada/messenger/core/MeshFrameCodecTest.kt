package com.ada.messenger.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshFrameCodecTest {

    @Test
    fun `decoder reconstructs handshake and payload across fragmented transport chunks`() {
        val decoder = MeshFrameCodec.Decoder()
        val handshake = MeshFrameCodec.encodeHandshake("peer-123")
        val payload = MeshFrameCodec.encodePayload(byteArrayOf(1, 2, 3, 4, 5))
        val stream = handshake + payload

        val frames = mutableListOf<MeshFrameCodec.MeshFrame>()
        frames += decoder.append(stream.copyOfRange(0, 5))
        frames += decoder.append(stream.copyOfRange(5, 13))
        frames += decoder.append(stream.copyOfRange(13, stream.size))

        assertEquals(2, frames.size)
        assertEquals(MeshFrameCodec.FrameType.Handshake, frames[0].type)
        assertEquals("peer-123", MeshFrameCodec.decodeHandshakePeerId(frames[0].payload))
        assertEquals(MeshFrameCodec.FrameType.Payload, frames[1].type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frames[1].payload)
    }

    @Test
    fun `chunk preserves frame bytes exactly`() {
        val frame = MeshFrameCodec.encodePayload(ByteArray(96) { it.toByte() })
        val chunks = MeshFrameCodec.chunk(frame, 20)
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        assertArrayEquals(frame, reassembled)
        assertEquals(6, chunks.size)
    }
}