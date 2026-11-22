package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kazumaproject.markdownhelperkeyboard.variant.AppVariantConfig
import com.kazumaproject.zenz.ZenzEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round 25: dump the final norm and logits for one backend.
 *
 * The backend comes from an instrumentation argument so the harness can run the
 * same test twice, once on the NPU and once on the CPU, and compare the files.
 *
 *   -e backend npu|cpu
 */
@RunWith(AndroidJUnit4::class)
class ZenzTensorDumpTest {

    @Test(timeout = 600_000)
    fun dumpTensors() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val backend = InstrumentationRegistry.getArguments().getString("backend").orEmpty()
            .ifEmpty { "npu" }
        val useNpu = backend == "npu"

        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        val dumpPath = File(context.filesDir, "zenz_tensors_$backend.txt").absolutePath
        File(dumpPath).delete()

        ZenzEngine.setHexagonOpFilter("")
        ZenzEngine.setTensorDumpPath(dumpPath)
        ZenzEngine.setNpuEnabled(useNpu)
        ZenzEngine.setMarginTau(0.0f)

        val report = StringBuilder()
        report.appendLine("backend=$backend npu=$useNpu")

        val loaded = ZenzEngine.initModel(model.absolutePath)
        report.appendLine("loaded=$loaded")
        if (loaded) {
            ZenzEngine.setRuntimeConfig(512, 4)
            try {
                val text = ZenzEngine.generateWithContext("", "セイドガタカイ", 24)
                report.appendLine("text=$text")
            } finally {
                ZenzEngine.closeModel()
            }
        }
        ZenzEngine.setTensorDumpPath("")

        val dump = File(dumpPath)
        report.appendLine("dump_bytes=${if (dump.exists()) dump.length() else 0}")
        context.openFileOutput("zenz_tensor_dump_$backend.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("dump must exist", dump.exists() && dump.length() > 0)
    }
}
