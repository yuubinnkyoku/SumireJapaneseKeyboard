# 第19ラウンド: CPU/NPU 数値偏差の実機定量

実施日: 2026-11-21
端末: nubia NX741J（再起動なし）
モデル: zenz-v3.2-xsmall Q5_K_M（20.9 MB、変更なし）
ビルド: `:app:assembleNpuStandardDebug -PzenzLogitDump=true`

## 1. 結論

**NPU と CPU の logits 偏差は mean 0.284 / median 0.214 / max 0.885 だった。** 判定枠組みの「0.1〜0.5」に該当する。

- `セイドガタカイ` の順位反転は**再現した**。CPU は 精 が +0.14 優勢、NPU は 制 が -0.38 優勢。
- 反転の原因は**マージン差 -0.52** で、これは NPU 側の偏差（mean 0.28）と同オーダー。量子化で薄くなったマージン（+0.14）を、NPU の数値差が押し切っている。
- 文脈長を伸ばしても偏差は増えなかった（long1: mean 0.330、long2: mean 0.233、短い例: mean 0.194-0.358）。**attention 経路より matmul/GELU 寄り**という切り分け材料になる。

## 2. 実装

### 2.1 計測フック

`zenz/src/main/cpp/zenz_bridge.cpp` に `#ifdef ZENZ_LOGIT_DUMP` でガードした `dump_top_logits()` を追加した。

- 生成位置 0 の `llama_get_logits_ith(ctx, -1)` から上位20トークンの (id, 生logit) を出す。
- `精`(id 1246) と `制`(id 743) は上位20に入らなくても必ず出力する。
- 出力形式: `ZENZLOG pos=0 npu=1 | tok=743 val=21.6977 | ... | tok=1246 val=21.3140 | tok=743 val=21.6977`

**logcat が使えなかった。** この端末は `log.tag=S`（SILENT）でアプリログが抑制されており、`LOGI` は届かない。そこで `ZENZ_LOGIT_DUMP_PATH` 環境変数のパスに**ファイル追記**する方式を併用した。

```cpp
const char *path = getenv("ZENZ_LOGIT_DUMP_PATH");
if (path && path[0]) {
    FILE *file = fopen(path, "a");
    if (file) { fprintf(file, "%s\n", line.c_str()); fclose(file); }
}
```

パスは JNI `setLogitDumpPath(String)` で設定する。`:zenz` プロセスで動くので、AIDL `IZenzRuntime` にも `setLogitDumpPath` を追加した。

### 2.2 ビルドフラグ

`zenz/src/main/cpp/CMakeLists.txt`:

```cmake
option(ZENZ_LOGIT_DUMP "Print top logits for NPU/CPU comparison" OFF)
if(ZENZ_LOGIT_DUMP)
    target_compile_definitions(zenz PRIVATE ZENZ_LOGIT_DUMP=1)
endif()
```

`zenz/build.gradle`:

```groovy
def zenzLogitDump = providers.gradleProperty("zenzLogitDump").orNull == "true"
if (zenzLogitDump) {
    zenzNpuCmakeArgs += "-DZENZ_LOGIT_DUMP=ON"
}
```

**既定は OFF。** release ビルドには一切影響しない。

### 2.3 測定テスト

- `zenz/src/androidTest/.../ZenzLogitDumpTest.kt`（CPU 側）
- `app/src/androidTestNpu/.../ZenzLogitDumpNpuTest.kt`（NPU 側、`:zenz` サービス経由）

どちらも同じ6プロンプトを3周する。`zenz` モジュール単体のテストAPKは `libcdsprpc.so` を宣言していないため NPU が使えない（第22ラウンドで確認済み）。NPU 側は `androidTestNpu` に置く必要がある。

## 3. 測定結果

### 3.1 プロンプト

| ラベル | プロンプト |
|---|---|
| seido | `U+EE00セイドガタカイU+EE01` |
| kanji | `U+EE00カンジヘンカンU+EE01` |
| nihongo | `U+EE00ニホンゴニュウリョクU+EE01` |
| kyouha | `U+EE00キョウハテンキガイイU+EE01` |
| long1 | `U+EE02キョウハテンキガヨクテサンポビヨリデスネU+EE00セイドガタカイU+EE01` |
| long2 | `U+EE02ワタシハマイニチカイシャニイキマスU+EE00カンジヘンカンU+EE01` |

### 3.2 `セイドガタカイ` の生 logits（pos 0）

**CPU:**

```text
ZENZLOG pos=0 npu=0 | tok=1246 val=21.6665 | tok=743 val=21.5257 | tok=478 val=18.1479 | tok=1343 val=14.2281 | tok=329 val=14.0620 | tok=889 val=13.3186 | tok=1335 val=13.2997 | tok=408 val=13.2838 | tok=1223 val=13.2453 | tok=2394 val=13.0431 | tok=1562 val=12.8499 | tok=825 val=12.8134 | tok=710 val=12.7876 | tok=492 val=12.5610 | tok=1478 val=12.1094 | tok=526 val=11.7014 | tok=637 val=11.1041 | tok=1133 val=10.7006 | tok=1030 val=10.5597 | tok=54 val=10.3299 | tok=1246 val=21.6665 | tok=743 val=21.5257
```

**NPU:**

```text
ZENZLOG pos=0 npu=1 | tok=743 val=21.6977 | tok=1246 val=21.3140 | tok=478 val=17.6598 | tok=1343 val=14.0935 | tok=329 val=13.6731 | tok=1562 val=13.4162 | tok=1335 val=13.3572 | tok=710 val=13.1798 | tok=408 val=13.1187 | tok=889 val=13.0766 | tok=1223 val=13.0559 | tok=825 val=12.5753 | tok=2394 val=12.5371 | tok=492 val=12.3933 | tok=1478 val=12.3061 | tok=526 val=11.5696 | tok=1133 val=10.6681 | tok=637 val=10.5722 | tok=1454 val=10.4057 | tok=1319 val=10.3282 | tok=1246 val=21.3140 | tok=743 val=21.6977
```

| | 精 (1246) | 制 (743) | margin (精-制) |
|---|---|---|---|
| CPU | 21.6665 | 21.5257 | **+0.1408** |
| NPU | 21.3140 | 21.6977 | **-0.3837** |
| 差 | -0.3525 | +0.1720 | **-0.5245** |

**順位が反転している。** CPU は 精 が優勢、NPU は 制 が優勢。

参考: Vibe サーバーの純 F32 参照実装は 精=21.84 / 制=21.49（margin +0.35）。CPU 実測（+0.14）は同じ向きで、量子化でマージンが圧縮されている。NPU は向きが逆。

### 3.3 全プロンプトの margin

| ラベル | CPU margin | NPU margin | 差 | 反転 |
|---|---|---|---|---|
| seido | +0.1408 | -0.3837 | -0.5245 | **あり** |
| kanji | -0.9032 | -0.4528 | +0.4504 | なし |
| nihongo | -0.9241 | -1.2992 | -0.3751 | なし |
| kyouha | +3.1503 | +3.0592 | -0.0911 | なし |
| long1 | +3.6508 | +3.5459 | -0.1049 | なし |
| long2 | +2.6543 | +2.4498 | -0.2045 | なし |

**反転は `seido` のみ。** 他の5例は margin の絶対値が大きく（0.9〜3.7）、NPU の偏差（0.28程度）では反転しない。

### 3.4 偏差分布

上位20トークンの共通集合での `|NPU - CPU|`:

| ラベル | 共通トークン数 | mean | median | max |
|---|---|---|---|---|
| seido | 18 | 0.2752 | 0.2381 | 0.5663 |
| kanji | 21 | 0.3580 | 0.2821 | 0.8499 |
| nihongo | 22 | 0.1937 | 0.1822 | 0.5442 |
| kyouha | 19 | 0.3191 | 0.2544 | 0.7180 |
| long1 | 21 | 0.3296 | 0.2586 | 0.8854 |
| long2 | 21 | 0.2328 | 0.2025 | 0.5717 |

**全体（n=366）: mean 0.2837 / median 0.2135 / max 0.8854**

### 3.5 文脈長依存

| 種別 | ラベル | mean 偏差 |
|---|---|---|
| 短い（左文脈なし） | seido | 0.2752 |
| 短い | kanji | 0.3580 |
| 短い | nihongo | 0.1937 |
| 短い | kyouha | 0.3191 |
| 長い（U+EE02 前置） | long1 | 0.3296 |
| 長い | long2 | 0.2328 |

**文脈長で偏差は増えない。** long1（0.330）は短い例の範囲内（0.194-0.358）、long2（0.233）はむしろ小さい。

これは **attention 経路（FA/softmax）より matmul/GELU 寄り**という切り分け材料になる。attention が原因なら文脈長に比例して偏差が増えるはず。

### 3.6 再現性

同じプロンプトを3回繰り返したが、**logits は完全に同一**だった（例: seido の 3回すべてで 精=21.3140 / 制=21.6977）。NPU・CPU とも決定論的に動いている。セッション状態の揺らぎはない。

## 4. 判定枠組みへの当てはめ

指示書の分岐:

- 偏差 0.05 以下 -> 別要因の疑い
- **偏差 0.1〜0.5 -> カーネル修正の余地が收益あるか要精査**（← 今回ここ）
- 偏差 0.5 以上 -> カーネル修正の価値が高い

**mean 0.284 / median 0.214 なので「0.1〜0.5」に該当する。**

ただし重要な補足:

- **max は 0.885** で、局所的には 0.5 を超える。
- **反転を起こすマージン差は -0.52** で、これは分布の上位数%に入る大きな差。平均的な偏差（0.28）の約2倍。
- つまり「平均的には中程度だが、特定のトークンで大きな差が出て、それが薄いマージンを押し切る」構造。

## 5. 次の一手の判断材料

### 5.1 カーネル修正は收益があるか

**ある程度はあるが、優先度は高くない。**

理由:

1. 反転するのは `seido` のような**マージンが 0.15 程度しかない近接ペア**だけ。他の5例は margin 0.9〜3.7 で安定。
2. 偏差を 0.28 から 0.1 に減らしても、margin +0.14 のペアは依然として危うい。**根本解決にはマージン確保（量子化を上げる）の方が効く。**
3. ただし max 0.885 の外れ値は気になる。特定トークンで大きな差が出る原因は調べる価値がある。

### 5.2 文脈長で増えない -> matmul/GELU 寄り

attention 経路を疑う根拠が弱まった。第20ラウンドで調べるなら:

- `hvx_vec_exp_f32`（softmax の近似 exp）は attention 経路なので優先度低。
- GELU の erf 変種、HMX mma タイル内部、vec_dot のリダクション順序を優先。

### 5.3 マージン確保の選択肢

- 量子化を Q5_K_M から Q6_K か Q8_0 に上げる（サイズ増と引き換えにマージン回復）。
- モデルを small に上げる（第24ラウンドで試したが、`seido` の揺れは解消しなかった）。
- プロンプト側で `制度`/`精度` のどちらかを文脈から決められるようにする（根本的でない）。

## 6. 成果物

- `_artifacts/round19/npu_raw.txt` — NPU の生 ZENZLOG 18行
- `_artifacts/round19/cpu_raw.txt` — CPU の生 ZENZLOG 18行
- `_artifacts/round19/analysis.txt` — 比較結果
- `_artifacts/round19/analyze.ps1` — 比較スクリプト

## 7. 計測コードの扱い

`zenz-npu-cleanup-plan.md` に従い、提出前に以下を除去する:

- `zenz_bridge.cpp` の `dump_top_logits()` と `#ifdef ZENZ_LOGIT_DUMP` ブロック
- `zenz_bridge.cpp` の `Java_com_kazumaproject_zenz_ZenzEngine_setLogitDumpPath`
- `ZenzEngine.kt` の `setLogitDumpPath` 宣言
- `IZenzRuntime.aidl` の `setLogitDumpPath`
- `ZenzRuntimeService.kt`（full/npu 両方）の `setLogitDumpPath` 実装
- `CMakeLists.txt` の `ZENZ_LOGIT_DUMP` オプション
- `zenz/build.gradle` の `zenzLogitDump` ブロック
- テスト2ファイル

**既定 OFF なので release には影響しないが、upstream には出さない。**

## 8. ローカル状態

- 端末: `.npu` に logit dump 有効ビルドをインストール済み。スイッチON。既定IMEは本番アプリ。
- モデル: xsmall（20.9 MB）に戻した。
- push / merge / force-push なし。端末再起動なし。
