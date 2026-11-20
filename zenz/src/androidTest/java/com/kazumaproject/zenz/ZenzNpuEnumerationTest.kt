package com.kazumaproject.zenz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZenzNpuEnumerationTest {

    @Test
    fun enumerate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ZenzEngine.setNpuEnabled(true)
        val available = ZenzEngine.isNpuAvailable()
        val summary = ZenzEngine.getBackendDeviceSummary()
        File(context.filesDir, "npu-enumeration.txt").writeText(
            "available=$available\n$summary\n"
        )
    }
}
