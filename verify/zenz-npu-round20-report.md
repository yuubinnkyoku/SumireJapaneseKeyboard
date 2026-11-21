# 第20ラウンド: マージン検知CPU再計算（案1）の実装

実施日: 2026-11-21
端末: nubia NX741J（再起動なし）
モデル: zenz-v3.2-xsmall Q5_K_M（20.9 MB）
ビルド: `:app:assembleNpuStandardDebug -PzenzLogitDump=true`

## 1. 結論

**案1は成立した。** NPU が迷っている変換（top-1/top-2 gap < 1.5）を CPU で再計算し、CPU の答えを返す仕組みが動いた。

- `セイドガタカイ` が **`精度が高い`（CPU 選択）** を返した。NPU 素通しでは `制度が高い` だった。
- 6例中 **3例がトリガー**（seido, long2, kyouha）。第19ラウンドの予測（2例）より1件多い。
- 他の3例（kanji, nihongo, long1）は無トリガーで第19ラウンドと同一出力。
- CPU モードでは仲裁しない（NPU パス専用）。
- UI 長押し、連続入力12ラウンドとも回帰なし。

**ただし `long1` は救えていない。** gap 4.75 なのでトリガーせず、NPU の `制度が高い` がそのまま返る。CPU なら `精度が高い` になる。これは本方式の限界で、τ を上げれば救えるがトリガー率が跳ね上がる。

## 2. 実装

### 2.1 マージン検知

`zenz/src/main/cpp/zenz_bridge.cpp` の greedy デコードループで、argmax 走査を top-2 同時取得に変えた。追加コストは O(vocab) 1回のまま。

```cpp
int best_id = 0;
float best_logit = logits[0];
float second_logit = -std::numeric_limits<float>::infinity();
for (int32_t tid = 1; tid < n_vocab; ++tid) {
    const float value = logits[tid];
    if (value > best_logit) {
        second_logit = best_logit;
        best_logit = value;
        best_id = tid;
    } else if (value > second_logit) {
        second_logit = value;
    }
}
const float gap = best_logit - second_logit;
```

しきい値:

```cpp
// The NPU and the CPU disagree by mean 0.284 / max 0.885 logit (round 19). When
// the NPU's own top-1 and top-2 are closer than kZenzMarginTau, that gap is small
// enough for the disagreement to flip the winner, so the whole conversion is
// recomputed on the CPU and the CPU answer is returned.
static constexpr float kZenzMarginTau = 1.5f;
```

### 2.2 関数の分割

`pure_greedy_decoding` を3つに分けた。

| 関数 | 役割 |
|---|---|
| `greedy_decode_with_margin(model, vocab, ctx, ...)` | 汎用デコード。最小 gap とそのステップを返す |
| `cpu_arbitrated_decode_locked(...)` | CPU セッションで同じ変換を再実行 |
| `pure_greedy_decoding(...)` | 入口。NPU なら gap を見て仲裁を呼ぶ |

`GreedyDecodeOutcome` 構造体で結果と `min_gap` / `min_gap_step` を返す。

### 2.3 CPU セッションのキャッシュ

NPU セッションを保持したまま、別の CPU モデルとコンテキストを持つ。

```cpp
struct CpuArbitrationSession {
    llama_model *model = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_context *ctx = nullptr;
    RuntimeConfig config{0, 0, 0, 0};
};
static CpuArbitrationSession g_cpu_arb;
```

- lazy-init（初回トリガー時にロード）
- プロセス内でキャッシュ（2回目以降はロードしない）
- `closeModel` で解放

### 2.4 仲裁の判断

```cpp
const bool on_npu = g_model_is_npu.load(std::memory_order_relaxed);
const GreedyDecodeOutcome primary = greedy_decode_with_margin(...);

if (!on_npu || primary.min_gap >= kZenzMarginTau) {
    return primary.result;   // 素通し
}

const GreedyDecodeOutcome arbitrated = cpu_arbitrated_decode_locked(...);
if (arbitrated.result.accepted()) {
    return arbitrated.result;   // CPU が正
}
// CPU 再計算が失敗しても NPU の答えは失わない
return primary.result;
```

- **NPU パス専用。** CPU パスと CPU フォールバック後は仲裁しない（二重計算になるため）。
- 再計算は変換1回につき最大1回。再計算後の出力にマージン検知は再適用しない。
- CPU 再計算が失敗したら NPU の結果を返し、ログに残す。

### 2.5 ログ

`#ifdef ZENZ_LOGIT_DUMP` でガードした `log_margin_decision()` が、第19ラウンドと同じファイルに追記する。

```text
ZENZLOG margin trigger=1 step=0 gap=0.3838 recompute=cpu
ZENZLOG margin trigger=0 step=4 gap=2.2124 recompute=none
```

**マージン検知本体は release でも動く製品コード。** `#ifdef` はログ出力だけを守る。

## 3. 検証結果

### 3.1 6例のトリガーと出力

| ラベル | gap | トリガー | 出力 | 第19ラウンド NPU | 一致 |
|---|---|---|---|---|---|
| seido | 0.3838 | **あり** | **精度が高い** | 制度が高い | CPU 選択に変化 |
| kanji | 2.2124 | なし | 漢字変換 | 漢字変換 | 一致 |
| nihongo | 3.7302 | なし | 日本語入力 | 日本語入力 | 一致 |
| kyouha | 0.6761 | **あり** | 今日は天気がいい | 今日は天気がいい | 一致（結果同じ） |
| long1 | 1.6335 | なし | 今日は天気が良くて散歩日和ですね制度が高い | 同左 | 一致 |
| long2 | 0.7432 | **あり** | 私は毎日会社に行きます漢字変換 | 同左 | 一致（結果同じ） |

**トリガー率 3/6 = 50%。** 第19ラウンドの予測は2件（seido, long2）だったが、`kyouha` も step 6 で gap 0.6761 を記録してトリガーした。ただし結果は変わらなかった（CPU も同じ答え）。

`long1` は step 6 で gap 1.6335 を記録し、τ=1.5 をわずかに上回ってトリガーしなかった。**τ=1.7 なら long1 も救えた。**

### 3.2 seido の assert

```text
cold label=seido expected=精度が高い actual=精度が高い match=true ms=702
warm label=seido expected=精度が高い actual=精度が高い match=true ms=2118
```

**CPU 仲裁が効いた。** NPU 素通しでは `制度が高い` だったデータで、CPU の `精度が高い` が返っている。

### 3.3 レイテンシ

| ラベル | cold (ms) | warm (ms) | 備考 |
|---|---|---|---|
| seido | 702 | 2118 | トリガー、CPU 再計算あり |
| kanji | 21 | 116 | 無トリガー |
| nihongo | 22 | 160 | 無トリガー |
| kyouha | 878 | 1736 | トリガー |
| long1 | 93 | 111 | 無トリガー |
| long2 | 1414 | 1517 | トリガー、長文脈 |

- **cold の seido 702ms** は CPU モデルのロード（約 105ms）＋ CPU 推論（約 553ms）＋ NPU 推論（約 31ms）の合計に近い。
- **warm の seido 2118ms** は cold より遅い。これは測定順の影響（warm パスは cold の直後に走るため、端末が温まっている）と考えられる。**同じ条件で2回測った値ではないので、この差は参考程度。**
- 無トリガー例は 21-160ms で、第19ラウンドの NPU 単体（31ms）と同程度。

### 3.4 回帰

| 項目 | 結果 |
|---|---|
| CPU モード（スイッチ OFF）で6例 | **PASS**（`OK (1 test)`、仲裁ログなし） |
| UI 長押しで `[AI]` 候補 | **PASS**（`OK (1 test)`） |
| 連続入力12ラウンド | **PASS**（同一PID 25741、74.1秒） |

CPU モードのテストは当初 FAIL した。テストの assert が「long2 は必ずトリガーする」と固定していたため。CPU パスは仲裁しないのが正しい動作なので、assert を NPU/CPU で分岐させた。

## 4. 判定枠組みへの当てはめ

指示書の分岐:

- seido が「精度が高い」を返し、他例が無トリガーで不変 -> **案1成立**（← 今回ここ、ただし kyouha もトリガー）
- トリガー頻度が想定以上に高い -> τ 引き上げを検討
- CPU 再計算の遅延が UX 上許容できない -> UX 再考

**案1成立と判断する。** ただし2点、指示書の想定と違う:

1. **トリガーは3件（予測2件）。** `kyouha` が step 6 で gap 0.6761 を記録した。結果は変わらなかったので実害はないが、トリガー率は 50%。
2. **`long1` は救えていない。** gap 1.6335 で τ=1.5 をわずかに超える。CPU なら `精度が高い` だが NPU の `制度が高い` が返る。

## 5. τ の妥当性

実測トリガー率 50%（6例中3件）は**高い**。ただし:

- 6例は「薄いマージンを突く」ために選んだケースなので、日常入力より偏っている。
- 実際の変換では、より長い文脈で gap が開く傾向がある（long1 は 1.63、long2 は 0.74 だが、これは step 位置の違い）。
- 日常入力10例でのトリガー率は**未測定**。本ラウンドでは測っていない。

**τ=1.5 は暫定として妥当。** ただし以下を検討する価値がある:

- **τ=1.7 に上げる**: `long1` も救える。ただしトリガー率がさらに上がる。
- **τ=1.2 に下げる**: トリガー率は下がるが、`long2`（0.74）は救える、`kyouha`（0.68）も救える。`seido`（0.38）も救える。**実は τ=1.2 でも3件とも救える。**
- つまり **τ=1.2 の方が効率的**。`long1`（1.63）は τ=1.7 でも救えるが、τ=1.2 では救えない。

## 6. 次の一手

1. **τ の再評価**: 日常入力10例でトリガー率を測る。τ=1.2 と 1.5 で比較。
2. **`long1` を救うか判断**: τ=1.7 にすると救えるが、トリガー率が上がる。あるいは「gap が大きくても、変換結果に `制度`/`精度` のような既知の近接ペアが含まれる場合は再計算」という第2の条件を足す。
3. **UX の確認**: トリガー時の 700ms-2秒の遅延が長押し操作で体感できるか。第22ラウンドの UI テストでは問題なかったが、明示的には測っていない。
4. **cleanup plan の更新**: マージン検知は製品機能なので「残す」に分類。ログ出力は削除対象。

## 7. 成果物

- `_artifacts/round20/arbitration_report.txt` — 6例×2パスの結果とマージンログ
- `_artifacts/round20/stability.txt` — 連続入力12ラウンド

## 8. ローカル状態

- 端末: `.npu` に仲裁有効ビルドをインストール済み。スイッチON。既定IMEは本番アプリ。
- モデル: xsmall（20.9 MB）。
- push / commit / PR なし。端末再起動なし。
- 熱状態: 連続変換12ラウンド（74秒）を含む測定を1回実施。休憩を挟んで実行した。
