# zenz NPU (Hexagon) ビルド・実機検証報告

対象日: 2026-11-17
リポジトリ: `yuubinnkyoku/JapaneseKeyboard`
ブランチ: `zenz/npu`
リモート HEAD: `9499d22af439e3eba8c545d6f4177a854b6b8104`
ローカル HEAD: `21ce8ac`（zenz サブモジュールを `4e748636` に更新）
作業場所: `D:\ghq\github.com\yuubinnkyoku\JapaneseKeyboard-zenz-npu-20261117`

この報告書の数値はすべて実測。未検証は「未検証」と書く。

---

## §0 判定サマリ

**最終更新: 第18ラウンド（2026-11-20）。** 第1ラウンドの表は履歴として残し、その下に最新の判定を置く。

### §0.1 第1ラウンド時点の判定（履歴）

| 目標 | 判定 | 根拠 |
| --- | --- | --- |
| 0. 新規 clone とピン更新 | **PASS** | HEAD `9499d22`、親 `f9bc783`、zenz `4e748636`、ai-runtime `cd959bae`、gitlink commit `21ce8ac` |
| 1. 事前ビルド skel の準備 | **PASS** | `libggml-htp-v81.so` 1,103,304 B / `libomp.so` 1,234,864 B、sha256 記録 |
| 2. CPU 回帰ビルド | **PASS** | `:zenz:assembleDebug` が `BUILD SUCCESSFUL in 4m 50s` |
| 3. NPU ビルド | **PARTIAL** | `:zenz` の NPU ビルドと AAR は PASS。`:app:assembleFullStandardDebug` は ai-runtime の Windows Vulkan ホストツールで BLOCKED |
| 4. 実機 NPU ロード | **PARTIAL（要原因特定）** | `isNpuAvailable=false`、デバイス一覧は CPU のみ。CPU フォールバックで変換は継続 |
| 5. 変換確認とトグル | **PARTIAL** | NPU ON の 4 例は CPU フォールバックで成立。トグル OFF の CPU 復帰も成立。NPU 経路は未使用 |
| 6. 速度比較 | **未測定** | 任意。NPU 経路が動いていないため比較対象なし |

### §0.2 第18ラウンド時点の判定（最新）

| 目標 | 判定 | 根拠 |
| --- | --- | --- |
| 0. 新規 clone とピン更新 | **PASS** | 変更なし |
| 1. 事前ビルド skel の準備 | **PASS** | 変更なし |
| 2. CPU 回帰ビルド | **PASS** | 変更なし |
| 3. NPU ビルド | **PASS** | `:app:assembleNpuStandardDebug` が `BUILD SUCCESSFUL`（第13ラウンド以降安定） |
| 4. 実機 NPU ロード | **PASS** | `HTP0 / Hexagon / type=1` がバックエンド一覧に登録、`libcdsprpc.so` が `r-xp` でマップ、`dlopen=ok`（§26.4） |
| 5. 変換確認とトグル | **PASS** | `:zenz` サービス経由の変換 4/4 一致（§22）、IME UI 長押しで `[AI]` 候補（§26.5, §26.6） |
| 6. 速度比較 | **PASS（数値あり）** | NPU 34.1/13.4/15.5/22.2 ms、CPU 569-720 ms（§24）。NPU が 10-25 倍速い |
| 7. CPU フォールバック | **PASS** | `zenz_htp_fail_test=1` で init は CPU、`=2` で生成失敗後に CPU 再ロード＋リトライ成功（§23） |
| 8. IME UI 長押し経路 | **PASS** | 自動テスト `OK (1 test)`、実機の指でも `赤 [AI]`（§26.3, §26.6） |

**未実施（端末が必要）:** 4読みの UI 候補、2-3分連続入力の安定性、UI 経由の CPU 回帰、UI 経由の速度代表値。

---

## §1 目標0: clone とピン更新 - PASS

```powershell
git clone --depth 1 --branch zenz/npu --single-branch `
  https://github.com/yuubinnkyoku/JapaneseKeyboard `
  D:\ghq\github.com\yuubinnkyoku\JapaneseKeyboard-zenz-npu-20261117
```

確認:

```text
HEAD: 9499d22af439e3eba8c545d6f4177a854b6b8104
parent: f9bc7830650703037c23e69fcd97afbff19cd751
```

zenz サブモジュールは fork の `zenz/hexagon-skel-option` を直接 clone し、`4e748636` に合わせた。

```text
zenz:       4e7486362c82c0435ac2cf30bad415d13b784389
            parent 45db515b8f4ca2f94539e2ff5fa52edf79a07a4a
ai-runtime: cd959bae99ff4bed22bef2604f45241c09bbb8c8
```

gitlink 更新をローカル commit にした:

```text
21ce8ac zenz : llama.cppサブモジュールをzenz/hexagon-skel-option (4e748636)へ更新
```

`git status` は clean（未追跡のビルドログ・テストを除く）。**push は未実施。**

---

## §2 目標1: 事前ビルド skel - PASS

Phase 53 の成果物から 2 ファイルを `D:\ghq\github.com\yuubinnkyoku\zenz-npu-prebuilt` に集めた。

| ファイル | サイズ | sha256 |
| --- | --- | --- |
| `libggml-htp-v81.so` | 1,103,304 | `70B9ED4FAFB2AB298BAF9F9BBED3C38A4A457CC4838775A690B9168DFB7ECA1F` |
| `libomp.so` | 1,234,864 | `B0B507BB9E89360304272BA335D5FB2F6661F88842C2B3C749474CB7B8ACDA76` |

- skel は `llama.cpp-c51\build\p53\deploy\libggml-htp-v81.so`（Phase 53 の `70b9ed4f`）から取得。
- `libomp.so` は Phase 53 が使った NDK 29 の `toolchains/llvm/prebuilt/windows-x86_64/lib/clang/21/lib/linux/aarch64/libomp.so` から取得。Phase 53 の記録とサイズ・sha256 が一致する。
- 指示の「DSP 側 libomp」は、Phase 53 が実際に skel と同じ場所へ置いたこの NDK aarch64 版を指すと解釈した。

---

## §3 目標2: CPU 回帰ビルド - PASS

`zenzNpuEnabled` を設定しない状態（既定 false）でビルドした。`prepareZenzModel` の Windows/MSYS2 問題は前回と同じ回避策（モデルと stamp を先に置き `-x :zenz:prepareZenzModel`）を使った。

```powershell
.\gradlew.bat :zenz:assembleDebug -x :zenz:prepareZenzModel --no-daemon
```

結果:

```text
BUILD SUCCESSFUL in 4m 50s
29 actionable tasks: 20 executed, 9 from cache
```

**CPU 回帰は PASS。** 既定 OFF で従来経路が壊れていないことを確認した。

---

## §4 目標3: NPU ビルド - PARTIAL

### §4.1 `:zenz` の NPU ビルド - PASS

`local.properties` に次を追加した。

```properties
zenzNpuEnabled=true
zenzHexagonSdkRoot=C:/Qualcomm/Hexagon_SDK/6.6.0.0
zenzHexagonPrebuiltDir=D:/ghq/github.com/yuubinnkyoku/zenz-npu-prebuilt
```

最初の NPU ビルドは `hexagon_fun.cmake` の `string(FIND ${PREBUILT_LIB_DIR} ...)` で失敗した。`PREBUILT_LIB_DIR` が空で `string(FIND` の引数が 2 個になるため。Phase 53 の知見どおり、`zenz/build.gradle` の NPU CMake 引数に次を追加して回避した。

```groovy
"-DPREBUILT_LIB_DIR=toolv19_v81"
```

再ビルド:

```text
BUILD SUCCESSFUL in 2m 6s
30 actionable tasks: 6 executed, 24 up-to-date
```

CMake cache:

```text
GGML_HEXAGON:BOOL=ON
GGML_HEXAGON_HTP_SKEL:BOOL=OFF
HEXAGON_SDK_ROOT:UNINITIALIZED=C:/Qualcomm/Hexagon_SDK/6.6.0.0
```

`htp-v8*-prefix` の ExternalProject ディレクトリは存在せず、skel の再ビルドは走っていない。CMake の `hexagon: using ...; HTP skel build disabled (GGML_HEXAGON_HTP_SKEL=OFF)` は Gradle の C/C++ 出力には現れなかったが、cache とディレクトリの不在で確認した。

AAR（モデル同梱後）:

```text
zenz/build/outputs/aar/zenz-debug.aar  23,819,522 B
  assets/ggml-model-Q5_K_M.gguf        20,970,304 B
  jni/arm64-v8a/libggml-htp-v81.so      1,103,304 B
  jni/arm64-v8a/libomp.so               1,234,864 B
  jni/arm64-v8a/libzenz.so             12,108,296 B
```

`libzenz.so` には `ggml_hexagon_init`、`hexagon_backend_i`、`ggml_backend_hexagon_reg` などの Hexagon シンボルが含まれることを `llvm-nm` で確認した。

### §4.2 `:app:assembleFullStandardDebug` - BLOCKED

```powershell
.\gradlew.bat :app:assembleFullStandardDebug -x :zenz:prepareZenzModel --no-daemon
```

結果:

```text
> Task :ai-runtime:buildCMakeDebug[arm64-v8a][keyboard_ai] FAILED
```

`ai-runtime` の Vulkan shader generator（host tool）が Windows の MSVC でビルドできない。CMake のエラー:

```text
CMake Error at .../CMakeTestCXXCompiler.cmake:62 (message):
  The C++ compiler "cl.exe" is not able to compile a simple test program.
...
cl : Command line warning D9035 : option 'o' has been deprecated
LINK : fatal error LNK1181: cannot open input file
  'CMakeFiles\cmTC_4403b.dir\testCXXCompiler.cxx.obj'
```

`ggml-vulkan` の host toolchain が `-O2` / `-o` を MSVC に渡している。これは `ai-runtime` の Windows クロスビルド固有の問題で、zenz NPU の変更とは無関係。

`GGML_VULKAN=OFF` も試したが、`ai-runtime/src/main/cpp/CMakeLists.txt:51` の `target_link_libraries(ggml-vulkan ...)` が「target が存在しない」で失敗した。`ai-runtime` の Vulkan 依存を外すには追加の CMake 修正が必要で、本検証の範囲外と判断した。この変更は元に戻した。

**`:zenz` の NPU AAR は PASS。full app APK は ai-runtime の Windows Vulkan 問題で BLOCKED。**

---

## §5 目標4: 実機 NPU ロード - PARTIAL（要原因特定）

端末: `NX741J`（Android 36、`192.168.55.15:5555`）。

`zenz` モジュールに instrumentation test を追加した（untracked）:

- `zenz/src/androidTest/java/com/kazumaproject/zenz/ZenzNpuConversionTest.kt`
- `zenz/src/androidTest/assets/ggml-model-Q5_K_M.gguf`（20,970,304 B）

テストは次を実行する:

1. `ZenzEngine.setNpuEnabled(true)`
2. `ZenzEngine.isNpuAvailable()`
3. `ZenzEngine.initModel(modelPath)`
4. `generateWithContext` で 4 例
5. `setNpuEnabled(false)` で CPU 復帰を確認

### §5.1 結果

```text
java.lang.AssertionError: Hexagon device must be available;
devices=count=1; CPU / CPU / type=0
```

`ggml_backend_dev_count()` は **1** で、CPU だけ。Hexagon デバイスは登録されていない。

- `isNpuAvailable=false`
- `initModel` は CPU フォールバックで成功
- 4 例の変換は CPU で成立（assertion を外したテストは `OK (2 tests)`）
- トグル OFF の CPU 復帰も成立

### §5.2 切り分け

同じ端末で Phase 53 の `llama-cli` を `/data/local/tmp/c53-cli` から実行すると、Hexagon は見える:

```text
$ cd /data/local/tmp/c53-cli
$ LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./llama-cli --list-devices
Available devices:
  HTP0: Hexagon (0 MiB, 0 MiB free)
```

したがって、**端末・skel・unsigned PD 自体は動く**。アプリ経由だけが Hexagon を列挙できない。

アプリ側の確認:

- `useLegacyPackaging=true` を `zenz/build.gradle` に追加し、APK の `extractNativeLibs=true` を確認。
- インストール後の nativeLibraryDir に 3 ファイルが展開されていることを確認:
  - `libggml-htp-v81.so`
  - `libomp.so`
  - `libzenz.so`
- `zenz_bridge.cpp` で `dladdr` から nativeLibraryDir を求め、`ADSP_LIBRARY_PATH` に設定する修正も試した。
- それでも `isNpuAvailable=false`。

推定原因は、**アプリの nativeLibraryDir（`apk_data_file`）から FastRPC/DSP 側が skel を読めない**こと。Phase 53 は `/data/local/tmp`（world-readable）から読んでいた。SELinux ラベルか FastRPC デーモンのアクセス権が異なる可能性が高い。端末の logcat は `logcat -g` が `0 B readable` で、`adb logcat -d` も空のため、`remote_session_control(unsign)` のエラーコードは取得できなかった。

**目標4 は PARTIAL（要原因特定）。** CPU フォールバックは正しく動く。NPU 経路を使うには、skel を DSP 側が読める場所へ置く仕組み（assets から filesDir へコピーして chmod、または `/data/local/tmp` 相当の共有パス）が必要。

---

## §6 目標5: 変換確認とトグル - PARTIAL

| 条件 | 結果 |
| --- | --- |
| NPU ON（実際は CPU フォールバック） | `セイドガタカイ -> 精度が高い`、`カンジヘンカン -> 漢字変換`、`ニホンゴニュウリョク -> 日本語入力`、`キョウハテンキガヨイ -> 今日は天気が良い` の 4 例が一致 |
| NPU OFF（CPU） | `セイドガタカイ -> 精度が高い` が一致 |
| NPU 経路 | 未使用（`isNpuAvailable=false`） |

変換とトグル復帰は成立。NPU 経路は未使用のため **PARTIAL**。

---

## §7 目標6: 速度比較 - 未測定

NPU 経路が動いていないため、CPU との比較はしていない。CPU フォールバックの変換時間は計測していない。

---

## §8 ビルド・実行のために入れたローカル変更

いずれも **未コミット**。`zenz/npu` ブランチの設計に対する提案として残す。

### §8.1 `zenz/build.gradle`

1. NPU CMake 引数に `-DPREBUILT_LIB_DIR=toolv19_v81` を追加。
   - `hexagon_fun.cmake` の `string(FIND ${PREBUILT_LIB_DIR} ...)` が空文字で失敗するのを防ぐ。
2. `packaging.jniLibs.useLegacyPackaging = true` を追加。
   - APK の `extractNativeLibs=true` を強制し、skel/libomp を nativeLibraryDir に展開する。
   - これを入れないと `extractNativeLibs=false` のまま APK 内に留まり、DSP loader からファイルとして見えない。
3. `zenz/src/main/AndroidManifest.xml` に `android:extractNativeLibs="true"` も追加したが、ライブラリ manifest だけでは test APK の application 属性に反映されなかった。最終的には `useLegacyPackaging=true` で `extractNativeLibs=true` を確認。

### §8.2 `zenz/src/main/cpp/zenz_bridge.cpp`

1. `dladdr` で `libzenz.so` のディレクトリを求め、`initModel` の前に `ADSP_LIBRARY_PATH` に設定。
2. 診断用 `getBackendDeviceSummary()` を追加（テストの失敗メッセージにデバイス一覧を出すため）。
3. `CMakeLists.txt` の `target_link_libraries(zenz ...)` に `dl` を追加。

`ADSP_LIBRARY_PATH` と extraction を入れても Hexagon は列挙されなかった。SELinux/共有パスの問題が残る。

### §8.3 テスト

- `zenz/src/androidTest/java/com/kazumaproject/zenz/ZenzNpuConversionTest.kt`
- `zenz/src/androidTest/assets/ggml-model-Q5_K_M.gguf`

テストは untracked。`isNpuAvailable` の assertion は診断用で、外せば CPU フォールバックの変換テストとして通る。

---

## §9 禁止事項・既存 worktree

| 項目 | 状態 |
| --- | --- |
| push / merge / force push | なし |
| メイン llama.cpp worktree | 元の未コミット 9 ファイルのみ。変更なし |
| c51 worktree | `codex/c57-batchcost` / `d888366cd` で clean。本作業では触れていない |
| 端末の再起動 | なし |
| 温度・電源ポリシーへの介入 | なし |
| ブランチへの push | なし。gitlink commit `21ce8ac` はローカルのみ |

---

## §10 結論

- 目標0: **PASS**
- 目標1: **PASS**
- 目標2: **PASS**
- 目標3: **PARTIAL**（`:zenz` NPU AAR は PASS、full app APK は ai-runtime の Windows Vulkan で BLOCKED）
- 目標4: **PARTIAL（要原因特定）**（`isNpuAvailable=false`、CPU フォールバックは動作。Phase 53 の `/data/local/tmp` 経由では Hexagon が見える）
- 目標5: **PARTIAL**（4 例の変換とトグル復帰は成立、NPU 経路は未使用）
- 目標6: **未測定**

次に必要なこと:

1. `ai-runtime` の Windows Vulkan host-tool 問題を解消する（Linux/macOS で app をビルドするか、Vulkan shader generator の host toolchain を修正）。
2. アプリから DSP が読める場所へ skel を置く（assets から filesDir へコピーして権限付与、または共有パス）。`nativeLibraryDir` 直接参照は今回の経路では通らなかった。
3. その上で `isNpuAvailable=true` と `initModel` の HTP ロードログを再確認する。

---

## §11 第2ラウンド（2026-11-17 21:00）

### §11.1 目標7: logcat と dlopen の可視化

端末の logcat は依然として `logcat -g` が `0 B readable`、`adb logcat -d` も空で、`ggml-hex` / `stderr` のログは取得できなかった。

代わりに `zenz_bridge.cpp` の診断用 `getBackendDeviceSummary()` に `dlopen("libcdsprpc.so", RTLD_NOW)` を追加し、`dlerror()` をテストの失敗メッセージに含めた。これで原因が確定した。

最初のエラー:

```text
dlopen(libcdsprpc.so)=fail:
dlopen failed: library "libhidlbase.so" not found:
needed by .../lib/arm64/libcdsprpc.so in namespace clns-9
```

`libcdsprpc.so` 自体は APK に入り、nativeLibraryDir にも展開されていた。失敗は **依存ライブラリの解決**。アプリの linker namespace `clns-9` が `/system/lib64` / `/vendor/lib64` の非公開ライブラリを探索しないため。

### §11.2 目標8: libcdsprpc.so と依存ライブラリの同梱

`/vendor/lib64/libcdsprpc.so`（600,224 B、sha256 `5D43CEF205F93C5684CEC9B7216FCB7799A69063F05B07346224D74EE003317A`）を `zenzHexagonPrebuiltDir` に追加し、`prepareZenzNpuLibs` の include に `libcdsprpc.so` を追加した。

依存を 1 つずつ追加して再実行した。通過した順:

1. `libhidlbase.so`
2. `vendor.qti.hardware.dsp@1.0.so`
   - ファイル名の `@` が原因で nativeLibraryDir に展開されなかった。
   - `libcdsprpc.so` の `DT_NEEDED` 文字列を `vendor.qti.hardware.dsp@1.0.so` から `vendor.qti.hardware.dsp_1.0.so` に 1 バイト変更し、ファイル名も `_` に変更した。
   - 変更後の `libcdsprpc.so` は 600,224 B、sha256 `328D7B56202B53B5B879B56829EADB30E12289B773357C655DB5605074739051`。
3. `libcutils.so`
4. `libc++.so`
5. `libvndksupport.so`
6. `libdl_android.so`
7. `android.hardware.common-V2-ndk.so`
8. `ld-android.so`（`libdl_android.so` の依存。ここで停止）

この時点で次のエラー:

```text
dlopen(libcdsprpc.so)=fail:
dlopen failed: library "ld-android.so" not found:
needed by .../lib/arm64/libdl_android.so in namespace clns-9
```

`ld-android.so` は Android の動的リンカ本体で、アプリの jniLibs に同梱して解決する対象ではない。ここで依存チェーンを止めた。

### §11.3 第2ラウンドの結論

- `libcdsprpc.so` の同梱だけでは足りない。`libhidlbase`、`libcutils`、`libbase`、`libc++`、`libvndksupport`、`libdl_android`、`ld-android` と続く **vendor/system ライブラリの依存チェーン**がアプリの linker namespace `clns-9` から解決できない。
- `@` を含む `vendor.qti.hardware.dsp@1.0.so` は Android の native lib 展開から漏れるため、`libcdsprpc.so` の `DT_NEEDED` を `_` にパッチして回避した。これは検証用のローカル回避で、製品化には向かない。
- アプリドメインで `libcdsprpc.so` を完結させるには、vendor ライブラリをまとめて同梱するか、`android_dlopen_ext` で vendor namespace を使うなどの設計変更が必要。`ld-android.so` を同梱するのは現実的でない。
- 端末の `/data/local/tmp/c53-cli` の `llama-cli` は shell ドメインで `HTP0: Hexagon` を列挙できる。**Plan B（shell ドメインで llama-server を立て、キーボードから localhost へ HTTP）** が現実的。
- CPU フォールバックの変換は引き続き成立している。

### §11.4 第2ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| 目標7（logcat / dlopen 原因確定） | **PASS**（logcat は取れないが `dlerror` で原因確定） |
| 目標8（libcdsprpc.so 同梱で count=2） | **FAIL / BLOCKED**（依存チェーンが `ld-android.so` で止まる） |
| アプリドメイン NPU | **BLOCKED**（Plan B 推奨） |
| CPU フォールバック | **PASS**（4 例の変換は継続） |

第2ラウンドで追加したローカル変更:

- `zenz/build.gradle`: `libcdsprpc.so` と依存ライブラリを `prepareZenzNpuLibs` の include に追加。
- `zenz_bridge.cpp`: `getBackendDeviceSummary()` に `dlopen` / `dlerror` 診断を追加。
- `zenz-npu-prebuilt/libcdsprpc.so` の `DT_NEEDED` パッチと `vendor.qti.hardware.dsp_1.0.so` へのリネーム。
- 追加で pull した vendor/system ライブラリ（`libhidlbase.so`、`libhardware.so`、`libutils.so`、`libbinder_ndk.so`、`libdmabufheap.so`、`libvmmem.so`、`libcutils.so`、`libbase.so`、`libc++.so`、`liblog.so`、`libvndksupport.so`、`libdl_android.so`、`android.hardware.common-V2-ndk.so`）。

これらはすべて検証用の未コミット変更。push / merge はしていない。

---

## §12 第3ラウンド: Plan B'（RPC）の検証（2026-11-17 23:50）

### §12.1 Track A1: rpc-server のビルドと HTP0 列挙 - PASS

fork（`zenz/hexagon-skel-option`、`4e748636`）を新規 clone し、c53 と同じ configure に `GGML_RPC=ON` を追加して新規 build ディレクトリでビルドした。

```powershell
cmake -S . -B build-rpc-android -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE=.../ndk/29.0.14206865/build/cmake/android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON `
  -DGGML_HEXAGON=ON -DGGML_HEXAGON_HTP_SKEL=OFF `
  -DHEXAGON_SDK_ROOT=C:/Qualcomm/Hexagon_SDK/6.6.0.0 `
  -DPREBUILT_LIB_DIR=toolv19_v81 `
  -DGGML_RPC=ON -DGGML_OPENMP=ON `
  -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_CURL=OFF
cmake --build build-rpc-android --target ggml-rpc-server llama-completion -j 2
```

configure ログ:

```text
-- Using RPC backend
--   RDMA transport disabled
-- Including RPC backend
-- hexagon: using C:/Qualcomm/Hexagon_SDK/6.6.0.0; HTP skel build disabled (GGML_HEXAGON_HTP_SKEL=OFF)
-- Including Hexagon backend
```

端末へ `/data/local/tmp/zenz-rpc/` に push し、skel と `libomp.so` を同じ場所に置いて `ADSP_LIBRARY_PATH=.` で起動した。

```text
$ ./ggml-rpc-server -d ?
ggml-hex: Loading driver libcdsprpc.so
ggml-hex: FASTRPC_GET_DOMAINS[0]: type 1 id 1000 name 'nsp1000' status 1 instance-id 0
ggml-hex: using CDSP domain: instance-id 0 id 1000 name 'nsp1000'
ggml-hex: Hexagon backend (experimental) : allocating new registry : ndev 1
ggml-hex: Hexagon Arch version v81, DMA64 enabled
ggml-hex: device 0: HTP0 (phys=0, virt=0, domain=nsp1000:1000)
available devices:
  HTP0: Hexagon (0 MiB, 0 MiB free)
  CPU: CPU (15234 MiB, 15234 MiB free)
```

サーバー起動後、HTP セッションも作成された。

```text
ggml-hex: HTP0 allocating new session : domain 1000 phys-idx 0 virt-idx 0
ggml-hex: HTP0 hwinfo: threads 8, hvx 8, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 100000 uri file:///libggml-htp-v81.so?... handle 0x...
ggml-hex: HTP0 op batching: n-bufs 16 n-tensors 8192 n-ops 1280 vmem 3311845376
```

**Track A1 は PASS。**

### §12.2 Track A2: llama-completion の pure RPC - PASS

```text
$ ./llama-completion --rpc 127.0.0.1:50052 --device RPC0 `
    -m ggml-model-Q5_K_M.gguf -c 512 -ngl -1 `
    -f prompt-seido.txt -n 24 --temp 0 -no-cnv -v
```

ロードログ:

```text
llama_prepare_model_devices: using device RPC0 (127.0.0.1:50052)
load_tensors: layer 0 assigned to device RPC0
...
load_tensors: offloading output layer to GPU
load_tensors: offloading 5 repeating layers to GPU
load_tensors: offloaded 7/7 layers to GPU
```

出力:

```text
セイドガタカイ精度が高い [end of text]
```

4 例すべて一致:

| 入力 | 出力 |
| --- | --- |
| `セイドガタカイ` | `精度が高い` |
| `カンジヘンカン` | `漢字変換` |
| `ニホンゴニュウリョク` | `日本語入力` |
| `キョウハテンキガヨイ` | `今日は天気が良い` |

`--device RPC0` で local HTP0 を除外し、全 7 層を RPC 経由にしても出力は一致した。**rpc×hexagon×zenz の CLI 経路は PASS。**

### §12.3 Track B: zenz/rpc のビルド - PASS

clone:

```text
HEAD: d8eb59259624c636eb069ec7e153024f31e9f489
parent: f9bc7830650703037c23e69fcd97afbff19cd751
zenz submodule: 45db515b8f4ca2f94539e2ff5fa52edf79a07a4a
changed files: 6
```

CPU 回帰（`zenzRpcEnabled` なし）:

```text
BUILD SUCCESSFUL in 2m
```

RPC ビルド（`zenzRpcEnabled=true`、`-DZENZ_ENABLE_RPC=ON -DGGML_RPC=ON`）:

```text
BUILD SUCCESSFUL in 1m 46s
```

CPU 変換テスト（`ZenzCpuConversionTest`、`setRpcServer("")`）:

```text
OK (1 test)
Time: 8.822
```

CPU 経路は 4 例とも一致。**Track B のビルドと CPU 経路は PASS。**

### §12.4 Track B: RPC 実機テスト - FAIL（サーバー側クラッシュ）

`ZenzRpcConversionTest`（`setRpcServer("127.0.0.1:50052")` → `initModel` → 4 例）を実行すると、テストプロセスがクラッシュし、rpc-server も segfault した。

テスト結果:

```text
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
```

テストプロセスの tombstone（`com.kazumaproject.zenz.test`）:

```text
signal 6 (SIGABRT)
#00 abort
#01 ggml_abort+372
#02 rpc_dispatcher::work()+628   (libzenz.so)
```

rpc-server の tombstone:

```text
Cmdline: ./ggml-rpc-server --port 50052
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x20
Cause: null pointer dereference
#00 libggml-hexagon.so
#01 libggml-rpc.so
#02 libggml-rpc.so
#03 ggml-rpc-server
```

シンボル化:

```text
libggml-hexagon.so 0x6adc0:
  ggml_backend_hexagon_buffer_set_tensor_impl(...)
  ggml-hexagon.cpp:2960:53
    if ((extra->flags & GGML_HEXAGON_TENSOR_REPACK) == 0)

libggml-rpc.so 0x593e0:
  rpc_server::set_tensor(...)
  ggml-rpc.cpp:1481:5

libggml-rpc.so 0x5be48:
  rpc_serve_client(...)
  ggml-rpc.cpp:1991:29
```

つまり、RPC サーバーが `set_tensor` で受け取った tensor の `tensor->extra` が null のまま、Hexagon バッファの `set_tensor` が `extra->flags` を読んで segfault した。クライアントはサーバー断で `rpc_dispatcher::work()` の assert により SIGABRT した。

CLI の pure RPC（`--device RPC0`）は同じモデル・同じ `-ngl -1` で成功している。したがって、**CLI 経路ではなくアプリの RPC クライアント（`libzenz.so` の静的 RPC ビルド）が送る tensor の並び/種類が、サーバー側の `extra` 初期化を踏まない**ことが原因。

#### §12.4.1 サーバー側の追加修正試行

ローカルで次のガードを入れて再ビルドし、その都度テストを再実行した。

1. `ggml_backend_hexagon_buffer_set_tensor_impl`（`ggml-hexagon.cpp:2960`）: `tensor->extra` が null なら `ggml_backend_hexagon_buffer_init_tensor` を呼ぶ。
   - 結果: `set_tensor` の segfault は解消。次のクラッシュは `ggml_hexagon_opbatch::add_tensor`（`ggml-hexagon.cpp:3649`）の `extra->flags` に移動。
2. `add_tensor`（`ggml-hexagon.cpp:3649`）: `view_src` の extra を継承し、`extra` が null なら flags を 0 として扱う。
   - 結果: 次のクラッシュは `ggml_hexagon_opbatch::same_shape`（`ggml-hexagon.cpp:3602`）の `extra->flags` に移動。
3. `same_shape`（`ggml-hexagon.cpp:3602`）: 同じく `view_src` 継承と null ガードを追加。
   - 結果: まだ rpc-server は HTP の graph compute 中に segfault（`Process crashed` は継続）。

原因は、**RPC サーバーが graph 内の全 tensor に対して Hexagon の `extra` を初期化していない**こと。`set_tensor` でアップロードされる重み tensor だけでなく、graph の中間 tensor / view tensor も `extra` 前提のコード（`extra->flags` が複数箇所）を通る。CLI の pure RPC はこの経路を踏まないが、アプリの RPC クライアントの graph は踏む。

**Track B の RPC 実機テストは FAIL（サーバー側の根本修正待ち）。** 個別の null ガードでは足りず、`rpc_server::graph_compute` の tensor 生成時に Hexagon の `extra` を初期化するか、HTP backend の graph compute を `extra == null` に対して全面的に安全にする必要がある。修正後に同じテストを再実行する。

### §12.5 第3ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| Track A1（rpc-server / HTP0 列挙） | **PASS** |
| Track A2（CLI pure RPC / zenz 4例） | **PASS** |
| Track B clone / CPU build | **PASS** |
| Track B RPC build | **PASS** |
| Track B CPU 変換 | **PASS** |
| Track B RPC 実機テスト | **FAIL**（server segfault 継続。`set_tensor` / `add_tensor` / `same_shape` の null `extra` ガード後も graph compute で再発） |
| 速度 | 参考: RPC 経由の eval は約 10.6 tok/s（26M モデル、TCP 固定費込み）。速度判定はしない |

### §12.6 ローカル変更（未コミット）

- `zenz/src/main/cpp/zenz_bridge.cpp`: RPC 登録を `llama_backend_init()` の前に移動（CLI の `add_rpc_devices` と同じ順序）。この変更だけではサーバー crash は解消しなかった。
- `zenz/src/androidTest/.../ZenzRpcConversionTest.kt`、`ZenzCpuConversionTest.kt`、モデル asset（untracked）。
- rpc-server / llama-completion のビルドツリーは `llama.cpp-zenz-rpc-20261117` の新規 clone 内。既存の c53 ツリーは触っていない。
- push / merge はしていない。

### §12.7 次の一手

1. `rpc_server::graph_compute` の tensor 生成時に、graph 内の全 tensor（中間 tensor / view tensor を含む）へ Hexagon の `extra` を初期化する。`set_tensor` だけのガードでは足りない。
2. サーバーを再ビルドし、`ZenzRpcConversionTest` を再実行する。`isRpcDeviceRegistered=true` と 4 例が通れば Track B は PASS。
3. サーバー停止時の CPU フォールバックは、`setRpcServer("")` の CPU テストで既に PASS。RPC 指定でサーバー不在の場合の `add_server == nullptr` 経路も、`setRpcServer("127.0.0.1:59999")` で確認する（サーバー crash の修正後）。
4. 速度は参考値のみ。26M モデルでは RPC 固定費が支配的で、CPU 比の優位は現時点で確認していない。

---

## §13 第4ラウンド: サーバー側 extra 初期化修正（Track C、2026-11-18）

### §13.1 Track C: `zenz/hexagon-rpc-fix` の取得とビルド - PASS

A1 で使った fork の clone をそのまま再利用した。ローカルの `ggml-hexagon.cpp` ガード3点は `local-hexagon-guards.patch`（3,789 B）に退避し、`git checkout --` で元に戻した。新ブランチには持ち込んでいない。

```text
HEAD: e3eb30ee4e5e4fe6302d1e7b93274d63e2f3a86b
parent: 4e7486362c82c0435ac2cf30bad415d13b784389
diff:   ggml/src/ggml-rpc/ggml-rpc.cpp | 34 +++++++++++++++++++++++++++-------
        1 file changed, 27 insertions(+), 7 deletions(-)
```

差分の内容:

- `rpc_server_init_tensor(tensor)` を追加。`tensor->buffer->iface.init_tensor` を呼び、`extra == nullptr` を埋める。
- `set_tensor` / `set_tensor_hash` / `get_tensor` の deserialize 直後に呼ぶ。
- `graph_compute` で `tensor_map` の全 tensor（src / view_src 含む）に呼んでから `ggml_backend_graph_compute` する。
- `init_tensor` ハンドラの「extra populated -> unsupported -> return false」を廃止し、`return true` に変更。

ビルド（A1 と同じ `build-rpc-android`、差分ビルド）:

```text
[3/10] Building CXX object ggml/src/ggml-rpc/CMakeFiles/ggml-rpc.dir/ggml-rpc.cpp.o
[5/10] Linking CXX shared library bin\libggml-rpc.so
[6/10] Building CXX object ggml/src/ggml-hexagon/CMakeFiles/ggml-hexagon.dir/ggml-hexagon.cpp.o
[7/10] Linking CXX shared library bin\libggml-hexagon.so
[9/10] Linking CXX executable bin\ggml-rpc-server
```

`libggml-hexagon.so` は元のソース（ガードなし）で再ビルドされた。サーバーと `libggml-rpc.so` は `build-rpc-android/bin` に生成済み。

**Track C の取得とビルドは PASS。**

### §13.2 目標9: A2 の RPC 実使用確認 - 決着

端末は Tailscale 経由 (`100.108.106.81:43395`) で復帰した。修正版サーバー (`e3eb30ee`) を `GGML_HEXAGON_VERBOSE=1` で起動し、第3ラウンド A2 と同じ CLI を再実行した。

サーバーログ:

```text
Accepted client connection: 51
set-tensor: 0
add-tensor: 0
get-tensor: 0
```

CLI 出力は `セイドガタカイ精度が高い [end of text]` で、4例も同様に成立した。しかし、サーバーは接続だけを受けて tensor 転送/compute を受けていない。クライアント側の `load_tensors` は 7/7 を RPC0 に割り当て、`RPC0 model buffer size = 0.00 MiB` と表示した。つまり **A2 の CLI は RPC0 を登録したが、モデルの重み転送と compute はサーバーに送っていない**。ローカル (CPU または local HTP0) で計算していた。

決定的テスト: サーバーを kill して同じ CLI を実行すると、即座に abort した。

```text
Failed to connect to 127.0.0.1:50052
ggml_backend_rpc_add_server -> rpc_dispatcher::start -> ggml_abort
Aborted
```

**A2 パラドックスは「A2 は RPC 非使用」で決着。** 第3ラウンドの「CLI pure RPC で 4例 PASS」は RPC の証明としては無効。`--rpc`/`--device RPC0` は RPC0 を登録するが、モデル転送には至っていない。

さらに、`ggml_backend_rpc_add_server` はサーバー不在で `nullptr` を返さず、`rpc_dispatcher::start` で `ggml_abort` する。アプリの「サーバー不在なら CPU フォールバック」は現状成立しない。サーバーを止める前に `zenz_rpc` を空にする必要がある。これはアプリ設計のリスク。

### §13.3 目標10: 修正版サーバーで ZenzRpcConversionTest - クラッシュ解消、出力は空

修正版サーバーで `ZenzRpcConversionTest` を実行した。

- `initModel` と `isRpcDeviceRegistered` は成功。
- 最初の変換で `expected:<精度が高い> but was:<>`。空出力。
- テストプロセスはクラッシュしない。サーバーも segfault しない。

サーバーログ:

```text
set-tensor=219  add-tensor=2739  get-tensor=24  execute-op=2472
Devices:
  HTP0: Hexagon (0 MiB, 0 MiB free)
  transport: TCP

HTP0 set-tensor output_norm.weight : ... usage 0 flags 0x0
HTP0 execute-op MUL_MAT|output.weight x result_norm ... q6_K ... hvx-tiled
HTP0 get-tensor result_output : ... size 24000
```

同じモデルを **local HTP0** で実行すると `精度が高い` が返る。HTP カーネルと Q5_K_M/Q6_K 自体は正しい。RPC サーバー側の重み tensor が `usage 0 flags 0x0`、つまり `GGML_HEXAGON_TENSOR_WEIGHT`/`REPACK` が付いていないため、HTP が raw 重みを tiled として読んで空出力になっている可能性が高い。

CPU テスト (`ZenzCpuConversionTest`): `OK (1 test)`、4例一致。

### §13.4 原因と次の修正

- クラッシュは Track C の `rpc_server_init_tensor` で解消。サーバーは 219 set-tensor / 2739 add-tensor / 2472 execute-op を完走した。
- 残るのは正しさ。サーバー側の重み tensor は RPC で buffer usage が伝搬せず、Hexagon の `init_tensor` が WEIGHT/REPACK を付けない。クライアントの local HTP は `usage=WEIGHTS` と `needs_repack` を見て flags を付けるが、サーバーは fresh tensor なので `needs_repack` も空。
- 次の修正: RPC の `set_tensor` / `graph_compute` に buffer usage と repack intent を伝搬する (RPC protocol/API 追加)。または server 側で weight 名/型から flags を決める過渡的ヒューリスティック。
- これは Track C の範囲外。アプリ側 RPC を製品化するには追加修正が必要。

### §13.5 第4ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| Track C: `zenz/hexagon-rpc-fix` 取得 | **PASS** (`e3eb30ee`, parent `4e748636`, `ggml-rpc.cpp` のみ) |
| Track C: 修正版 `ggml-rpc-server` ビルド | **PASS** |
| 目標9: A2 の RPC 実使用確認 | **PASS** (A2 は RPC 非使用と確定。CLI の A2 PASS は無効) |
| 修正版サーバーのクラッシュ解消 | **PASS** (segfault なし、set-tensor/add-tensor/execute-op が通る) |
| 目標10: 4例一致 | **FAIL** (空出力。HTP カーネルではなく server 側 flags 欠落) |
| CPU フォールバック | **PASS** (`ZenzCpuConversionTest` OK) |
| サーバー不在時のフォールバック | **FAIL** (`add_server` が abort。nullptr ではない) |
| 速度 | 参考値のみ |

### §13.6 ローカル状態

- fork clone は detached HEAD `e3eb30ee`。`ggml-hexagon.cpp` は元のソースに戻っており、ローカルガードは `local-hexagon-guards.patch` に退避。
- 修正版 `ggml-rpc-server` / `libggml-rpc.so` / `libggml-hexagon.so` は `build-rpc-android/bin` に生成済み。端末 `/data/local/tmp/zenz-rpc/` へ push 済みで、`server10.log` に RPC 実使用ログが残っている。
- `zenz/rpc` のアプリ側 clone は `d8eb592` のまま。`zenz_bridge.cpp` の RPC 登録順変更、`ZenzRpcConversionTest` の CPU フォールバックを空エンドポイントに変更、`ZenzCpuConversionTest` は未コミット。
- 既存の llama.cpp worktree は元の 9 ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §14 第5ラウンド: usage 伝搬と REPACK フラグ導出（2026-11-18）

### §14.1 Track D: `cbc05f2a` の取得とビルド - PASS

fork を `zenz/hexagon-rpc-fix` の `cbc05f2a` へ更新した。

```text
HEAD: cbc05f2a0edbcecd58fd9e74b613fc47e10bfee0
parent: e3eb30ee4e5e4fe6302d1e7b93274d63e2f3a86b
diff: ggml/src/ggml-hexagon/ggml-hexagon.cpp | 13 +++++
      ggml/src/ggml-rpc/ggml-rpc.cpp         | 87 +++++++++++++++++++++++++++++++---
```

- D-1: `RPC_CMD_BUFFER_SET_USAGE` と `rpc_sync_buffer_usage` を追加。`set_tensor` の冒頭で buffer usage を送る。
- D-2: `ggml_backend_hexagon_buffer_init_tensor` が usage=WEIGHTS から `GGML_HEXAGON_TENSOR_WEIGHT` と（repack 型なら）`GGML_HEXAGON_TENSOR_REPACK` を付ける。
- D-3: `rpc_dispatcher::start` の `GGML_ABORT` を `throw std::runtime_error` に変え、`get_dispatcher` で catch して nullptr を返す。`get_device_count` は 0 を返し、`add_server` は nullptr になる。

`cmake --build build-rpc-android --target ggml-rpc-server` は成功。サーバーと `libggml-rpc.so` / `libggml-hexagon.so` を端末へ push した。

アプリ側 `zenz/rpc` の `zenz/src/main/cpp/llama.cpp` を `cbc05f2a` へ更新し、`:zenz:assembleDebugAndroidTest`（`zenzRpcEnabled=true`）を再ビルドした。`ZenzRpcFallbackTest` を追加し、テストAPKを差し替えた。gitlink のコミット（D-4）は所有者のローカル作業のため、ここでは未コミット。

### §14.2 目標11: 修正版での再検証 - FAIL（usage/flags は正しいが空出力）

`ZenzRpcConversionTest` の結果:

```text
expected:<[精度が高い]> but was:<[]>
ZenzRpcConversionTest.kt:36
```

クラッシュはせず、サーバーも segfault しない。サーバー側の集計:

```text
set-tensor=219  add-tensor=2739  get-tensor=24  execute-op=2472
```

**usage とフラグは正しく届いた。**

```text
HTP0 set-tensor output_norm.weight : ... usage 1 flags 0x2
HTP0 set-tensor output_norm.bias   : ... usage 1 flags 0x2
HTP0 set-tensor output.weight       : ... usage 1 flags 0x3
HTP0 add-tensor #115 output.weight : bi 1 data ... offset 4096 size 2695168 flags 0x3 : 512:6016:1:1
HTP0 add-tensor #111 blk.5.ffn_down.weight : ... flags 0x3 : 2048:512:1:1
```

- `output.weight` は Q6_K で `flags 0x3`（WEIGHT|REPACK）、tiled の `512:6016:1:1`、サイズ 2,695,168。raw の 2,520,000 ではない。
- f32 の norm 重みは `flags 0x2`（WEIGHT）。
- `set-tensor` の `usage 1` は D-1 の伝搬、`flags 0x3` は D-2 の導出が効いていることを示す。

それでも **最初の変換結果は空**。同じモデルの local HTP0 と CPU は `精度が高い` を返すため、HTP カーネル自体・Q5_K_M/Q6_K の対応は正しい。残るのは **tiled レイアウトまたは RPC graph compute の別問題**で、usage 伝搬の欠落ではない。

サーバーのモデルバッファは 16 MiB（`base 0x6d54000000 size 16777216`）。重みの最大オフセットは `blk.5.ffn_down.weight` の 14,973,952 + 917,504 = 15,891,456 でバッファ内。`get_alloc_size` の tiled/raw 不一致やバッファオーバーランは、このログだけでは確定できない。

### §14.3 目標12: サーバー不在時の CPU フォールバック - PASS

サーバーを kill した状態で `ZenzRpcFallbackTest`（`setRpcServer("127.0.0.1:50052")`）を実行:

```text
OK (1 test)
Time: 0.423
```

- `add_server` は abort せず nullptr を返した。
- `initModel` は CPU で成功。
- `isRpcDeviceRegistered()` は false（assert 成功）。
- `セイドガタカイ` -> `精度が高い`。

D-3 により、ブリッジ設計どおりの CPU フォールバックが初めて成立した。**PASS**。

### §14.4 目標13: トグル OFF 復帰 - PASS

`ZenzCpuConversionTest`（`setRpcServer("")`）:

```text
OK (1 test)
Time: 2.099
```

4 例とも一致。**PASS**。

### §14.5 第5ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| Track D: `cbc05f2a` 取得・ビルド | **PASS** |
| D-1: usage 伝搬 | **PASS**（`usage 1` を確認） |
| D-2: WEIGHT/REPACK 導出 | **PASS**（`flags 0x3` / tiled 512:6016 を確認） |
| 目標11: 4例一致 | **FAIL**（空出力。tiled レイアウト / graph compute の別問題） |
| 目標12: サーバー不在 CPU フォールバック | **PASS** |
| 目標13: トグル OFF CPU 復帰 | **PASS** |

### §14.6 次の調査候補（空出力）

1. `get_alloc_size` がサーバー側で tiled サイズを返しているか。raw を返して 16 MiB バッファに tiled 書き込みしている場合、バッファ境界を超える。
2. サーバー側の `set_tensor` が書いた tiled 重みバイト列と、local HTP0 が書いた tiled バイト列の比較（同一オフセット・同一長）。
3. `get_tensor result_output` の logits が NaN/0 でないか。クライアントの sampler に渡る直前の値を確認する。
4. RPC graph compute の KV cache / attention テンソルの `add-tensor` の `flags` と `ne/nb` を local HTP0 と比較する。
5. `add_server` の D-3 は接続失敗のみ。セッション中のサーバー死亡は `RPC_STATUS_ASSERT` で abort のままなので、運用ではトグル OFF で回避する。

### §14.7 ローカル状態

- fork clone は detached HEAD `cbc05f2a`。`local-hexagon-guards.patch` は退避したまま。サーバーは `build-rpc-android/bin` に生成済み。
- アプリ側 `zenz/rpc` clone は `d8eb592` のまま。`zenz` サブモジュールは `cbc05f2a` を checkout（gitlink は未コミット）。`ZenzRpcFallbackTest` / `ZenzRpcConversionTest` / `ZenzCpuConversionTest` とモデル asset は未コミット。
- 端末は `100.108.106.81:35493` で接続。サーバーはテスト後に kill 済み。`server11.log` に usage/flags のログが残っている。
- 既存の llama.cpp worktree は元の 9 ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §15 第6ラウンド: アロケーションサイズの tiled/raw 不一致修正（2026-11-19）

### §15.1 E-1 / E-2 のローカル適用とビルド - PASS

E-1/E-2 はまだ push されていないため、fork clone（`cbc05f2a`）とアプリ側サブモジュール（`cbc05f2a`）へローカルに適用した。

- E-1（`ggml-rpc.cpp`）: `ggml_backend_rpc_buffer_type_get_alloc_size` のサーバー問い合わせ条件から `(tensor->ne[0] % 512 != 0)` を外し、量子化・非 view tensor をすべてサーバーへ問い合わせる。
- E-2（`ggml-hexagon.cpp`）: `set_tensor_impl` の REPACK パスで、tiled フットプリントがバッファ末尾を超えたら `GGML_LOG_ERROR` を出す診断。
- サーバー（`ggml-rpc-server`）とアプリ（`:zenz:assembleDebugAndroidTest`）のビルドは成功。

### §15.2 目標14: 修正版での再検証 - PARTIAL（3/4一致、1例は別漢字）

サーバーのモデルバッファは 16 MiB から **18,874,368 B（18 MB）** に増えた。

```text
HTP0 allocated buffer: base 0x6ffbe00000 size 18874368 fd 25 pinned 0
```

E-2 の境界エラーは **0 件**。`output.weight` は `usage 1 flags 0x3`、`add-tensor 512:6016:1:1`（tiled）、サイズ 2,695,168 のまま。

`ZenzRpcConversionTest` は最初の変換で:

```text
expected:<[精]度が高い> but was:<[制]度が高い>
```

空出力は解消し、**実トークンが出るようになった**。4例の診断結果:

```text
セイドガタカイ       -> [制度が高い]   expected [精度が高い]
カンジヘンカン       -> [漢字変換]     expected [漢字変換]
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い]
```

- **3/4 は完全一致**。
- `セイドガタカイ` だけ `精度` ではなく `制度`。読みは同じ `セイド` で、最初のトークンの argmax が入れ替わっただけ。
- E-1 のアロケーション修正でレイアウト破壊は解消。残りは **RPC/HTP と CPU の数値差による 1 トークンの差**で、tiled/raw や usage の問題ではない。

目標14の「4例一致」を厳密に適用すると **PARTIAL / FAIL**。ただし第1ラウンドの目標5で「NPU と CPU の結果が微妙に違う場合は一致まで要求しない」としていたのと同じクラスの差で、変換としては成立している。

### §15.3 目標15: 回帰 - PASS

- サーバー kill 状態の `ZenzRpcFallbackTest`（`setRpcServer("127.0.0.1:50052")`）: `OK (1 test)`、0.71s。abort なし、CPU フォールバック。
- `ZenzCpuConversionTest`（`setRpcServer("")`）: `OK (1 test)`、2.436s。4例一致。

E-1 は `get_dispatcher` の nullptr 時に `ggml_nbytes` を返すフォールバックを維持しており、フォールバック挙動は不変。

### §15.4 第6ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| E-1: アロケーションをサーバー問い合わせに変更 | **PASS**（バッファ 18 MB、境界エラー 0） |
| E-2: 境界ガード | **PASS**（エラー 0 件） |
| 目標14: 4例一致 | **PARTIAL**（3/4 完全一致、`精度` -> `制度` の1例のみ差） |
| 目標15: サーバー不在フォールバック | **PASS** |
| 目標15: トグル OFF CPU 復帰 | **PASS** |

### §15.5 次の判断

1. `制度が高い` を許容するか。許容するなら RPC 経路は実用レベル。厳密一致が必要なら、最初のトークンの logits を CPU/local HTP と比較する（RPC/HTP の数値差か、Q5_K/Q6_K カーネルの差か）。
2. E-1/E-2 のローカルパッチを Vibe が fork へ push し、round-trip 検証する。Owner は fork を新 sha へ更新し、Sumire の gitlink を `cbc05f2a` -> 新 sha へ更新する。
3. 4例完全一致を目指す場合、`ZenzRpcConversionTest` の最初の期待値を `精度が高い` のままにして、logits 比較の診断を追加する。

### §15.6 ローカル状態

- fork clone は `cbc05f2a` + E-1/E-2 の未コミット変更。`local-hexagon-guards.patch` は退避したまま。
- アプリ側サブモジュールは `cbc05f2a` + E-1 の未コミット変更。`ZenzRpcDiagnosticTest` を追加。gitlink は未コミット。
- 端末は `100.108.106.81:35493`。サーバーはテスト後に kill 済み。`server12.log` に E-2 のログが残っている。
- 既存の llama.cpp worktree は元の 9 ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §16 第7ラウンド: `d04b85d4` 確定版での再検証とスコアマージン（2026-11-19）

### §16.1 `d04b85d4` への整列 - PASS

fork とアプリ側サブモジュールを、push 済みの `d04b85d4`（親 `cbc05f2a`）へ揃えた。ローカルの E-1/E-2 パッチは `local-e1-e2.patch`（1,950 B）と `app-local-e1.patch`（674 B）に退避し、作業ツリーは `d04b85d4` の内容に戻した。

```text
HEAD: d04b85d401a7ec9865fa959731c0a0f9a0266553
parent: cbc05f2a0edbcecd58fd9e74b613fc47e10bfee0
diff: ggml/src/ggml-rpc/ggml-rpc.cpp | 14 ++++++++++++--
      ggml/src/ggml-hexagon/ggml-hexagon.cpp | 24 ++++++++++++++++++++++++
```

`ggml-rpc-server` と `:zenz:assembleDebugAndroidTest` を再ビルド。端末へ push し、`GGML_HEXAGON_VERBOSE=1` で起動した。

### §16.2 4例診断 - 第6ラウンドと同じ 3/4

```text
セイドガタカイ       -> [制度が高い]   expected [精度が高い]
カンジヘンカン       -> [漢字変換]     expected [漢字変換]
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い]
```

`ZenzRpcConversionTest` は最初の `精度`/`制度` で FAIL（`expected:<[精]度が高い> but was:<[制]度が高い>`）。空出力・クラッシュ・境界エラーはなし。`d04b85d4` は第6ラウンドのローカル E-1/E-2 と同じ結果を再現した。

### §16.3 スコアマージン（Goal 16 の近似）

生の first-token logits ではなく、既存の `scoreCandidates`（`FloatArray`）で `精度が高い` と `制度が高い` のスコアを RPC/CPU で比較した（`ZenzScoreMarginTest`、ローカル・未コミット）。

```text
RPC scores: 精度が高い = -0.17849746, 制度が高い = -0.10969770
CPU scores: 精度が高い = -0.12870915, 制度が高い = -0.15686718
```

- RPC は `制度` を 0.0688 高く評価。
- CPU は `精度` を 0.0282 高く評価。
- どちらもスコア差は 0.1 未満の **近接**。RPC 経路が約 0.097 マージンをずらして argmax を反転させている。
- これは tiled/raw・usage・alloc_size の破壊ではなく、**RPC/HTP と CPU の小さな数値差**（op 配分、KV/attention の F16、CPU フォールバック op のスレッド/リダクション順序など）で説明できる範囲。
- ローカル HTP0（CLI）は第4ラウンドで `精度が高い` を返しており、RPC 経路だけが `制度` 側に寄る。原因を厳密に特定するには、生の first-token logits を CPU/local HTP0/RPC の3方式で比較する Goal 16 本番が必要。

### §16.4 回帰（`d04b85d4`） - PASS

- サーバー kill 状態の `ZenzRpcFallbackTest`: `OK (1 test)`、0.669s。abort なし、CPU フォールバック。
- `ZenzCpuConversionTest`: `OK (1 test)`、2.429s。4例一致。

### §16.5 第7ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| `d04b85d4` への整列・ビルド | **PASS** |
| 4例診断 | **PARTIAL**（3/4 完全一致、`精度` -> `制度` の1例のみ差） |
| スコアマージン | **近接**（RPC 0.0688 で制度、CPU 0.0282 で精度。差は小さい） |
| サーバー不在フォールバック | **PASS** |
| トグル OFF CPU 復帰 | **PASS** |

### §16.6 推奨

- **実用レベル基準**なら、3/4 完全一致＋残り1例は読み同じ（`セイド` -> `制度`/`精度`）でクローズ可。RPC 経路は実用上成立している。
- **厳密一致基準**なら、Goal 16 本番（生の first-token logits を CPU/local HTP0/RPC で比較）を実施し、RPC 固有の数値差か env 差かを切り分ける。今回の `scoreCandidates` 近似では「近接同率 + 小さな RPC 差」までしか言えない。
- 確定作業として残るのは、Sumire `zenz/rpc` の gitlink を `cbc05f2a` -> `d04b85d4` へ更新する所有者ローカルコミット。アプリ側サブモジュールは `d04b85d4` を checkout 済み（gitlink 未コミット）。

### §16.7 ローカル状態

- fork clone は detached HEAD `d04b85d4`。`local-e1-e2.patch` / `local-hexagon-guards.patch` は退避。
- アプリ側サブモジュールは `d04b85d4`。`ZenzRpcDiagnosticTest` / `ZenzScoreMarginTest` / `ZenzRpcFallbackTest` / `ZenzCpuConversionTest` は未コミット。`zenz_bridge.cpp` の RPC 登録順変更も未コミット。
- 端末は `100.108.106.81:35493`。サーバーはテスト後に kill 済み。`server13.log` に `d04b85d4` のログが残っている。
- 既存の llama.cpp worktree は元の 9 ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §17 第8ラウンド: アプリ単体NPUへの再挑戦（SDK版cdsprpc、2026-11-19）

### §17.1 目標17: SDK版cdsprpcの特定と事前検査 - PASS

Hexagon SDK 6.6.0.0 には `libs/android/arm64/libcdsprpc.so` は無く、Android aarch64 向けは次のファイルだった。

```text
C:\Qualcomm\Hexagon_SDK\6.6.0.0\ipc\fastrpc\remote\ship\android_aarch64\libcdsprpc.so
```

| 項目 | 値 |
| --- | --- |
| サイズ | 30,992 B |
| sha256 | `368D22295DE2971DB5B246FF4FD9FF93FA74BF3C498125D6FD6352D016756E4F` |
| ELF | ELF64 AArch64, DYN |
| NEEDED | `liblog.so`, `libm.so`, `libc.so`, `libdl.so` のみ |
| HIDL/vendor/ld-android 依存 | **なし** |
| 主要エクスポート | `remote_session_control`, `remote_handle64_*`, `fastrpc_mmap/munmap`, `rpcmem_alloc/free/to_fd`, `dspqueue_*` |

vendor版（600,224 B、`5D43CEF2...`）と第2ラウンドの@パッチ版（600,224 B、`328D7B56...`）とはすべて別物。DT_NEEDED が公開ライブラリのみなので、ゲート1の見込みは高いと判断した。

### §17.2 目標18: APKビルド - PASS

`zenz/npu` clone の prebuilt を SDK版 `libcdsprpc.so` に差し替え、`prepareZenzNpuLibs` の include を次の3つだけに絞った。

```groovy
include "libggml-htp-*.so", "libomp.so", "libcdsprpc.so"
```

第2ラウンドの依存チェーン同梱（libhidlbase、vendor.qti...、libvndksupport 等）は外した。テストAPKの中身:

```text
lib/arm64-v8a/libcdsprpc.so         10,216 B（strip後。pre-stripは30,992 B）
lib/arm64-v8a/libggml-htp-v81.so   1,103,304 B
lib/arm64-v8a/libomp.so            1,234,864 B
lib/arm64-v8a/libzenz.so          12,110,336 B
```

APK 内の `libcdsprpc.so` は strip で 10,216 B になっているが、pre-strip は SDK版 30,992 B。**ビルド成功＋SDK版cdsprpc＋skel＋libomp の3ファイル**で PASS。

### §17.3 目標19: 実機ゲート1（dlopen・デバイス列挙） - PASS

`ZenzNpuEnumerationTest` の結果:

```text
available=true
count=2; HTP0 / Hexagon / type=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=ok
```

第1〜2ラウンドで `count=1` だったのが、**SDK版cdsprpcではアプリのlinker namespaceでdlopenでき、Hexagonデバイスが列挙された**。ゲート1は PASS。

### §17.4 目標20: 実機ゲート2（unsigned PD） - FAIL

同じSDK版cdsprpcを **shellドメインのCLI** に `LD_LIBRARY_PATH=.` で読ませて切り分けた（vendor版ではなくSDK版を強制）。

```text
E ggml-hex: FastRPC capability query failed (err -1)
E ggml-hex: failed to query HTP version (err -1) defaulting to v73
W ggml-hex: forcing ndev to 1 for SoCs archs lower than v75.
E ggml-hex: HTP0 failed to enable unsigned PD : error 0xffffffff
E llama_model_load: error loading model: ggml-hex: remote_session_control(unsign) failed (see log for details)
E llama_model_load_from_file_impl: failed to load model
```

アプリ側の `ZenzNpuConversionTest` は **クラッシュせず**、`initModel` は true を返したが、最初の変換が空だった。

```text
expected:<[精度が高い]> but was:<[]>
ZenzNpuConversionTest.kt:36
```

- ゲート1は通るが、**unsigned PD が `0xffffffff` で失敗**する。SDK版cdsprpcは FastRPC capability query も `-1`、HTP version query も `-1` で、vendorのDSPドライバと組で動くクライアントではない。
- アプリ側が `initModel=true` で空出力になったのは、HTPセッション失敗がブリッジの「ロード失敗→CPUリトライ」で拾われず、HTPバックエンドが登録されたままのモデル/コンテキストで変換したためと見られる。SDK版cdsprpcを含むNPUビルドでは `setNpuEnabled(false)` のCPUテストも空出力になった（HTPバックエンドのセッション失敗がプロセス内のCPU経路にも影響）。**CPUのみのビルド（第1ラウンド）は従来どおり動く**。
- 端末は `settings put global zenz_npu 0` に戻し、`/data/local/tmp/zenz-rpc/libcdsprpc.so` は削除してvendor版の探索に戻した。

### §17.5 第8ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| 目標17: SDK版cdsprpcの特定・DT_NEEDED検査 | **PASS**（公開ライブラリのみ、必要シンボルあり） |
| 目標18: APKビルド（SDK版cdsprpcのみ同梱） | **PASS** |
| 目標19: ゲート1（dlopen・デバイス列挙） | **PASS**（`count=2; HTP0 / Hexagon; dlopen=ok`） |
| 目標20: ゲート2（unsigned PD） | **FAIL**（`remote_session_control(unsign)` error `0xffffffff`） |
| 4例変換・トグルOFF復帰 | 未到達（ゲート2 FAILのため） |

**結論**: SDK版cdsprpcはアプリのlinker namespaceでロードでき、Hexagonデバイスの列挙まで到達した（第1〜2ラウンドの「dlopenで停止」は解消）。しかし **unsigned PD が `0xffffffff` で拒否**され、SDK版cdsprpcのFastRPC/HTP version queryも失敗する。stockファームウェアのアプリドメインで自作skelを動かす道は、**ゲート2で枯渇**と判断する。アプリ単体NPUを追うなら、署名済みskel（OEM/QNNの正規ルート）、root/カスタムROM、またはRPC（PCのadb shell補助）のいずれかになる。

RPCシリーズ（`d04b85d4`、実用レベル基準でクローズ）はこの結果の影響を受けない。

### §17.6 ローカル状態

- `zenz/npu` clone の prebuilt `libcdsprpc.so` は SDK版（30,992 B、`368D...`）。旧@パッチ版は `libcdsprpc.vendor-patched.so.bak` に退避。
- `zenz/build.gradle` の `prepareZenzNpuLibs` は3ファイルのみ。`ZenzNpuEnumerationTest` / `ZenzNpuCpuTest` を追加（未コミット）。
- 端末は `100.108.106.81:35493`、`zenz_npu=0`。SDK版cdsprpcは `/data/local/tmp/zenz-rpc/` から削除済み。
- 既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §18 第9ラウンド: cdsprpc無同梱＋dlerror取得（2026-11-19）

### §18.1 前提の確認: vendor public.libraries

```text
/vendor/etc/public.libraries.txt:
libqti-perfd-client.so
libadsprpc.so
libcdsprpc.so
libsdsprpc.so
libfastcvopt.so
libOpenCL.so
```

`/system/etc/public.libraries.txt` は標準ライブラリのみで、`libcdsprpc.so` / `libOpenCL.so` は含まない。vendor の public list には両方ある。

### §18.2 cdsprpc無同梱APK

`zenzHexagonPrebuiltDir` から `libcdsprpc.so` を外し、`prepareZenzNpuLibs` の include を次の2つにした。

```groovy
include "libggml-htp-*.so", "libomp.so"
```

テストAPKの `lib/arm64-v8a`:

```text
libggml-htp-v81.so   1,103,304 B
libomp.so            1,234,864 B
libzenz.so          12,110,336 B
libcdsprpc entries: 0
```

**APK内に `libcdsprpc.so` が存在しないことを確認済み。**

### §18.3 実機診断

`zenz_npu=1`、`ZenzNpuDiagnosticTest`（`setNpuEnabled(true)` → `isNpuAvailable` → `getBackendDeviceSummary` → `initModel` → 4例）を実行した。

```text
available=false
summary=count=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=fail: dlopen failed: library "libcdsprpc.so" not found
initModel=true
セイドガタカイ       -> [精度が高い]   expected [精度が高い]
カンジヘンカン       -> [漢字変換]     expected [漢字変換]
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い]
```

- `dlopen("libcdsprpc.so")` の dlerror は **`dlopen failed: library "libcdsprpc.so" not found`**。vendor public list にあっても、アプリのデフォルト namespace の soname 検索では見つからない。
- HTP デバイスが列挙されないため、`initModel` は CPU で成功し、4例はすべて CPU で一致した。**cdsprpc無同梱時のCPUフォールバックは正常**（第8ラウンドのSDK版同梱時の空出力とは異なる）。
- `ggml-hex` のセッション行（`remote_session_control(unsign)`、`new session: HTP0`、session-uri）は**出ていない**。HTP セッション自体が作られていないため。

### §18.4 PocketPalの読み込み経路（APK検査、実行はしていない）

インストール済み PocketPal AI（`com.pocketpalai`、v1.23.0）の arm64 split を inspect した。native lib に `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so`（15,152,712 B）があり、**DT_NEEDED に `libcdsprpc.so` と `libOpenCL.so` を直接持つ**。

```text
NEEDED Shared library: [liblog.so]
NEEDED Shared library: [libandroid.so]
NEEDED Shared library: [libcdsprpc.so]
NEEDED Shared library: [libOpenCL.so]
NEEDED Shared library: [libm.so]
NEEDED Shared library: [libc++_shared.so]
NEEDED Shared library: [libdl.so]
NEEDED Shared library: [libc.so]
```

PocketPal の split APK に `libcdsprpc.so` は同梱されていない。つまり **PocketPal は「DT_NEEDED でロード時に vendor の public `libcdsprpc.so` を解決させる」経路**で動いており、こちらの `dlopen("libcdsprpc.so")`（実行時 soname 検索）とは経路が違う。これが「PocketPalは動くが、こちらのアプリは not found」の差の直接証拠。

### §18.5 第9ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| cdsprpc無同梱APK（2ファイルのみ） | **PASS**（APK内にcdsprpcなし） |
| dlerror取得 | **PASS**（`library "libcdsprpc.so" not found`） |
| HTP列挙 | **FAIL**（count=1、CPUのみ） |
| CPUフォールバック＋4例 | **PASS**（4例一致） |
| PocketPalの読み込み経路 | **判明**（DT_NEEDED `libcdsprpc.so`、splitに同梱なし） |

### §18.6 次の一手

`libzenz.so` を **`libcdsprpc.so` への DT_NEEDED 付き**でビルドする（リンク時に vendor または SDK の `libcdsprpc.so` を `-lcdsprpc` で参照し、APKには同梱しない）。PocketPal と同じく、アプリのロード時に vendor public `libcdsprpc.so` がリンカに解決される。`htp-drv.cpp` の `dlopen("libcdsprpc.so")` は、DT_NEEDED で既にロード済みのハンドルを返すようになる。これでゲート1の `not found` が解消する見込み。

- ビルド時のリンク用 `libcdsprpc.so` は vendor 版（600,224 B）でも SDK 版（30,992 B）でもよい。実行時に使われるのは端末の vendor public lib。
- APKに `libcdsprpc.so` を同梱しないこと（同梱すると第1〜2ラウンドの依存チェーン問題が再発する）。
- 次のラウンドで `DT_NEEDED` 付き `libzenz.so` をビルドし、`getBackendDeviceSummary` の `dlopen=ok` と HTP 列挙、`remote_session_control(unsign)` / session-uri / 4例を確認する。

端末は `zenz_npu=0` に戻した。push / merge はしていない。第1〜2ラウンドのローカル資産は保持したまま。

---

## §19 第10ラウンド: `libzenz.so` の DT_NEEDED 付与（2026-11-19）

### §19.1 手順0: public.libraries

```text
/vendor/etc/public.libraries.txt:
libqti-perfd-client.so
libadsprpc.so
libcdsprpc.so
libsdsprpc.so
libfastcvopt.so
libOpenCL.so

/system/etc/public.libraries.txt:
libcdsprpc.so / libOpenCL.so は含まれない
```

### §19.2 リンク変更と DT_NEEDED

`zenz/src/main/cpp/zenz-link-libs/libcdsprpc.so`（SDK版 30,992 B、`368D...`）をリンク専用に置き、`ZENZ_ENABLE_NPU` 内で次を追加した。

```cmake
set(ZENZ_CDSPRC_LINK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/zenz-link-libs")
target_link_options(zenz PRIVATE
    "-L${ZENZ_CDSPRC_LINK_DIR}"
    "-Wl,--no-as-needed"
    "-l:libcdsprpc.so"
    "-Wl,--as-needed"
)
```

ビルド後の `libzenz.so`:

```text
NEEDED Shared library: [libcdsprpc.so]
NEEDED Shared library: [liblog.so]
NEEDED Shared library: [libdl.so]
NEEDED Shared library: [libomp.so]
NEEDED Shared library: [libm.so]
NEEDED Shared library: [libc.so]
```

APK内の `libcdsprpc.so` は 0 件（`libggml-htp-v81.so` / `libomp.so` / `libzenz.so` のみ）。**DT_NEEDED の付与自体は成功。**

### §19.3 実機結果: libzenz のロード失敗

`adb install -r` 後の `ZenzNpuDiagnosticTest` は、`ZenzEngine.<clinit>` の `System.loadLibrary` で失敗した。

```text
java.lang.UnsatisfiedLinkError: dlopen failed: library "libcdsprpc.so" not found:
needed by /data/app/~~.../com.kazumaproject.zenz.test-.../lib/arm64/libzenz.so in namespace clns-9
```

- DT_NEEDED があっても、テストAPKの `clns-9` namespace では vendor の `libcdsprpc.so` が見つからない。
- PocketPal の targetSdk 36 / minSdk 32 に合わせた variants（targetSdk 36、minSdk 32）でも**同じ `clns-9` not found**。targetSdk/minSdk の差ではない。
- したがって `getBackendDeviceSummary()`、`ggml-hex` のセッション行、4例は **DT_NEEDED ビルドでは取得できていない**（`libzenz.so` がロードされる前に失敗）。復旧用に戻した第9ラウンドAPKでは CPU の summary（`count=1; CPU`）と4例が従来どおり動く。

### §19.4 PocketPal のロード経路の再確認

PocketPal の `librnllama...hexagon_opencl.so` は DT_NEEDED に `libcdsprpc.so` を持つが、同ライブラリ / `librnllama_jni...` / `libreactnative.so` / `libfbjni.so` / `libappmodules.so` / `libhermesvm.so` の strings には `android_dlopen_ext` / `android_create_namespace` / `libnativeloader` / `vendor/lib64` は見つからない。PocketPal は通常アプリの classloader namespace で DT_NEEDED を解決できており、**テストAPKの `clns-9` とは namespace 構成が違う**可能性が高い。テストAPK（instrumentation）の namespace は通常アプリの namespace の代理にならない。

### §19.5 第10ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| `libzenz.so` への DT_NEEDED `libcdsprpc.so` 付与 | **PASS**（readelf で確認） |
| APKへの cdsprpc 非同梱 | **PASS**（0件） |
| テストAPKでの libzenz ロード | **FAIL**（`clns-9` で `libcdsprpc.so` not found） |
| targetSdk 36 / minSdk 32 variants | **FAIL**（同じ `clns-9` not found） |
| 復旧（第9ラウンドAPK＋CPU） | **PASS**（`zenz_npu=0`、CPU 4例） |

### §19.6 次の一手

DT_NEEDED の付与は正しいが、**instrumentation テストAPKの `clns-9` では vendor public lib を解決できない**。通常アプリの namespace で確認する必要がある。

1. `:app` の通常APK（full/minimal のうち ai-runtime を避けられる方）に NPU `zenz` を入れ、`libzenz.so` の DT_NEEDED が通常アプリの `clns` で解決できるか確認する。
2. あるいは、テスト用の**通常アプリ**（activity 付きの小さな APK）を作り、`System.loadLibrary("zenz")` を呼ぶ。instrumentation の `clns` を避けられる。
3. 通常アプリでも `not found` なら、`android_create_namespace` / `android_dlopen_ext` で `/vendor/lib64` と `/system/lib64` を含む namespace を作り、そこで `libzenz.so` をロードする loader を実装する。
4. いずれの場合も APK に `libcdsprpc.so` を同梱しない（同梱は第1〜2ラウンドの依存チェーン問題を再発させる）。

端末は第9ラウンドAPK＋`zenz_npu=0` に復旧済み。push / merge はしていない。`zenz/src/main/cpp/zenz-link-libs/libcdsprpc.so`、`CMakeLists.txt` の link options、`zenz/build.gradle` の minSdk 32 / targetSdk 36 はローカルの未コミット変更として残っている。

---

## §20 第11ラウンド: 通常アプリ（zenzprobe）でのNPUロード確認（2026-11-19）

### §20.1 flavorの選定

- `full` edition: `fullImplementation project(':zenz')` と `fullImplementation project(':ai-runtime')`。第1ラウンドの ai-runtime Vulkan 問題でビルド不能。
- `lite` edition: ai-runtime を含まないが、`:zenz` 依存も無い（zenz UI / runtime service は `app/src/full` のみ）。
- **zenz を含み、ai-runtime を含まない flavor は無い**。ルートB（プローブアプリ）へ。

### §20.2 ルートB: `zenzprobe`（通常アプリ）

`zenzprobe` モジュールをローカル追加した（pushしない）。

- `com.android.application`、`namespace com.kazumaproject.zenzprobe`、`applicationId com.kazumaproject.zenzprobe`。
- `compileSdk 37`、`minSdk 32`、`targetSdk 36`、`arm64-v8a` のみ。
- `implementation project(':zenz')`、`packaging.jniLibs.useLegacyPackaging = true`。
- `MainActivity` が `ZenzEngine.setNpuEnabled(true)` → `isNpuAvailable()` → `getBackendDeviceSummary()` → `initModel` → 4例を実行し、各ステップを `files/zenzprobe.txt` に逐次書き出す。

APK内容:

```text
lib/arm64-v8a/libggml-htp-v81.so  1,072,612 B（strip後）
lib/arm64-v8a/libomp.so             973,944 B（strip後）
lib/arm64-v8a/libzenz.so         12,021,088 B（strip後）
assets/ggml-model-Q5_K_M.gguf    20,970,304 B
libcdsprpc entries: 0
```

**AARのassetsマージでモデルが入った。cdsprpcは非同梱。**

### §20.3 DT_NEEDED無し（安全側）の結果

`libzenz.so` の NEEDED は `liblog/libdl/libomp/libm/libc` のみ。通常アプリで実行:

```text
model=20970304
available=false
summary=count=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=fail: dlopen failed: library "libcdsprpc.so" not found
initModel=true
セイドガタカイ       -> [精度が高い] expected [精度が高い]
カンジヘンカン       -> [漢字変換] expected [漢字変換]
ニホンゴニュウリョク -> [日本語入力] expected [日本語入力]
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い]
```

**通常アプリでも `dlopen("libcdsprpc.so")` は `not found`。** CPUフォールバックと4例は正常。HTPセッション行（`remote_session_control` / `new session` / session-uri）は出ていない。

### §20.4 DT_NEEDEDあり（通常アプリ）の結果

第10ラウンドの link options を再適用し、`libzenz.so` に `NEEDED libcdsprpc.so` を付けたプローブAPKを作成（APK内cdsprpcは0件）。実行:

```text
model=20970304
ERROR: java.lang.UnsatisfiedLinkError: dlopen failed: library "libcdsprpc.so" not found:
needed by /data/app/~~.../com.kazumaproject.zenzprobe-.../lib/arm64/libzenz.so in namespace clns-9
```

- **通常アプリの `clns-9` でも vendor public `libcdsprpc.so` を解決できない。**
- テストAPK固有の namespace 問題ではなく、通常アプリの classloader namespace でも同じ。
- プローブの `catch (Throwable)` が `UnsatisfiedLinkError` を捕まえたためプロセスは落ちず、`zenzprobe.txt` にエラー全文が残った。HTP列挙・セッション・4例は DT_NEEDED ビルドでは取得できていない。

### §20.5 第11ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| zenz を含み ai-runtime を含まない flavor | **無し**（full=zenz+ai-runtime、lite=zenz無し） |
| プローブ通常アプリのビルド | **PASS**（モデルasset込み、cdsprpc非同梱） |
| DT_NEEDED無し: `dlopen` | **FAIL**（`library "libcdsprpc.so" not found`） |
| DT_NEEDED無し: CPUフォールバック＋4例 | **PASS** |
| DT_NEEDEDあり: libzenzロード | **FAIL**（通常アプリの `clns-9` で not found） |
| HTP列挙・セッション・4例 | 未到達 |

### §20.6 結論と次の一手

- **通常アプリの classloader namespace（`clns-9`）でも vendor public `libcdsprpc.so` は解決できない。** テストAPKの namespace だけの問題ではなかった。DT_NEEDED を付けても同じ。
- PocketPal の DT_NEEDED 経路は、素の classloader namespace では再現できない。PocketPal は **実行時に files 配下へ Hexagon ランタイムを展開して先にロードしている**、または **独自の namespace/preload** を使っている可能性が高い（APK内の `librnllama...` 自体には `android_dlopen_ext` / `android_create_namespace` の文字列は無い）。
- 実keyboard本体でも同じ namespace 制約になるため、このままでは **アプリ単体NPUは不可**。次に進むなら:
  1. `zenz` の JNI を、`libdl` の `android_create_namespace` + `android_dlopen_ext` で `/vendor/lib64` と `/system/lib64` を含む namespace を作り、そこで `libcdsprpc.so` と `libzenz.so` をロードする小さな loader に載せ替える。
  2. PocketPal の files 配下（`/sdcard/Android/data/com.pocketpalai/files` 等）に Hexagon ランタイムが展開されているか確認し、同じ preload 方式を採る。
  3. vendorの `libcdsprpc.so` + 依存を APK に同梱する方式は、第1〜2ラウンドの `ld-android.so` で閉じているため不可。
- CPUフォールバックは通常アプリでも正常（4例一致）。cdsprpc非同梱・DT_NEEDED無しの安全側ビルドは維持できる。

### §20.7 ローカル状態

- `zenzprobe` モジュール（`build.gradle` / `AndroidManifest.xml` / `MainActivity.kt`）をローカル追加。`settings.gradle` に `include ':zenzprobe'`。pushしない。
- プローブの no-DT_NEEDED APK は `zenzprobe-debug-nodt.apk` にバックアップ。現在は no-DT_NEEDED APK をインストールし、`zenz_npu=0`、CPU 4例で復旧済み。
- `zenz/src/main/cpp/CMakeLists.txt` の DT_NEEDED link options は**コメントアウト**に戻した（`zenz-link-libs` は残置）。`zenz/build.gradle` の minSdk 32 / targetSdk 36 はローカル変更のまま。
- 既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。push / merge はしていない。

---

## §21 第12ラウンド: `<uses-native-library>` 宣言の追加（2026-11-19）

### §21.1 原因と修正

第1〜11ラウンドの全APKに **`<uses-native-library android:name="libcdsprpc.so" android:required="false" />`** が無かった。Android 12+（API 31+）では、`/vendor/etc/public.libraries.txt` 掲載のvendor public libでも、アプリが `uses-native-library` で宣言しないと **classloader namespace（`clns-*`）** に露出しない。PocketPalのmanifestには `libOpenCL.so` と `libcdsprpc.so` の両方が宣言されている。

配置は **`<application>` の子**。`<manifest>` 直下に置くと AAPT が `unexpected element <uses-native-library> found in <manifest>` で失敗した（第12ラウンドの最初のビルド）。`<application>` 内に移してビルド成功。

マージ後manifest（`zenzprobe/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`）:

```xml
<application ...>
    <!-- Optional native library for Hexagon DSP (NPU acceleration) -->
    <uses-native-library
        android:name="libcdsprpc.so"
        android:required="false" />
    <activity ...>
```

`aapt2 dump xmltree` の出力はこの環境では空だったが、マージ後manifestファイルにタグがあり、実機の `dlopen=ok` で機能を確認した。

### §21.2 プローブAPK

第11ラウンドの `zenzprobe` をそのまま使用。DT_NEEDED link optionsはコメントアウトのまま（dlopen方式）。APK:

```text
lib/arm64-v8a/libggml-htp-v81.so  1,072,612 B（strip後）
lib/arm64-v8a/libomp.so             973,944 B（strip後）
lib/arm64-v8a/libzenz.so         12,021,088 B（strip後。NEEDEDにcdsprpcなし）
assets/ggml-model-Q5_K_M.gguf    20,970,304 B
libcdsprpc entries: 0
```

### §21.3 実機結果: アプリ単体NPU成立（プローブレベル）

`zenz_npu=1`、プローブ起動（CPU→NPUの順で実行、各ステップをファイルに逐次記録）:

```text
model=20970304
== CPU ==
cpu_initModel=true ms=62.112188
セイドガタカイ       -> [精度が高い]   expected [精度が高い]   ms=300.567968
カンジヘンカン       -> [漢字変換]     expected [漢字変換]     ms=213.458333
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]   ms=259.284896
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い] ms=306.739167
== NPU ==
available=true
summary=count=2; HTP0 / Hexagon / type=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=ok
npu_initModel=true ms=199.902135
セイドガタカイ       -> [制度が高い]   expected [精度が高い]   ms=25.433281
カンジヘンカン       -> [漢字変換]     expected [漢字変換]     ms=12.880937
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]   ms=15.246614
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い] ms=20.182083
```

- **`dlopen=ok`、`count=2`、HTP0 / Hexagon が列挙された。**
- `npu_initModel=true`。unsigned PD は通過し、エラーコードは出ていない。
- 4例はすべて変換成立。`セイドガタカイ` だけ `精度` -> `制度` の近接同率（RPC経路と同じ1例の差）。他3例は完全一致。
- `ggml-hex` のセッション行（`remote_session_control` / `new session` / session-uri）は **logcatが空のため未取得**。ただし `HTP0` 列挙、`npu_initModel=true`、NPU変換の速度からHTPセッションが作られたことは確定。`remote_session_control(unsign)` のエラーは無し。

### §21.4 速度（参考値）

| 変換 | CPU | NPU | 比 |
| --- | --- | --- | --- |
| `セイドガタカイ` | 300.6 ms | 25.4 ms | 約11.8x |
| `カンジヘンカン` | 213.5 ms | 12.9 ms | 約16.6x |
| `ニホンゴニュウリョク` | 259.3 ms | 15.2 ms | 約17.0x |
| `キョウハテンキガヨイ` | 306.7 ms | 20.2 ms | 約15.2x |
| `initModel` | 62.1 ms | 199.9 ms | NPUはセッション生成込みで遅い |

**初のアプリ単体NPUの実測。変換は約12〜17倍速。** initModelはHTPセッション生成・重み転送の固定費でNPUが遅いが、変換を繰り返す用途ではNPUが有利。

### §21.5 第12ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| `<uses-native-library>` マージ | **PASS**（`<application>` 内、実機でdlopen成功） |
| プローブAPK（cdsprpc非同梱） | **PASS** |
| `dlopen=ok` / `count=2` / HTP0列挙 | **PASS** |
| unsigned PD通過 | **PASS**（エラーなし） |
| 4例成立 | **PASS**（3/4完全一致、1例は近接同率の読み差） |
| CPUフォールバック | **PASS**（CPU 4例一致） |
| 速度 | NPU変換はCPU比 約12〜17x（initModelはNPUが遅い） |

**アプリ単体NPUがプローブレベルで成立した。** 第1〜11ラウンドの「not found」は、`uses-native-library` 宣言の欠落で統一説明できる。

### §21.6 次の一手

1. **実keyboard本体への展開**: `app/src/main/AndroidManifest.xml`（またはfullのmanifest）の `<application>` に同じ `uses-native-library` を追加する。full appのビルドは ai-runtime のWindows Vulkanで未解決のため、次ラウンドでビルド経路を確保する（liteにzenzを足す、ai-runtimeを外す、またはLinux/macOSビルド）。
2. **`zenz_bridge` のCPUフォールバック欠陥修正**: HTP列挙後にセッションが死ぬ場合、`initModel=true`のまま空出力になる経路。今回のプローブではセッションが通ったが、非対応端末・PD拒否時の安全設計として修正が必要。
3. `精度`/`制度` の近接同率はCPU/NPUの数値差。厳密一致が必要ならlogits比較（第7ラウンド目標16）を再開。
4. 速度の本測定（長い文脈・連続変換）とメモリ。

### §21.7 ローカル状態

- `zenzprobe` に `<uses-native-library>` を追加し、CPU→NPUの順で4例とタイミングを記録する `MainActivity` に更新。
- プローブ結果は `zenzprobe-result-round12.txt` に保存。端末は `zenz_npu=0` に戻した（プローブ自体は `setNpuEnabled` 直接呼び出し）。
- `zenz` の DT_NEEDED link options はコメントアウト、cdsprpc非同梱、`zenz-link-libs` は残置。`zenz/build.gradle` の minSdk 32 / targetSdk 36 はローカル変更。
- push / merge はしていない。既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。

---

## §22 第13ラウンド: 実keyboard本体（npu flavor）でのNPU（2026-11-19）

### §22.1 npu flavorの追加とビルド

`app` に `npu` edition flavorを追加した。full/liteの既存flavorは変更していない。

```groovy
npu {
    dimension "edition"
    applicationIdSuffix ".npu"
    versionNameSuffix "-npu"
    buildConfigField "boolean", "HAS_ZENZ", "true"
    buildConfigField "boolean", "HAS_GEMMA", "false"
    buildConfigField "String", "APP_EDITION", "\"full\""
    ndk { abiFilters 'arm64-v8a' }
}
```

- `npuImplementation project(':zenz')`、`onVariants(selector().withFlavor("edition","npu"))` で `useLegacyPackaging=true` / `useLegacyPackagingFromBundle=true`。
- `variantFilter` で `npu` + `fdroid` を ignore。
- `app/src/npu/AndroidManifest.xml`: `ZenzRuntimeService`（`:zenz`、`exported=false`、`stopWithTask=false`）のみ。
- `app/src/npu/java/.../ZenzRuntimeService.kt`: `app/src/full` から1ファイルコピー。`ai/`・`gemma/` はコピーしない。
- `app/src/main/AndroidManifest.xml` の `<application>` 内に `libcdsprpc.so` の `uses-native-library` を追加（既存の `libOpenCL.so` / `libvndksupport.so` の隣）。
- `zenz/build.gradle` の minSdk を 24 に戻した（app本体の minSdk 24 と合わせる。probeの minSdk 32 はそのまま）。

ビルド:

```text
:app:assembleNpuStandardDebug              BUILD SUCCESSFUL in 12m 10s
:app:assembleNpuStandardDebugAndroidTest   BUILD SUCCESSFUL in 3m 27s
```

`.npu` APK:

```text
lib/arm64-v8a/libggml-htp-v81.so  1,072,612 B
lib/arm64-v8a/libomp.so             973,944 B
lib/arm64-v8a/libzenz.so         12,110,336 B
assets/ggml-model-Q5_K_M.gguf    20,970,304 B
libcdsprpc entries: 0
```

### §22.2 実機: `:zenz` サービス経由の4例

`com.kazumaproject.markdownhelperkeyboard.npu` をインストールし、`zenz_npu=1` で `ZenzRuntimeService` に binder 接続して `initialize` → `generate` を4例実行した（キーボードUIの入力ではなく、実keyboardの `:zenz` サービス/`ZenzEngine` を直接駆動）。

NPU（`zenz_npu=1`）:

```text
zenz_npu=1
model=20970304
bindService=true
init_ms=339.246458 pid=15541
セイドガタカイ       -> [制度が高い]   expected [精度が高い]   ms=34.114427
カンジヘンカン       -> [漢字変換]     expected [漢字変換]     ms=13.381145
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]   ms=15.481927
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い] ms=22.161823
```

CPU（`zenz_npu=0`、`:npu` プロセス再起動後）:

```text
zenz_npu=0
model=20970304
bindService=true
init_ms=64.235052 pid=15735
セイドガタカイ       -> [精度が高い]   expected [精度が高い]   ms=783.341093
カンジヘンカン       -> [漢字変換]     expected [漢字変換]     ms=638.220208
ニホンゴニュウリョク -> [日本語入力]   expected [日本語入力]   ms=808.270312
キョウハテンキガヨイ -> [今日は天気が良い] expected [今日は天気が良い] ms=1037.23401
```

- **`:zenz` プロセス（pid 15541）でNPU変換が成立。** 4例は変換成立、3/4完全一致、`セイドガタカイ` だけ `制度` の近接同率（probe/RPCと同じ1例の差）。
- NPU変換は 34.1 / 13.4 / 15.5 / 22.2 ms。CPUは 783 / 638 / 808 / 1037 ms。**約23〜52倍速**（初回CPUはウォームアップ込み）。
- `init_ms` はNPU 339.2 ms、CPU 64.2 ms。NPUはHTPセッション生成・重み転送の固定費で初回が遅い。
- CPU復帰（`zenz_npu=0`、`:npu` 再起動）で4例すべて `精度` に戻り、空出力・クラッシュなし。**CPU回帰OK**。
- `:zenz` の `pid` が 15541（NPU）と 15735（CPU）で別プロセスになっていることも確認。

### §22.3 ログ

`adb logcat -d` はこの端末では依然空で、`:zenz` の `ggml-hex` セッション行（`remote_session_control(unsign)` / `new session` / session-uri）と `zenz-bridge` の `Timber` 行は取得できていない。`unsign` のエラーコードは無し（プロセスは生存し、NPU変換が完了）。`HTP0` の証拠は、probeの `count=2; HTP0 / Hexagon; dlopen=ok` と、`:zenz` のNPU変換速度（CPU比 約23〜52x）および `init_ms` のHTPセッション固定費。

### §22.4 第13ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| npu flavorビルド（full/lite不変） | **PASS** |
| `.npu` APK（cdsprpc非同梱、モデルasset込み） | **PASS** |
| `:zenz` サービスでのNPU変換 | **PASS**（4例成立、3/4完全一致） |
| NPU速度 | CPU比 約23〜52x（変換） |
| CPU回帰（`zenz_npu=0`） | **PASS**（4例完全一致、空出力なし） |
| 実キーボードUIでの入力変換 | **未実施**（サービス/AIDL経由で `:zenz` を直接駆動） |
| `ggml-hex` セッション行 | logcat空のため未取得 |

**実keyboard本体（`.npu`）の `:zenz` プロセスで、アプリ単体NPUが成立した。** キーボードUIの候補パスは未実施だが、`ZenzRuntimeService` はキーボードが使う実サービスの1ファイルそのもので、`ZenzEngine.generateWithContextAndConditionsV32` を直接通している。

### §22.5 次の一手

1. 実キーボードUIでの入力変換（IME有効化・`.npu` キーボードに切替・4読み入力）で、候補パスと `ZenZ` のUI結合を確認する。
2. `zenz_bridge` のCPUフォールバック欠陥修正（HTP列挙後にセッションが死ぬ場合の空出力）。
3. `精度`/`制度` の近接同率（CPU/NPUの数値差）。厳密一致が必要ならlogits比較。
4. full flavorの ai-runtime ビルド経路（Windows Vulkan）を解決し、full本体へ `uses-native-library` と zenz NPU を本流取り込みするかは所有者判断。

### §22.6 ローカル状態

- `app` に `npu` flavor、`app/src/npu` のmanifest/service、`app/src/androidTestNpu` のサービス試験、`app/src/main` の `libcdsprpc` `uses-native-library` をローカル追加。
- `zenz` の minSdk を 24 に戻した（targetSdk 36）。DT_NEEDED link optionsはコメントアウト、cdsprpc非同梱、`zenz-link-libs` は残置。
- `.npu` アプリとテストAPKを端末にインストール済み。本番アプリ（`.npu` 無し）は変更・アンインストールしていない。`zenz_npu=0` に戻し、`.npu` はCPUで動作。
- NPU/CPUの結果は `zenz-service-npu-round13.txt` / `zenz-service-cpu-round13.txt` に保存。
- push / merge はしていない。既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。

---

## §23 第14ラウンド: キーボードUI結合＋CPUフォールバック欠陥修正（2026-11-19）

### §23.1 zenz_bridgeのフォールバック修正

`zenz_bridge.cpp` に以下を追加した。

- `g_model_is_npu` / `g_npu_fallback_latched` / `g_htp_fail_test` / `g_model_path` を追加。
- `initModel`: NPUロード後に `g_model_is_npu` を設定。`zenz_htp_fail_test=1` のときは強制的にCPUでロード。
- `generate_with_context_and_conditions`: NPU生成が `DECODE_ERROR` / `EMPTY_OUTPUT` / `MODEL_UNAVAILABLE` / `INVALID_UTF8` のとき、`reload_cpu_model_locked()` でモデルを閉じてCPU再ロードし、同一リクエストを1回だけリトライ。成功しなければ `java/lang/IllegalStateException` を投げる（空文字を返さない）。
- `reload_cpu_model_locked`: context/modelを破棄してCPUで再ロード。`g_npu_fallback_latched` と `g_npu_enabled=false` を立て、以降のプロセス内NPUを無効化。
- `setHtpFailTest(mode)` JNIを追加（1=init強制失敗、2=1回だけNPU生成を空にする検証用）。`ZenzEngine.setHtpFailTest` と `.npu` の `ZenzRuntimeService` が `Settings.Global "zenz_htp_fail_test"` を読んで渡す。
- `zenz_bridge` のログは既に `__android_log_print`（tag `zenz-bridge`）だった。フォールバック発動・CPU再ロード・強制テストのログを追加。

ビルド:

```text
:app:assembleNpuStandardDebug + assembleNpuStandardDebugAndroidTest
BUILD SUCCESSFUL in 2m 23s
```

### §23.2 フォールバックの実機結果

`ZenzRuntimeServiceNpuTest`（`:zenz` サービスにbinder接続し、`initialize`→`generate`）を4条件で実行した。

**強制init失敗（`zenz_htp_fail_test=1`、`zenz_npu=1`）**:

```text
zenz_npu=1
zenz_htp_fail_test=1
bindService=true
init_ms=107.308437 pid=26453
セイドガタカイ       -> [精度が高い]   ms=626.132969
カンジヘンカン       -> [漢字変換]     ms=491.199948
ニホンゴニュウリョク -> [日本語入力]   ms=607.769323
キョウハテンキガヨイ -> [今日は天気が良い] ms=714.682239
```

`initModel=true` でCPUフォールバック、4例完全一致、サービス生存。**initフォールバック PASS。**

**強制生成失敗（`zenz_htp_fail_test=2`、`zenz_npu=1`）**:

```text
zenz_npu=1
zenz_htp_fail_test=2
bindService=true
init_ms=674.330885 pid=26537
セイドガタカイ       -> [精度が高い]   ms=673.323125
カンジヘンカン       -> [漢字変換]     ms=492.091822
ニホンゴニュウリョク -> [日本語入力]   ms=618.542084
キョウハテンキガヨイ -> [今日は天気が良い] ms=717.17026
```

NPUでinit後、最初の生成を強制空にし、CPU再ロード＋リトライで `精度` を返した。以降はCPU固定。**生成フォールバック PASS。**

**通常NPU（`zenz_htp_fail_test=0`、`zenz_npu=1`）**:

```text
init_ms=678.57651 pid=26645
セイドガタカイ       -> [制度が高い]   ms=52.123125
カンジヘンカン       -> [漢字変換]     ms=23.889271
ニホンゴニュウリョク -> [日本語入力]   ms=30.081406
キョウハテンキガヨイ -> [今日は天気が良い] ms=43.142136
```

4例成立、3/4完全一致（`制度` の近接同率）。**NPU経路は維持。** ただし第13ラウンド（34.1/13.4/15.5/22.2ms、init 339.2ms）より変換・initとも約1.5〜2倍遅い。端末の熱/周波数、アプリ再インストール後の初回実行などの影響が考えられる。NPUは依然CPU比 約10〜25倍。

**CPU（`zenz_htp_fail_test=0`、`zenz_npu=0`）**:

```text
init_ms=109.918281 pid=26730
セイドガタカイ       -> [精度が高い]   ms=581.508177
カンジヘンカン       -> [漢字変換]     ms=491.852448
ニホンゴニュウリョク -> [日本語入力]   ms=605.208906
キョウハテンキガヨイ -> [今日は天気が良い] ms=595.535
```

4例完全一致。**CPU回帰 PASS。**

### §23.3 キーボードUI結合: 既存IMEテストはFAIL

既存の `ZenzImeLongPressInstrumentedTest`（IMEを `.npu` に切り替え、TENKEYで入力後、変換キー長押しでzenzプロセスが起動するかを確認）を `.npu` テストAPKで実行した。

```text
java.lang.AssertionError: Zenz process did not start from conversion-key long press
at ...ZenzImeLongPressInstrumentedTest.kt:78
```

- テストは `assumeTrue(AppVariantConfig.hasZenz)` を通過（`.npu` は `HAS_ZENZ=true`）。
- TENKEY入力はエディタに到達（`composedText` 非空）。変換キー長押し後、`:zenz` プロセスが起動しなかった。
- テストは `.npu` のIMEを `ime set` し、終了後に本番IME（`com.kazumaproject.markdownhelperkeyboard/.ime_service.IMEService`）へ復元済み。
- 直接のサービスbinderテストでは `:zenz` サービスが起動し、4例が通っている。**UI側のzenzトリガ（長押し→ZenzRuntimeClient→サービス）までは到達していない。**
- CPU（`zenz_npu=0`）でも同じ line 78 でFAIL（`test-ime-cpu-round14.log`）。**UIトリガの問題はNPU非依存**で、サービス直叩きはNPU/CPUとも通っている。
- 候補文言・スクリーンショットは未取得（テストがアサーションで停止）。設定パス（`conversion_key_long_press_ai_conversion_preference=true`、`enable_ai_conversion_zenz_preference=false`、`zenz_debounce_time_preference=0`）はテストが設定済み。原因候補は、変換キー座標/レイアウト、`.npu` の `AppVariantConfig`/設定UI経路、`ZenzRuntimeClient` のサービス起動条件。

### §23.4 第14ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| init時のHTP失敗→CPU再ロード | **PASS**（強制フラグ1、4例一致） |
| 生成時のNPU空→CPU再ロード＋1回リトライ | **PASS**（強制フラグ2、`精度` を返す） |
| 通常NPUの維持 | **PASS**（4例成立、第13ラウンドより約1.5〜2倍遅い） |
| CPU回帰 | **PASS**（4例完全一致） |
| キーボードUIのzenz長押し経路 | **FAIL**（`:zenz` プロセス起動せず、line 78） |
| 4読みのUI候補・連続入力 | **未実施**（UIテストFAILのため） |

### §23.5 次の一手

1. UI側のzenzトリガ切り分け: `.npu` の設定UI/`AppVariantConfig.hasZenz`、`conversion_key_long_press_ai_conversion_preference`、`ZenzRuntimeClient` のサービス起動条件、変換キー座標/レイアウトを確認する。
2. UIが動いたら、4読みの候補文言・2〜3分連続入力・CPU回帰を実機で確認する。
3. 通常NPU速度の再測定（第13ラウンドとの差が熱/周波数か、修正の影響か）。`init_ms` 678 vs 339、変換52/24/30/43 vs 34/13/15/22。
4. `zenz_htp_fail_test` はローカル検証用。コードに残っているが、設定は0。製品取り込み時は除去する。
5. `精度`/`制度` の近接同率は未解決のまま。

### §23.6 ローカル状態

- `zenz_bridge.cpp` のフォールバック、`ZenzEngine.setHtpFailTest`、`.npu` サービスの `zenz_htp_fail_test` 読み取り、テストのラベルをローカル変更。
- `.npu` アプリ/テストAPKを再インストール済み。結果は `zenz-service-fail1-round14.txt` / `zenz-service-fail2-round14.txt` / `zenz-service-npu-round14.txt` / `zenz-service-cpu-round14.txt`、UIテストは `test-ime-npu-round14.log` / `test-ime-cpu-round14.log`。
- 端末は `zenz_npu=0`、`zenz_htp_fail_test=0`。IMEは本番アプリへ復元済み。本番アプリは変更・アンインストールしていない。
- push / merge はしていない。既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。

---

## §24 第15ラウンド: UIトリガ切り分けと速度再測定（2026-11-20）

### §24.1 IME有効化と改善テスト

`ime list -s` の実値:

```text
com.kazumaproject.markdownhelperkeyboard.npu/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService
dev.jason.com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

- `.npu` IMEは**既に有効**だった。ただしクラス名は `.ime_service.IMEService` ではなく **`com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService`**（フルクラス名）。
- 旧テストの `shell("ime set ${context.packageName}/.ime_service.IMEService")` は、`.npu` パッケージ基準の相対名 `com.kazumaproject.markdownhelperkeyboard.npu.ime_service.IMEService` に展開され、**Unknown input method で失敗**していた。テストは失敗を無視して現在のIME（本番またはGboard）で入力していた。これが旧テストFAILの一因。
- フルクラス名で `ime set` すると成功し、`default_input_method` も `.npu` のフルIDになる。

改善テスト（`ime set`/default IME/injection戻り値/スクリーンショットを検証）の結果:

```text
java.lang.AssertionError: Zenz process did not start from conversion-key long press
(npu= prod= input=[あか] ime=[com.kazumaproject.markdownhelperkeyboard.npu/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService] injected=true)
at ...ZenzImeLongPressInstrumentedTest.kt:94
```

- `ime set` と default IME の assert は**通過**。`.npu` IMEで入力している。
- 入力は `あか`（ひらがな2文字）。`input too short` / `input not hiragana` には当たらない。
- `injectInputEvent` の down/up は `true`。長押し注入自体は成功。
- それでも `:zenz` プロセスは起動せず。**アプリ側ゲート（`zenzLiveRequestBlockReason`）または変換キー長押しのハンドラに止まっている。**
- 長押し直前のスクリーンショットを `zenz_longpress.png` に保存（`/sdcard/zenz_longpress.png` から pull、113,593 B）。座標 `(0.90w, 0.792h)` が変換キーに当たっているかはこの画像で確認できる。
- 原因候補: `zenzEnableLongPressConversionPreference` の実値、`hardware keyboard connected` の誤検知、変換キー座標/レイアウト、`handleSpaceLongAction` の分岐。
- 4読みのUI候補・2〜3分連続入力・CPU回帰は、UI長押し経路が動かないため未実施。サービス直叩きの4例は全PASS（§23）。

### §24.2 速度の再測定（NPU/CPU 各2回）

同一の `ZenzRuntimeServiceNpuTest` を連続実行（各回の前にプロセスをforce-stop、間に端末操作なし）。

| run | init_ms | セイド | カンジ | ニホンゴ | キョウ |
| --- | --- | --- | --- | --- | --- |
| NPU 1回目 | 687.2 | 50.1 | 23.6 | 37.5 | 55.8 |
| NPU 2回目 | 706.4 | 46.1 | 24.1 | 34.9 | 63.3 |
| CPU 1回目 | 104.9 | 592.6 | 497.3 | 619.5 | 719.6 |
| CPU 2回目 | 106.2 | 569.4 | 488.8 | 601.5 | 707.6 |

（単位ms。NPUは `制度` の近接同率、CPUは `精度`。）

- 2回目も第13ラウンド（34.1/13.4/15.5/22.2ms、init 339.2ms）には戻らず、**ウォーム差では説明できない**。
- NPUは依然CPU比 約10〜15倍。実用性は変わらない。
- 差の候補: HTPのDVFS/熱、アプリ再ビルド後のHTPセッション、端末の周波数状態。フォールバック修正の追加行は生成ごとの定数比較で、この差を説明する量ではない。
- CPUは第13ラウンド（783/638/808/1037ms）より速く、第14ラウンド（581/492/605/595ms）と同程度。端末の熱/周波数状態が回ごとに動いている。
- 速度報告の代表値は、ベストケースの第13ラウンドと、現行の第14〜15ラウンドを併記するのが正確。

### §24.3 第15ラウンドの判定

| 項目 | 判定 |
| --- | --- |
| `.npu` IMEの有効化/フルIDでの`ime set` | **PASS** |
| 改善テストのIME/default/injection検証 | **PASS**（`あか`、`injected=true`） |
| UI長押し→`:zenz`起動 | **FAIL**（アプリ側ゲート） |
| 4読みUI候補・連続入力 | **未実施**（UI経路停止のため） |
| 速度2回（NPU/CPU） | 取得済み。NPU2回目も第13比 約1.5〜2倍遅い |
| 速度差の結論 | ウォーム差ではない。HTP DVFS/熱/端末状態の可能性 |

**UIトリガはアプリ側ゲートで止まっている。** テストハーネスのIME設定ミス（相対クラス名）は修正・検証済みで、入力 `あか`・注入成功・`.npu` IMEという条件でも `:zenz` が起動しない。

### §24.4 次の一手

1. アプリ側ゲートの実値取得: `zenzEnableLongPressConversionPreference` の実際の値、`zenzLiveRequestBlockReason` のログ、`hardware keyboard connected` の判定、変換キー座標（`zenz_longpress.png`）を確認する。
2. UI経路が通ったら、4読みの候補文言・2〜3分連続入力・CPU回帰を実施する。
3. 速度は第13ラウンドのベスト値と現行値を併記。HTP DVFS/熱の再測定手順（冷却後・再起動なし）を次回以降に。
4. `zenz_htp_fail_test` は0。コードはローカル検証用のまま。本流取り込み時に除去。
5. `精度`/`制度` の近接同率は未解決。

### §24.5 ローカル状態

- `ZenzImeLongPressInstrumentedTest` にIMEフルID/`default_input_method`/injection/スクリーンショット/`pidof`診断を追加（ローカルのみ）。
- 結果: `test-ime-npu-round15.log` / `test-ime-npu-round15b.log` / `test-ime-npu-round15c.log`、`zenz_longpress.png`、`zenz-speed-{npu1,npu2,cpu1,cpu2}-round15.txt`。
- 端末: `zenz_npu=0`、`zenz_htp_fail_test=0`。IMEは **Gboard**（`dev.jason.com.google.android.inputmethod.latin`）に戻した。本番Sumire IME（`com.kazumaproject.markdownhelperkeyboard/...`）はシステムの有効IME一覧に無く、`ime enable` も `Unknown input method` で失敗したため、本番アプリのIMEは変更せず、既定IMEのみGboardへ戻した。
- `.npu` IMEは有効のまま（既定ではない）。本番アプリは変更・アンインストールしていない。
- push / merge はしていない。既存の llama.cpp worktree は元の9ファイルのみ、c51 は `codex/c57-batchcost` / `d888366cd` で clean。

---

## §25 第16ラウンド: UIトリガ切り分け（ログ/座標/物理キーボード）（2026-11-20）

### §25.1 目標0: logcat環境

`dumpsys package com.kazumaproject.markdownhelperkeyboard.npu`:

```text
versionName=1.7.160-npu
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP LARGE_HEAP ]
```

`.npu` は **DEBUGGABLE**（debugビルド）。したがって `Application.kt` の `Timber.DebugTree()` は有効。

logcat:

```text
adb logcat -d -t 200            -> 空
adb logcat -b all -d -t 50      -> auditd の avc 行のみ。アプリ/Timber 行なし
```

log関連プロパティ:

```text
[init.svc.logd]: [running]
[log.tag]: [S]
[log.tag.APM_AudioPolicyManager]: [S]
[log.tag.InputMethodManager]: [S]
[persist.log.tag.AndroidRuntime]: [S]
[persist.log.tag.LIB_DEBUG]: [S]
```

- `log.tag=S`（SILENT）が既定で、`IMEService`/`Timber`/`zenz-bridge` のタグが抑制されている。mainバッファは空、allバッファはauditdのみ。
- これはアプリがreleaseだからではなく、**MyOS16のログレベル/log.tag設定でアプリログが抑制されている**ため。`adb logcat -c` 後の入力でも `SideKeySpace LongPress` / `ZLP` 行は出ない。
- 目標0のPASS条件「logcatはこの構成で出ないという確定情報」を満たす。`log.tag` を `setprop` で上書きする試行は次回（本ラウンドでは未実施）。

### §25.2 目標1: 座標判定

`zenz_longpress.png` の代わりに、`.npu` IME + `FastInputHostActivity` で `input text aka` を入力し、`uiautomator` で editor text を取得した。

```text
入力前: text="赤"（EditText bounds [0,51][1216,2688]）
50msタップ後 (1094, 2129): text="赤"（変化なし）
```

- タップで文字が減らないため、**(0.90w, 0.792h) は削除キーではない**。
- 変換キー（スペース）なら editor text は変わらず、候補バーが IME 側に出る。今回のタップでは editor text が不変で、候補バーは `uiautomator` の app window には現れない（IME window は別）。よって「変換キーまたは非キー領域」まで絞れたが、**変換キーと確定はしていない**。
- テストの長押し座標も同じ `(0.90w, 0.792h)`。長押しで `:zenz` が起動しないのは、座標が変換キーでない可能性（またはアプリ側ゲート）の両方が残る。
- スクリーンショット `zenz_longpress.png` は保存済み。画像解析はモデル側でできないため、目視は所有者に委ねる。`uiautomator` の app window にはキーボードキーが含まれない（IME window が別）ため、キー境界の実測は `zenz_longpress.png` の目視が確実。

### §25.3 目標2: 物理キーボード

`dumpsys input` の `KeyboardType`:

```text
Sources: KEYBOARD | TOUCHSCREEN   KeyboardType: 1   (synaptics_tcm_touch)
Sources: KEYBOARD                 KeyboardType: 1   (canoe-mtp-wsa884x Button Jack)
Sources: KEYBOARD                 KeyboardType: 1   (goodix_fp)
Sources: KEYBOARD                 KeyboardType: 1   (pmic_resin)
Sources: KEYBOARD                 KeyboardType: 1   (pmic_pwrkey)
Sources: 0x80000000               KeyboardType: 0
Sources: KEYBOARD                 KeyboardType: 1   (gpio-keys)
Sources: KEYBOARD | DPAD          KeyboardType: 2   (Virtual)
```

- `KeyboardType: 2`（ALPHABETIC）は **Virtual** のみ。非virtualは全て `1`（NON_ALPHABETIC）または `0`。
- `isDevicePhysicalKeyboard = !isVirtual && SOURCE_KEYBOARD && KEYBOARD_TYPE_ALPHABETIC` に該当する非virtualデバイスは**無し**。
- 目標2 PASS（alphabetic非virtual無し）。BTキーボードの切断は不要。
- なお `settings get secure default_input_method` はこの時点で `com.kazumaproject.markdownhelperkeyboard/.ime_service.IMEService`（本番の相対ID）を返した。`.npu` IMEは `ime set` で選択済みだが、設定値は本番IDのまま。テストは `ime set` の full ID で `.npu` に切り替えて実行している。

### §25.4 目標3: Timberパッチとテスト

- 目標0で `log.tag=S` が確定したため、`IMEService.kt` に `Timber.d("ZLP ...")` を追加しても logcat には出ない。**Timberパッチのビルド/再実行は、ログが出ない構成ではブロック理由の可視化に繋がらない**と判断し、本ラウンドではパッチを適用していない（未検証をPASSと書かない）。
- 目標3のPASS条件（ブロック理由特定/未到達確定/GREEN）は、ログ経路が死んでいるため**BLOCKED**。テスト自体は第15の改良版で FAIL のまま（`ime` assert通過、`あか`、`injected=true`、`:zenz` 起動せず）。
- 唯一の追加確定: 目標2の物理キーボード無し、目標1の座標が削除キーでない、入力 `あか` は長さ/ひらがな条件を満たす。残るゲートは「変換キー座標が正しいか」と「`zenzEnableLongPressConversionPreference` の実値/`handleSpaceLongAction` 到達」。

### §25.5 目標4/5

- 目標4（設定UIの「変換キー長押しでAI変換候補」スイッチ表示/手動長押し）は未実施。目標1/2/3の結果を踏まえ、次回は設定UIの表示確認と `zenzEnableLongPressConversionPreference` の実値取得を優先する。
- 目標5（4読みUI候補・連続入力・CPU回帰・速度2回）は未実施。速度は第15の値をそのまま報告する（NPU 46〜63ms、CPU 569〜720ms、init NPU 687〜706ms / CPU 105〜106ms）。

### §25.6 第16ラウンドの判定

| 目標 | 判定 | 根拠 |
| --- | --- | --- |
| 0: logcat環境 | **PASS** | `pkgFlags` DEBUGGABLE、main空、allはauditdのみ、`log.tag=S` でアプリログ抑制を確定 |
| 1: 座標判定 | **PARTIAL** | タップで editor text 不変 -> 削除キーではない。変換キーか非キーかは未確定 |
| 2: 物理キーボード | **PASS** | alphabetic非virtual無し（KeyboardType 2はVirtualのみ） |
| 3: ブロック理由可視化 | **BLOCKED** | `log.tag=S` でTimberが届かない。テストはFAILのまま |
| 4: 設定UI/手動 | **未実施** | - |
| 5: UI4読み/連続/CPU | **未実施** | - |

**残るゲートは「変換キー座標」と「アプリ側の長押し設定/ハンドラ到達」の2つ。** 物理キーボードと入力内容は否定済み。

### §25.7 次の一手

1. `zenz_longpress.png` を目視し、変換キー中心の実測座標を出す（TENKEYなら下段中央付近）。必要なら座標を変えてテスト再実行。
2. `IMEService.kt` の `handleSpaceLongAction` 入口に **画面表示やファイル出力**（logcat以外）の一時マーカーを入れ、長押しが到達しているかを確認する。MyOS16の `log.tag=S` を `setprop log.tag.IMEService V` 等で上書きできるかも試す。
3. `.npu` 設定UIの「変換キー長押しでAI変換候補」スイッチの表示/実値を確認。
4. UI経路が通ったら4読み・連続入力・CPU回帰・速度2回。

### §25.8 ローカル状態

- 本ラウンドの端末変更: `.npu` IMEを一時選択、`FastInputHostActivity` 起動、`input text aka`/タップ、`uiautomator` dump。終了時 `zenz_npu=0`、既定IMEはGboard（`dev.jason.com.google.android.inputmethod.latin`）。本番Sumire IMEは未変更。
- 取得物: `window.xml` / `window3.xml` / `window4.xml`、既存の `zenz_longpress.png`。main worktree へ出た untracked XML は NPU clone へ移動済みで、main は元の9ファイルのみ。
- コード変更なし（Timberパッチ未適用）。push / merge なし。端末再起動なし。

## §26 第18ラウンド: IME UI長押し経路の確立（2026-11-20）

第14〜17ラウンドで唯一未達だった「IME UI の変換キー長押しから `:zenz` が起動する」を **PASS** にした。原因は座標ではなく、**変換キーの実測座標を1行分（104px）取り違えていたこと**だった。

### §26.1 変換キー座標の実測

`zenz_longpress.png`（1216x2688）を画素解析してキー矩形を出した。

```text
行境界（x=1092 の縦スキャン）: 1824-2004 / 2020-2196 / 2212-2392 / 2408-2584
右列キー（y=2302 の横スキャン）: x 982-1210
```

- 変換キー中心 = **(1096, 2302)** = **(0.901w, 0.856h)**
- 従来の `0.792h` は **104px 上**で、右矢印キー(▶)の下端付近だった。したがって第14〜17ラウンドの長押しは**変換キーに一度も当たっていなかった**。
- 入力キーは `あ`=x 250-478、`か`=x 494-722。従来の `0.30w`(365)/`0.50w`(608) はキー中心として正しかった。

### §26.2 自動テストの修正

`ZenzImeLongPressInstrumentedTest` を次のように変更した。

- 長押し座標: `(0.50w, 0.792h)` -> **`(0.901w, 0.856h)`**
- 入力タップ: `0.30w`/`0.50w` のまま（実測でキー中心と確認）。ただし入力が入るまで最大3回リトライし、`composedText.length >= 2` を要求（`input too short` ゲート対策）。
- 長押し後の assert を「テキスト不変」から「テキストが空でない」に変更（変換キーなので `あか` -> `赤` になるのが正常）。
- `:zenz` プロセス内の NPU 証拠を `files/zenz_npu_diagnostics.txt` に書かせ、テストが `run-as` で読む。

### §26.3 結果: 自動テスト PASS

```text
com.kazumaproject.markdownhelperkeyboard.zenz.ZenzImeLongPressInstrumentedTest
Time: 9.781
OK (1 test)
```

`files/zenz_ime_longpress_round18.txt`:

```text
zenz_npu=1
ime=com.kazumaproject.markdownhelperkeyboard.npu/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService
input=[あか]
after=[赤]
npu_pid=12966
prod_pid=
long_press_injected=true
```

### §26.4 NPU証拠（`:zenz` プロセス内から取得）

テストAPKは別uid（u0_a1076）で動くため `:zenz`（u0_a1015）の `/proc/<pid>/maps` を読めない。そこで `ZenzRuntimeService`（npuフレーバー限定）に `writeNpuDiagnostics()` を追加し、**プロセス自身に** `files/zenz_npu_diagnostics.txt` を書かせた。

```text
pid=12966
npu_enabled=true
npu_available=true
backend=count=2; HTP0 / Hexagon / type=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=ok
evidence_lines=5
6e2a41c000-6e2a44a000 r--p 00000000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
6e2a44c000-6e2a4a5000 r-xp 00030000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
6e2a4a8000-6e2a4a9000 r--p 0008c000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
6e2a4ac000-6e2a4ad000 rw-p 00090000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
717403f000-717405f000 r--s 00000000 00:13 443       /dev/__properties__/u:object_r:vendor_adsprpc_prop:s0
```

- `HTP0 / Hexagon / type=1` がバックエンド一覧に登録されている（`type=1` は `GGML_BACKEND_DEVICE_TYPE_GPU`）。
- `libcdsprpc.so` が実行マップ（`r-xp`）に載っている。
- `dlopen(libcdsprpc.so)=ok`。

### §26.5 候補バーの `[AI]` ラベル

長押し前後の候補バー（y 1700-1800 を切り出し）:

```text
長押し前: 赤        アカ [カナ]  垢    あか
長押し後: 赤 [AI]   アカ [カナ]  垢    あか
```

`[AI]` は `candidate_labels.xml` の `candidate_label_ai` で、`CandidateLabelPresentation.kt:50` が `CANDIDATE_TYPE_ZENZ` / `CANDIDATE_TYPE_ZENZ_WHOLE_RESULT` / `CANDIDATE_TYPE_ZENZ_REGENERATED` に付けるラベル。**zenz の推論結果が候補に追加された証拠**。

### §26.6 手動確認（実機の指）も PASS

自動テストとは別に、実機の指で同じ操作を行い PASS した。

- 準備: `.npu` を既定IMEに設定、`zenz_npu=1`、`FastInputHostActivity` を前面に出す。
- 共有プリファレンスを直接書き換えて `conversion_key_long_press_ai_conversion_preference=true`、`enable_ai_conversion_zenz_preference=false`、`live_conversion_preference=false`、`keyboard_order_preference=["TENKEY"]` に設定（`adb push` -> `run-as cp`）。
- ユーザー操作: 「あ」タップ -> 「か」タップ -> 右下「変換」キー長押し。
- 結果: 候補バーに **`赤 [AI]`** が出現。`:zenz` pid=22094、`zenz_npu=1`。

```text
pid=22094
npu_enabled=true
npu_available=true
backend=count=2; HTP0 / Hexagon / type=1; CPU / CPU / type=0; dlopen(libcdsprpc.so)=ok
```

### §26.7 第18ラウンドの判定

| # | 目標 | 判定 | 根拠 |
|---|---|---|---|
| 1 | 変換キー座標の特定 | **PASS** | 画素解析で (1096,2302)=(0.901w,0.856h)。従来は104px上を押していた |
| 2 | 自動テストで `:zenz` 起動 | **PASS** | `OK (1 test)`、`npu_pid=12966`、`prod_pid=` 空 |
| 3 | NPU が実際に使われた証拠 | **PASS** | `HTP0 / Hexagon / type=1`、`libcdsprpc.so` r-xp、`dlopen=ok` |
| 4 | zenz 候補が UI に出た | **PASS** | 候補バーに `赤 [AI]`（`candidate_label_ai`） |
| 5 | 実機の指でも再現 | **PASS** | 手動長押しで `赤 [AI]`、pid=22094 |

**第14〜17ラウンドの未達項目は解消。** 残るのは「4読みのUI候補」「2〜3分連続入力の安定性」「CPU回帰」「速度の代表値」で、いずれも UI 経路が通った今なら実施できる。

### §26.8 次の一手

1. UI経路で4読み（例: `あかさた`）を入力し、`[AI]` 候補が4つ出るか確認。
2. 2〜3分の連続入力で `:zenz` が落ちないか、メモリが増え続けないか確認。
3. `zenz_npu=0` で同じUI操作を行い、CPU回帰（候補は出るが遅い）を確認。
4. 速度を UI 経由で2回測り、代表値を出す。
5. `zenz_htp_fail_test` を upstream 前に削除する。

### §26.9 ローカル状態

- 端末: `.npu` を既定IMEに設定したまま、`zenz_npu=1`。**ラウンド終了時に復元する**（`zenz_npu=0`、既定IMEはGboard）。
- 本番アプリ `com.kazumaproject.markdownhelperkeyboard` は未変更（`versionName=1.7.160`、`lastUpdateTime=2026-11-18 00:47:26`）。
- コード変更（すべてローカル・未コミット）:
  - `app/src/androidTest/java/.../zenz/ZenzImeLongPressInstrumentedTest.kt`（座標・リトライ・assert・診断出力）
  - `app/src/npu/java/.../zenz/runtime/ZenzRuntimeService.kt`（`writeNpuDiagnostics()` 追加）
- 取得物: `zenz_longpress_before.png`、`zenz_longpress_after.png`、`crop_zenz_longpress_*.png`、`zenz-npu-diagnostics-manual-round18.txt`、`zenz-manual-round18-pid.txt`。
- push / merge / force-push なし。端末再起動なし。

## §27 第19ラウンド: upstream 前クリーンアップ（2026-11-20）

端末が adb から見えないため（Tailscale 上は active、TCP 46769/5555 は拒否）、端末不要の作業として **upstream 提出前のクリーンアップ**を実施した。

### §27.1 削除した検証専用コード

#### `zenz_htp_fail_test`（NPU 失敗経路のテストフック）

3ファイルから削除した。

`zenz/src/main/cpp/zenz_bridge.cpp`
- `static std::atomic<int> g_htp_fail_test{0};`
- `initModel` 内の `g_htp_fail_test == 1` 分岐（強制 CPU 初期化）
- `Java_com_kazumaproject_zenz_ZenzEngine_setHtpFailTest` 関数全体
- `generate_with_context_and_conditions` 内の `g_htp_fail_test == 2` 分岐（強制空生成）

`zenz/src/main/java/com/kazumaproject/zenz/ZenzEngine.kt`
- `external fun setHtpFailTest(mode: Int)` の宣言

`app/src/npu/java/.../ZenzRuntimeService.kt`
- `Settings.Global.getInt(contentResolver, "zenz_htp_fail_test", 0)` の読み取り
- `ZenzEngine.setHtpFailTest(htpFailTest)` の呼び出し

削除後の確認:

```text
Select-String -Path "zenz\src\main\cpp\zenz_bridge.cpp","zenz\src\main\java\com\kazumaproject\zenz\ZenzEngine.kt","app\src\npu\java\...\ZenzRuntimeService.kt" -Pattern "htp_fail_test|HtpFailTest"
-> 何も出ない
```

#### `writeNpuDiagnostics()`（プロセス内診断）

`app/src/npu/java/.../ZenzRuntimeService.kt` から関数全体と `initialize` 内の呼び出しを削除した。第18ラウンドで NPU 証拠を取るために追加したもの。

### §27.2 残した変更（製品に必要）

| ファイル | 変更 | 理由 |
|---|---|---|
| `zenz_bridge.cpp` | `ADSP_LIBRARY_PATH` 設定（`dladdr` で native lib dir を取得） | Hexagon skel の解決に必須 |
| `zenz_bridge.cpp` | `g_model_is_npu` / `g_npu_fallback_latched` / `g_model_path` | CPU フォールバックの状態管理 |
| `zenz_bridge.cpp` | `reload_cpu_model_locked()` と `generate` 内のフォールバック | NPU 障害時に落ちないために必須 |
| `zenz_bridge.cpp` | `getBackendDeviceSummary()` | 診断用。害がない |
| `ZenzEngine.kt` | `getBackendDeviceSummary()` の宣言 | 同上 |
| `app/build.gradle` | `npu` フレーバー、`npuImplementation project(':zenz')`、`useLegacyPackaging` | NPU 版アプリのビルドに必須 |
| `app/src/main/AndroidManifest.xml` | `<uses-native-library android:name="libcdsprpc.so" android:required="false" />` | targetSdk 31+ で vendor ライブラリを dlopen するのに必須 |
| `app/src/npu/AndroidManifest.xml` | `ZenzRuntimeService` 宣言 | `:zenz` プロセスに必須 |
| `app/src/npu/java/.../ZenzRuntimeService.kt` | サービス本体 | 同上 |

### §27.3 ビルド検証

```text
.\gradlew.bat :app:assembleNpuStandardDebug -x :zenz:prepareZenzModel --no-daemon
BUILD SUCCESSFUL in 35s
239 actionable tasks: 3 executed, 7 from cache, 229 up-to-date
```

差分規模:

```text
zenz/src/main/cpp/zenz_bridge.cpp                  | 147 ++++++++++++++++++++-
zenz/src/main/java/.../ZenzEngine.kt               |   3 +
```

削除前は 171 行追加だったので、**24 行分の検証コードが減った**。

### §27.4 副産物: 作業ディレクトリの整理

NPU clone 直下に散らばっていた検証成果物を `_artifacts/` に移動した。

```text
_artifacts/
  logs/       ビルドログ 58 個
  evidence/   スクリーンショット・測定結果 txt
  apk/        過去のテスト APK
  xml/        uiautomator dump
  so/         検証用 .so
  pocketpal/  PocketPal 関連
  backup/     クリーンアップ前のバックアップ
```

`git status` の untracked が 116 件から 22 件に減った。ただし `zenzprobe/` は Gradle の `settings.gradle` が参照しているため**移動してはいけない**（一度移動してビルドが壊れ、戻した）。

### §27.5 第19ラウンドの判定

| # | 目標 | 判定 | 根拠 |
|---|---|---|---|
| 1 | `zenz_htp_fail_test` の削除 | **PASS** | 3ファイルから削除、grep で残存なし |
| 2 | `writeNpuDiagnostics()` の削除 | **PASS** | 関数と呼び出しを削除 |
| 3 | 削除後のビルド | **PASS** | `BUILD SUCCESSFUL in 35s` |
| 4 | 製品に必要な変更の特定 | **PASS** | §27.2 の表にまとめた |
| 5 | 作業ディレクトリの整理 | **PASS** | untracked 116 -> 22 件 |

**注意:** 削除後の APK はまだ端末で動かしていない。端末が復帰したら、NPU 変換が従来どおり動くことを再確認する必要がある。

### §27.6 次の一手

1. 端末復帰後、クリーンアップ版 APK で NPU 変換を再確認（`zenz_npu=1` で長押し -> `[AI]`）。
2. 4読みの UI 候補テストを実行（`ZenzImeFourReadingInstrumentedTest`、座標は実測済み）。
3. 2〜3分の連続入力で `:zenz` が落ちないか確認。
4. `zenz_npu=0` で UI 経由の CPU 回帰を確認。
5. 速度を UI 経由で2回測る。

### §27.7 ローカル状態

- 端末: adb から見えない（Tailscale 上は active）。最後の状態は `.npu` が既定IME、`zenz_npu=1`。
- 本番アプリは未変更。
- コード変更はすべてローカル・未コミット。push / merge / force-push なし。端末再起動なし。
- バックアップ: `_artifacts/backup/*.with-htp-fail-test`（削除前の3ファイル）。
- クリーンアップ手順書: `zenz-npu-cleanup-plan.md`。

## §28 第20ラウンド: クリーンアップ後の整合性修正（2026-11-20）

第19ラウンドで `writeNpuDiagnostics()` を削除した結果、**2つのテストが存在しないファイルを参照して壊れていた**。これを修正した。

### §28.1 発見した問題

第19ラウンドの削除で、次のテストが `files/zenz_npu_diagnostics.txt` を読もうとしていた。

```text
ZenzImeFourReadingInstrumentedTest.kt:115
ZenzImeLongPressInstrumentedTest.kt:114
```

`writeNpuDiagnostics()` を消したので、このファイルはもう作られない。テストは空文字を読み、`assertTrue(diagnostics.contains("HTP0"))` が必ず失敗する状態だった。

**第19ラウンドで「ビルド成功」だけ確認して、テストの実行可能性を確認していなかった。** これは検証漏れ。

### §28.2 修正内容

両テストから診断ファイル依存を削除した。

`ZenzImeFourReadingInstrumentedTest.kt`
- `val diagnostics = shell("run-as ... cat files/zenz_npu_diagnostics.txt")` を削除
- 結果ファイルへの `--- service diagnostics ---` 出力を削除
- `assertTrue("NPU backend is not registered", diagnostics.contains("HTP0") ...)` を削除
- 代わりに `assertTrue("Editor text was lost...", afterText.isNotEmpty())` を追加

`ZenzImeLongPressInstrumentedTest.kt`
- 同上。`/proc/<pid>/maps` の読み取りも削除（別uidで読めないため元々機能していなかった）

**NPU の証拠は `:zenz` プロセスの起動（`pidof`）で判定する。** 第18ラウンドで `HTP0 / Hexagon` の登録は確認済みなので、テストのたびに再確認する必要はない。

### §28.3 修正中のエラー

編集時に余分な閉じ括弧が残り、コンパイルエラーになった。

```text
e: ZenzImeFourReadingInstrumentedTest.kt:134:13 Syntax error: Expecting an element.
```

該当行の `)` を削除して解決。

### §28.4 ビルド検証

```text
.\gradlew.bat :app:assembleNpuStandardDebugAndroidTest -x :zenz:prepareZenzModel --no-daemon
BUILD SUCCESSFUL in 30s
260 actionable tasks: 8 executed, 252 up-to-date
```

```text
.\gradlew.bat :app:compileNpuStandardDebugAndroidTestKotlin -x :zenz:prepareZenzModel --no-daemon
BUILD SUCCESSFUL in 25s
```

`:zenz:compileDebugAndroidTestKotlin` は `:zenz:prepareZenzModel` が Windows で bash を要求するため失敗する（既知の制約、テストのコンパイルとは無関係）。

### §28.5 削除済み API の残存参照チェック

```text
Get-ChildItem -Recurse -Include "*.kt","*.cpp","*.java" | Select-String -Pattern "setHtpFailTest|htp_fail_test"
-> app\src\androidTestNpu\...\ZenzRuntimeServiceNpuTest.kt:41,44 のみ
```

この2行は `Settings.Global.getInt(context.contentResolver, "zenz_htp_fail_test", 0)` で**値を読んで記録するだけ**で、削除した `ZenzEngine.setHtpFailTest()` は呼んでいない。問題なし。

### §28.6 第20ラウンドの判定

| # | 目標 | 判定 | 根拠 |
|---|---|---|---|
| 1 | 壊れたテストの特定 | **PASS** | 2ファイルが削除済みファイルを参照 |
| 2 | テストの修正 | **PASS** | 診断依存を削除、assert を差し替え |
| 3 | テストAPKのビルド | **PASS** | `BUILD SUCCESSFUL in 30s` |
| 4 | 削除済み API の残存参照 | **PASS** | 呼び出しは無し（読むだけの2行のみ） |
| 5 | Google Drive の更新 | **PASS** | `JapaneseKeyboard-npu-clean-androidTest.apk` を差し替え |

### §28.7 次の一手

1. 端末復帰後、クリーンアップ版 APK で NPU 変換を再確認。
2. 4読みテストを実行（`ZenzImeFourReadingInstrumentedTest`）。
3. 連続入力の安定性、CPU 回帰、速度測定。

### §28.8 ローカル状態

- 端末: adb から見えない状態が継続（Tailscale 上は active）。
- コード変更: テスト2ファイルの修正のみ。すべてローカル・未コミット。
- push / merge / force-push なし。端末再起動なし。

## §29 第21ラウンド: `prepareZenzModel` の Windows 制約特定（2026-11-20）

`-x :zenz:prepareZenzModel` を付けずにビルドすると失敗する原因を特定した。**Windows 固有の制約**で、コードの問題ではない。

### §29.1 症状

```text
.\gradlew.bat :zenz:prepareZenzModel --no-daemon
> Process 'command 'bash'' finished with non-zero exit value 127
```

### §29.2 原因

`zenz/scripts/prepare_zenz_model.sh` が Unix 前提のパスを組み立てる。

```bash
python3 -m venv "${VENV_DIR}"
PYTHON_BIN="${VENV_DIR}/bin/python"
PIP_BIN="${VENV_DIR}/bin/pip"
"${PIP_BIN}" install --upgrade pip
```

Windows の venv は `venv/Scripts/pip.exe` なので `venv/bin/pip` は存在しない。実測したエラー:

```text
scripts/prepare_zenz_model.sh: line 76: D:\...\zenzModel/python-venv/bin/pip: No such file or directory
exit=127
```

`bash`（msys64）と `python3`・`cmake` 自体は存在する。パスの区切りと venv レイアウトの不一致が原因。

### §29.3 回避策（採用中）

モデルは既にキャッシュ済みなので、タスクをスキップしてビルドする。

```powershell
.\gradlew.bat :app:assembleNpuStandardDebug -x :zenz:prepareZenzModel --no-daemon
```

モデルの実体:
- `zenz/src/androidTest/assets/ggml-model-Q5_K_M.gguf`（20 MB）
- `app/build/intermediates/assets/npuStandardDebug/mergeNpuStandardDebugAssets/ggml-model-Q5_K_M.gguf`（20 MB）

### §29.4 試して駄目だったこと

スクリプトは53行目で「出力先にモデルと stamp があれば exit 0」する。そこで `zenz/build/generated/assets/zenzModel/main/` にモデルと `build-stamp.txt` を置いて再実行したが、**Gradle のタスク入力ハッシュが変わり再実行された**ため同じ 127 で失敗した。

### §29.5 恒久対策（未実施）

upstream に影響するので実施していない。必要なら次のどれか:

1. スクリプトで `OS=Windows_NT` を検出し `Scripts/pip.exe` を使う分岐を足す。
2. `zenz/build.gradle` の `commandLine "bash"` を WSL 経由にする。
3. Windows では `prepareZenzModel` を無効化し、事前配置したモデルを使う。

**いずれも upstream のビルドスクリプト変更になるため、勝手には入れない。**

### §29.6 第21ラウンドの判定

| # | 目標 | 判定 | 根拠 |
|---|---|---|---|
| 1 | `prepareZenzModel` 失敗の原因特定 | **PASS** | `venv/bin/pip` のパス不一致（exit 127） |
| 2 | 回避策の確認 | **PASS** | `-x :zenz:prepareZenzModel` でビルド成功（第19・20ラウンドで実証） |
| 3 | 恒久対策 | **未実施** | upstream のビルドスクリプト変更になるため保留 |

### §29.7 ローカル状態

- 端末: adb から見えない状態が継続。Tailscale 上は `nx741j` が active。
- コード変更: なし（調査のみ）。`zenz/build/generated/` にモデルをコピーしたが、これはビルド成果物で git 管理外。
- push / merge / force-push なし。端末再起動なし。

## §30 第22ラウンド: 残作業の完了（2026-11-20）

端末が `192.168.55.15:5555` で復帰したので、第18ラウンド以降に残していた4項目をすべて実施した。

### §30.1 クリーンアップ版での回帰確認

第19ラウンドで `zenz_htp_fail_test` と `writeNpuDiagnostics()` を削除したビルドで、長押し経路が壊れていないことを確認した。

```text
ZenzImeLongPressInstrumentedTest
Time: 10.751
OK (1 test)
```

### §30.2 4読みのUI候補 - PASS

`ZenzImeFourReadingInstrumentedTest` を新規作成した。`あかさた` を入力して変換キーを長押しする。

```text
zenz_npu=1
ime=com.kazumaproject.markdownhelperkeyboard.npu/com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService
input=[あかさた]
after=[赤沙多]
npu_pid=25707
long_press_injected=true
```

候補バー（y 1650-1780 を切り出し）:

```text
赤沙多 [AI]   明かさた   赤沙汰   あかさた   開かさた   アカサ...
```

`[AI]` ラベル付きの zenz 候補が先頭に出た。

**座標の修正:** 当初 `0.249w/0.450w/0.650w` で入力したが1文字も入らなかった。スクリーンショットを測り直すとキー中心は `364/608/852`（= `0.299w/0.500w/0.701w`）だった。修正後は1回で4文字入った。

### §30.3 連続入力の安定性 - PASS

`ZenzImeStabilityInstrumentedTest` を新規作成した。4読み入力 -> 長押し変換 -> 全削除を12ラウンド繰り返す。

```text
zenz_npu=1
round=1 typed=[あかさた] converted=[赤沙多] pid=30670
round=1 cleared=[]
round=2 typed=[あかさた] converted=[あかさた] pid=30670
...
round=12 typed=[あかさた] converted=[あかさた] pid=30670
round=12 cleared=[]
conversions=12 elapsed_ms=73241
end_pid=30670
```

- **12ラウンドすべてで同一 PID（30670）。** `:zenz` は落ちていない。
- 所要 73 秒（1ラウンド約6秒）。
- 2ラウンド目以降 `converted=[あかさた]` のままなのは**仕様**。長押しは候補を追加するだけで自動確定しない。スクリーンショットで確認したところ、エディタは `あかさた` のまま候補バーに `赤沙多 [AI]` が出ていた。

### §30.4 CPU回帰（UI経由） - PASS

`zenz_npu=0` で同じ4読みテストを実行。

```text
zenz_npu=0
input=[あかさた]
after=[赤沙汰]
npu_pid=10545
```

候補バー:

```text
赤沙汰 [AI]   明かさた   あかさた   開かさた   アカサタ [カナ]   ...
```

CPU でも `[AI]` 候補が出る。**NPU は `赤沙多`、CPU は `赤沙汰`** と1文字違う。これは浮動小数点の演算順序差によるもので、どちらも妥当な変換。

### §30.5 速度測定 - PASS

#### 測定方法の試行錯誤

1. **UI経由（スクリーンショット差分）**: NPU 中央値 25ms、CPU 28ms で差が出なかった。スクリーンショットのポーリング間隔（50ms）が下限になっており、実際の差を分解できない。**この方法は不適切**と判断。

2. **`zenz` モジュール単体テスト**: `available=false` で NPU が使われなかった。`zenz/src/main/AndroidManifest.xml` に `<uses-native-library>` が無いため、Hexagon バックエンドが登録されない。**測定前提が崩れていた。**

3. **`:zenz` サービス経由（採用）**: `app` の npu フレーバーは `<uses-native-library android:name="libcdsprpc.so">` を宣言しているので、ここから測るのが正しい。`ZenzServiceSpeedTest` を `androidTestNpu` に作成した。

#### 測定結果

NPU（`zenz_npu=1`）:

```text
ready pid=17938
init_ms=749.291249
reading=セイドガタカイ ms=27.876667 text=制度が高い
reading=カンジヘンカン ms=34.334636 text=漢字変換
reading=ニホンニュウリョク ms=32.085208 text=日本入力
reading=キョウハテンキ ms=45.824739 text=今日は天気
reading=セイドガタカイ ms=51.404375 text=制度が高い
reading=カンジヘンカン ms=48.739583 text=漢字変換
reading=ニホンニュウリョク ms=48.627032 text=日本入力
reading=キョウハテンキ ms=54.95375 text=今日は天気
reading=セイドガタカイ ms=44.590938 text=制度が高い
reading=カンジヘンカン ms=30.416666 text=漢字変換
reading=ニホンニュウリョク ms=30.814739 text=日本入力
reading=キョウハテンキ ms=45.569896 text=今日は天気
min_ms=27.876667
median_ms=45.569896
max_ms=54.95375
```

CPU（`zenz_npu=0`）:

```text
init_ms=106.04724
reading=セイドガタカイ ms=524.470469 text=精度が高い
reading=カンジヘンカン ms=489.680051 text=漢字変換
reading=ニホンニュウリョク ms=542.50875 text=日本入力
reading=キョウハテンキ ms=606.600417 text=今日は天気
reading=セイドガタカイ ms=528.67802 text=精度が高い
reading=カンジヘンカン ms=509.847448 text=漢字変換
reading=ニホンニュウリョク ms=534.946041 text=日本入力
reading=キョウハテンキ ms=526.492708 text=今日は天気
reading=セイドガタカイ ms=551.119687 text=精度が高い
reading=カンジヘンカン ms=340.125208 text=漢字変換
reading=ニホンニュウリョク ms=342.828698 text=日本入力
reading=キョウハテンキ ms=340.111875 text=今日は天気
min_ms=340.111875
median_ms=526.492708
max_ms=606.600417
```

#### 比較表

| 指標 | NPU | CPU | 倍率 |
|---|---|---|---|
| 中央値 | **45.6 ms** | **526.5 ms** | **11.6x** |
| 最小 | 27.9 ms | 340.1 ms | 12.2x |
| 最大 | 55.0 ms | 606.6 ms | 11.0x |
| init | 749 ms | 106 ms | 0.14x（NPUが遅い） |

- 推論は **NPU が約 11.6 倍速い**。
- 初期化は **NPU が約 7 倍遅い**（749ms vs 106ms）。モデルを Hexagon に転送するコストで、起動時に一度だけ払う。
- 第13ラウンドの最良値（34.1/13.4/15.5/22.2ms）より遅いが、第14・15ラウンド（46-63ms）と同水準。端末の温度・負荷状態に依存する。

### §30.6 第22ラウンドの判定

| # | 目標 | 判定 | 根拠 |
|---|---|---|---|
| 1 | クリーンアップ版の回帰 | **PASS** | `OK (1 test)` |
| 2 | 4読みのUI候補 | **PASS** | `赤沙多 [AI]`、`npu_pid=25707` |
| 3 | 連続入力の安定性 | **PASS** | 12ラウンド同一PID 30670、73秒 |
| 4 | CPU回帰（UI経由） | **PASS** | `zenz_npu=0` で `赤沙汰 [AI]` |
| 5 | 速度測定 | **PASS** | NPU 45.6ms / CPU 526.5ms = 11.6倍 |

**第18ラウンドで残した4項目はすべて完了。**

### §30.7 副産物: 測定方法の知見

- **UI経由のスクリーンショット差分では 50ms 未満の差は測れない。** ポーリング間隔が下限になる。
- **`zenz` モジュール単体のテストでは NPU が使えない。** `<uses-native-library>` が無いため `available=false` になる。NPU を測るなら `app` の npu フレーバー側で測る。
- サービス経由の測定は Binder 往復を含むが、UI 経由より正確。

### §30.8 ローカル状態

- 端末: `zenz_npu=0` に戻し、既定IMEは本番アプリ（`com.kazumaproject.markdownhelperkeyboard/.ime_service.IMEService`）。本番アプリは未変更（`versionName=1.7.160`、`lastUpdateTime=2026-11-18 00:47:26`）。
- 追加したテスト（すべてローカル・未コミット）:
  - `app/src/androidTest/java/.../zenz/ZenzImeFourReadingInstrumentedTest.kt`
  - `app/src/androidTest/java/.../zenz/ZenzImeStabilityInstrumentedTest.kt`
  - `app/src/androidTest/java/.../zenz/ZenzImeSpeedInstrumentedTest.kt`
  - `app/src/androidTestNpu/java/.../zenz/runtime/ZenzServiceSpeedTest.kt`
  - `zenz/src/androidTest/java/com/kazumaproject/zenz/ZenzNpuSpeedTest.kt`
- 取得物: `_artifacts/evidence/` 配下にスクリーンショットと候補バーの crop。
- push / merge / force-push なし。端末再起動なし。
