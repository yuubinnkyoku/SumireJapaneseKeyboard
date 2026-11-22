# 第24ラウンド: 仕上げと常用開始

実施日: 2026-11-22
端末: nubia NX741J（再起動なし）
常用開始: **`.npu` 版（`1.7.162-npu`）**

## 1. 結論

**`.npu` 版を常用キーボードとして採用した。** 通常パッケージ名のビルドも作成・検査済みだが、署名の制約でストア版の学習辞書を引き継げないため、`.npu` 版を選んだ。

| 項目 | 結果 |
|---|---|
| 仕上げ（fallback・文言・plain ID） | **完了** |
| 通常 ID ビルド | **成功**（`1.7.162-npu-daily`、152.6 MB） |
| ストア版の学習辞書引き継ぎ | **不可能**（署名が3種とも別） |
| 常用版 | **`.npu` 版**（学習辞書 303 KB を保持） |
| 実機確認 | 長押し・4読み・安定性12ラウンド・CPUフォールバック・モデルfallback すべて PASS |

## 2. Phase 1: 仕上げ

### 2.1 モデル不在時の graceful fallback

`IMEService.resolveZenzModelPath` を整理した。

```kotlin
val wantsSmall = AppPreference.zenz_model_preference == "small"
val hasSmall = BuildConfig.APP_EDITION == "full" && hasSecondZenzModel()
if (wantsSmall && !hasSmall) {
    Timber.w("zenz model preference is small but the asset is missing, using the default model")
}
val defaultAssetFileName = if (hasSmall && wantsSmall) {
    "ggml-model-small-Q5_K_M.gguf"
} else {
    "ggml-model-Q5_K_M.gguf"
}
```

- 選択モデルがアセットに無ければ既定モデルへ自動フォールバック
- 例外を投げず、ログ1行を残す
- 既存の失敗経路（`MODEL_UNAVAILABLE` → CPU 再ロード）は変更していない

### 2.2 スイッチ文言

両方の summary に「次回の変換エンジン起動時（キーボードの切り替え、またはアプリの再起動）に反映」を明記した。

- NPU スイッチ: `zenz_npu_execution_preference_summary`
- モデル選択: `zenz_model_preference_summary`（「同梱されていないモデルを選んだ場合は自動的に既定のモデルを使います」も追記）

### 2.3 通常 applicationId 化

`app/build.gradle` の npu flavor にプロパティ分岐を追加した。

```groovy
npu {
    dimension "edition"
    // -PplainAppId=true builds the npu flavor under the production id so it can
    // replace the store app. The default keeps the .npu suffix for side-by-side
    // testing.
    if (!project.hasProperty("plainAppId")) {
        applicationIdSuffix ".npu"
    }
    versionNameSuffix project.hasProperty("plainAppId") ? "-npu-daily" : "-npu"
    ...
}
```

生成物の確認:

```text
package: name='com.kazumaproject.markdownhelperkeyboard' versionCode='879' versionName='1.7.162-npu-daily'
```

**本線 applicationId と完全同一**（サフィックスなし）。

### 2.4 日常 APK のアセット

xsmall のみ同梱にした（small は `zenz/build/generated/assets/zenzModel/main/` から削除）。

## 3. Phase 2: ビルドと APK 検査

### 3.1 release ビルドは失敗

```text
> Task :app:generateNpuStandardReleaseDictionaryLicenses FAILED
AssertionError: app/src/main/assets/id.def
```

辞書ライセンス生成タスクが `id.def` の検証で失敗する。**私の変更とは無関係な既存の問題**（`tools/classified-dictionaries/prepare.py` の `check()` が原因）。

指示書の選択肢に従い、**debug ビルドで日常使いを先行**した。機能差は計測ログの有無のみ（release では `ZENZ_LOGIT_DUMP` が `#ifdef` で無効、Timber 未 plant）。

### 3.2 ビルド中の環境問題

**Android SDK が見つからなかった。** `local.properties` が `C:/Users/yuubi/AppData/Local/Android/Sdk` を指していたが、そのパスは存在しなかった。所有者の情報で `D:/android-sdk` に修正した。

その際、Gradle が自動的に `build-tools 36.0.0` をインストールした。

**KSP の生成ディレクトリがロックされ、ビルドが失敗した。** Gradle デーモン停止と `app/build/generated/ksp` / `app/build/kspCaches` の削除で解決。

### 3.3 APK 検査（通常 ID ビルド）

| 項目 | 結果 |
|---|---|
| package | `com.kazumaproject.markdownhelperkeyboard`（サフィックスなし） |
| versionName | `1.7.162-npu-daily` |
| サイズ | 152.6 MB |
| `libggml-htp-v81.so` | 1.02 MB |
| `libomp.so` | 0.93 MB |
| `libzenz.so` | 11.56 MB |
| **`libcdsprpc.so`** | **同梱されていない**（非同梱方針どおり） |
| assets GGUF | `ggml-model-Q5_K_M.gguf` のみ（20 MB） |

## 4. Phase 3: 署名衝突の調査

### 4.1 3つの署名はすべて異なる

| 版 | 署名 | 入手元 |
|---|---|---|
| Play ストア版（端末にインストール済み） | `8917e5be` | Play App Signing で再署名 |
| GitHub リリース版（v1.7.160） | `b54a0809...`（CN=Kazuma Naka） | 開発者のリリースキー |
| 今回のビルド | `be4cdcdc` | ローカルの debug キー |

**GitHub リリースは存在する**（v1.7.149 / v1.7.150 / v1.7.160）。ただし Play ストア版は Google が再署名しているため、GitHub 版とも一致しない。

### 4.2 上書きを試した結果

```text
adb install -r github-v1.7.160-full.apk
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
com.kazumaproject.markdownhelperkeyboard signatures do not match newer version; ignoring!]
```

**ストア版は無傷**（`versionName=1.7.160`、`lastUpdateTime=2026-11-18`）。

### 4.3 学習辞書を引き継ぐ手段はない

| 手段 | 可否 | 理由 |
|---|---|---|
| `run-as` で DB コピー | ✗ | ストア版は `debuggable` でない |
| root で `/data/data/` アクセス | ✗ | 端末に root がない |
| `adb backup` | ✗ | Android 16（SDK 36）で廃止 |
| アプリのエクスポート機能 | ✗ | SharedPreferences のみ、DB は対象外 |

**アプリ側にも辞書のエクスポート機能はない。**

### 4.4 判断

**`.npu` 版を常用する。** 理由:

1. 学習辞書 303 KB を保持できる（第13〜23ラウンドで蓄積）
2. 設定もそのまま
3. 機能は同一（NPU 変換、案1、モデル選択すべて入っている）
4. パッケージ名が `.npu` なだけで日常使いに支障はない

通常 ID 版（`1.7.162-npu-daily`）はビルド済みで保存しておく。将来ストア版を整理するときに使える。

## 5. Phase 3: 実機確認

### 5.1 IME 切り替え

```text
ime set com.kazumaproject.markdownhelperkeyboard.npu/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService
-> selected for user #0
```

既定 IME が `.npu` 版になった。

### 5.2 長押しで `[AI]` 候補

```text
ZenzImeLongPressInstrumentedTest
Time: 9.65
OK (1 test)
```

### 5.3 4読み一発変換

```text
ZenzImeFourReadingInstrumentedTest
Time: 11.692
OK (1 test)

zenz_npu_execution_preference=true
input=[あかさた]
after=[赤沙汰]
npu_pid=23323
```

### 5.4 連続入力12ラウンド

```text
ZenzImeStabilityInstrumentedTest
Time: 78.368
OK (1 test)

round=12 typed=[あかさた] editor=[あかさた] pid=23643
conversions=12 elapsed_ms=75418
end_pid=23643
```

**12ラウンド同一 PID。** `:zenz` は落ちていない。

### 5.5 CPU フォールバック（NPU スイッチ OFF）

```text
zenz_npu_execution_preference=false
input=[あかさた]
after=[赤沙汰]
npu_pid=25043
```

**CPU でも変換成立。** スイッチを ON に戻すと NPU に復帰する（再起動後）。

### 5.6 モデル fallback

small を選んだ状態（アセットに small が無い）で変換:

```text
ZenzImeLongPressInstrumentedTest
Time: 9.264
OK (1 test)
```

コピーされたファイル:

```text
-rw------- 1 u0_a1015 u0_a1015 20970304 17:23 ggml-model-Q5_K_M.gguf
```

**xsmall（20,970,304 bytes）が使われ、変換も成立。** fallback が正しく動いている。

### 5.7 31文字超の左文脈

```text
round=0 label=long37 chars=37 ms=948 len=0 text= error=
round=0 label=long33 chars=31 ms=877 len=0 text= error=
round=0 label=long30 chars=23 ms=678 len=19 text=私は毎朝指示に起きてコーヒーを飲みます error=
```

**31文字超は空出力、エラーなし。** ダイアログも出ない。仕様どおり。

## 6. 日常運用ルール

1. **端末の `files/` に旧モデルや検証用 GGUF を置かない。** モデルを差し替えるときは必ず削除する。
2. 初回起動の init は数百 ms かかる（許容）。
3. 設定変更（NPU スイッチ、モデル選択）は**キーボードの切り替えかアプリ再起動**で反映される。

## 7. 積み残し

- **upstream PR は当面出さない**（所有者判断 2026-11-22）。
- 将来 PR する場合の準備: full ビルドでの NPU スイッチ非表示化（`src/npu` 限定リソースへの移動）、GGUF モデルのライセンス確認、PR 説明の実測要約（round19〜23）。
- v3.2 の高量子化版（Q6_K/Q8_0）公開は週次監視タスクが継続中。公開されたら `selectionVersion` 基盤で差し替え評価できる。
- **release ビルドの辞書ライセンス生成失敗**は未解決（`id.def` の検証）。日常使いには影響しない。

## 8. 成果物

- `_artifacts/round24/github-v1.7.160-full.apk` — GitHub リリース版（署名比較用）
- 通常 ID ビルド: `app/build/outputs/apk/npuStandard/debug/app-npu-standard-debug.apk`（`-PplainAppId=true` でビルドしたもの）

## 9. ローカル状態

- 端末: `.npu` 版（`1.7.162-npu`）が既定 IME。NPU スイッチ ON、モデル xsmall。
- ストア版は無傷（`1.7.160`）。
- push / commit / PR なし。端末再起動なし。
- 熱条件: 測定は短時間（各テスト 10-80秒）で、休憩を挟んで実施。
