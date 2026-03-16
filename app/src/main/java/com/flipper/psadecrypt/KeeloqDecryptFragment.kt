package com.flipper.psadecrypt

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class KeeloqDecryptFragment : Fragment() {
    companion object {
        private const val TOTAL_32BIT = 0x100000000L
        private val LEARN_TYPES = arrayOf("Auto (6→7→8)", "Type 6 (Serial 1)", "Type 7 (Serial 2)", "Type 8 (Serial 3)")
        private val LEARN_TYPE_VALUES = intArrayOf(0, 6, 7, 8)
    }

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var speedText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button
    private lateinit var benchButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var fixInput: EditText
    private lateinit var hop1Input: EditText
    private lateinit var hop2Input: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var coreSpinner: Spinner
    private var coreOptions = mutableListOf<String>()
    private var coreValues = mutableListOf<Int>()

    private val handler = Handler(Looper.getMainLooper())
    var bfExecutor: KeeloqBfExecutor? = null
    private var progressRunnable: Runnable? = null
    private var bfStartTime = 0L

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_keeloq_decrypt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText   = view.findViewById(R.id.kl_status_text)
        progressText = view.findViewById(R.id.kl_progress_text)
        speedText    = view.findViewById(R.id.kl_speed_text)
        resultText   = view.findViewById(R.id.kl_result_text)
        runButton    = view.findViewById(R.id.kl_run_button)
        benchButton  = view.findViewById(R.id.kl_bench_button)
        progressBar  = view.findViewById(R.id.kl_progress_bar)
        fixInput     = view.findViewById(R.id.kl_fix_input)
        hop1Input    = view.findViewById(R.id.kl_hop1_input)
        hop2Input    = view.findViewById(R.id.kl_hop2_input)
        typeSpinner  = view.findViewById(R.id.kl_type_spinner)
        coreSpinner  = view.findViewById(R.id.kl_core_spinner)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, LEARN_TYPES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = adapter

        val probe = KeeloqBfExecutor(1)
        val bigCores = probe.bigCoreCount
        val totalCores = probe.totalCoreCount
        probe.shutdown()

        coreOptions.clear()
        coreValues.clear()
        coreOptions.add("Big cores ($bigCores)")
        coreValues.add(bigCores)
        if (totalCores != bigCores) {
            coreOptions.add("All cores ($totalCores)")
            coreValues.add(totalCores)
        }
        for (n in 1..totalCores) {
            if (n != bigCores && n != totalCores) {
                coreOptions.add("$n cores")
                coreValues.add(n)
            }
        }
        val coreAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, coreOptions)
        coreAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        coreSpinner.adapter = coreAdapter

        runButton.setOnClickListener { runManualBf() }
        benchButton.setOnClickListener { runBenchmark() }

        statusText.text = "Ready — $bigCores big / $totalCores total cores"
    }

    private fun selectedCoreCount(): Int {
        val idx = coreSpinner.selectedItemPosition
        return if (idx >= 0 && idx < coreValues.size) coreValues[idx] else 0
    }

    private fun parseHex(s: String): Int? {
        val clean = s.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return null
        return try { java.lang.Long.parseLong(clean, 16).toInt() } catch (e: Exception) { null }
    }

    private fun runManualBf() {
        val fix  = parseHex(fixInput.text.toString())
        val hop1 = parseHex(hop1Input.text.toString())
        val hop2 = parseHex(hop2Input.text.toString())
        if (fix == null || hop1 == null || hop2 == null) {
            statusText.text = "Enter valid hex for Fix, Hop1, Hop2"
            return
        }
        val serial = fix and 0x0FFFFFFF
        val typeIdx = typeSpinner.selectedItemPosition
        val learnType = LEARN_TYPE_VALUES[typeIdx]

        mainActivity?.appendLog("KL BF: fix=0x${Integer.toHexString(fix).uppercase()} hop1=0x${Integer.toHexString(hop1).uppercase()} type=$learnType")
        setButtonsEnabled(false)

        if (learnType == 0) {
            runAutoSequence(serial, fix, hop1, hop2)
        } else {
            runSingleType(learnType, serial, fix, hop1, hop2)
        }
    }

    private fun runAutoSequence(serial: Int, fix: Int, hop1: Int, hop2: Int) {
        val cores = selectedCoreCount()
        Thread {
            for (type in intArrayOf(6, 7, 8)) {
                handler.post { statusText.text = "Trying Type $type (2^32)…" }

                val executor = KeeloqBfExecutor(cores)
                bfExecutor = executor
                bfStartTime = System.currentTimeMillis()
                handler.post { startProgressPolling(executor, TOTAL_32BIT) }

                mainActivity?.appendLog("KL BF Type $type started")
                val result = executor.run(type, serial, fix, hop1, hop2, 0L, TOTAL_32BIT)
                stopProgressPolling()

                if (result.found) {
                    val finalResult = result.copy(learnType = type)
                    handler.post {
                        showResult(finalResult)
                        setButtonsEnabled(true)
                    }
                    mainActivity?.sendBleData(KeeloqBleProtocol.encodeResult(finalResult))
                    mainActivity?.appendLog("KL FOUND! type=$type mfkey=0x${java.lang.Long.toHexString(finalResult.mfkey).uppercase()}")
                    bfExecutor = null
                    return@Thread
                }
                mainActivity?.appendLog("KL Type $type: not found (${result.elapsedMs}ms)")
            }

            handler.post {
                statusText.text = "Not found in Types 6, 7, 8"
                resultText.text = "—"
                progressBar.progress = 100
                setButtonsEnabled(true)
                context?.let { BleKeepAliveService.clearBfProgress(it) }
            }
            val notFound = KlBfResult(found = false, elapsedMs = 0)
            mainActivity?.sendBleData(KeeloqBleProtocol.encodeResult(notFound))
            bfExecutor = null
        }.start()
    }

    private fun runSingleType(learnType: Int, serial: Int, fix: Int, hop1: Int, hop2: Int) {
        val cores = selectedCoreCount()
        statusText.text = "Running Type $learnType on $cores cores…"
        progressBar.progress = 0
        resultText.text = "—"

        val executor = KeeloqBfExecutor(cores)
        bfExecutor = executor
        bfStartTime = System.currentTimeMillis()
        startProgressPolling(executor, TOTAL_32BIT)

        Thread {
            val result = executor.run(learnType, serial, fix, hop1, hop2, 0L, TOTAL_32BIT)
            stopProgressPolling()
            val finalResult = result.copy(learnType = learnType)

            handler.post {
                showResult(finalResult)
                setButtonsEnabled(true)
            }
            mainActivity?.sendBleData(KeeloqBleProtocol.encodeResult(finalResult))
            bfExecutor = null
        }.start()
    }

    fun handleBleData(data: ByteArray) {
        if (KeeloqBleProtocol.isCancelMessage(data)) {
            bfExecutor?.cancel()
            statusText.text = "BF cancelled by Flipper"
            mainActivity?.appendLog("KL BF cancel received")
            return
        }
        val request = KeeloqBleProtocol.parseKlBfRequest(data) ?: return
        mainActivity?.appendLog("KL BF request: type=${request.learningType} fix=0x${Integer.toHexString(request.fix).uppercase()}")

        fixInput.setText(Integer.toHexString(request.fix).uppercase())
        hop1Input.setText(Integer.toHexString(request.hop1).uppercase())
        hop2Input.setText(if (request.hop2 != 0) Integer.toHexString(request.hop2).uppercase() else Integer.toHexString(request.hop1).uppercase())

        val learnType = request.learningType
        setButtonsEnabled(false)

        if (learnType == 0) {
            runAutoSequence(request.serial, request.fix, request.hop1,
                if (request.hop2 != 0) request.hop2 else request.hop1)
        } else {
            runSingleType(learnType, request.serial, request.fix, request.hop1,
                if (request.hop2 != 0) request.hop2 else request.hop1)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        handler.post {
            runButton.isEnabled = enabled
            benchButton.isEnabled = enabled
        }
    }

    private fun showResult(result: KlBfResult) {
        if (result.found) {
            val mfHex = String.format("%016X", result.mfkey)
            val dkHex = String.format("%016X", result.devkey)
            statusText.text = "FOUND (Type ${result.learnType}) — ${result.elapsedMs}ms"
            resultText.text = "MfKey:   $mfHex\nDevKey:  $dkHex\nCounter: 0x${String.format("%04X", result.cnt)}\nType:    ${result.learnType}\nTime:    ${result.elapsedMs}ms"
            mainActivity?.appendLog("KL FOUND! mfkey=$mfHex devkey=$dkHex cnt=${result.cnt}")
        } else {
            statusText.text = "Not found — ${result.elapsedMs}ms"
            resultText.text = "—"
        }
        progressBar.progress = 100
        context?.let { BleKeepAliveService.clearBfProgress(it) }
    }

    private fun runBenchmark() {
        statusText.text = "Benchmarking KeeLoq (1M keys)…"
        progressBar.progress = 0
        resultText.text = "—"
        setButtonsEnabled(false)

        val executor = KeeloqBfExecutor(selectedCoreCount())
        bfExecutor = executor
        bfStartTime = System.currentTimeMillis()
        startProgressPolling(executor, 0x100000L)

        Thread {
            val result = executor.run(6, 0x1234567, 0x91234567.toInt(), 0, 0, 0L, 0x100000L)
            stopProgressPolling()

            val elapsed = result.elapsedMs.coerceAtLeast(1)
            val keysPerSec = 0x100000L * 1000 / elapsed

            handler.post {
                statusText.text = "Bench: ${formatCount(keysPerSec)} keys/sec (${elapsed}ms)"
                progressBar.progress = 100
                val etaType67 = TOTAL_32BIT / keysPerSec.coerceAtLeast(1)
                resultText.text = "${Runtime.getRuntime().availableProcessors()} cores\n${String.format("%,d", keysPerSec)} keys/sec\nType 6/7 ETA: ${etaType67}s\nType 8 ETA: ${etaType67 * 256}s (~${etaType67 * 256 / 3600}h)"
                setButtonsEnabled(true)
            }
            bfExecutor = null
        }.start()
    }

    private fun startProgressPolling(executor: KeeloqBfExecutor, totalKeys: Long) {
        val runnable = object : Runnable {
            override fun run() {
                val tested = executor.getTotalKeysTested()
                val elapsed = (System.currentTimeMillis() - bfStartTime).coerceAtLeast(1)
                val kps = tested * 1000 / elapsed
                val pct = if (totalKeys > 0) (tested * 100 / totalKeys).toInt().coerceIn(0, 100) else 0

                progressBar.progress = pct
                progressText.text = "$pct% — ${formatCount(tested)} / ${formatCount(totalKeys)}"
                speedText.text = "${formatCount(kps)} k/s"

                mainActivity?.sendBleData(KeeloqBleProtocol.encodeProgress(0, (tested and 0xFFFFFFFFL).toInt(), kps.toInt()))
                context?.let { BleKeepAliveService.updateBfProgress(it, pct, formatCount(kps)) }
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = runnable
        handler.postDelayed(runnable, 500)
    }

    private fun stopProgressPolling() {
        handler.post {
            progressRunnable?.let { handler.removeCallbacks(it) }
            progressRunnable = null
        }
    }

    private fun formatCount(n: Long): String {
        return when {
            n >= 1_000_000_000 -> "${n / 1_000_000_000}.${(n % 1_000_000_000) / 100_000_000}G"
            n >= 1_000_000     -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
            n >= 1_000         -> "${n / 1_000}K"
            else               -> "$n"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bfExecutor?.cancel()
        stopProgressPolling()
    }
}
