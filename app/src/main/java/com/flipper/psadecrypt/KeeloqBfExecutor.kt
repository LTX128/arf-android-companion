package com.flipper.psadecrypt

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class KlBfResult(
    val found: Boolean,
    val mfkey: Long = 0,
    val devkey: Long = 0,
    val cnt: Int = 0,
    val elapsedMs: Long = 0,
    val learnType: Int = 0
)

class KeeloqBfExecutor(threadCount: Int = 0) {
    private val bf = KeeloqBruteForce()
    val bigCoreCount = bf.nativeGetBigCoreCount().coerceIn(1, 16)
    val totalCoreCount = Runtime.getRuntime().availableProcessors()
    private val numThreads = if (threadCount > 0) threadCount.coerceIn(1, 16) else bigCoreCount
    private val executor = Executors.newFixedThreadPool(numThreads)
    private val cancelled = AtomicBoolean(false)

    fun getTotalKeysTested(): Long {
        var sum = 0L
        for (i in 0 until numThreads) {
            sum += (bf.nativeGetKeysTested(i).toLong() and 0xFFFFFFFFL)
        }
        return sum
    }

    fun cancel() {
        cancelled.set(true)
        for (i in 0 until numThreads) {
            bf.nativeSetCancel(i)
        }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun getCandidateCount(): Int = bf.nativeGetCandidateCount()

    fun getCandidate(index: Int): KlBfResult? {
        val out = LongArray(4)
        if (!bf.nativeGetCandidate(index, out)) return null
        return KlBfResult(
            found = true,
            mfkey = out[0],
            devkey = out[1],
            cnt = out[2].toInt(),
            learnType = out[3].toInt()
        )
    }

    fun run(
        learningType: Int,
        serial: Int, fix: Int,
        hop1: Int, hop2: Int,
        rangeStart: Long, rangeEnd: Long
    ): Long {
        cancelled.set(false)
        bf.nativeResetCandidates()
        for (i in 0 until numThreads) {
            bf.nativeResetThread(i)
        }

        val startTime = System.currentTimeMillis()
        val rangeSize = rangeEnd - rangeStart
        val chunkSize = rangeSize / numThreads

        val futures = mutableListOf<Future<*>>()

        for (i in 0 until numThreads) {
            val chunkStart = rangeStart + (chunkSize * i)
            val chunkEnd = if (i == numThreads - 1) rangeEnd else chunkStart + chunkSize

            val threadIdx = i
            val future = executor.submit {
                val threadBf = KeeloqBruteForce()
                threadBf.nativeBruteForce(
                    learningType, serial, fix, hop1, hop2,
                    chunkStart.toInt(), chunkEnd.toInt(),
                    threadIdx
                )
            }
            futures.add(future)
        }

        for (future in futures) {
            future.get()
        }

        return System.currentTimeMillis() - startTime
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }
}
