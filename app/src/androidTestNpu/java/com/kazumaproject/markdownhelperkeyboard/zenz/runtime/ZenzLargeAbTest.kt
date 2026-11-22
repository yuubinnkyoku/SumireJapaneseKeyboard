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
 * Round 26: large-scale A/B between xsmall and small.
 *
 * Same prompt shape as the device: katakana reading, profile set, no left or right
 * context. Both models run in one session so the device state is shared.
 */
@RunWith(AndroidJUnit4::class)
class ZenzLargeAbTest {

    @Test(timeout = 3_600_000)
    fun largeAb() {
        assumeTrue("Zenz is only available in the full edition", AppVariantConfig.hasZenz)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val models = listOf(
            "xsmall" to "ggml-model-Q5_K_M.gguf",
            "small" to "ggml-model-small-Q5_K_M.gguf",
        )
        val paths = models.associate { (label, asset) ->
            val dest = File(context.filesDir, "ab_$label.gguf")
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open(asset).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            label to dest.absolutePath
        }

        val report = StringBuilder()
        report.appendLine("profile=[$PROFILE] cases=${INPUTS.size}")

        models.forEach { (label, _) ->
            val path = paths.getValue(label)
            report.appendLine("=== model=$label bytes=${File(path).length()} ===")

            val latch = CountDownLatch(1)
            var remote: IZenzRuntime? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    remote = IZenzRuntime.Stub.asInterface(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            assertTrue(
                "bind",
                context.bindService(
                    Intent(context, ZenzRuntimeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            assertTrue("connect", latch.await(20, TimeUnit.SECONDS))
            val runtime = remote!!

            try {
                val initLatch = CountDownLatch(1)
                var initFailure = ""
                val initCallback = object : IZenzRuntimeCallback.Stub() {
                    override fun onReady(requestId: Long, processId: Int) = initLatch.countDown()
                    override fun onStringResult(requestId: Long, result: String) = Unit
                    override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
                    override fun onError(requestId: Long, message: String) {
                        initFailure = message
                        initLatch.countDown()
                    }
                }
                runtime.initialize(1L, path, 512, 4, initCallback)
                assertTrue("init", initLatch.await(300, TimeUnit.SECONDS))
                if (initFailure.isNotEmpty()) {
                    report.appendLine("init failed: $initFailure")
                    return@forEach
                }

                var requestId = 500L
                INPUTS.forEachIndexed { index, (caseLabel, reading) ->
                    val start = SystemClock.uptimeMillis()
                    val text = generate(runtime, requestId++, reading)
                    val ms = SystemClock.uptimeMillis() - start
                    report.appendLine("idx=$index label=$caseLabel reading=$reading ms=$ms text=$text")
                }
            } finally {
                runCatching { runtime.closeEngine() }
                runCatching { context.unbindService(connection) }
                SystemClock.sleep(500L)
            }
        }

        context.openFileOutput("zenz_large_ab_report.txt", Context.MODE_PRIVATE)
            .use { it.write(report.toString().toByteArray()) }
        assertTrue("both models", report.contains("model=xsmall") && report.contains("model=small"))
    }

    private fun generate(runtime: IZenzRuntime, requestId: Long, reading: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        var failure = ""
        val callback = object : IZenzRuntimeCallback.Stub() {
            override fun onReady(requestId: Long, processId: Int) = Unit
            override fun onStringResult(requestId: Long, value: String) {
                result = value
                latch.countDown()
            }

            override fun onScoresResult(requestId: Long, scores: FloatArray) = Unit
            override fun onError(requestId: Long, message: String) {
                failure = message
                latch.countDown()
            }
        }
        runtime.generate(
            requestId, PROFILE, "", "", "",
            "", "", "\uEE00$reading\uEE01", 24, callback,
        )
        if (!latch.await(180, TimeUnit.SECONDS)) return "TIMEOUT"
        if (failure.isNotEmpty()) return "ERROR:$failure"
        return result
    }

    companion object {
        private const val PROFILE = "職業:プログラマー"

        /**
         * 120 readings across everyday speech, business, technical, and the
         * near-tie pairs that the earlier rounds kept hitting.
         */
        private val INPUTS = listOf(
            // Greetings and courtesy
            "greeting" to "オハヨウゴザイマス",
            "thanks" to "アリガトウゴザイマシタ",
            "apology" to "モウシワケゴザイマセン",
            "confirm" to "ショウチシマシタ",
            "closing" to "シッレイシマス",
            "thanks2" to "タスカリマシタ",
            "welcome" to "ヨウコソ",
            "goodnight" to "オヤスミナサイ",
            "congrats" to "オメデトウゴザイマス",
            "cheer" to "ガンバッテクダサイ",
            // Scheduling
            "schedule" to "アシタノゴゴサンジニカイギガアリマス",
            "meeting" to "カイギハジュウジカラデス",
            "deadline" to "シメキリハアシタデス",
            "postpone" to "カイギヲエンキシマシタ",
            "cancel" to "ヤクジョハキャンセルデス",
            "early" to "スコシハヤクツキマス",
            "late" to "スコシアトデス",
            "tomorrow" to "アシタハイソガシイデス",
            "weekend" to "シュウマツハアケテイマス",
            "holiday" to "ライシュウハレンキュウデス",
            // Weather and daily
            "weather" to "キョウハアメガフルソウデス",
            "hot" to "キョウハアツイデスネ",
            "cold" to "トテモサムイデス",
            "sunny" to "イイテンキデスネ",
            "typhoon" to "タイフウガキテイマス",
            "season" to "キセツノカワリメデス",
            // Food
            "food" to "ヒルゴハンハラーメンガタベタイデス",
            "cooking" to "ユウゴハンガデキマシタ",
            "coffee" to "コーヒーガノミタイデス",
            "restaurant" to "ヨヤクハデキマスカ",
            "hungry" to "オナカガスイテイマス",
            "delicious" to "トテモオイシイデス",
            "recipe" to "レシピヲオシエテクダサイ",
            "shopping" to "スーパーデギュウニュウヲカイマス",
            // Work
            "work" to "シゴトガオワッタラスグカエリマス",
            "overtime" to "ザンギョウガオオイデス",
            "report" to "ホウコクショヲテイシュツシマス",
            "review" to "レビューオネガイシマス",
            "deploy" to "デプロイハカンリョウデス",
            "bug" to "バグガミツカリマシタ",
            "test" to "テストガシッパイシマシタ",
            "build" to "ビルドガトオッタデス",
            "release" to "リリースヲエンキシマス",
            "review2" to "コードレビューヲオネガイシマス",
            // Technical
            "server" to "サーバーガオチテイマス",
            "database" to "データベースニセツゾクシマス",
            "memory" to "メモリガフソクシテイマス",
            "network" to "ネットワークガオソイデス",
            "security" to "セキュリティヲカクニンシマス",
            "backup" to "バックアップヲトルベキデス",
            "log" to "ログヲカクニンシマス",
            "api" to "エーピーアイヲツナギマス",
            "cache" to "キャッシュヲクリアシマス",
            "timeout" to "タイムアウトガハッセイシマシタ",
            // Precision and measurement (the near-tie family)
            "seido" to "セイドガタカイ",
            "seido2" to "セイドヲタカメル",
            "seido3" to "セイドガヒクイ",
            "seido4" to "セイドノカクニン",
            "seido5" to "セイドガタカイトオモイマス",
            "seido6" to "コノセイドハタカイデス",
            "seido7" to "セイドガタカイノデスネ",
            "seido8" to "セイドガタカイカドウカ",
            "seido9" to "セイドガタカイトイウケッカ",
            "seido10" to "セイドガタカイコトヲカクニン",
            // Other near-tie families
            "kousei" to "コウセイガタカイ",
            "seikaku" to "セイカクナスウチ",
            "seisaku" to "セイサクヲツクル",
            "seisaku2" to "セイサクガオワッタ",
            "kanshou" to "カンショウガアル",
            "kanshou2" to "カンショウヲスル",
            "shiyou" to "シヨウヲカクニン",
            "shiyou2" to "シヨウガワカル",
            "taishou" to "タイショウガアル",
            "taishou2" to "タイショウヲエラブ",
            // Questions
            "question" to "ナニジニデンシャガデマスカ",
            "address" to "ジュウショハドコデスカ",
            "time" to "イマナンジデスカ",
            "name" to "オナマエヲオシエテクダサイ",
            "phone" to "デンワバンゴウヲオシエテクダサイ",
            "price" to "ネダンハイクラデスカ",
            "why" to "ナゼソウナリマシタカ",
            "how" to "ドウヤッテヤリマスカ",
            "where" to "ドコニアリマスカ",
            "when" to "イツデスカ",
            // Places and travel
            "place" to "エキマデアルイテイキマス",
            "travel" to "ライシュウキョウトニイキマス",
            "transport" to "デンシャガオクレテイマス",
            "hotel" to "ホテルヲヨヤクシマシタ",
            "airport" to "クウコウニムカイマス",
            "map" to "チズヲミセテクダサイ",
            "direction" to "ミギニマガッテクダサイ",
            "station" to "エキハドコデスカ",
            "ticket" to "キップヲカイマス",
            "luggage" to "ニモツガオモイデス",
            // Health and body
            "health" to "カラダノチョウシガワルイデス",
            "hospital" to "ビョウインニイキマス",
            "medicine" to "クスリヲノミマス",
            "sleep" to "ヨクネムレマシタ",
            "tired" to "ツカレマシタ",
            "fever" to "ネツガアルミタイデス",
            "injury" to "ケガヲシマシタ",
            "exercise" to "ウンドウガヒツヨウデス",
            // Family and social
            "family" to "カゾクトショクジヲシマス",
            "friend" to "トモダチトアソビマス",
            "child" to "コドモガウマレマシタ",
            "marriage" to "ケッコンシマシタ",
            "party" to "パーティーヲシマス",
            "invite" to "ショウタイシマス",
            "gift" to "プレゼントヲカイマス",
            "celebrate" to "イワイマショウ",
            // Money and admin
            "money" to "サイフヲワスレマシタ",
            "payment" to "シキュウヲカクニンシマス",
            "invoice" to "セイキュウショヲオクル",
            "budget" to "ヨサンヲミツモル",
            "tax" to "ゼイキンノケイサン",
            "bank" to "ギンコウニイキマス",
            "contract" to "ケイヤクヲムスブ",
            "estimate" to "ミツモリショヲツクル",
            // Short and edge
            "short" to "ハイ",
            "short2" to "イイエ",
            "short3" to "ソウデス",
            "short4" to "ワカリマシタ",
            "medium" to "デンワヲカケテモイイデスカ",
            "request" to "コノショリョウヲカクニンシテクダサイ",
            "decline" to "ソノヒハツゴウデス",
            "study" to "ベンキョウガアルイテイキマス",
        )
    }
}
