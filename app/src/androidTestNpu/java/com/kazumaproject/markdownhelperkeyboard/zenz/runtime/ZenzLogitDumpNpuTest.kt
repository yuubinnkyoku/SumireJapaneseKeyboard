package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
 * Round 19: dumps the raw logits through the `:zenz` service.
 *
 * This lives in androidTestNpu because the app declares libcdsprpc.so, which the
 * zenz module's own test APK does not. The native side prints one `ZENZLOG` line
 * per generation when the build sets ZENZ_LOGIT_DUMP.
 */
@RunWith(AndroidJUnit4::class)
class ZenzLogitDumpNpuTest {

    @Test(timeout = 900_000)
    fun dumpLogits() {
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
            override fun onReady(requestId: Long, processId: Int) {
                Log.i(TAG, "ready pid=$processId")
                initLatch.countDown()
            }

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

        // The dump file lives in the :zenz process, so the path is set through the binder.
        val dumpPath = File(context.filesDir, "zenz_logit_dump_npu_raw.txt").absolutePath
        File(dumpPath).delete()
        runtime.setLogitDumpPath(dumpPath)

        val report = StringBuilder()
        var requestId = 10L
        try {
            repeat(3) { round ->
                PROMPTS.forEach { (label, prompt) ->
                    val text = generate(runtime, requestId++, prompt)
                    report.appendLine("round=$round label=$label prompt=$prompt text=$text")
                    Log.i(TAG, "round=$round label=$label text=$text")
                }
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }
        context.openFileOutput("zenz_logit_dump_npu.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
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
        if (!latch.await(120, TimeUnit.SECONDS)) return "TIMEOUT"
        if (error.isNotEmpty()) return "ERROR:$error"
        return result
    }

    companion object {
        private const val TAG = "zenz-logit-dump"

        /** Same list as ZenzLogitDumpTest so the two runs line up. */
        val PROMPTS: List<Pair<String, String>> = listOf(
            "seido" to "\uEE00セイドガタカイ\uEE01",
            "kanji" to "\uEE00カンジヘンカン\uEE01",
            "nihongo" to "\uEE00ニホンゴニュウリョク\uEE01",
            "kyouha" to "\uEE00キョウハテンキガイイ\uEE01",
            "long1" to "\uEE02キョウハテンキガヨクテサンポビヨリデスネ\uEE00セイドガタカイ\uEE01",
            "long2" to "\uEE02ワタシハマイニチカイシャニイキマス\uEE00カンジヘンカン\uEE01",
        )
    }
}
