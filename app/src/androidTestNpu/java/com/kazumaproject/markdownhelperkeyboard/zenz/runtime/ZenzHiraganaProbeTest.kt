package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
 * Round 25: the IME hands zenz hiragana, not katakana.
 *
 * The earlier probes used katakana readings, which is not what the keyboard sends.
 * This test runs both forms so the difference is visible.
 */
@RunWith(AndroidJUnit4::class)
class ZenzHiraganaProbeTest {

    @Test(timeout = 900_000)
    fun compareHiraganaAndKatakana() {
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
        var initFailure = ""
        val initCallback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = initLatch.countDown()
            override fun onStringResult(requestId: Long, result: String) = Unit
            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                initFailure = message
                initLatch.countDown()
            }
        }
        runtime.initialize(1L, model.absolutePath, 512, 4, initCallback)
        assertTrue("initialize", initLatch.await(180, TimeUnit.SECONDS))
        assertTrue("initialize failed: $initFailure", initFailure.isEmpty())

        val dumpPath = File(context.filesDir, "zenz_hiragana_probe.txt").absolutePath
        File(dumpPath).delete()
        runtime.setLogitDumpPath(dumpPath)

        val report = StringBuilder()
        var requestId = 10L
        try {
            CASES.forEach { (label, reading) ->
                val text = generate(runtime, requestId++, "\uEE00$reading\uEE01")
                report.appendLine("label=$label reading=$reading text=$text")
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }

        val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
        report.appendLine("--- logits ---")
        report.appendLine(raw)
        context.openFileOutput("zenz_hiragana_probe_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have cases", report.contains("label="))
    }

    private fun generate(runtime: IZenzRuntime, requestId: Long, prompt: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        var genFailure = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = Unit
            override fun onStringResult(requestId: Long, value: String) {
                result = value
                latch.countDown()
            }

            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                genFailure = message
                latch.countDown()
            }
        }
        runtime.generate(requestId, "", "", "", "", "", "", prompt, 24, callback)
        if (!latch.await(180, TimeUnit.SECONDS)) return "TIMEOUT"
        if (genFailure.isNotEmpty()) return "ERROR:$genFailure"
        return result
    }

    companion object {
        private val CASES = listOf(
            "hiragana" to "せいどがたかい",
            "katakana" to "セイドガタカイ",
            "hiragana2" to "かんじへんかん",
            "katakana2" to "カンジヘンカン",
        )
    }
}
