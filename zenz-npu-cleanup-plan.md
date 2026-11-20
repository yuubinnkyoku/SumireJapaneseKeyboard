# zenz NPU 検証コードの upstream 前クリーンアップ手順

このファイルは、検証用に追加したコードを upstream 提出前にどう扱うかをまとめたもの。
検証は完了しているので、提出時は「製品に必要な変更」と「検証専用の変更」を分ける。

## 1. 製品に必要な変更（残す）

| ファイル | 変更内容 | 理由 |
|---|---|---|
| `app/build.gradle` | `npu` フレーバー追加、`npuImplementation project(':zenz')`、`useLegacyPackaging` | NPU 版アプリを作るのに必須 |
| `app/src/main/AndroidManifest.xml` | `<uses-native-library android:name="libcdsprpc.so" android:required="false" />` | targetSdk 31+ で vendor ライブラリを dlopen するのに必須 |
| `app/src/npu/AndroidManifest.xml` | `ZenzRuntimeService` の宣言 | `:zenz` プロセスを立てるのに必須 |
| `app/src/npu/java/.../ZenzRuntimeService.kt` | `:zenz` プロセスのサービス本体 | 同上 |
| `zenz/src/main/cpp/zenz_bridge.cpp` | `ADSP_LIBRARY_PATH` 設定、`g_model_is_npu`、`reload_cpu_model_locked()`、CPU フォールバック | NPU が使えない端末で落ちないために必須 |
| `zenz/src/main/java/.../ZenzEngine.kt` | `getBackendDeviceSummary()` | 診断に有用。残してよい |
| `zenz/build.gradle` | minSdk 24 / targetSdk 36 | ビルド設定 |

## 2. 検証専用の変更（提出前に削除する）

### 2.1 `zenz_htp_fail_test`

NPU の失敗経路を人為的に作るためのテストフック。製品には不要。

**削除対象（3ファイル）:**

`zenz/src/main/cpp/zenz_bridge.cpp`
- 58行目: `static std::atomic<int> g_htp_fail_test{0};`
- 1256-1259行目: `initModel` 内の `g_htp_fail_test == 1` 分岐
- 1421-1431行目: `Java_com_kazumaproject_zenz_ZenzEngine_setHtpFailTest` 関数全体
- 1542-1548行目: `generate_with_context_and_conditions` 内の `g_htp_fail_test == 2` 分岐

`zenz/src/main/java/com/kazumaproject/zenz/ZenzEngine.kt`
- 27-28行目: `setHtpFailTest` の宣言とコメント

`app/src/npu/java/.../ZenzRuntimeService.kt`
- 53-56行目: `htpFailTest` の読み取りと `ZenzEngine.setHtpFailTest(htpFailTest)` 呼び出し

**削除後の確認:**
```powershell
Select-String -Path "zenz\src\main\cpp\zenz_bridge.cpp","zenz\src\main\java\com\kazumaproject\zenz\ZenzEngine.kt","app\src\npu\java\com\kazumaproject\markdownhelperkeyboard\zenz\runtime\ZenzRuntimeService.kt" -Pattern "htp_fail_test|HtpFailTest"
```
何も出なければ削除完了。

### 2.2 `writeNpuDiagnostics()`

`:zenz` プロセス内から NPU 証拠をファイルに書く検証用メソッド。第18ラウンドで追加した。

**削除対象:**
- `app/src/npu/java/.../ZenzRuntimeService.kt` の `writeNpuDiagnostics()` 関数全体
- 同ファイル `initialize` 内の `writeNpuDiagnostics(npuEnabled)` 呼び出し

**残す判断も可:** デバッグビルドのみで動くなら害はない。ただし upstream では「なぜプロセス内でファイルを書くのか」の説明が必要になるため、削除を推奨。

### 2.3 テストコード

`app/src/androidTest/java/.../zenz/` 配下と `zenz/src/androidTest/` 配下は upstream に含めない（検証専用）。

- `ZenzImeLongPressInstrumentedTest.kt`
- `ZenzImeFourReadingInstrumentedTest.kt`
- `ZenzFloatingCandidateInstrumentedTest.kt`
- `zenz/src/androidTest/java/com/kazumaproject/zenz/ZenzNpu*.kt`

## 3. 削除の順序

1. `zenz_htp_fail_test` を削除（2.1）
2. `writeNpuDiagnostics()` を削除（2.2）
3. ビルドが通ることを確認:
   ```powershell
   .\gradlew.bat :app:assembleNpuStandardDebug -x :zenz:prepareZenzModel --no-daemon
   ```
4. 端末で NPU 変換が動くことを再確認（`zenz_npu=1` で長押し -> `[AI]` 候補）
5. テストコードを upstream の差分から除外

## 4. 注意点

- `zenz_htp_fail_test` を削除しても、**CPU フォールバック本体は残す**。これは実際の NPU 障害で必要になる製品機能。
- `g_npu_fallback_latched` も残す。フォールバック後に NPU を再有効化しないためのラッチ。
- `getBackendDeviceSummary()` は残してよい。診断用だが害がない。
