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
 * Round 25: one op filter per process.
 *
 * The HTP backend reads GGML_HEXAGON_OPFILTER once, in a static initialiser, so a
 * single process cannot compare filters. The filter comes from an instrumentation
 * argument and the harness runs this test once per filter.
 *
 *   -e opfilter MUL_MAT
 */
@RunWith(AndroidJUnit4::class)
class ZenzOpFilterSingleTest {

    @Test(timeout = 600_000)
    fun runWithFilter() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val filter = InstrumentationRegistry.getArguments().getString("opfilter").orEmpty()
        val label = InstrumentationRegistry.getArguments().getString("label").orEmpty()
            .ifEmpty { "case" }

        val model = File(context.filesDir, "ggml-model-Q5_K_M.gguf")
        if (!model.exists() || model.length() == 0L) {
            context.assets.open("ggml-model-Q5_K_M.gguf").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("model must exist", model.length() > 0)

        ZenzEngine.setHexagonOpFilter(filter)
        ZenzEngine.setNpuEnabled(true)
        ZenzEngine.setMarginTau(0.0f)

        val report = StringBuilder()
        report.appendLine("label=$label filter=[$filter]")

        val loaded = ZenzEngine.initModel(model.absolutePath)
        report.appendLine("loaded=$loaded")
        if (loaded) {
            ZenzEngine.setRuntimeConfig(512, 4)
            val dumpPath = File(context.filesDir, "zenz_opfilter_$label.txt").absolutePath
            File(dumpPath).delete()
            ZenzEngine.setLogitDumpPath(dumpPath)
            try {
                val text = ZenzEngine.generateWithContext("", "セイドガタカイ", 24)
                report.appendLine("text=$text")
            } finally {
                ZenzEngine.closeModel()
            }
            val raw = File(dumpPath).takeIf { it.exists() }?.readText().orEmpty()
            raw.lineSequence().filter { it.contains("pos=0") }.forEach { report.appendLine(it) }
        }

        context.openFileOutput("zenz_opfilter_$label.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have a result", report.contains("loaded="))
    }
}
