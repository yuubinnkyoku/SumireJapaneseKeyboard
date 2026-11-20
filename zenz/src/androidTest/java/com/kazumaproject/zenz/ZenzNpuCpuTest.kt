package com.kazumaproject.zenz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZenzNpuCpuTest {

    @Test
    fun cpuFallbackWithSdkCdsprpc() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val model = File(targetContext.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            instrumentation.context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

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
