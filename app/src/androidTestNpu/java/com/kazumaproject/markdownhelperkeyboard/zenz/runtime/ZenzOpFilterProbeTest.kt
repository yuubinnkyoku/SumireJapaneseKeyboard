package com.kazumaproject.markdownhelperkeyboard.zenz.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kazumaproject.markdownhelperkeyboard.variant.AppVariantConfig
import com.kazumaproject.zenz.ZenzEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 25: which op family causes the NPU logit scale shrink?
 *
 * Runs the same prompt with different GGML_HEXAGON_OPFILTER values. Each filter
 * pushes one op family back to the CPU, so the logits move toward the CPU answer
 * by the amount that family contributes.
 *
 * Runs in the app process, not the :zenz service, because the filter has to be set
 * before the backend initialises and the service owns that lifetime.
 */
@RunWith(AndroidJUnit4::class)
class ZenzOpFilterProbeTest {

    @Test(timeout = 1_800_000)
    fun probeOpFamilies() {
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

        val report = StringBuilder()
        val dumpPath = File(context.filesDir, "zenz_opfilter_probe.txt").absolutePath

        // Each entry: label, op filter regex (empty = no filter).
        val cases = listOf(
            "baseline" to "",
            "no_mul_mat" to "MUL_MAT",
            "no_flash_attn" to "FLASH_ATTN_EXT",
            "no_norm" to "NORM",
            "no_glu" to "GLU",
            "no_mul" to "MUL",
        )

        cases.forEach { (label, filter) ->
            File(dumpPath).delete()
            ZenzEngine.setHexagonOpFilter(filter)
            ZenzEngine.setNpuEnabled(true)
            ZenzEngine.setMarginTau(0.0f)   // no arbitration, we want raw NPU logits
            val loaded = ZenzEngine.initModel(model.absolutePath)
            report.appendLine("=== $label filter=[$filter] loaded=$loaded ===")
            if (loaded) {
                ZenzEngine.setRuntimeConfig(512, 4)
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
            report.appendLine()
        }

        ZenzEngine.setHexagonOpFilter("")
        context.openFileOutput("zenz_opfilter_probe_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("report must have cases", report.contains("baseline"))
    }
}
