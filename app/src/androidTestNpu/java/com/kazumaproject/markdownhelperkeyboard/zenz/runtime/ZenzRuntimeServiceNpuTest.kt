package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ZenzRuntimeServiceNpuTest {

    private val cases = listOf(
        "セイドガタカイ" to "精度が高い",
        "カンジヘンカン" to "漢字変換",
        "ニホンゴニュウリョク" to "日本語入力",
        "キョウハテンキガヨイ" to "今日は天気が良い",
    )

    @Test
    fun generateFourCases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val npuSetting = Settings.Global.getInt(context.contentResolver, "zenz_npu", 0)
        val htpFailTest = Settings.Global.getInt(context.contentResolver, "zenz_htp_fail_test", 0)
        val sb = StringBuilder()
        sb.append("zenz_npu=$npuSetting\n")
        sb.append("zenz_htp_fail_test=$htpFailTest\n")
        sb.append("model=${model.length()}\n")

        val connectLatch = CountDownLatch(1)
        var remote: IZenzRuntime? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = IZenzRuntime.Stub.asInterface(service)
                connectLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }
        val bound = context.bindService(
            Intent(context, ZenzRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        sb.append("bindService=$bound\n")
        assertTrue("service bind", connectLatch.await(15, TimeUnit.SECONDS))
        val runtime = remote!!

        var pid = -1
        val initLatch = CountDownLatch(1)
        val initCallback = callback(
            onReady = { _, processId ->
                pid = processId
                initLatch.countDown()
            },
            onError = { _, message ->
                sb.append("init error: $message\n")
                initLatch.countDown()
            },
        )
        val initStart = System.nanoTime()
        runtime.initialize(1L, model.absolutePath, 512, 4, initCallback)
        assertTrue("initialize", initLatch.await(120, TimeUnit.SECONDS))
        val initMs = (System.nanoTime() - initStart) / 1_000_000.0
        sb.append("init_ms=$initMs pid=$pid\n")

        cases.forEachIndexed { index, (input, expected) ->
            val latch = CountDownLatch(1)
            var out = ""
            val callback = callback(
                onString = { _, result ->
                    out = result ?: ""
                    latch.countDown()
                },
                onError = { _, message ->
                    out = "ERROR: $message"
                    latch.countDown()
                },
            )
            val start = System.nanoTime()
            runtime.generate(100L + index, "", "", "", "", "", "", input, 24, callback)
            assertTrue("generate $input", latch.await(120, TimeUnit.SECONDS))
            val ms = (System.nanoTime() - start) / 1_000_000.0
            sb.append("$input -> [$out] expected [$expected] ms=$ms\n")
        }

        sb.append("done\n")
        File(context.filesDir, "zenz_service_test.txt").writeText(sb.toString())

        runCatching { runtime.closeEngine() }
        context.unbindService(connection)
    }

    private fun callback(
        onReady: (Long, Int) -> Unit = { _, _ -> },
        onString: (Long, String?) -> Unit = { _, _ -> },
        onScores: (Long, FloatArray?) -> Unit = { _, _ -> },
        onError: (Long, String?) -> Unit = { _, _ -> },
    ): IZenzRuntimeCallback = object : IZenzRuntimeCallback.Stub() {
        override fun onReady(requestId: Long, processId: Int) = onReady(requestId, processId)
        override fun onStringResult(requestId: Long, result: String?) = onString(requestId, result)
        override fun onScoresResult(requestId: Long, scores: FloatArray?) = onScores(requestId, scores)
        override fun onError(requestId: Long, message: String?) = onError(requestId, message)
    }
}
