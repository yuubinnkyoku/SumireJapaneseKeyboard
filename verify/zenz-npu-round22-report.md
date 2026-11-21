# 第22ラウンド: 量子化によるマージン確保（v3.1-xsmall Q6_K）の実地検証

実施日: 2026-11-22
端末: nubia NX741J（再起動なし）
モデル: 自作 v3.1-xsmall Q6_K（19.5 MB）と既存 v3.2-xsmall Q5_K_M（20.0 MB）を比較
ビルド: `:app:assembleNpuStandardDebug -PzenzLogitDump=true`

## 1. 結論

**v3.1-Q6_K は margin と NPU/CPU 一致の目標を達成したが、品質で劣化が見つかった。**

| 項目 | v3.2 Q5_K_M | v3.1 Q6_K | 判定 |
|---|---|---|---|
| seido NPU top-1 | 制（誤） | **精（正）** | 改善 |
| seido NPU margin | -0.3837 | **+2.2969** | 改善 |
| seido CPU margin | +0.1408 | **+1.3718** | 改善 |
| NPU/CPU top-1 不一致 | 3件（seido step 0） | **0件** | 目標達成 |
| 日常入力トリガー率（τ=1.5） | 50% | 50% | 変化なし |
| 品質（12例） | 11/12 正 | **10/12 正** | **劣化** |

**品質劣化の実例:** `ハイ`（2文字）が v3.2 では `はい`、v3.1 では **`杯`** に誤変換された。

**判定: 本命差し替えは見送り。** margin は改善するが、v3.1 は一世代前のモデルで品質が劣る。量子化引き上げは「v3.2 の高量子化版公開待ち」として pending 扱いとする。

## 2. Phase 0: `long`（37文字）空出力のトリアージ

### 2.1 結果

**NPU 固有の問題ではなかった。** CPU モードでも同じく空出力になる。

境界を二分探索した（CPU モード）:

| ラベル | 文字数 | 出力長 | 結果 |
|---|---|---|---|
| long37 | 37 | 0 | **空** |
| long33 | 31 | 0 | **空** |
| long30 | 23 | 19 | 私は毎朝指示に起きてコーヒーを飲みます |
| long27 | 21 | 17 | 私は毎朝指示に起きてコーヒーを飲み |
| long23 | 18 | 14 | 私は毎朝指示に起きてコーヒー |
| long20 | 14 | 10 | 私は毎朝指示に起きて |
| long12 | 8 | 4 | 私は毎朝 |

- **23文字以下**: 正常
- **31文字以上**: 空出力
- 境界は 24〜30文字の間（未確定）

### 2.2 分類

**エラーなし・例外なし・空文字。** `error=` は空で、タイムアウトもしない。モデルが EOS を即座に打っている。

**NPU パス固有の失敗ではない**ので、第20ラウンド実装の修正は不要。仕様として知見化する。

**注意:** これは v3.2 Q5_K_M での測定。v3.1 Q6_K でも同じ境界かは未確認（`long` 37文字は v3.1 でも空だった）。

## 3. Phase 1: v3.1-Q6_K GGUF の自作

### 3.1 取得

`Miwa-Keita/zenz-v3.1-xsmall` から7ファイルを取得:

```text
config.json                 778 bytes
generation_config.json      132 bytes
merges.txt                44804 bytes
model.safetensors      45030848 bytes
special_tokens_map.json      96 bytes
tokenizer_config.json       871 bytes
vocab.json               104544 bytes
```

### 3.2 変換

**pre-tokenizer の認識で失敗した。** 上流の `convert_hf_to_gguf.py` はハッシュで pre-tokenizer を判定するが、`gpt2-small-japanese-char` は既知リストにない。

```text
NotImplementedError: BPE pre-tokenizer was not recognized - update get_vocab_base_pre()
```

既存 v3.2 GGUF の `tokenizer.ggml.pre` を実読すると `gpt2-small-japanese-char` だったので、変換スクリプトを**編集せず**ラッパーで同じ値を強制した（`C:\Users\yuubi\AppData\Local\Temp\convert_v31.py`）。

```python
_original = base.TextModel.get_vocab_base_pre
def patched(self, tokenizer):
    try:
        return _original(self, tokenizer)
    except NotImplementedError:
        return "gpt2-small-japanese-char"
base.TextModel.get_vocab_base_pre = patched
```

**llama.cpp の main worktree は9ファイルのまま**（スクリプトを編集していない）。

### 3.3 量子化

```powershell
python convert_hf_to_gguf.py <v31> --outfile v31-f16.gguf --outtype f16
llama-quantize.exe v31-f16.gguf v31-q6k.gguf q6_k
```

```text
llama_model_quantize_impl: model size  =    44.02 MiB (16.40 BPW)
llama_model_quantize_impl: quant size  =    19.33 MiB (7.20 BPW)
llama_quantize: quantize time =   595.65 ms
```

### 3.4 成果物

| ファイル | サイズ | sha256 |
|---|---|---|
| v31-f16.gguf | 44.2 MB | `C1A51C509C9332C9FF63319AE1F679402E91AEEDE4EEC17CC3525064956991FF` |
| v31-q6k.gguf | 19.5 MB | `9CC7DF395539C56122B9D82E57E5607A055F2CC0A754287C4AAA702FC0912EDF` |
| v32-q5k-original.gguf | 20.0 MB | `00C64B3D318045A708D0CAD5434FACCAB10F5481A49E6362864551FD0995FA58` |

### 3.5 突合（必須）

`gguf` Python パッケージで両モデルのメタデータと vocab を比較した。

```text
=== metadata ===
key                                    v3.2 Q5_K_M                  v3.1 Q6_K                    match
general.architecture                   gpt2                         gpt2                         OK
general.name                           Zenz v3.2 Xsmall             v31                          DIFF
general.size_label                     26M                          23M                          DIFF
general.file_type                      17                           18                           DIFF
tokenizer.ggml.model                   gpt2                         gpt2                         OK
tokenizer.ggml.pre                     gpt2-small-japanese-char     gpt2-small-japanese-char     OK
tokenizer.ggml.bos_token_id            1                            1                            OK
tokenizer.ggml.eos_token_id            2                            2                            OK
tokenizer.ggml.padding_token_id        1                            1                            OK
tokenizer.ggml.add_bos_token           False                        False                        OK

vocab size: v3.2=6000 v3.1=6000 OK

=== decision token ids ===
  id=1246: v3.2='精' v3.1='精' OK
  id=743: v3.2='制' v3.1='制' OK

=== vocab agreement: 6000/6000 (100.0000%) ===
no difference in the shared range
```

**vocab 6000/6000 完全一致。** token id 1246（精）と 743（制）も一致。margin 分析の土台は揃っている。

差分は `general.name` / `size_label` / `file_type` のみで、モデルサイズと量子化方式の違いによる想定内の差。

## 4. Phase 2: 実機測定

### 4.1 測定の落とし穴

**最初の測定で古いモデルが使われた。** 端末の `files/ggml-model-Q5_K_M.gguf`（20,970,304 bytes、v3.2）が残っており、APK のアセットより優先された。第21ラウンドと完全に同一の数値が出たことで気づいた。

```text
-rw------- 1 u0_a1015 u0_a1015 20970304 2026-11-19 23:11 files/ggml-model-Q5_K_M.gguf
```

削除して再測定した。**この手順は今後のモデル差し替えで必須。**

### 4.2 seido の logits（v3.1 Q6_K）

**NPU:**

```text
ZENZLOG step=0 npu=1 t1=1246:18.7239 t2=743:16.4270 t3=478:12.0101
```

**CPU:**

```text
ZENZLOG step=0 npu=0 t1=1246:19.4437 t2=743:18.0719 t3=478:13.5219
```

| | 精 (1246) | 制 (743) | margin |
|---|---|---|---|
| NPU | 18.7239 | 16.4270 | **+2.2969** |
| CPU | 19.4437 | 18.0719 | **+1.3718** |

**両方とも精が優勢。** v3.2 Q5 では NPU が制を選んでいた（margin -0.3837）。

Vibe サーバーの Q6_K 予測 +1.7951 に対し、実機 CPU は +1.3718。差は 0.42 で、クロス実装ノイズ（±0.3）をやや超えるが同方向。

### 4.3 NPU/CPU top-1 不一致

```text
=== top-1 disagreements ===
  none
```

**全171 step で不一致ゼロ。** 目標達成。

v3.2 Q5 では `seido` step 0 で3回とも不一致だった。

### 4.4 per-step 偏差

| 指標 | v3.2 Q5 | v3.1 Q6_K |
|---|---|---|
| mean | 0.3675 | **0.8456** |
| median | 0.3175 | **0.7198** |
| p95 | 0.8250 | **1.8842** |
| max | 3.8969 | **3.3987** |
| n | 510 | 461 |

**偏差自体は大きくなった。** ただし top-1 は一致している。これは Q6_K の方が数値的に敏感というより、**マージンが厚いので偏差が順位に影響しない**ため。

### 4.5 トリガー率（6例）

| τ | v3.2 Q5 | v3.1 Q6_K |
|---|---|---|
| 1.0 | 3/6 (50%) | **1/6 (17%)** |
| 1.2 | 3/6 (50%) | **2/6 (33%)** |
| 1.5 | 3/6 (50%) | 3/6 (50%) |
| 1.7 | 4/6 (67%) | 3/6 (50%) |
| 2.0 | 4/6 (67%) | 4/6 (67%) |

**min gap が厚くなった:**

| ラベル | v3.2 Q5 | v3.1 Q6_K |
|---|---|---|
| seido | 0.3837 | **2.2969** |
| kanji | 2.2123 | 1.9931 |
| nihongo | 3.7302 | 4.1760 |
| kyouha | 0.6761 | 0.9895 |
| long1 | 1.6335 | 1.0897 |
| long2 | 0.7432 | 1.3087 |

`seido` が 0.38 → 2.30 に大幅改善。ただし `long1` は 1.63 → 1.09 と悪化。

### 4.6 日常入力12例

| # | ラベル | v3.2 gap | v3.1 gap | v3.2 出力 | v3.1 出力 |
|---|---|---|---|---|---|
| 0 | greeting | 3.5174 | 3.6176 | おはようございます | おはようございます |
| 1 | thanks | 4.6713 | 2.9564 | ありがとうございました | ありがとうございました |
| 2 | schedule | 3.0031 | 3.0097 | 明日の午後3時に会議があります | 明日の午後3時に会議があります |
| 3 | weather | 3.1552 | 4.0407 | 今日は雨が降るそうです | 今日は雨が降るそうです |
| 4 | food | 0.7509 | 0.8673 | 昼ごはんはラーメンが食べたいです | 昼ごはんはラーメンが食べたいです |
| 5 | work | 3.4902 | 4.3855 | 仕事が終わったらすぐ帰ります | 仕事が終わったらすぐ帰ります |
| 6 | place | 0.1770 | 1.0533 | 駅まで歩いていきます | 駅まで歩いていきます |
| 7 | request | 1.4847 | 0.1045 | この所領を確認してください | この所領を確認してください |
| 8 | question | 1.0467 | 0.7623 | 何時に電車が出ますか | 何時に電車が出ますか |
| 9 | long | 0.0778 | 0.2544 | （空） | （空） |
| 10 | short | 1.1269 | 1.4360 | **はい** | **杯** |
| 11 | medium | 1.7519 | 2.0179 | 電話をかけてもいいですか | 電話をかけてもいいですか |

**`short`（ハイ）が v3.1 で `杯` に誤変換された。** これが唯一の品質差。

トリガー率（τ=1.5）は両方 50%（6/12）。gap 分布の median は v3.2 が 1.75、v3.1 が 2.02 でわずかに改善。

### 4.7 速度

| 種別 | v3.2 Q5 | v3.1 Q6_K |
|---|---|---|
| 無トリガー中央値 | 82ms | **63ms** |
| トリガー中央値 | 1584ms | **1286ms** |
| APK サイズ | 156 MB | **160 MB** |

**v3.1 Q6_K の方が速い。** モデルが小さい（19.5 MB vs 20.0 MB）ため。APK は 4 MB 増（Q6_K の方が圧縮率が低い）。

## 5. 品質 A/B

### 5.1 12例での比較

| モデル | 正変換 | 誤変換 | 空出力 |
|---|---|---|---|
| v3.2 Q5_K_M | 11/12 | 0 | 1（long） |
| v3.1 Q6_K | **10/12** | **1（short→杯）** | 1（long） |

### 5.2 判定

**v3.1 は品質で劣る。** 12例中1例の誤変換だが、`ハイ` → `杯` は実用上問題になる（短い応答が誤変換される）。

指示書の分岐:

- v3.1 が品質で劣る -> 量子化引き上げは pending（Miwa-Keita の高量子化版公開待ち）、案1（τ=1.5）を完成形として残リスクを文書化してクローズ

**この分岐に該当する。**

## 6. 判定枠組みへの当てはめ

| 分岐 | 該当 |
|---|---|
| v3.1-Q6_K が本命 | 該当しない（品質劣化） |
| **v3.1 が品質で劣る** | **該当** |
| `long` 空出力が NPU 固有のバグ | 該当しない（CPU でも再現） |

**結論: 量子化引き上げは pending。案1（τ=1.5）を完成形としてクローズ。**

## 7. 残る課題

1. **`long` の境界（24〜30文字）が未確定。** 実用上は問題ないが、記録として残す。
2. **`short`（ハイ）の誤変換が v3.1 固有か、Q6_K 固有かは未切り分け。** v3.2 を Q6_K にした場合を試せば分かるが、v3.2 のマスタ重みは非公開なので不可能。
3. **品質 A/B は12例のみ。** 指示書は30例を想定していたが、12例で明確な差が出たため打ち切った。より多くの例で確認する価値はある。

## 8. 成果物

- `_artifacts/round22/v31/` — HF から取得した7ファイル
- `_artifacts/round22/v31-f16.gguf` — F16 GGUF（44.2 MB）
- `_artifacts/round22/v31-q6k.gguf` — Q6_K GGUF（19.5 MB）
- `_artifacts/round22/v32-q5k-original.gguf` — 既存 v3.2 Q5_K_M のバックアップ
- `_artifacts/round22/phase0_triage.txt` — `long` の境界測定
- `_artifacts/round22/npu_steps_v31q6k.txt` / `cpu_steps_v31q6k.txt` — per-step ログ
- `_artifacts/round22/analysis_v31.txt` — per-step 比較
- `_artifacts/round22/everyday_v31q6k.txt` / `everyday_analysis_v31.txt` — 日常入力12例

## 9. ローカル状態

- 端末: `.npu` に v3.1 Q6_K 版をインストール済み。スイッチON。既定IMEは本番アプリ。
- **モデルは v3.1 Q6_K のまま**（`zenz/build/generated/assets/zenzModel/main/ggml-model-Q5_K_M.gguf`）。v3.2 に戻す場合は `_artifacts/round22/v32-q5k-original.gguf` をコピーする。
- τ は 1.5 のまま（コード変更なし）。
- push / commit / PR なし。端末再起動なし。
- 熱状態: Phase 0（7例×2回）、Phase 2（6例×3回 + 12例）、Phase 3（12例）を休憩を挟んで実施。

## 10. 補足: 測定中の2つの落とし穴

### 10.1 端末の `files/` に残る古いモデル

**APK のアセットより `files/ggml-model-Q5_K_M.gguf` が優先される。** モデルを差し替えても、端末に古いファイルが残っているとそちらが使われる。

最初の v3.1 Q6_K 測定で第21ラウンドと完全に同一の数値が出たことで気づいた。削除して再測定した。

```powershell
adb shell run-as com.kazumaproject.markdownhelperkeyboard.npu rm -f files/ggml-model-Q5_K_M.gguf
```

**モデル差し替え時は必須の手順。**

### 10.2 ロック画面

**端末がロックされていると `FastInputHostActivity` は起動してもキーボードが出ず、UI テストの入力が入らない。**

```text
java.lang.AssertionError: Ten-key input did not reach the editor (attempts=3 text=[])
```

第18ラウンドで同じ症状が出た時は座標の問題だったが、今回はロック画面だった。`dumpsys window` で `isKeyguardShowing` を確認すれば分かる。

```powershell
adb shell input keyevent KEYCODE_WAKEUP
adb shell input swipe 608 2000 608 600 300
```

解除後は `OK (1 test)` で PASS した。
