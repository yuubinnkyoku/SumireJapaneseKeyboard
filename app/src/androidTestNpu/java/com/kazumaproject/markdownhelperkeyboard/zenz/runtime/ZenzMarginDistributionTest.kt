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
 * Round 21 Phase 2: min gap distribution over everyday inputs.
 *
 * These are ordinary sentences, not near-tie probes, so the trigger rate here is
 * the number that matters for the tau decision. Each conversion runs once; the
 * native side writes one `ZENZLOG margin` line per conversion.
 */
@RunWith(AndroidJUnit4::class)
class ZenzMarginDistributionTest {

    @Test(timeout = 900_000)
    fun everydayInputsTriggerRate() {
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

        val dumpPath = File(context.filesDir, "zenz_margin_round21.txt").absolutePath
        File(dumpPath).delete()
        runtime.setLogitDumpPath(dumpPath)

        val report = StringBuilder()
        var requestId = 10L
        try {
            INPUTS.forEachIndexed { index, (label, reading) ->
                val start = SystemClock.uptimeMillis()
                val text = generate(runtime, requestId++, "\uEE00$reading\uEE01")
                val elapsed = SystemClock.uptimeMillis() - start
                report.appendLine("idx=$index label=$label chars=${reading.length} ms=$elapsed text=$text")
                // Short pause so the device does not heat up across the set.
                SystemClock.sleep(1_500L)
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }

        val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
        report.appendLine("--- margin log ---")
        report.appendLine(raw)
        context.openFileOutput("zenz_margin_round21_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }

        assertTrue("no margin lines were written:\n$report", raw.contains("ZENZLOG margin"))
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
        /**
         * Ordinary sentences of mixed length, not near-tie probes. Readings are
         * katakana because that is what the IME hands to zenz.
         */
        private val INPUTS = listOf(
            "greeting" to "オハヨウゴザイマス",
            "thanks" to "アリガトウゴザイマシタ",
            "schedule" to "アシタノゴゴサンジニカイギガアリマス",
            "weather" to "キョウハアメガフルソウデス",
            "food" to "ヒルゴハンハラーメンガタベタイデス",
            "work" to "シゴトガオワッタラスグカエリマス",
            "place" to "エキマデアルイテイキマス",
            "request" to "コノショリョウヲカクニンシテクダサイ",
            "question" to "ナニジニデンシャガデマスカ",
            "long" to "ワタシハマイアサシジニオキテコーヒーヲノミマスソレカラシンブンオヨミシマス",
            "short" to "ハイ",
            "medium" to "デンワヲカケテモイイデスカ",
        )
    }
}
