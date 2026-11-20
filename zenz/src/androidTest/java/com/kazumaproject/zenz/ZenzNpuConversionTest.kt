package com.kazumaproject.zenz

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZenzNpuConversionTest {

    @Test
    fun npuConversionAndCpuFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val model = File(targetContext.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            instrumentation.context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        ZenzEngine.setNpuEnabled(true)
        val npuAvailable = ZenzEngine.isNpuAvailable()
        val deviceSummary = ZenzEngine.getBackendDeviceSummary()
        Log.i("zenz-npu-test", "isNpuAvailable=$npuAvailable devices=$deviceSummary")
        assertTrue("Hexagon device must be available; devices=$deviceSummary", npuAvailable)

        assertTrue("initModel with NPU requested", ZenzEngine.initModel(model.absolutePath))
        ZenzEngine.setRuntimeConfig(512, 4)
        try {
            assertEquals("精度が高い", ZenzEngine.generateWithContext("", "セイドガタカイ", 24))
            assertEquals("漢字変換", ZenzEngine.generateWithContext("", "カンジヘンカン", 24))
            assertEquals("日本語入力", ZenzEngine.generateWithContext("", "ニホンゴニュウリョク", 24))
            assertEquals("今日は天気が良い", ZenzEngine.generateWithContext("", "キョウハテンキガヨイ", 24))
        } finally {
            ZenzEngine.closeModel()
        }

        ZenzEngine.setNpuEnabled(false)
        assertTrue("initModel with CPU", ZenzEngine.initModel(model.absolutePath))
        ZenzEngine.setRuntimeConfig(512, 4)
        try {
            assertEquals("精度が高い", ZenzEngine.generateWithContext("", "セイドガタカイ", 24))
        } finally {
            ZenzEngine.closeModel()
        }
    }
}
