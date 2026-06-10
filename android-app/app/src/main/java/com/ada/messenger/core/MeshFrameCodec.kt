package com.ada.messenger.core

internal object MeshFrameCodec {
    private val MAGIC = byteArrayOf(0x41, 0x44, 0x4D, 0x31)
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 10
    private const val MAX_PAYLOAD_BYTES = 1_048_576

    enum class FrameType(val id: Byte) {
        Handshake(1),
        Payload(2);

        companion object {
            fun fromId(id: Byte): FrameType? = entries.firstOrNull { it.id == id }
        }
    }

    data class MeshFrame(
        val type: FrameType,
        val payload: ByteArray,
    )

    fun encodeHandshake(peerId: String): ByteArray =
        encodeFrame(FrameType.Handshake, peerId.toByteArray(Charsets.UTF_8))

    fun encodePayload(payload: ByteArray): ByteArray =
        encodeFrame(FrameType.Payload, payload)

    fun decodeHandshakePeerId(payload: ByteArray): String = payload.toString(Charsets.UTF_8)

    fun chunk(frame: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        require(maxChunkBytes > 0) { "maxChunkBytes must be positive" }
        if (frame.size <= maxChunkBytes) return listOf(frame)

        val chunks = ArrayList<ByteArray>((frame.size + maxChunkBytes - 1) / maxChunkBytes)
        var offset = 0
        while (offset < frame.size) {
            val end = (offset + maxChunkBytes).coerceAtMost(frame.size)
            chunks += frame.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun append(bytes: ByteArray): List<MeshFrame> {
            if (bytes.isEmpty()) return emptyList()

            pending = if (pending.isEmpty()) bytes.copyOf() else pending + bytes
            val frames = mutableListOf<MeshFrame>()
            var offset = 0

            while (true) {
                if (pending.size - offset < HEADER_SIZE) {
                    break
                }

                val magicIndex = findMagic(pending, offset)
                if (magicIndex < 0) {
                    val keepFrom = (pending.size - MAGIC.size + 1).coerceAtLeast(0)
                    pending = pending.copyOfRange(keepFrom, pending.size)
                    return frames
                }

                if (pending.size - magicIndex < HEADER_SIZE) {
                    offset = magicIndex
                    break
                }

                if (pending[magicIndex + MAGIC.size] != VERSION) {
                    offset = magicIndex + 1
                    continue
                }

                val type = FrameType.fromId(pending[magicIndex + MAGIC.size + 1])
                if (type == null) {
                    offset = magicIndex + 1
                    continue
                }

                val payloadLengthOffset = magicIndex + MAGIC.size + 2
                val payloadLength = readInt(pending, payloadLengthOffset)
                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                    offset = magicIndex + 1
                    continue
                }

                val payloadOffset = payloadLengthOffset + 4
                val frameEnd = payloadOffset + payloadLength
                if (pending.size < frameEnd) {
                    offset = magicIndex
                    break
                }

                frames += MeshFrame(type, pending.copyOfRange(payloadOffset, frameEnd))
                offset = frameEnd
            }

            if (offset > 0) {
                pending = pending.copyOfRange(offset, pending.size)
            }

            return frames
        }
    }

    private fun encodeFrame(type: FrameType, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "mesh frame payload exceeds $MAX_PAYLOAD_BYTES bytes" }

        val out = ByteArray(HEADER_SIZE + payload.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[MAGIC.size] = VERSION
        out[MAGIC.size + 1] = type.id
        writeInt(out, MAGIC.size + 2, payload.size)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    private fun findMagic(bytes: ByteArray, start: Int): Int {
        val lastStart = bytes.size - MAGIC.size
        for (index in start..lastStart) {
            var matches = true
            for (magicIndex in MAGIC.indices) {
                if (bytes[index + magicIndex] != MAGIC[magicIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}