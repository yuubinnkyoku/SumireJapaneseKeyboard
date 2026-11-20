package com.kazumaproject.zenz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures the native generation latency for NPU and CPU on the same inputs.
 *
 * The UI path adds screenshot polling and IME dispatch, which hides the real
 * difference, so this test calls the engine directly.
 */
@RunWith(AndroidJUnit4::class)
class ZenzNpuSpeedTest {

    @Test
    fun measureNpuAndCpuLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            instrumentation.context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        val readings = listOf(
            "セイドガタカイ",
            "カンジヘンカン",
            "ニホンゴニュウリョク",
            "キョウハテンキガヨイ",
        )
        val report = StringBuilder()

        fun run(label: String, npu: Boolean) {
            ZenzEngine.setNpuEnabled(npu)
            val available = ZenzEngine.isNpuAvailable()
            val initStart = System.nanoTime()
            val loaded = ZenzEngine.initModel(model.absolutePath)
            val initMs = (System.nanoTime() - initStart) / 1_000_000.0
            report.appendLine("$label npu_requested=$npu available=$available init_ok=$loaded init_ms=$initMs")
            if (!loaded) return
            ZenzEngine.setRuntimeConfig(512, 4)
            try {
                // Warm up once so the first call does not dominate.
                ZenzEngine.generateWithContext("", readings.first(), 24)
                val samples = mutableListOf<Double>()
                repeat(3) {
                    readings.forEach { reading ->
                        val start = System.nanoTime()
                        val text = ZenzEngine.generateWithContext("", reading, 24)
                        val ms = (System.nanoTime() - start) / 1_000_000.0
                        samples += ms
                        report.appendLine("$label reading=$reading ms=$ms text=$text")
                    }
                }
                val sorted = samples.sorted()
                report.appendLine(
                    "$label min_ms=${sorted.first()} median_ms=${sorted[sorted.size / 2]} " +
                        "max_ms=${sorted.last()}"
                )
            } finally {
                ZenzEngine.closeModel()
            }
        }

        run("NPU", true)
        run("CPU", false)

        context.openFileOutput("zenz_native_speed_round22.txt", android.content.Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must contain both runs", report.contains("NPU") && report.contains("CPU"))
    }
}
