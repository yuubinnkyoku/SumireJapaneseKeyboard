package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kazumaproject.markdownhelperkeyboard.variant.AppVariantConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 23 Phase 2: same-session A/B between the xsmall and small models.
 *
 * The model file is swapped between blocks and the `:zenz` process is recreated,
 * so both models see the same device state. Blocks alternate xsmall/small/small/
 * xsmall to spread any thermal drift across both.
 *
 * The test reads the model from filesDir, so the harness copies the requested
 * GGUF there before each block. See the companion script.
 */
@RunWith(AndroidJUnit4::class)
class ZenzModelAbBenchTest {

    @Test(timeout = 1_800_000)
    fun abBench() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val report = StringBuilder()
        val npuEnabled = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("zenz_npu_execution_preference", true)
        report.appendLine("npu_enabled=$npuEnabled")

        // ABBA over three repetitions: xsmall, small, small, xsmall.
        val order = listOf("xsmall", "small", "small", "xsmall", "xsmall", "small")
        order.forEachIndexed { block, model ->
            // The runtime reads filesDir, so copy the requested asset there first.
            // A stale file in filesDir takes priority over the APK asset, which is
            // the trap that produced round 21 numbers in round 22.
            val modelFile = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
            modelFile.delete()
            val assetName = if (model == "small") {
                "ggml-model-small-Q5_K_M.gguf"
            } else {
                "ggml-model-Q5_K_M.gguf"
            }
            context.assets.open(assetName).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            val size = modelFile.length()
            report.appendLine("--- block=$block model=$model asset=$assetName file_bytes=$size ---")

            val runtime = bindRuntime(context)
            try {
                val initStart = SystemClock.uptimeMillis()
                val initError = initialize(runtime, modelFile.absolutePath)
                val initMs = SystemClock.uptimeMillis() - initStart
                report.appendLine("init_ms=$initMs error=[$initError]")
                if (initError.isNotEmpty()) {
                    report.appendLine("block=$block init failed, skipping")
                    return@forEachIndexed
                }

                val dumpPath = File(context.filesDir, "zenz_ab_$block.txt").absolutePath
                File(dumpPath).delete()
                runtime.setLogitDumpPath(dumpPath)

                var requestId = 100L
                // Short cases first, then everyday, then long context.
                BENCH.forEach { (label, reading) ->
                    val start = SystemClock.uptimeMillis()
                    val text = generate(runtime, requestId++, "\uEE00$reading\uEE01")
                    val elapsed = SystemClock.uptimeMillis() - start
                    report.appendLine("case=$label chars=${reading.length} ms=$elapsed text=$text")
                    SystemClock.sleep(400L)
                }

                val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
                val margins = raw.lineSequence().filter { it.contains("margin trigger=") }.toList()
                report.appendLine("margin_lines=${margins.size}")
                margins.forEach { report.appendLine("  $it") }
            } finally {
                runCatching { runtime.closeEngine() }
                SystemClock.sleep(600L)
            }
        }

        context.openFileOutput("zenz_ab_bench_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have blocks", report.contains("block="))
    }

    private fun bindRuntime(context: Context): IZenzRuntime {
        val latch = CountDownLatch(1)
        var remote: IZenzRuntime? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = IZenzRuntime.Stub.asInterface(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(
            "service bind",
            context.bindService(
                Intent(context, ZenzRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        assertTrue("service connect", latch.await(20, TimeUnit.SECONDS))
        return remote!!
    }

    private fun initialize(runtime: IZenzRuntime, modelPath: String): String {
        val latch = CountDownLatch(1)
        var error = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = latch.countDown()
            override fun onStringResult(requestId: Long, result: String) = Unit
            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                error = message
                latch.countDown()
            }
        }
        runtime.initialize(1L, modelPath, 512, 4, callback)
        if (!latch.await(300, TimeUnit.SECONDS)) return "init timeout"
        return error
    }

    private fun generate(runtime: IZenzRuntime, requestId: Long, prompt: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        var error = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = Unit
            override fun onStringResult(requestId: Long, value: String) {
                result = value
                latch.countDown()
            }

            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                error = message
                latch.countDown()
            }
        }
        runtime.generate(requestId, "", "", "", "", "", "", prompt, 24, callback)
        if (!latch.await(180, TimeUnit.SECONDS)) return "TIMEOUT"
        if (error.isNotEmpty()) return "ERROR:$error"
        return result
    }

    companion object {
        /** Short cases, everyday inputs, and two long-context cases. */
        private val BENCH = listOf(
            "seido" to "セイドガタカイ",
            "kanji" to "カンジヘンカン",
            "nihongo" to "ニホンゴニュウリョク",
            "kyouha" to "キョウハテンキガイイ",
            "greeting" to "オハヨウゴザイマス",
            "thanks" to "アリガトウゴザイマシタ",
            "schedule" to "アシタノゴゴサンジニカイギガアリマス",
            "weather" to "キョウハアメガフルソウデス",
            "food" to "ヒルゴハンハラーメンガタベタイデス",
            "work" to "シゴトガオワッタラスグカエリマス",
            "place" to "エキマデアルイテイキマス",
            "request" to "コノショリョウヲカクニンシテクダサイ",
            "question" to "ナニジニデンシャガデマスカ",
            "short" to "ハイ",
            "medium" to "デンワヲカケテモイイデスカ",
            "longctx1" to "キョウハテンキガヨクテサンポビヨリデスネ",
            "longctx2" to "ワタシハマイニチカイシャニイキマス",
        )
    }
}
