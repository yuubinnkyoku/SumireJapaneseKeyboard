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
 * Round 22 Phase 0: triage the empty output for the 37-character input.
 *
 * The round 21 run returned an empty string for `long` and the margin log said
 * recompute=none, so the CPU arbitration also failed. This test runs the same
 * input in both modes and records the termination reason.
 */
@RunWith(AndroidJUnit4::class)
class ZenzLongInputTriageTest {

    @Test(timeout = 900_000)
    fun triageLongInput() {
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

        val dumpPath = File(context.filesDir, "zenz_long_triage.txt").absolutePath
        File(dumpPath).delete()
        runtime.setLogitDumpPath(dumpPath)

        val report = StringBuilder()
        val npuEnabled = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("zenz_npu_execution_preference", true)
        report.appendLine("npu_enabled=$npuEnabled")

        var requestId = 10L
        try {
            // The failing input, plus a shorter variant to see where it breaks.
            CASES.forEach { (label, reading) ->
                repeat(2) { round ->
                    val start = SystemClock.uptimeMillis()
                    val outcome = generate(runtime, requestId++, "\uEE00$reading\uEE01")
                    val elapsed = SystemClock.uptimeMillis() - start
                    report.appendLine(
                        "round=$round label=$label chars=${reading.length} " +
                            "ms=$elapsed len=${outcome.first.length} text=${outcome.first} " +
                            "error=${outcome.second}"
                    )
                    SystemClock.sleep(800L)
                }
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }

        val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
        report.appendLine("--- margin log ---")
        report.appendLine(raw)
        context.openFileOutput("zenz_long_triage_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }

        assertTrue("report must have results", report.contains("label="))
    }

    private fun generate(
        runtime: IZenzRuntime,
        requestId: Long,
        prompt: String,
    ): Pair<String, String> {
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
        if (!latch.await(180, TimeUnit.SECONDS)) return "TIMEOUT" to "timeout"
        return result to error
    }

    companion object {
        /**
         * Bisect the empty-output boundary. long37 fails and long30 works, so the
         * cut is somewhere between 23 and 37 characters.
         */
        private val CASES = listOf(
            "long37" to "ワタシハマイアサシジニオキテコーヒーヲノミマスソレカラシンブンオヨミシマス",
            "long33" to "ワタシハマイアサシジニオキテコーヒーヲノミマスソレカラシンブン",
            "long30" to "ワタシハマイアサシジニオキテコーヒーヲノミマス",
            "long27" to "ワタシハマイアサシジニオキテコーヒーヲノミ",
            "long23" to "ワタシハマイアサシジニオキテコーヒー",
            "long20" to "ワタシハマイアサシジニオキテ",
            "long12" to "ワタシハマイアサ",
        )
    }
}
