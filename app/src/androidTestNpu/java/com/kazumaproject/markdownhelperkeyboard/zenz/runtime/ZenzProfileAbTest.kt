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
 * Round 26: the same prompt the device actually sends.
 *
 * The device sends katakana with the profile field set. Every earlier probe used
 * an empty profile, so the two never matched. This test runs both models over the
 * same 30 readings with the profile byte-identical to the device.
 */
@RunWith(AndroidJUnit4::class)
class ZenzProfileAbTest {

    @Test(timeout = 1_800_000)
    fun compareModelsWithProfile() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // Copy both models so each run uses its own path and the service reloads.
        val models = listOf("xsmall" to "ggml-model-Q5_K_M.gguf", "small" to "ggml-model-small-Q5_K_M.gguf")
        val paths = models.associate { (label, asset) ->
            val dest = File(context.filesDir, "ab_$label.gguf")
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open(asset).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            label to dest.absolutePath
        }

        val report = StringBuilder()
        report.appendLine("profile=[$PROFILE]")

        models.forEach { (label, _) ->
            val path = paths.getValue(label)
            report.appendLine("=== model=$label bytes=${File(path).length()} ===")

            val runtime = bindRuntime(context)
            try {
                val initFailure = initialize(runtime, path)
                if (initFailure.isNotEmpty()) {
                    report.appendLine("init failed: $initFailure")
                    return@forEach
                }
                var requestId = 300L
                INPUTS.forEachIndexed { index, (caseLabel, reading) ->
                    val text = generate(runtime, requestId++, reading)
                    report.appendLine("idx=$index label=$caseLabel reading=$reading text=$text")
                }
            } finally {
                runCatching { runtime.closeEngine() }
                runCatching { context.unbindService(connectionFor(context)) }
            }
        }

        context.openFileOutput("zenz_profile_ab_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have both models", report.contains("model=xsmall") && report.contains("model=small"))
    }

    private val connections = mutableListOf<ServiceConnection>()

    private fun connectionFor(context: Context): ServiceConnection =
        connections.lastOrNull() ?: object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
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
        connections += connection
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
        var failure = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = latch.countDown()
            override fun onStringResult(requestId: Long, result: String) = Unit
            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                failure = message
                latch.countDown()
            }
        }
        runtime.initialize(1L, modelPath, 512, 4, callback)
        if (!latch.await(300, TimeUnit.SECONDS)) return "init timeout"
        return failure
    }

    private fun generate(runtime: IZenzRuntime, requestId: Long, reading: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        var failure = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = Unit
            override fun onStringResult(requestId: Long, value: String) {
                result = value
                latch.countDown()
            }

            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                failure = message
                latch.countDown()
            }
        }
        runtime.generate(
            requestId, PROFILE, "", "", "",
            "", "", "\uEE00$reading\uEE01", 24, callback,
        )
        if (!latch.await(180, TimeUnit.SECONDS)) return "TIMEOUT"
        if (failure.isNotEmpty()) return "ERROR:$failure"
        return result
    }

    companion object {
        /** Byte-identical to what the device sends. */
        private const val PROFILE = "職業:プログラマー"

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
            "seido" to "セイドガタカイ",
        )
    }
}
