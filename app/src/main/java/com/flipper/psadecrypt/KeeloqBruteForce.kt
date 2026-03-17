package com.flipper.psadecrypt

class KeeloqBruteForce {
    companion object {
        init {
            System.loadLibrary("keeloq_bruteforce")
        }
    }

    external fun nativeBruteForce(
        learningType: Int,
        serial: Int, fix: Int,
        hop1: Int, hop2: Int,
        rangeStart: Int, rangeEnd: Int,
        threadIdx: Int
    )

    external fun nativeResetThread(threadIdx: Int)
    external fun nativeSetCancel(threadIdx: Int)
    external fun nativeGetKeysTested(threadIdx: Int): Int
    external fun nativeGetBigCoreCount(): Int
    external fun nativeResetCandidates()
    external fun nativeGetCandidateCount(): Int
    external fun nativeGetCandidate(index: Int, resultOut: LongArray): Boolean
}
