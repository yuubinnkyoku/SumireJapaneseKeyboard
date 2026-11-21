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
 * Round 23 Phase 3: accuracy A/B over 30 everyday inputs.
 *
 * Runs the same list on both models in one session. The model file is copied from
 * the APK asset into filesDir before each half, because a stale file there takes
 * priority over the asset.
 */
@RunWith(AndroidJUnit4::class)
class ZenzModelAccuracyAbTest {

    @Test(timeout = 1_800_000)
    fun accuracyAb() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val report = StringBuilder()
        listOf("xsmall", "small").forEach { model ->
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
            report.appendLine("=== model=$model bytes=${modelFile.length()} ===")

            val runtime = bindRuntime(context)
            try {
                val initError = initialize(runtime, modelFile.absolutePath)
                if (initError.isNotEmpty()) {
                    report.appendLine("init failed: $initError")
                    return@forEach
                }
                var requestId = 200L
                INPUTS.forEachIndexed { index, (label, reading) ->
                    val start = SystemClock.uptimeMillis()
                    val text = generate(runtime, requestId++, "\uEE00$reading\uEE01")
                    val elapsed = SystemClock.uptimeMillis() - start
                    report.appendLine(
                        "idx=$index label=$label chars=${reading.length} ms=$elapsed " +
                            "reading=$reading text=$text"
                    )
                    SystemClock.sleep(300L)
                }
            } finally {
                runCatching { runtime.closeEngine() }
                SystemClock.sleep(600L)
            }
        }

        context.openFileOutput("zenz_accuracy_ab_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have both models", report.contains("model=xsmall") && report.contains("model=small"))
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
        /** 30 everyday readings, mixed length, no artificial near-tie probes. */
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
            "short" to "ハイ",
            "medium" to "デンワヲカケテモイイデスカ",
            "apology" to "モウシワケゴザイマセン",
            "confirm" to "ショウチシマシタ",
            "decline" to "ソノヒハツゴウデス",
            "meeting" to "カイギハジュウジカラデス",
            "deadline" to "シメキリハアシタデス",
            "transport" to "デンシャガオクレテイマス",
            "shopping" to "スーパーデギュウニュウヲカイマス",
            "cooking" to "ユウゴハンガデキマシタ",
            "study" to "ベンキョウガアルイテイキマス",
            "health" to "カラダノチョウシガワルイデス",
            "family" to "カゾクトショクジヲシマス",
            "travel" to "ライシュウキョウトニイキマス",
            "money" to "サイフヲワスレマシタ",
            "phone" to "デンワバンゴウヲオシエテクダサイ",
            "address" to "ジュウショハドコデスカ",
            "time" to "イマナンジデスカ",
            "name" to "オナマエヲオシエテクダサイ",
            "thanks2" to "タスカリマシタ",
            "closing" to "シッレイシマス",
        )
    }
}
