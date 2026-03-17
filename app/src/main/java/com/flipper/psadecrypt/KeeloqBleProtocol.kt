package com.flipper.psadecrypt

import java.nio.ByteBuffer
import java.nio.ByteOrder

object KeeloqBleProtocol {
    const val MSG_KL_BF_REQUEST: Byte = 0x10
    const val MSG_KL_BF_PROGRESS: Byte = 0x11
    const val MSG_KL_BF_RESULT: Byte = 0x12
    const val MSG_KL_BF_CANCEL: Byte = 0x13

    data class KlBfRequest(
        val learningType: Int,
        val fix: Int,
        val hop1: Int,
        val hop2: Int,
        val serial: Int
    )

    fun parseKlBfRequest(data: ByteArray): KlBfRequest? {
        if (data.size < 18 || data[0] != MSG_KL_BF_REQUEST) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get()
        val learningType = buf.get().toInt() and 0xFF
        val fix = buf.getInt()
        val hop1 = buf.getInt()
        val hop2 = buf.getInt()
        val serial = buf.getInt()
        return KlBfRequest(learningType, fix, hop1, hop2, serial)
    }

    fun encodeProgress(phase: Int, keysTested: Int, keysPerSec: Int): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_KL_BF_PROGRESS)
        buf.put(phase.toByte())
        buf.putInt(keysTested)
        buf.putInt(keysPerSec)
        return buf.array()
    }

    fun encodeCandidate(result: KlBfResult): ByteArray {
        val buf = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_KL_BF_RESULT)
        buf.put(1.toByte())
        buf.putLong(result.mfkey)
        buf.putLong(result.devkey)
        buf.putInt(result.cnt)
        buf.putInt(0)
        buf.put(result.learnType.toByte())
        return buf.array()
    }

    fun encodeBfComplete(candidateCount: Int, elapsedMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_KL_BF_RESULT)
        buf.put(2.toByte())
        buf.putLong(0L)
        buf.putLong(0L)
        buf.putInt(candidateCount)
        buf.putInt(elapsedMs.toInt())
        buf.put(0.toByte())
        return buf.array()
    }

    fun encodeNotFound(elapsedMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_KL_BF_RESULT)
        buf.put(0.toByte())
        buf.putLong(0L)
        buf.putLong(0L)
        buf.putInt(0)
        buf.putInt(elapsedMs.toInt())
        buf.put(0.toByte())
        return buf.array()
    }

    fun isCancelMessage(data: ByteArray): Boolean {
        return data.isNotEmpty() && data[0] == MSG_KL_BF_CANCEL
    }

    fun isKeeloqMessage(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val type = data[0].toInt() and 0xFF
        return type in 0x10..0x13
    }
}
