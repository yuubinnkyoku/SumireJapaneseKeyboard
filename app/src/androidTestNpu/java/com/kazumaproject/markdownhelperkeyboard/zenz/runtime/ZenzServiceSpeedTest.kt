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
 * Measures the `:zenz` service latency for NPU and CPU.
 *
 * This runs inside the npu app, which declares `<uses-native-library>` for
 * libcdsprpc.so, so the Hexagon backend is reachable here. The zenz module's own
 * test APK does not declare it and always falls back to CPU.
 */
@RunWith(AndroidJUnit4::class)
class ZenzServiceSpeedTest {

    @Test(timeout = 600_000)
    fun measureServiceLatency() {
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

        val readings = listOf(
            "セイドガタカイ",
            "カンジヘンカン",
            "ニホンニュウリョク",
            "キョウハテンキ",
        )
        val report = StringBuilder()
        report.appendLine("zenz_npu=${shell(context, "settings get global zenz_npu").trim()}")

        val connectLatch = CountDownLatch(1)
        var remote: IZenzRuntime? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = IZenzRuntime.Stub.asInterface(service)
                connectLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = context.bindService(
            Intent(context, ZenzRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        report.appendLine("bindService=$bound")
        assertTrue("service bind", connectLatch.await(20, TimeUnit.SECONDS))
        val runtime = remote!!

        val initLatch = CountDownLatch(1)
        var initError = ""
        val initCallback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) {
                report.appendLine("ready pid=$processId")
                initLatch.countDown()
            }

            override fun onStringResult(requestId: Long, result: String) = Unit
            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                initError = message
                initLatch.countDown()
            }
        }
        val initStart = System.nanoTime()
        runtime.initialize(1L, model.absolutePath, 512, 4, initCallback)
        assertTrue("initialize", initLatch.await(180, TimeUnit.SECONDS))
        val initMs = (System.nanoTime() - initStart) / 1_000_000.0
        report.appendLine("init_ms=$initMs error=[$initError]")
        assertTrue("initialize failed: $initError", initError.isEmpty())

        val samples = mutableListOf<Double>()
        var requestId = 10L
        try {
            // Warm up once.
            generate(runtime, requestId++, readings.first(), report, "warmup")
            repeat(3) {
                readings.forEach { reading ->
                    val start = System.nanoTime()
                    val text = generate(runtime, requestId++, reading, report, "run")
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    samples += ms
                    report.appendLine("reading=$reading ms=$ms text=$text")
                }
            }
        } finally {
            runCatching { runtime.closeEngine() }
            runCatching { context.unbindService(connection) }
        }

        if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            report.appendLine("min_ms=${sorted.first()}")
            report.appendLine("median_ms=${sorted[sorted.size / 2]}")
            report.appendLine("max_ms=${sorted.last()}")
        }
        context.openFileOutput("zenz_service_speed_round22.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("no samples:\n$report", samples.isNotEmpty())
    }

    private fun generate(
        runtime: IZenzRuntime,
        requestId: Long,
        reading: String,
        report: StringBuilder,
        label: String,
    ): String {
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
        runtime.generate(requestId, "", "", "", "", "", "", reading, 24, callback)
        if (!latch.await(120, TimeUnit.SECONDS)) {
            report.appendLine("$label timeout reading=$reading")
            return ""
        }
        if (error.isNotEmpty()) report.appendLine("$label error=[$error] reading=$reading")
        return result
    }

    private fun shell(context: Context, command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also {
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
