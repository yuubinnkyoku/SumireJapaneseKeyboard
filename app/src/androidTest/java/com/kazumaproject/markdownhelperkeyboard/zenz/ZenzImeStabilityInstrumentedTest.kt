package com.kazumaproject.markdownhelperkeyboard.zenz

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kazumaproject.markdownhelperkeyboard.FastInputHostActivity
import com.kazumaproject.markdownhelperkeyboard.variant.AppVariantConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keeps the IME busy for a few minutes and checks that the `:zenz` process
 * survives repeated NPU conversions.
 *
 * The ten-key grid is measured from a 1216x2688 screenshot: columns are
 * centered at x = 364/608/852 and rows at y = 1912/2107/2307.
 */
@RunWith(AndroidJUnit4::class)
class ZenzImeStabilityInstrumentedTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val uiAutomation: UiAutomation
        get() = instrumentation.uiAutomation

    @Test(timeout = 600_000)
    fun repeatedConversionsKeepZenzAlive() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val context = instrumentation.targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val originalPreferences = preferences.all.toMap()
        var scenario: ActivityScenario<FastInputHostActivity>? = null

        try {
            applyScenario(preferences)
            uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            val imeComponent =
                "${context.packageName}/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService"
            val imeSetOutput = shell("ime set $imeComponent")
            assertTrue(
                "ime set failed: $imeSetOutput",
                !imeSetOutput.contains("Unknown", ignoreCase = true) &&
                    !imeSetOutput.contains("cannot be", ignoreCase = true),
            )
            SystemClock.sleep(700L)

            scenario = ActivityScenario.launch(
                Intent(context, FastInputHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            restartInput(scenario)
            SystemClock.sleep(1_500L)

            val screenshot = uiAutomation.takeScreenshot()
            val width = screenshot.width
            val height = screenshot.height
            screenshot.recycle()

            val readings = listOf(
                PointF(width * 0.299f, height * 0.712f),
                PointF(width * 0.500f, height * 0.712f),
                PointF(width * 0.701f, height * 0.712f),
                PointF(width * 0.299f, height * 0.784f),
            )
            val convert = PointF(width * 0.901f, height * 0.856f)
            val delete = PointF(width * 0.901f, height * 0.712f)

            val report = StringBuilder()
            report.appendLine("zenz_npu=${shell("settings get global zenz_npu").trim()}")
            val startPid = shell("pidof ${context.packageName}:zenz").trim()
            report.appendLine("start_pid=$startPid")

            val rounds = 12
            var conversions = 0
            val startedAt = SystemClock.uptimeMillis()
            for (round in 1..rounds) {
                readings.forEach { point ->
                    injectTouch(point = point, holdMillis = 40L)
                    SystemClock.sleep(110L)
                }
                SystemClock.sleep(500L)
                val typed = readText(scenario)
                if (typed.length < 4) {
                    report.appendLine("round=$round typed=[$typed] SKIP")
                    restartInput(scenario)
                    SystemClock.sleep(1_200L)
                    continue
                }
                injectTouch(point = convert, holdMillis = 650L)
                SystemClock.sleep(2_500L)
                conversions++
                val pid = shell("pidof ${context.packageName}:zenz").trim()
                val converted = readText(scenario)
                // The long press adds zenz candidates; it does not commit them. The editor
                // keeps the reading, so the survival check is the process id.
                report.appendLine("round=$round typed=[$typed] editor=[$converted] pid=$pid")
                if (round == 2) {
                    shell("screencap -p /sdcard/zenz_stab_round2.png")
                }
                if (pid.isEmpty()) {
                    report.appendLine("FAIL: zenz process died at round $round")
                    break
                }
                // Clear the editor for the next round.
                repeat(typed.length + 8) {
                    injectTouch(point = delete, holdMillis = 30L)
                    SystemClock.sleep(60L)
                }
                SystemClock.sleep(400L)
                val cleared = readText(scenario)
                report.appendLine("round=$round cleared=[$cleared]")
            }
            val elapsedMs = SystemClock.uptimeMillis() - startedAt
            val endPid = shell("pidof ${context.packageName}:zenz").trim()
            report.appendLine("conversions=$conversions elapsed_ms=$elapsedMs")
            report.appendLine("end_pid=$endPid")

            writeResult("zenz_ime_stability_round22.txt", report.toString())
            assertTrue(
                "Zenz process died during the stability run:\n$report",
                endPid.isNotEmpty(),
            )
            assertTrue(
                "Too few conversions completed: $conversions\n$report",
                conversions >= 8,
            )
        } finally {
            scenario?.close()
            restorePreferences(preferences, originalPreferences)
            uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun applyScenario(preferences: SharedPreferences) {
        check(
            preferences.edit()
                .putString("keyboard_order_preference", """["TENKEY"]""")
                .putBoolean("save_last_used_keyboard", false)
                .putBoolean("keyboard_floating_preference", false)
                .putString("physical_keyboard_input_mode_preference", "romaji")
                .putBoolean("candidate_tab_visibility_preference", false)
                .putString("candidate_column_preference", "1")
                .putInt("candidate_view_height_dp_preference", 110)
                .putInt("candidate_view_empty_height_dp_preference", 110)
                .putInt("keyboard_height_preference", 280)
                .putBoolean("live_conversion_preference", false)
                .putBoolean("enable_ai_conversion_zenz_preference", false)
                .putBoolean("conversion_key_long_press_ai_conversion_preference", true)
                .putBoolean("enable_ai_conversion_zenzai_preference", false)
                .putBoolean("enable_zenz_rerank_preference", false)
                .putInt("zenz_debounce_time_preference", 0)
                .putInt("long_press_timeout_preference", 100)
                .commit()
        ) {
            "Could not prepare the stability scenario"
        }
    }

    private fun restartInput(scenario: ActivityScenario<FastInputHostActivity>) {
        scenario.onActivity { activity ->
            activity.editText.setText("")
            activity.editText.requestFocus()
            val inputMethodManager = activity.getSystemService(InputMethodManager::class.java)
            inputMethodManager.restartInput(activity.editText)
            inputMethodManager.showSoftInput(
                activity.editText,
                InputMethodManager.SHOW_IMPLICIT,
            )
        }
    }

    private fun readText(scenario: ActivityScenario<FastInputHostActivity>): String {
        var text = ""
        scenario.onActivity { activity ->
            text = activity.editText.text?.toString().orEmpty()
        }
        return text
    }

    private fun injectTouch(point: PointF, holdMillis: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point)
        val downInjected = uiAutomation.injectInputEvent(down, true)
        down.recycle()
        SystemClock.sleep(holdMillis)

        val upTime = SystemClock.uptimeMillis()
        val up = motionEvent(downTime, upTime, MotionEvent.ACTION_UP, point)
        val upInjected = uiAutomation.injectInputEvent(up, true)
        up.recycle()
        return downInjected && upInjected
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        point: PointF,
    ): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = point.x
                y = point.y
                pressure = 1f
                size = 1f
            }
        )
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun writeResult(name: String, body: String) {
        instrumentation.targetContext.openFileOutput(name, Context.MODE_PRIVATE)
            .use { it.write(body.toByteArray()) }
    }

    private fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.bufferedReader().readText()
        }
    }

    private fun restorePreferences(
        preferences: SharedPreferences,
        original: Map<String, *>,
    ) {
        val editor = preferences.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        check(editor.commit()) { "Failed to restore preferences after the stability test" }
    }
}
