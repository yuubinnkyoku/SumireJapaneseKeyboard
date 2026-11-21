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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 20: checks the margin arbitration.
 *
 * The NPU and the CPU disagree by mean 0.284 logit. When the NPU's own top-1 and
 * top-2 are closer than 1.5, the conversion is recomputed on the CPU and the CPU
 * answer is returned. This test pins the expected trigger set from round 19.
 */
@RunWith(AndroidJUnit4::class)
class ZenzMarginArbitrationTest {

    @Test(timeout = 900_000)
    fun marginArbitrationMatchesCpu() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        val connectLatch = CountDownLatch(1)
        var remote: IZenzRuntime? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = IZenzRuntime.Stub.asInterface(service)
                connectLatch.countDown()
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
        assertTrue("service connect", connectLatch.await(20, TimeUnit.SECONDS))
        val runtime = remote!!

        val initLatch = CountDownLatch(1)
        var initError = ""
        val initCallback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = initLatch.countDown()
            override fun onStringResult(requestId: Long, result: String) = Unit
            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                initError = message
                initLatch.countDown()
            }
        }
        runtime.initialize(1L, model.absolutePath, 512, 4, initCallback)
        assertTrue("initialize", initLatch.await(180, TimeUnit.SECONDS))
        assertTrue("initialize failed: $initError", initError.isEmpty())

        val dumpPath = File(context.filesDir, "zenz_margin_round20.txt").absolutePath
        File(dumpPath).delete()
        runtime.setLogitDumpPath(dumpPath)

        val report = StringBuilder()
        var requestId = 10L
        try {
            // First pass: cold, so the CPU arbitration model loads here.
            CASES.forEach { case ->
                val start = SystemClock.uptimeMillis()
                val text = generate(runtime, requestId++, case.prompt)
                val elapsed = SystemClock.uptimeMillis() - start
                report.appendLine(
                    "cold label=${case.label} expected=${case.expected} actual=$text " +
                        "match=${text == case.expected} ms=$elapsed"
                )
            }
            // Second pass: the CPU model is cached now.
            CASES.forEach { case ->
                val start = SystemClock.uptimeMillis()
                val text = generate(runtime, requestId++, case.prompt)
                val elapsed = SystemClock.uptimeMillis() - start
                report.appendLine(
                    "warm label=${case.label} expected=${case.expected} actual=$text " +
                        "match=${text == case.expected} ms=$elapsed"
                )
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }

        val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
        report.appendLine("--- margin log ---")
        report.appendLine(raw)
        context.openFileOutput("zenz_margin_round20_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }

        // The thin-margin case must now return the CPU answer.
        val seido = report.lineSequence().first { it.contains("label=seido") }
        assertTrue(
            "seido must return the CPU answer (精度が高い): $seido",
            seido.contains("actual=精度が高い"),
        )
        // The wide-margin cases must be untouched.
        listOf("kanji", "nihongo", "kyouha", "long1").forEach { label ->
            val line = report.lineSequence().first { it.contains("label=$label") }
            assertTrue("$label must keep its round 19 answer: $line", line.contains("match=true"))
        }
        // Arbitration only runs on the NPU path, so the CPU mode must not trigger.
        val npuEnabled = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("zenz_npu_execution_preference", true)
        if (npuEnabled) {
            assertTrue(
                "long2 must trigger the margin check:\n$raw",
                raw.contains("trigger=1") && raw.contains("recompute=cpu"),
            )
        } else {
            assertTrue(
                "the CPU path must not arbitrate:\n$raw",
                !raw.contains("recompute=cpu"),
            )
        }
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

    private data class Case(val label: String, val prompt: String, val expected: String)

    companion object {
        /**
         * Expected answers come from the CPU build. seido and long2 have a top-1
         * gap below 1.5 (0.38 and 0.83 in round 19), so they arbitrate; the other
         * four keep the NPU answer because their gap is 3.7 or more.
         *
         * long1 keeps 制度が高い: its gap is 4.75, so the NPU answer stands even
         * though the CPU would pick 精度. That is the documented limit of this
         * approach - it only rescues conversions the NPU itself is unsure about.
         */
        private val CASES = listOf(
            Case("seido", "\uEE00セイドガタカイ\uEE01", "精度が高い"),
            Case("kanji", "\uEE00カンジヘンカン\uEE01", "漢字変換"),
            Case("nihongo", "\uEE00ニホンゴニュウリョク\uEE01", "日本語入力"),
            Case("kyouha", "\uEE00キョウハテンキガイイ\uEE01", "今日は天気がいい"),
            Case("long1", "\uEE02キョウハテンキガヨクテサンポビヨリデスネ\uEE00セイドガタカイ\uEE01",
                "今日は天気が良くて散歩日和ですね制度が高い"),
            Case("long2", "\uEE02ワタシハマイニチカイシャニイキマス\uEE00カンジヘンカン\uEE01",
                "私は毎日会社に行きます漢字変換"),
        )
    }
}
