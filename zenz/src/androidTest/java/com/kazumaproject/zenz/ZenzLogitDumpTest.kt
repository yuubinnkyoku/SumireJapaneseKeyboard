package com.kazumaproject.zenz

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round 19: dumps the raw logits for the same prompts on the NPU and on the CPU.
 *
 * The native side prints one `ZENZLOG` line per generation when the build sets
 * ZENZ_LOGIT_DUMP. This test only drives the prompts; the numbers come from logcat.
 *
 * The zenz module's own test APK does not declare libcdsprpc.so, so the NPU half
 * has to run from the app's androidTestNpu source set instead. This test covers
 * the CPU half and the prompt list.
 */
@RunWith(AndroidJUnit4::class)
class ZenzLogitDumpTest {

    @Test
    fun dumpCpuLogits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            instrumentation.context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        ZenzEngine.setNpuEnabled(false)
        val dumpPath = File(context.filesDir, "zenz_logit_dump_cpu_raw.txt").absolutePath
        File(dumpPath).delete()
        ZenzEngine.setLogitDumpPath(dumpPath)
        assertTrue("initModel with CPU", ZenzEngine.initModel(model.absolutePath))
        ZenzEngine.setRuntimeConfig(512, 4)
        val report = StringBuilder()
        try {
            repeat(3) { round ->
                PROMPTS.forEach { (label, prompt) ->
                    val text = ZenzEngine.generateWithContext("", prompt, 24)
                    report.appendLine("round=$round label=$label prompt=$prompt text=$text")
                    Log.i(TAG, "round=$round label=$label text=$text")
                }
            }
        } finally {
            ZenzEngine.closeModel()
        }
        context.openFileOutput("zenz_logit_dump_cpu.txt", android.content.Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
    }

    companion object {
        private const val TAG = "zenz-logit-dump"

        /** The four cases from the round 19 brief, plus two long-context cases. */
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
