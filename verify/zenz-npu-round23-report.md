# 第23ラウンド: xsmall vs small の拡張ABベンチとモデル選択スイッチ

実施日: 2026-11-22
端末: nubia NX741J（再起動なし）
モデル: v3.2-xsmall Q5_K_M（20.0 MB）と v3.2-small Q5_K_M（70.4 MB）
ビルド: `:app:assembleNpuStandardDebug -PzenzLogitDump=true`

## 1. 結論

**xsmall を本命として維持する。** small は精度で同等以上だが、仲裁レイテンシが5倍悪く、init が3倍遅い。

| 項目 | xsmall | small | 優位 |
|---|---|---|---|
| 精度（30例） | 27/30 | 27/30 | 同等 |
| init | **654 ms** | 2101 ms | xsmall |
| 無トリガー変換 | **64 ms** | 120 ms | xsmall |
| トリガー変換 | **1009 ms** | 5333 ms | xsmall |
| トリガー率（τ=1.5） | 47% | **35%** | small |
| seido gap | 0.3838（トリガー） | **1.9452（トリガーせず）** | small |
| APK（両モデル同梱） | - | - | 228.6 MB |

**small の仲裁レイテンシが壊滅的に悪い。** 第18ラウンドで「small の CPU が速い（269.6ms vs 552.9ms）」と測ったが、**同一セッション AB では逆転した**（5333ms vs 1009ms）。ラウンド間の見かけの差だった。

**モデル選択スイッチは実装・検証済み。** npu flavor 限定で動作し、full ビルドには表示されない。

## 2. Phase 1: 準備と整合確認

### 2.1 モデルの sha256

| モデル | サイズ | sha256 |
|---|---|---|
| v3.2-xsmall Q5_K_M | 20.0 MB | `00C64B3D318045A708D0CAD5434FACCAB10F5481A49E6362864551FD0995FA58` |
| v3.2-small Q5_K_M | 70.4 MB | `29C223D4C23327B80FD13EBB5AB2555057A46317997D5DA391584FFBEF0DB673` |

xsmall の sha256 は round22 のバックアップと**完全一致**した。

### 2.2 端末の `files/` 削除

round22 の落とし穴（旧モデル優先）を避けるため、測定前に必ず削除した。

```powershell
adb shell run-as com.kazumaproject.markdownhelperkeyboard.npu rm -f files/ggml-model-Q5_K_M.gguf files/ggml-model-small-Q5_K_M.gguf
```

## 3. Phase 2: 同一セッション AB ベンチ

### 3.1 方法

ABBA ブロックを3反復（xsmall→small→small→xsmall→xsmall→small）。各ブロックでモデルをアセットから `files/` にコピーし、`:zenz` プロセスを作り直した。

17例（短4例 + 日常11例 + 長文脈2例）を各ブロックで実行。

### 3.2 init 時間

| モデル | min | median | max |
|---|---|---|---|
| xsmall | 646 ms | **654 ms** | 747 ms |
| small | 1955 ms | **2101 ms** | 2443 ms |

**small は約3.2倍遅い。** モデルサイズ比（3.5倍）とほぼ一致。

### 3.3 変換レイテンシ（中央値）

| ケース | xsmall | small | 比 |
|---|---|---|---|
| seido | 673 ms | **112 ms** | 0.17x |
| kanji | 26 ms | 56 ms | 2.2x |
| nihongo | 41 ms | 69 ms | 1.7x |
| kyouha | 1009 ms | **4237 ms** | 4.2x |
| greeting | 58 ms | 109 ms | 1.9x |
| thanks | 81 ms | 153 ms | 1.9x |
| schedule | 79 ms | 170 ms | 2.2x |
| weather | 74 ms | 161 ms | 2.2x |
| food | 1334 ms | **5418 ms** | 4.1x |
| work | 74 ms | 159 ms | 2.1x |
| place | 888 ms | **4870 ms** | 5.5x |
| request | 1203 ms | **6007 ms** | 5.0x |
| question | 934 ms | **4524 ms** | 4.8x |
| short | 326 ms | **68 ms** | 0.21x |
| medium | 54 ms | 200 ms | 3.7x |
| longctx1 | 103 ms | 224 ms | 2.2x |
| longctx2 | 1581 ms | **5887 ms** | 3.7x |

**トリガーしたケースは small が 4-5倍遅い。** トリガーしないケースは small が 2倍程度遅い。

**例外は seido と short。** small は seido でトリガーしない（gap 1.9452）ので 112ms で済む。xsmall はトリガーして 673ms。

### 3.4 トリガー率と仲裁レイテンシ

| モデル | トリガー率 | 無トリガー中央値 | トリガー中央値 |
|---|---|---|---|
| xsmall | 24/51 (47%) | **64 ms** | **1009 ms** |
| small | 18/51 (35%) | 120 ms | **5333 ms** |

**トリガー率は small が低い（35% vs 47%）が、トリガー時のコストが5.3倍。**

UX の積（頻度 × 長さ）で比較すると:

- xsmall: 0.47 × 1009 = 474
- small: 0.35 × 5333 = 1867

**small の方が約4倍悪い。**

### 3.5 seido の margin

| ブロック | モデル | gap | トリガー | 出力 |
|---|---|---|---|---|
| 0 | xsmall | 0.3838 | 1 | 精度が高い |
| 1 | small | **1.9452** | 0 | 精度が高い |
| 2 | small | 1.9452 | 0 | 精度が高い |
| 3 | xsmall | 0.3838 | 1 | 精度が高い |
| 4 | xsmall | 0.3838 | 1 | 精度が高い |
| 5 | small | 1.9452 | 0 | 精度が高い |

**small は案1なしで正解を返す。** これは small の利点だが、他のケースの遅さが上回る。

### 3.6 CPU 逆転の訂正

第18ラウンドで「small CPU 269.6ms が xsmall CPU 552.9ms より速い」と報告したが、**同一セッション AB では逆転した**。

- 第18ラウンド: 別セッション比較（xsmall を測ってから small を測った）
- 第23ラウンド: 同一セッション ABBA

**ラウンド間の見かけの差だった。** 速度比較は同一セッション AB で行う原則を再確認した。

## 4. Phase 3: 精度比較（30例）

### 4.1 結果

| モデル | 正変換 | 誤変換 | 備考 |
|---|---|---|---|
| xsmall | 27/30 | 3 | decline, study, closing |
| small | 27/30 | 3 | 同上 |

**誤変換は両モデルで完全に同一:**

| ラベル | 読み | 出力 | 判定 |
|---|---|---|---|
| decline | ソノヒハツゴウデス | その日は都合です | 誤（「不都合」が正解） |
| study | ベンキョウガアルイテイキマス | 勉強が歩いていきます | 誤（入力自体が不自然） |
| closing | シッレイシマス | シッ〇します | 誤（未知語の処理） |

**違いは1箇所だけ:**

| ラベル | xsmall | small |
|---|---|---|
| cooking | 夕ご飯が**出来**ました | 夕ご飯が**でき**ました |

small の方が自然（ひらがな）。ただしこれは好みの範囲。

### 4.2 長文脈の境界

`long`（37文字）は両モデルで空出力。**31文字超で即 EOS になる仕様は small でも同一。**

### 4.3 min gap 分布

| モデル | min gap 中央値 | トリガー率（τ=1.5） |
|---|---|---|
| xsmall | 1.75（round21） | 50%（12例） |
| small | 未測定（17例ベンチで 35%） | 35% |

## 5. Phase 4: モデル選択スイッチ

### 5.1 実装

**`AppPreference`:**

```kotlin
private val ZENZ_MODEL_PREFERENCE = Pair("zenz_model_preference", "xsmall")

var zenz_model_preference: String
    get() = preferences.getString(ZENZ_MODEL_PREFERENCE.first, ZENZ_MODEL_PREFERENCE.second)
        ?: ZENZ_MODEL_PREFERENCE.second
    set(value) = preferences.edit {
        it.putString(ZENZ_MODEL_PREFERENCE.first, value)
        it.putString(ZENZ_MODEL_SELECTION_VERSION, java.util.UUID.randomUUID().toString())
    }
```

**`selectionVersion` の更新が必須だった。** IME は `cachedZenzModelPath` をキャッシュしており、`selectionVersion` が変わらないと再解決しない。最初の実装ではこれが抜けていて、small に切り替えても xsmall が使われた。

**`IMEService.resolveZenzModelPath`:**

```kotlin
val defaultAssetFileName = if (BuildConfig.APP_EDITION == "full" && hasSecondZenzModel()) {
    if (AppPreference.zenz_model_preference == "small") {
        "ggml-model-small-Q5_K_M.gguf"
    } else {
        "ggml-model-Q5_K_M.gguf"
    }
} else {
    "ggml-model-Q5_K_M.gguf"
}
```

`hasSecondZenzModel()` はアセットの存在確認で判定する。full ビルドには small アセットがないので、自動的に既定モデルになる。

**UI（`app/src/npu/res/xml/pref_zenz.xml`）:**

```xml
<ListPreference
    android:key="zenz_model_preference"
    android:title="@string/zenz_model_preference_title"
    android:entries="@array/zenz_model_entries"
    android:entryValues="@array/zenz_model_values"
    app:defaultValue="xsmall"
    app:summary="@string/zenz_model_preference_summary"
    app:useSimpleSummaryProvider="true" />
```

**npu フレーバー専用リソースに置いた。** `app/src/npu/res/xml/pref_zenz.xml` が `app/src/main/res/xml/pref_zenz.xml` を上書きする。round22 で指摘した「full ビルドにも NPU スイッチが表示される問題」をモデル選択では繰り返さない。

**CPU 仲裁のキャッシュ無効化:** `closeModel` が `close_cpu_arb_locked()` を呼ぶので、モデル切替時に自動的に無効化される。追加実装は不要だった。

### 5.2 検証

**UI 表示（npu flavor）:**

スクリーンショット `_artifacts/round23/model_switch.png` で確認。

```text
AI変換に使うモデル
xsmall（高速・21MB）
```

タップすると選択ダイアログが出る:

```text
AI変換に使うモデル
xsmall（高速・21MB）
small（高精度・74MB）
キャンセル
```

**切替の動作:**

| 設定 | コピーされたファイル | サイズ |
|---|---|---|
| small | `ggml-model-small-Q5_K_M.gguf` | 73,871,936 bytes |
| xsmall | `ggml-model-Q5_K_M.gguf` | 20,970,304 bytes |

**両方向とも正しく切り替わった。**

**full ビルドに表示されないこと:**

full のマージ済みリソースを確認:

```text
model switch absent (good)
npu switch present
```

`zenz_model_preference` は full に存在しない。**NPU スイッチは full にも出る**が、これは round22 で指摘した既存の問題で今回のスコープ外。

### 5.3 APK サイズ

| 構成 | サイズ |
|---|---|
| xsmall のみ（round22） | 160 MB |
| small のみ（round18） | 202 MB |
| **両モデル同梱** | **228.6 MB** |

増分は +68.6 MB（xsmall のみ比）。

## 6. 判定枠組みへの当てはめ

| 基準 | xsmall | small | 判定 |
|---|---|---|---|
| ① 精度（30例） | 27/30 | 27/30 | 同等 |
| ② 速度（NPU） | 64ms | 120ms | xsmall |
| ③ UX（トリガー率×レイテンシ） | 474 | 1867 | **xsmall** |
| ④ メモリ・init・APK | 654ms / 160MB | 2101ms / 202MB | xsmall |
| ⑤ seido 揺れ | 案1でカバー | 案1不要 | small |

**xsmall 優位。** ⑤ だけ small が勝るが、①〜④ で xsmall が勝る。

**既定は xsmall のまま。** スイッチは検証・将来の高量子化版差し替え基盤として残す。

## 7. 残る課題

1. **small の仲裁レイテンシがなぜ 5倍なのか未調査。** CPU 推論が遅いのか、モデルロードが毎回走っているのか。`g_cpu_arb` のキャッシュが効いていない可能性がある。
2. **メモリ（RSS）が未測定。** テスト終了時にプロセスが落ちるため、アイドル時の測定ができなかった。
3. **min gap 分布（small、12例）が未測定。** 17例ベンチのトリガー率 35% のみ。
4. **NPU スイッチが full ビルドにも出る問題**（round22 から継続、今回のスコープ外）。

## 8. 成果物

- `_artifacts/round23/v32-xsmall-q5k.gguf` — xsmall モデル
- `_artifacts/round23/v32-small-q5k.gguf` — small モデル
- `_artifacts/round23/ab_bench.txt` — AB ベンチ生ログ
- `_artifacts/round23/ab_analysis.txt` — AB 分析
- `_artifacts/round23/accuracy_ab.txt` — 30例の精度比較
- `_artifacts/round23/model_switch.png` — スイッチのスクリーンショット
- `_artifacts/round23/analyze_ab.ps1` — 分析スクリプト

## 9. ローカル状態

- 端末: `.npu` に両モデル同梱版をインストール済み。モデル選択は **xsmall**。NPU スイッチ ON。既定IMEは本番アプリ。
- 熱条件: 開始時 41.0°C、AB ベンチ後 42.0°C、終了時 30.0°C。AB ベンチは 207秒、精度比較は 76秒。
- push / commit / PR なし。端末再起動なし。
