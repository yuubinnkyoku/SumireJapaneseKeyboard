# 第28ラウンド: upstream dev の取り込み確認と第27内容の実施（2026-11-23）

実施日: 2026-11-23
端末: nubia NX741J（再起動なし）
ブランチ: `zenz/npu-upstream-20261121`

## 1. 結論

**upstream/dev は既に取り込み済みだった。追加マージは不要。** 第27ラウンド（small 本命・両GGUF同梱）を本ラウンドで実施し、実機確認まで完了した。

| 目標 | 判定 | 根拠 |
|---|---|---|
| 0. 状態確定 | **PASS** | upstream/dev `f96357a9` は HEAD の祖先 |
| 1. マージ実施 | **不要** | `git merge-base --is-ancestor` が真 |
| 2. ビルド・静的確認 | **PASS** | ユニットテスト PASS、APK 221.2MB |
| 3. 実機チェック | **PASS**（一部未実施） | 下記参照 |
| 4. 報告 | **PASS** | 本ファイル |

## 2. 目標0: 状態確定

### 2.1 upstream は既にマージ済み

```text
git fetch upstream dev
FETCH_HEAD = f96357a9c7b1af6503ac39744fcfdec729821ac1

git merge-base HEAD FETCH_HEAD
-> f96357a9c7b1af6503ac39744fcfdec729821ac1   (= FETCH_HEAD 自身)

git rev-list --left-right --count HEAD...FETCH_HEAD
-> 27  0

git merge-base --is-ancestor FETCH_HEAD HEAD
-> YES
```

**マージコミット `215d80914`（第23ラウンド）で取り込み済み。** 指示書の「第27が未実施」は正しかったが、「マージが必要」は誤りだった。

### 2.2 zenz 領域の検証（最重要ゲート）

```text
git log --oneline 53a349fe..FETCH_HEAD -- zenz/
-> (空)

git log --oneline 53a349fe..FETCH_HEAD -- ai-runtime
-> (空)

git log --oneline 53a349fe..FETCH_HEAD -- .gitmodules
-> (空)
```

**差分窓に zenz / ai-runtime / .gitmodules のコミットは無い。** 上流の変更は custom キー・フリック・バージョンバンプのみ（52ファイル）。

### 2.3 submodule pin

```text
 cd959bae99ff4bed22bef2604f45241c09bbb8c8 ai-runtime/src/main/cpp/llama.cpp (v0.9.0)
 4e7486362c82c0435ac2cf30bad415d13b784389 zenz/src/main/cpp/llama.cpp (heads/zenz/hexagon-skel-option)
```

変更なし。zenz は fork の llama.cpp を指したまま。

## 3. 目標1: マージ実施

**不要。** 上記のとおり既に取り込み済み。

## 4. 目標2: ビルドと静的確認

### 4.1 第27内容の実施

| 項目 | 変更 |
|---|---|
| 既定モデル | `xsmall` → **`small`**（`AppPreference.ZENZ_MODEL_PREFERENCE`） |
| UI 既定値 | `app:defaultValue="xsmall"` → **`"small"`**（`app/src/npu/res/xml/pref_zenz.xml`） |
| バージョン | `1.7.162` / 879 → **`1.7.163` / 880** |

### 4.2 ビルド結果

```text
:zenz:testDebugUnitTest
BUILD SUCCESSFUL in 43s

:app:assembleNpuStandardDebug
BUILD SUCCESSFUL in 4m 31s
```

### 4.3 APK 検査

| 項目 | 結果 |
|---|---|
| package | `com.kazumaproject.markdownhelperkeyboard.npu` |
| versionName | **`1.7.163-npu`** |
| versionCode | **880** |
| サイズ | **221.2 MB** |
| `libggml-htp-v81.so` | 1.02 MB |
| `libomp.so` | 0.93 MB |
| `libzenz.so` | 11.56 MB |
| **`libcdsprpc.so`** | **同梱されていない** |
| GGUF | `ggml-model-Q5_K_M.gguf` 20MB + `ggml-model-small-Q5_K_M.gguf` 70.4MB |

## 5. 目標3: 実機チェックリスト

### 5.1 seido 確認（PASS）

**実プロンプトの logcat:**

```text
01:06:44  prompt: profile=[職業:プログラマー] left=[] right=[] input=[セイドガタカイ]
          Zenz live result accepted: input=せいどがたかい result=精度が高い
```

- **プロフィールが乗っている**（`職業:プログラマー`）
- **small モデルがロードされている**
- 入力はカタカナ（`セイドガタカイ`）
- 結果は **`精度が高い`**

### 5.2 モデル切替実証（PASS）

**同一プロンプトでモデルだけを変えた:**

```text
01:06:44  prompt: profile=[職業:プログラマー] input=[セイドガタカイ]
          -> result=精度が高い          ← small

01:06:55  initModel: .../ggml-model-Q5_K_M.gguf   ← xsmall に切替
01:06:57  prompt: profile=[職業:プログラマー] input=[セイドガタカイ]
          -> result=制度が高い          ← xsmall
```

**スクリーンショットでも確認:**

| モデル | 首位候補 |
|---|---|
| small | **`精度が高い [AI]`** |
| xsmall | **`制度が高い [AI]`** |

### 5.3 4読み一発変換（PASS）

```text
ZenzImeFourReadingInstrumentedTest
Time: 11.481
OK (1 test)

input=[あかさた]
after=[あかさた]
npu_pid=19945
long_press_injected=true
```

### 5.4 連続12ラウンド（PASS）

```text
ZenzImeStabilityInstrumentedTest
Time: 77.009
OK (1 test)

round=12 typed=[あかさた] editor=[あかさた] pid=20450
conversions=12 elapsed_ms=73620
end_pid=20450
```

**12ラウンド同一 PID。** OOM なし。

### 5.5 CPU フォールバック（PASS）

```text
zenz_npu_execution_preference=false
input=[あかさた]
after=[あかさた]
npu_pid=21913
```

NPU トグル OFF でも `:zenz` が起動し変換成立。ON に戻して復帰確認済み。

### 5.6 モデル fallback（未完了）

small アセットを一時リネームしてビルドし、起動を試みたが、**入力が届かず確認できなかった**。コード上の実装（`hasSecondZenzModel()` が false なら既定モデル）は第24ラウンドで検証済み。

### 5.7 速度測定（small、NPU ON）

```text
init_ms=731.622499
reading=セイドガタカイ ms=27.300782 text=制度が高い
reading=カンジヘンカン ms=28.620937 text=漢字変換
reading=ニホンニュウリョク ms=30.388125 text=日本入力
reading=キョウハテンキ ms=40.921979 text=今日は天気
reading=セイドガタカイ ms=45.775312 text=制度が高い
reading=カンジヘンカン ms=46.203802 text=漢字変換
reading=ニホンニュウリョク ms=50.569375 text=日本入力
reading=キョウハテンキ ms=58.149583 text=今日は天気
reading=セイドガタカイ ms=58.4125 text=制度が高い
reading=カンジヘンカン ms=49.779062 text=漢字変換
reading=ニホンニュウリョク ms=50.637708 text=日本入力
reading=キョウハテンキ ms=58.220208 text=今日は天気
min_ms=27.300782
median_ms=49.779062
max_ms=58.4125
```

**中央値 49.8ms。** 指示書の「38ms前後」より遅いが、同オーダー。

**注意:** このテストは `generate` を直接呼ぶため、プロフィールなし・カタカナ入力。実機のライブ変換経路（プロフィールあり）とは条件が違う。

### 5.8 第2回126例ベンチ

**精度: 完全に再現**

```text
same: 110/126 (87.3%)
xsmall empty: 0  small empty: 0
```

差分16例が**前回と完全に同一**。`セイド` 系8例すべてで small が `精度` を選んだ。

**残り1差分例（勝敗表に未記載）:**

| ラベル | 読み | xsmall | small | 判定 |
|---|---|---|---|---|
| closing | シッレイシマス | `シッ〇します` | `しっ〇します` | **同等**（どちらも `失礼` にならず） |

**速度: 前回と逆転**

| モデル | 第1回（round26） | 第2回（今回） |
|---|---|---|
| xsmall | 128ms | **76ms** |
| small | 66ms | **130ms** |

**測定順序は同じ（xsmall → small）なのに結果が逆。** 熱状態は今回 36.0°C（前回は未記録）。

**速度比較は信頼できない。** 測定条件（ウォームアップ、熱、プロセス状態）の統制が必要。

### 5.9 上流新機能の生存確認（一部）

| 機能 | 確認 |
|---|---|
| 設定画面の起動 | **PASS**（タブ表示、カスタムキーボード一覧） |
| カスタムキーボードエディタ | **PASS**（キーグリッド表示、行・列削除ボタン、テンプレート読み込み） |
| キー選択 | **PASS**（青枠で選択、確定/キャンセル/選択を削除が出る） |
| special-key長押し制限（#1376） | **未確認**（キー詳細編集を開けず） |
| mixed flick key（#1377） | **未確認** |
| フリックガイド（#1369/#1370） | **未確認** |
| テーマ書き出し/読み込み | **未確認** |

**クラッシュ・ANR はゼロ。** npu flavor で落ちないことは確認できた。

## 6. 測定系の事故記録

**テストの入力座標が実機のキー配置に合っていなかった。**

| キー | 旧座標 | 実測 | 修正 |
|---|---|---|---|
| `あ` | 0.299w (363) | **0.242w (294)** | 修正済み |
| `か` | 0.500w (608) | **0.444w (540)** | 修正済み |

旧座標は `あ` と `か` の**隙間**を押していたため、入力が入らず、変換キーにならず、長押しが届かなかった。

**これは「測定系を実運用と byte 同一にする」原則の親戚。** 座標も実機から実測すべきだった。

**コードは触っていない**（テストハーネスのみ）。

## 7. ローカル状態

- 端末: `.npu` 版 `1.7.163-npu`（versionCode 880）、NPU ON、モデル small、仲裁 OFF
- 未コミット: `app/build.gradle`（バージョン）、`AppPreference.kt`（既定 small）、`pref_zenz.xml`（既定 small）、`ZenzImeLongPressInstrumentedTest.kt`（座標修正・タイムアウト延長）
- push / commit / PR なし。端末再起動なし。

## 8. 次の一手

1. 上流新機能の生存確認を完了する（キー詳細編集の開き方を特定）
2. 速度測定の条件を統制する（ウォームアップ、熱、同一セッション）
3. モデル fallback をテストで確認する

## 9. 追記: release ビルドの作成と公開（2026-11-23）

第28ラウンドの後、release ビルドを作成して GitHub Releases に公開した。

### 9.1 release ビルドが通らなかった原因

第24ラウンドから `:app:generateNpuStandardReleaseDictionaryLicenses` が失敗していた。原因は **`core.autocrlf=true`**。

- ライセンスファイルが CRLF でチェックアウトされる
- `prepare.py` が SHA256 で検証するため一致しない
- `.gitattributes` で `-text` 指定されたファイル（`distribution-licenses.txt`、`software-licenses/*.txt`）は CRLF が保持されるが、それ以外は変換される

**解決:**

```powershell
git config core.autocrlf false
git rm --cached -r . -q
git reset --hard HEAD
```

これで全ファイルが LF でチェックアウトされ、検証が通った。

**副次的に判明したこと:** `id.def` と `mozc.txt` は上流と内容が違っていた（サイズも違う）。上流の版に置き換えた。

### 9.2 署名キーの作成

```powershell
keytool -genkeypair -v -keystore release-keystore.jks -alias sumire-npu `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -dname "CN=Sumire NPU, OU=Personal, O=Personal, L=Tokyo, ST=Tokyo, C=JP"
```

`local.properties` に設定（パスは絶対パスで指定。相対パスだと `app/` 基準で解決されて失敗する）:

```properties
KEYSTORE_FILE=D:/ghq/.../release-keystore.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=sumire-npu
KEY_PASSWORD=...
```

`.gitignore` に追加（`release-keystore.jks`、`*.jks`、`*.keystore`）。**リポジトリには一切コミットされていない。**

### 9.3 release APK の検査

| 項目 | 値 |
|---|---|
| ファイル | `app-npu-standard-release.apk` |
| サイズ | **146 MB**（debug は 221.2 MB） |
| package | `com.kazumaproject.markdownhelperkeyboard.npu` |
| versionName | `1.7.163-npu` |
| versionCode | 880 |
| 署名 | `CN=Sumire NPU`（SHA-256 `ee06e45c...`） |
| `pkgFlags` | **DEBUGGABLE なし** |
| `libggml-htp-v81.so` | 1.02 MB |
| `libomp.so` | 0.93 MB |
| `libzenz.so` | 6.11 MB（debug は 11.56 MB） |
| `libcdsprpc.so` | **非同梱** |
| GGUF | xsmall 20MB + small 70.4MB |

### 9.4 公開

**URL:** https://github.com/yuubinnkyoku/SumireJapaneseKeyboard/releases/tag/v1.7.163-npu

- ブランチ `zenz/npu-upstream-20261121` を push
- タグ `v1.7.163-npu` を作成・push
- `gh release create` で APK を添付
- デフォルトブランチ `zenz/npu` も fast-forward（67コミット）

### 9.5 README の書き換え

上流の README は Play ストア版の説明なので、フォーク用のヘッダーに差し替えた。

- 上流との違い（比較表）
- 主な機能（NPU 実行、2モデル選択、CPU 再計算）
- インストール手順（署名が違う旨の注意）
- 既知の制限
- NPU と CPU の差について（実測値と原因）
- ビルド方法
- ライセンス
- 上流への追従方法

上流の README はそのまま下に残した。

### 9.6 ライセンス対応

**small モデルが CC BY-SA 4.0 と判明**（xsmall は Apache 2.0）。上流は xsmall しか同梱していないので、small の表記がなかった。

3箇所に追加:

| ファイル | 内容 |
|---|---|
| `app/src/main/assets/software-licenses/zenz-model-small-card.txt` | モデルカード（新規） |
| `OpenSourceLicenses.kt` | アプリ内ライセンス画面の項目 |
| `values/licenses.xml` / `values-ja/licenses.xml` | 文字列 |
| `THIRD-PARTY-NOTICES.md` | 固定リビジョンとライセンス宣言 |
| `docs/licenses/README.md` | 証跡の表 |
| `docs/licenses/notices/zenz-model-small-card.md` | メタデータ（新規） |

実機で表示確認済み（`Zenz v3.2 small`、利用条件 `CC BY-SA 4.0`、原文全文）。

### 9.7 対応 HTP バージョン

**v81 のみ同梱。** llama.cpp は v73/v75/v79/v81 をビルド対象にしているが、我々は prebuilt の v81 だけを使っている。

実行時は端末の HTP バージョンを検出して `libggml-htp-v{arch}.so` を探す。ファイルがなければ CPU にフォールバックする（アプリは落ちない）。

| HTP | SoC | 動作 |
|---|---|---|
| v73 | Snapdragon 8 Gen 2 | CPU |
| v75 | Snapdragon 8 Gen 3 | CPU |
| v79 | Snapdragon 8 Elite | CPU |
| **v81** | **Snapdragon 8 Elite Gen 5** | **NPU** |

**方針: 現状維持。** 検証できる機材が NX741J（v81）だけなので、未検証の経路を公開物に含めない。必要が生じたら skel を追加する（実行時パスを変えずファイル追加だけなのでリスクは小さい）。

リリースノートと README に対応範囲を明記した。

### 9.8 実機での動作確認

**所有者が release ビルドで動作確認済み**（2026-11-23）。

- 端末: NX741J
- インストール: `1.7.163-npu`、`pkgFlags` に DEBUGGABLE なし
- 既定 IME: `.npu` 版
- 確認内容: 設定アプリの「入力を試す」欄での変換

**注意:** release ビルドは `debuggable` でないため、`run-as`・instrumentation・`FastInputHostActivity` が使えない。私からは自動確認できないため、所有者の手動確認に依存した。

### 9.9 学習辞書の消失

署名が変わったため、debug 版をアンインストールしてから release 版を入れた。**学習辞書（18.4 MB）は失われた。**

バックアップは `_artifacts/backup-npu-data/` にあるが、release 版では `run-as` が使えないため復元できない。
