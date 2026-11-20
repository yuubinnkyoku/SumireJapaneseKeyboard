package com.kazumaproject.zenz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZenzNpuDiagnosticTest {

    @Test
    fun npuDiagnostic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val model = File(targetContext.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            instrumentation.context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        val sb = StringBuilder()
        ZenzEngine.setNpuEnabled(true)
        sb.append("available=${ZenzEngine.isNpuAvailable()}\n")
        sb.append("summary=${ZenzEngine.getBackendDeviceSummary()}\n")
        val loaded = ZenzEngine.initModel(model.absolutePath)
        sb.append("initModel=$loaded\n")
        if (loaded) {
            ZenzEngine.setRuntimeConfig(512, 4)
            try {
                val cases = listOf(
                    "セイドガタカイ" to "精度が高い",
                    "カンジヘンカン" to "漢字変換",
                    "ニホンゴニュウリョク" to "日本語入力",
                    "キョウハテンキガヨイ" to "今日は天気が良い",
                )
                for ((input, expected) in cases) {
                    val out = ZenzEngine.generateWithContext("", input, 24)
                    sb.append("$input -> [$out] expected [$expected]\n")
                }
            } finally {
                ZenzEngine.closeModel()
            }
        }
        File(targetContext.filesDir, "npu-diagnostic.txt").writeText(sb.toString())
    }
}
