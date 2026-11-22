#include <jni.h>
#include <algorithm>
#include <string>
#include <string_view>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <limits>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <dlfcn.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "zenz-bridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "zenz-bridge", __VA_ARGS__)

// The Hexagon FastRPC loader resolves file:///libggml-htp-*.so through
// ADSP_LIBRARY_PATH. In an Android app the skel lives in nativeLibraryDir,
// so point the loader at the directory that contains libzenz.so.
static void zenz_dladdr_anchor() {}

static std::string zenz_native_lib_dir() {
    Dl_info info;
    if (dladdr(reinterpret_cast<void *>(&zenz_dladdr_anchor), &info) && info.dli_fname) {
        std::string path = info.dli_fname;
        const auto pos = path.find_last_of('/');
        if (pos != std::string::npos) {
            return path.substr(0, pos);
        }
    }
    return "";
}

// モデルと語彙のみグローバルで保持する。
// コンテキストはセッションで使い回す。
static llama_model *g_model = nullptr;
static const llama_vocab *g_vocab = nullptr;

// ランタイム設定用パラメータ（Kotlin から変更可能）
static int g_param_n_ctx = 512;
static int g_param_n_threads = 4;
static int g_param_n_threads_batch = 4;
static int g_param_n_batch = 512;
static std::mutex g_param_mutex;   // 設定値の読み書き用
static std::atomic<uint64_t> g_request_seq{0};
static std::atomic<uint64_t> g_draft_review_generation{0};
static bool g_backend_initialized = false;

// NPU(Hexagon)の明示トグル。initModelのタイミングで読み込まれる。
// ビルド時にZENZ_ENABLE_NPUが定義されていない場合は常に無効。
static std::atomic<bool> g_npu_enabled{false};
static std::atomic<bool> g_model_is_npu{false};
static std::atomic<bool> g_npu_fallback_latched{false};
static std::string g_model_path;

struct RuntimeConfig {
    int n_ctx;
    int n_threads;
    int n_threads_batch;
    int n_batch;
};

struct DraftPrediction {
    llama_token token;
    float log_prob;
    float prefix_score;
};

struct DraftReviewCache {
    std::string id;
    uint64_t generation = 0;
    std::vector<llama_token> prompt;
    // One prediction per matched token, followed by the prediction at the mismatch.
    std::vector<DraftPrediction> predictions;
};

struct ZenzSession {
    llama_context *ctx = nullptr;
    RuntimeConfig config{0, 0, 0, 0};
    DraftReviewCache draft_review;
    std::mutex mutex;
};

static ZenzSession g_session;

// 候補評価の結果タイプ
enum class CandidateEvaluationResultType {
    ERROR,
    PASS,
    FIX_REQUIRED,
    WHOLE_RESULT
};

// 候補評価の結果
struct CandidateEvaluationResult {
    CandidateEvaluationResultType type;
    float score;                // PASS の場合のスコア
    std::string prefix;         // FIX_REQUIRED の場合の接頭辞
    std::string whole_result;   // WHOLE_RESULT の場合の結果
};

// Zenz v3.x reserves the private-use range U+EE00..U+EE0F for prompt,
// alignment, and future protocol markers.  These code points are not
// necessarily classified as llama control tokens, so they must be handled by
// the Zenz decoder itself.
constexpr uint32_t kZenzProtocolMarkerFirst = 0xEE00;
constexpr uint32_t kZenzProtocolMarkerLast = 0xEE0F;

enum class ZenzGenerationTermination {
    EOG,
    PROTOCOL_MARKER,
    EMPTY_OUTPUT,
    TOKEN_LIMIT,
    CONTEXT_LIMIT,
    INVALID_UTF8,
    DECODE_ERROR,
    CANCELLED,
    MODEL_UNAVAILABLE,
    INVALID_REQUEST,
};

struct ZenzGenerationResult {
    std::string text;
    ZenzGenerationTermination termination = ZenzGenerationTermination::INVALID_REQUEST;

    bool accepted() const {
        return !text.empty() &&
               (termination == ZenzGenerationTermination::EOG ||
                termination == ZenzGenerationTermination::PROTOCOL_MARKER);
    }
};

enum class Utf8DecodeResult {
    OK,
    INCOMPLETE,
    INVALID,
};

static Utf8DecodeResult decode_utf8_codepoint(
        std::string_view text,
        size_t offset,
        uint32_t *codepoint,
        size_t *width
) {
    if (!codepoint || !width || offset >= text.size()) {
        return Utf8DecodeResult::INVALID;
    }

    const auto byte = [&](size_t index) -> uint8_t {
        return static_cast<uint8_t>(text[index]);
    };

    const uint8_t first = byte(offset);
    size_t sequence_width = 0;
    if (first <= 0x7F) {
        *codepoint = first;
        *width = 1;
        return Utf8DecodeResult::OK;
    } else if (first >= 0xC2 && first <= 0xDF) {
        sequence_width = 2;
    } else if (first >= 0xE0 && first <= 0xEF) {
        sequence_width = 3;
    } else if (first >= 0xF0 && first <= 0xF4) {
        sequence_width = 4;
    } else {
        return Utf8DecodeResult::INVALID;
    }

    if (offset + sequence_width > text.size()) {
        return Utf8DecodeResult::INCOMPLETE;
    }

    for (size_t i = 1; i < sequence_width; ++i) {
        if ((byte(offset + i) & 0xC0) != 0x80) {
            return Utf8DecodeResult::INVALID;
        }
    }

    uint32_t value = first & ((1u << (8 - sequence_width - 1)) - 1u);
    for (size_t i = 1; i < sequence_width; ++i) {
        value = (value << 6) | (byte(offset + i) & 0x3F);
    }

    // Reject overlong encodings, UTF-16 surrogates, and values outside
    // Unicode.  The first-byte constraints above already reject most of
    // these, but keeping the checks here makes the helper self-contained.
    if ((sequence_width == 2 && value < 0x80) ||
        (sequence_width == 3 && value < 0x800) ||
        (sequence_width == 4 && value < 0x10000) ||
        (value >= 0xD800 && value <= 0xDFFF) ||
        value > 0x10FFFF) {
        return Utf8DecodeResult::INVALID;
    }

    *codepoint = value;
    *width = sequence_width;
    return Utf8DecodeResult::OK;
}

static bool is_zenz_protocol_marker(uint32_t codepoint) {
    return codepoint >= kZenzProtocolMarkerFirst &&
           codepoint <= kZenzProtocolMarkerLast;
}

// Editors sometimes insert invisible characters (a zero-width space after a
// line break, for example). They carry no meaning, but they change the prompt
// and therefore the answer, so the same reading converts differently per app.
static bool is_invisible_prompt_char(uint32_t codepoint) {
    switch (codepoint) {
        case 0x200B:  // zero width space
        case 0x200C:  // zero width non-joiner
        case 0x200D:  // zero width joiner
        case 0x200E:  // left-to-right mark
        case 0x200F:  // right-to-left mark
        case 0x2060:  // word joiner
        case 0xFEFF:  // zero width no-break space
            return true;
        default:
            return false;
    }
}

// ------- JNI文字列変換（重要） -------
// llama_token_to_piece() が返すバイト列は不正UTF-8になり得るため、NewStringUTFは禁止。
// UTF-8(不正あり得る) -> UTF-16(不正は U+FFFD 置換) -> NewString で返す。

static inline void append_u16(std::u16string &out, uint32_t cp) {
    if (cp <= 0xFFFF) {
        out.push_back(static_cast<char16_t>(cp));
    } else {
        cp -= 0x10000;
        out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
        out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    }
}

static std::u16string utf8_to_utf16_lossy(const uint8_t *s, size_t n) {
    std::u16string out;
    out.reserve(n);

    size_t i = 0;
    while (i < n) {
        uint8_t b0 = s[i];

        // ASCII
        if (b0 <= 0x7F) {
            out.push_back(static_cast<char16_t>(b0));
            i += 1;
            continue;
        }

        int len = 0;
        uint32_t cp = 0;
        if ((b0 & 0xE0) == 0xC0) { len = 2; cp = b0 & 0x1F; }
        else if ((b0 & 0xF0) == 0xE0) { len = 3; cp = b0 & 0x0F; }
        else if ((b0 & 0xF8) == 0xF0) { len = 4; cp = b0 & 0x07; }
        else {
            out.push_back(u'\uFFFD');
            i += 1;
            continue;
        }

        if (i + static_cast<size_t>(len) > n) {
            out.push_back(u'\uFFFD');
            break;
        }

        bool ok = true;
        for (int k = 1; k < len; ++k) {
            uint8_t bx = s[i + k];
            if ((bx & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (bx & 0x3F);
        }

        if (ok) {
            // overlong
            if (len == 2 && cp < 0x80) ok = false;
            if (len == 3 && cp < 0x800) ok = false;
            if (len == 4 && cp < 0x10000) ok = false;

            // surrogate / range
            if (cp >= 0xD800 && cp <= 0xDFFF) ok = false;
            if (cp > 0x10FFFF) ok = false;
        }

        if (!ok) {
            out.push_back(u'\uFFFD');
            i += 1; // resync
            continue;
        }

        append_u16(out, cp);
        i += static_cast<size_t>(len);
    }

    return out;
}

static jstring toJString(JNIEnv *env, const std::string &bytes) {
    const auto *p = reinterpret_cast<const uint8_t *>(bytes.data());
    std::u16string u16 = utf8_to_utf16_lossy(p, bytes.size());
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                          static_cast<jsize>(u16.size()));
}

static jstring toJString(JNIEnv *env, const char *cstr) {
    if (!cstr) return env->NewString(reinterpret_cast<const jchar *>(u""), 0);
    return toJString(env, std::string(cstr));
}

// ------- 共通ヘルパー -------

// Swift の preprocessText とほぼ同じ:
// - 半角スペース -> 全角スペース (\u3000)
// - 改行は削除
static std::string preprocess_text(const std::string &text) {
    std::string out;
    out.reserve(text.size());

    for (unsigned char c: text) {
        if (c == ' ') {
            out.append(u8"\u3000");
        } else if (c == '\n' || c == '\r') {
            continue;
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

__attribute__((used)) static const char inputTag[] = u8"\uEE00";
__attribute__((used)) static const char outputTag[] = u8"\uEE01";
__attribute__((used)) static const char leftContextTag[] = u8"\uEE02";
__attribute__((used)) static const char profileTag[] = u8"\uEE03";
__attribute__((used)) static const char topicTag[] = u8"\uEE04";
__attribute__((used)) static const char styleTag[] = u8"\uEE05";
__attribute__((used)) static const char preferenceTag[] = u8"\uEE06";
__attribute__((used)) static const char rightContextTag[] = u8"\uEE07";

static std::string sanitize_prompt_field(std::string_view text);

static std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (!value) {
        return "";
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

static std::string build_conditions(
        const std::string &profile,
        const std::string &topic,
        const std::string &style,
        const std::string &preference
) {
    std::string conditions;
    if (!profile.empty()) {
        conditions += profileTag;
        conditions += profile;
    }
    if (!topic.empty()) {
        conditions += topicTag;
        conditions += topic;
    }
    if (!style.empty()) {
        conditions += styleTag;
        conditions += style;
    }
    if (!preference.empty()) {
        conditions += preferenceTag;
        conditions += preference;
    }
    return conditions;
}

static std::string build_zenz_prompt(
        const std::string &profile,
        const std::string &topic,
        const std::string &style,
        const std::string &preference,
        const std::string &leftContext,
        const std::string &rightContext,
        const std::string &input
) {
    const std::string safeProfile = sanitize_prompt_field(profile);
    const std::string safeTopic = sanitize_prompt_field(topic);
    const std::string safeStyle = sanitize_prompt_field(style);
    const std::string safePreference = sanitize_prompt_field(preference);
    const std::string safeLeftContext = sanitize_prompt_field(leftContext);
    const std::string safeRightContext = sanitize_prompt_field(rightContext);
    const std::string safeInput = sanitize_prompt_field(input);

    std::string prompt = build_conditions(
            safeProfile,
            safeTopic,
            safeStyle,
            safePreference);
    if (!safeLeftContext.empty()) {
        prompt += leftContextTag;
        prompt += safeLeftContext;
    }
    if (!safeRightContext.empty()) {
        prompt += rightContextTag;
        prompt += safeRightContext;
    }
    prompt += inputTag;
    prompt += safeInput;
    prompt += outputTag;
    LOGI("prompt: profile=[%s] left=[%s] right=[%s] input=[%s]",
         safeProfile.c_str(), safeLeftContext.c_str(), safeRightContext.c_str(), safeInput.c_str());
    return prompt;
}

// text を tokenize して llama_token の配列にする
static std::vector<llama_token> tokenize_text(const std::string &text, bool add_bos, bool add_eos) {
    std::vector<llama_token> tokens;

    if (!g_vocab) {
        return tokens;
    }

    const int32_t text_len = (int32_t) text.size();

    // 最初は適当に大きめ
    int32_t n_max = text_len + (add_bos ? 2 : 1);
    tokens.resize(n_max);

    int32_t n_tokens = llama_tokenize(
            g_vocab,
            text.c_str(),
            text_len,
            tokens.data(),
            n_max,
            add_bos,
            /*parse_special=*/false);

    if (n_tokens < 0) {
        n_max = -n_tokens;
        tokens.resize(n_max);
        n_tokens = llama_tokenize(
                g_vocab,
                text.c_str(),
                text_len,
                tokens.data(),
                n_max,
                add_bos,
                /*parse_special=*/false);
    }

    if (n_tokens <= 0) {
        tokens.clear();
        return tokens;
    }

    tokens.resize(n_tokens);

    if (add_eos) {
        tokens.push_back(llama_vocab_eos(g_vocab));
    }

    return tokens;
}

// 1トークン -> UTF-8 文字列（不正UTF-8が混ざり得る）
static std::string token_to_piece_str(llama_token token) {
    std::string out;
    if (!g_vocab) return out;

    int32_t buf_size = 8;
    std::vector<char> buf(buf_size);

    int32_t n = llama_token_to_piece(
            g_vocab,
            token,
            buf.data(),
            buf_size,
            /*lstrip=*/0,
            /*special=*/false);

    if (n < 0) {
        buf_size = -n;
        buf.resize(buf_size);
        n = llama_token_to_piece(
                g_vocab,
                token,
                buf.data(),
                buf_size,
                0,
                false);
    }

    if (n > 0) {
        out.assign(buf.data(), buf.data() + n);
    }
    return out;
}

class ZenzOutputDecoder {
public:
    enum class AppendResult {
        CONTINUE,
        PROTOCOL_MARKER,
        INVALID_UTF8,
    };

    AppendResult append_piece(std::string_view piece) {
        pending_utf8_.append(piece.data(), piece.size());

        size_t offset = 0;
        while (offset < pending_utf8_.size()) {
            uint32_t codepoint = 0;
            size_t width = 0;
            const Utf8DecodeResult status = decode_utf8_codepoint(
                    pending_utf8_, offset, &codepoint, &width);
            if (status == Utf8DecodeResult::INCOMPLETE) {
                break;
            }
            if (status == Utf8DecodeResult::INVALID) {
                return AppendResult::INVALID_UTF8;
            }

            if (is_zenz_protocol_marker(codepoint)) {
                pending_utf8_.clear();
                return AppendResult::PROTOCOL_MARKER;
            }

            // A conversion candidate is user-visible text.  Prompt/control
            // bytes, replacement characters, and C0/DEL controls must never
            // cross the native boundary as candidate text.
            if (codepoint == 0xFFFD || codepoint < 0x20 || codepoint == 0x7F) {
                return AppendResult::INVALID_UTF8;
            }

            output_.append(pending_utf8_, offset, width);
            offset += width;
        }

        if (offset > 0) {
            pending_utf8_.erase(0, offset);
        }
        return AppendResult::CONTINUE;
    }

    bool finish() const {
        return pending_utf8_.empty();
    }

    bool has_text() const {
        return !output_.empty();
    }

    const std::string &text() const {
        return output_;
    }

private:
    std::string output_;
    std::string pending_utf8_;
};

static bool is_safe_user_text(std::string_view text) {
    ZenzOutputDecoder decoder;
    return decoder.append_piece(text) == ZenzOutputDecoder::AppendResult::CONTINUE &&
           decoder.finish();
}

// Structured prompt fields are user-controlled, while the private-use range
// is the model protocol itself.  Strip protocol/control code points before
// attaching field tags so context/profile text cannot alter the prompt
// grammar. Invalid UTF-8 is dropped from the field; the generated output is
// handled fail-closed instead.
static std::string sanitize_prompt_field(std::string_view text) {
    std::string sanitized;
    sanitized.reserve(text.size());

    size_t offset = 0;
    while (offset < text.size()) {
        uint32_t codepoint = 0;
        size_t width = 0;
        const Utf8DecodeResult status = decode_utf8_codepoint(
                text, offset, &codepoint, &width);
        if (status != Utf8DecodeResult::OK) {
            break;
        }

        if (!is_zenz_protocol_marker(codepoint) &&
            !is_invisible_prompt_char(codepoint) &&
            codepoint >= 0x20 && codepoint != 0x7F) {
            sanitized.append(text, offset, width);
        }
        offset += width;
    }
    return sanitized;
}

static std::string decode_token_range(
        const std::vector<llama_token> &tokens,
        size_t begin,
        size_t end,
        bool *valid
) {
    ZenzOutputDecoder decoder;
    if (begin > end || end > tokens.size()) {
        if (valid) *valid = false;
        return "";
    }

    for (size_t index = begin; index < end; ++index) {
        const llama_token token = tokens[index];
        if (g_vocab && llama_vocab_is_eog(g_vocab, token)) {
            if (valid) *valid = false;
            return "";
        }

        const std::string piece = token_to_piece_str(token);
        const ZenzOutputDecoder::AppendResult append_result =
                decoder.append_piece(piece);
        if (append_result != ZenzOutputDecoder::AppendResult::CONTINUE) {
            if (valid) *valid = false;
            return "";
        }
    }

    const bool complete = decoder.finish();
    if (valid) *valid = complete;
    return complete ? decoder.text() : "";
}

static RuntimeConfig get_runtime_config() {
    std::lock_guard<std::mutex> lock(g_param_mutex);
    return RuntimeConfig{
            g_param_n_ctx,
            g_param_n_threads,
            g_param_n_threads_batch,
            g_param_n_batch
    };
}

static bool same_runtime_config(const RuntimeConfig &lhs, const RuntimeConfig &rhs) {
    return lhs.n_ctx == rhs.n_ctx &&
           lhs.n_threads == rhs.n_threads &&
           lhs.n_threads_batch == rhs.n_threads_batch &&
           lhs.n_batch == rhs.n_batch;
}

static bool is_request_stale(uint64_t request_seq) {
    return request_seq != g_request_seq.load(std::memory_order_relaxed);
}

struct AbortRequestState {
    uint64_t request_seq;
};

static bool abort_if_stale(void *data) {
    auto *state = static_cast<AbortRequestState *>(data);
    return state && is_request_stale(state->request_seq);
}

static bool never_abort(void * /*data*/) {
    return false;
}

static void clear_session_kv_locked(llama_context *ctx) {
    g_session.draft_review = {};
    // 旧KVキャッシュAPIは新しいllama.cppで削除されたため、llama_memory_* に移行した
    llama_memory_clear(llama_get_memory(ctx), true);
}

static void destroy_session_context_locked() {
    g_session.draft_review = {};
    if (!g_session.ctx) {
        return;
    }
    llama_set_abort_callback(g_session.ctx, never_abort, nullptr);
    llama_synchronize(g_session.ctx);
    llama_free(g_session.ctx);
    g_session.ctx = nullptr;
    g_session.config = RuntimeConfig{0, 0, 0, 0};
}

// ------- Round 25: per-node tensor dump -------
//
// The NPU logits come out about 6% smaller than the CPU ones. This hook writes
// selected graph nodes to a file so the two runs can be compared layer by layer.
// Enabled only when ZENZ_TENSOR_DUMP names a file.
static std::mutex g_dump_mutex;
static int g_dump_step = -1;

static const char * zenz_tensor_dump_path() {
    static const char * p = []() -> const char * {
        const char * e = getenv("ZENZ_TENSOR_DUMP");
        return e && *e ? e : nullptr;
    }();
    return p;
}

// Only the nodes that matter for the scale question: the final norm and the
// logits, plus the first block output as a sanity anchor.
static bool zenz_dump_wanted(const ggml_tensor * t) {
    if (!t || !t->name[0]) {
        return false;
    }
    const std::string name(t->name);
    return name == "result_norm" || name == "result_output" ||
           name == "l_out-0" || name == "inp_embd";
}

static bool zenz_dump_callback(ggml_tensor * t, bool ask, void * /*user_data*/) {
    if (ask) {
        return zenz_dump_wanted(t);
    }
    const char * path = zenz_tensor_dump_path();
    if (!path || !zenz_dump_wanted(t)) {
        return true;
    }
    if (t->type != GGML_TYPE_F32) {
        return true;
    }
    const size_t n = (size_t) ggml_nelements(t);
    std::vector<float> buf(n);
    ggml_backend_tensor_get(t, buf.data(), 0, n * sizeof(float));

    std::lock_guard<std::mutex> lock(g_dump_mutex);
    FILE * file = fopen(path, "a");
    if (!file) {
        return true;
    }
    fprintf(file, "TENSOR name=%s step=%d ne=%zu", t->name, g_dump_step, n);
    for (size_t i = 0; i < n; i++) {
        fprintf(file, " %.6f", buf[i]);
    }
    fprintf(file, "\n");
    fclose(file);
    return true;
}

static llama_context *ensure_session_context_locked() {
    if (!g_model) {
        return nullptr;
    }

    const RuntimeConfig config = get_runtime_config();
    if (g_session.ctx && same_runtime_config(g_session.config, config)) {
        return g_session.ctx;
    }

    destroy_session_context_locked();

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = config.n_ctx;
    cparams.n_threads = config.n_threads;
    cparams.n_threads_batch = config.n_threads_batch;
    cparams.n_batch = config.n_batch;
    if (zenz_tensor_dump_path()) {
        cparams.cb_eval = zenz_dump_callback;
        cparams.cb_eval_user_data = nullptr;
    }

    g_session.ctx = llama_init_from_model(g_model, cparams);
    if (!g_session.ctx) {
        LOGE("Failed to create llama_context");
        return nullptr;
    }

    g_session.config = config;
    LOGI("llama_context created: n_ctx=%d, n_threads=%d, n_batch=%d",
         cparams.n_ctx, cparams.n_threads, cparams.n_batch);
    return g_session.ctx;
}

// Called with g_session.mutex held. Drops the NPU model/session and reloads the
// same model on CPU, then latches CPU for the rest of the process.
static bool reload_cpu_model_locked() {
    destroy_session_context_locked();
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }
    if (g_model_path.empty()) {
        LOGE("cpu fallback: model path is empty");
        return false;
    }
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(g_model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("cpu fallback: failed to load model");
        return false;
    }
    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGE("cpu fallback: failed to get vocab");
        return false;
    }
    g_model_is_npu.store(false);
    g_npu_fallback_latched.store(true);
    g_npu_enabled.store(false);
    LOGI("cpu fallback: model reloaded on cpu");
    return true;
}

static ZenzGenerationResult no_zenz_result(ZenzGenerationTermination termination) {
    return ZenzGenerationResult{"", termination};
}

// ------- CPU arbitration (round 20) -------
//
// The NPU and the CPU disagree by mean 0.284 / max 0.885 logit (round 19). When
// the NPU's own top-1 and top-2 are closer than kZenzMarginTau, that gap is small
// enough for the disagreement to flip the winner, so the whole conversion is
// recomputed on the CPU and the CPU answer is returned.
//
// This guarantees "same answer as the CPU build", not "the true answer". Two CPU
// implementations already differ by about 0.2 logit on the same weights, so no
// kernel fix can promise more than this.
// Runtime value so the settings screen can trade speed against safety.
// 0 disables the arbitration entirely.
static std::atomic<float> g_margin_tau{1.5f};

// Separate CPU model and context so the NPU session stays loaded. Guarded by
// g_session.mutex like the main session.
struct CpuArbitrationSession {
    llama_model *model = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_context *ctx = nullptr;
    RuntimeConfig config{0, 0, 0, 0};
};

static CpuArbitrationSession g_cpu_arb;

static void destroy_cpu_arb_context_locked() {
    if (!g_cpu_arb.ctx) {
        return;
    }
    llama_set_abort_callback(g_cpu_arb.ctx, never_abort, nullptr);
    llama_synchronize(g_cpu_arb.ctx);
    llama_free(g_cpu_arb.ctx);
    g_cpu_arb.ctx = nullptr;
    g_cpu_arb.config = RuntimeConfig{0, 0, 0, 0};
}

static void close_cpu_arb_locked() {
    destroy_cpu_arb_context_locked();
    if (g_cpu_arb.model) {
        llama_model_free(g_cpu_arb.model);
        g_cpu_arb.model = nullptr;
        g_cpu_arb.vocab = nullptr;
    }
}

// Loads the CPU model once and keeps it for the process lifetime.
static bool ensure_cpu_arb_model_locked() {
    if (g_cpu_arb.model) {
        return true;
    }
    if (g_model_path.empty()) {
        LOGE("cpu arbitration: model path is empty");
        return false;
    }
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_cpu_arb.model = llama_model_load_from_file(g_model_path.c_str(), mparams);
    if (!g_cpu_arb.model) {
        LOGE("cpu arbitration: failed to load model");
        return false;
    }
    g_cpu_arb.vocab = llama_model_get_vocab(g_cpu_arb.model);
    if (!g_cpu_arb.vocab) {
        llama_model_free(g_cpu_arb.model);
        g_cpu_arb.model = nullptr;
        LOGE("cpu arbitration: failed to get vocab");
        return false;
    }
    LOGI("cpu arbitration: model loaded");
    return true;
}

static llama_context *ensure_cpu_arb_context_locked() {
    if (!g_cpu_arb.model) {
        return nullptr;
    }
    const RuntimeConfig config = get_runtime_config();
    if (g_cpu_arb.ctx && same_runtime_config(g_cpu_arb.config, config)) {
        return g_cpu_arb.ctx;
    }
    destroy_cpu_arb_context_locked();

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = config.n_ctx;
    cparams.n_threads = config.n_threads;
    cparams.n_threads_batch = config.n_threads_batch;
    cparams.n_batch = config.n_batch;

    g_cpu_arb.ctx = llama_init_from_model(g_cpu_arb.model, cparams);
    if (!g_cpu_arb.ctx) {
        LOGE("cpu arbitration: failed to create context");
        return nullptr;
    }
    g_cpu_arb.config = config;
    LOGI("cpu arbitration: context created n_ctx=%d", cparams.n_ctx);
    return g_cpu_arb.ctx;
}

#ifdef ZENZ_LOGIT_DUMP
// Round 19 measurement hook. Prints the top logits at the first decode step so
// the NPU and CPU runs can be compared on the same prompt. Debug builds only.
//
// logcat is suppressed on this device (log.tag=S), so the line also goes to a
// file next to the model. The path comes from ZENZ_LOGIT_DUMP_PATH.
// Round 21: one line per generation step with the top three tokens, so the
// NPU and CPU runs can be compared step by step. The round 19 dump only
// covered step 0, which does not represent the deeper steps.
static void dump_step_logits(const float *logits, int32_t n_vocab, int npu, int step) {
    int ids[3] = {-1, -1, -1};
    float vals[3] = {-std::numeric_limits<float>::infinity(),
                    -std::numeric_limits<float>::infinity(),
                    -std::numeric_limits<float>::infinity()};
    for (int32_t tid = 0; tid < n_vocab; ++tid) {
        const float value = logits[tid];
        if (value > vals[0]) {
            vals[2] = vals[1]; ids[2] = ids[1];
            vals[1] = vals[0]; ids[1] = ids[0];
            vals[0] = value;    ids[0] = tid;
        } else if (value > vals[1]) {
            vals[2] = vals[1]; ids[2] = ids[1];
            vals[1] = value;    ids[1] = tid;
        } else if (value > vals[2]) {
            vals[2] = value;    ids[2] = tid;
        }
    }
    char line[256];
    snprintf(line, sizeof(line),
             "ZENZLOG step=%d npu=%d t1=%d:%.4f t2=%d:%.4f t3=%d:%.4f",
             step, npu,
             ids[0], vals[0], ids[1], vals[1], ids[2], vals[2]);
    LOGI("%s", line);
    const char *path = getenv("ZENZ_LOGIT_DUMP_PATH");
    if (path && path[0]) {
        FILE *file = fopen(path, "a");
        if (file) {
            fprintf(file, "%s\n", line);
            fclose(file);
        }
    }
}

static void dump_top_logits(const float *logits, int32_t n_vocab, int npu, int pos) {
    struct Entry {
        int id;
        float value;
    };
    constexpr int kTop = 20;
    std::vector<Entry> top;
    top.reserve(kTop + 1);
    for (int32_t tid = 0; tid < n_vocab; ++tid) {
        const float value = logits[tid];
        if (top.size() < kTop) {
            top.push_back({tid, value});
            std::sort(top.begin(), top.end(),
                      [](const Entry &a, const Entry &b) { return a.value > b.value; });
        } else if (value > top.back().value) {
            top.back() = {tid, value};
            std::sort(top.begin(), top.end(),
                      [](const Entry &a, const Entry &b) { return a.value > b.value; });
        }
    }

    std::string line = "ZENZLOG pos=" + std::to_string(pos) +
                       " npu=" + std::to_string(npu);
    for (const Entry &entry : top) {
        char buffer[64];
        snprintf(buffer, sizeof(buffer), " | tok=%d val=%.4f", entry.id, entry.value);
        line += buffer;
    }
    // The two tokens that decide 制度/精度 must always be present.
    char buffer[64];
    snprintf(buffer, sizeof(buffer), " | tok=1246 val=%.4f", logits[1246]);
    line += buffer;
    snprintf(buffer, sizeof(buffer), " | tok=743 val=%.4f", logits[743]);
    line += buffer;
    LOGI("%s", line.c_str());

    const char *path = getenv("ZENZ_LOGIT_DUMP_PATH");
    if (path && path[0]) {
        FILE *file = fopen(path, "a");
        if (file) {
            fprintf(file, "%s\n", line.c_str());
            fclose(file);
        }
    }
}
#endif

#ifdef ZENZ_LOGIT_DUMP
// Round 20: records whether the margin check fired, so the trigger rate can
// be aggregated later. Same file sink as the logit dump.
static void log_margin_decision(int trigger, float gap, int step, bool recomputed) {
    char line[160];
    snprintf(line, sizeof(line),
             "ZENZLOG margin trigger=%d step=%d gap=%.4f recompute=%s",
             trigger, step, gap, recomputed ? "cpu" : "none");
    LOGI("%s", line);
    const char *path = getenv("ZENZ_LOGIT_DUMP_PATH");
    if (path && path[0]) {
        FILE *file = fopen(path, "a");
        if (file) {
            fprintf(file, "%s\n", line);
            fclose(file);
        }
    }
}
#endif

static ZenzGenerationResult finish_zenz_generation(
        const ZenzOutputDecoder &decoder,
        ZenzGenerationTermination termination
) {
    if (!decoder.finish()) {
        return no_zenz_result(ZenzGenerationTermination::INVALID_UTF8);
    }
    if (!decoder.has_text()) {
        return no_zenz_result(ZenzGenerationTermination::EMPTY_OUTPUT);
    }
    return ZenzGenerationResult{decoder.text(), termination};
}

static const char *zenz_generation_termination_name(
        ZenzGenerationTermination termination
) {
    switch (termination) {
        case ZenzGenerationTermination::EOG: return "eog";
        case ZenzGenerationTermination::PROTOCOL_MARKER: return "protocol_marker";
        case ZenzGenerationTermination::EMPTY_OUTPUT: return "empty_output";
        case ZenzGenerationTermination::TOKEN_LIMIT: return "token_limit";
        case ZenzGenerationTermination::CONTEXT_LIMIT: return "context_limit";
        case ZenzGenerationTermination::INVALID_UTF8: return "invalid_utf8";
        case ZenzGenerationTermination::DECODE_ERROR: return "decode_error";
        case ZenzGenerationTermination::CANCELLED: return "cancelled";
        case ZenzGenerationTermination::MODEL_UNAVAILABLE: return "model_unavailable";
        case ZenzGenerationTermination::INVALID_REQUEST: return "invalid_request";
    }
    return "unknown";
}

static std::string accepted_zenz_text(const ZenzGenerationResult &result) {
    LOGI("Zenz generation termination=%s accepted=%s bytes=%zu",
         zenz_generation_termination_name(result.termination),
         result.accepted() ? "true" : "false",
         result.text.size());
    return result.accepted() ? result.text : "";
}

// Swift の pure_greedy_decoding 相当。ただし、候補として返せるのは
// EOG または Zenz プロトコルマーカーで正常終了した完全な出力だけにする。
//
// Round 20: when the model runs on the NPU and its own top-1/top-2 gap drops
// below kZenzMarginTau, the caller recomputes the whole conversion on the CPU.
// The margin check is a product feature; only the logging is behind #ifdef.
struct GreedyDecodeOutcome {
    ZenzGenerationResult result;
    // Smallest top-1 minus top-2 gap seen during this decode, and the step it
    // happened at. Used to decide whether the CPU should arbitrate.
    float min_gap = std::numeric_limits<float>::max();
    int min_gap_step = -1;
};

static GreedyDecodeOutcome greedy_decode_with_margin(
        llama_model *model,
        const llama_vocab *vocab,
        llama_context *ctx,
        const std::string &leftSideContext,
        int maxCount,
        uint64_t request_seq,
        bool is_npu,
        bool ignore_stale
) {
    GreedyDecodeOutcome outcome;
    // The CPU arbitration runs while the IME keeps sending new requests, so the
    // request sequence moves on and every decode would abort as stale. The
    // recompute is short and its answer is still the one we want, so it ignores
    // the staleness check.
    const auto stale = [&]() { return !ignore_stale && is_request_stale(request_seq); };
    if (stale()) {
        outcome.result = no_zenz_result(ZenzGenerationTermination::CANCELLED);
        return outcome;
    }
    if (!model || !vocab || !ctx) {
        outcome.result = no_zenz_result(ZenzGenerationTermination::MODEL_UNAVAILABLE);
        return outcome;
    }

    clear_session_kv_locked(ctx);

    AbortRequestState abort_state{request_seq};
    llama_set_abort_callback(ctx, ignore_stale ? never_abort : abort_if_stale,
                             ignore_stale ? nullptr : &abort_state);
    const auto clear_abort_callback = [&]() {
        llama_set_abort_callback(ctx, never_abort, nullptr);
    };

    std::string pre = preprocess_text(leftSideContext);
    auto prompt_tokens = tokenize_text(pre, /*add_bos=*/false, /*add_eos=*/false);
    if (prompt_tokens.empty()) {
        clear_abort_callback();
        outcome.result = no_zenz_result(ZenzGenerationTermination::INVALID_REQUEST);
        return outcome;
    }

    const RuntimeConfig config = get_runtime_config();
    if (config.n_ctx <= 0 || prompt_tokens.size() >= static_cast<size_t>(config.n_ctx)) {
        LOGE("pure_greedy_decoding: prompt exceeds context window: prompt=%zu n_ctx=%d",
             prompt_tokens.size(), config.n_ctx);
        outcome.result = no_zenz_result(ZenzGenerationTermination::CONTEXT_LIMIT);
        return outcome;
    }

    const int requested_count = std::max(maxCount, 0);
    const int available_count = config.n_ctx - static_cast<int>(prompt_tokens.size());
    const int generation_limit = std::min(requested_count, available_count);
    if (generation_limit <= 0) {
        clear_abort_callback();
        outcome.result = no_zenz_result(
                requested_count <= 0
                        ? ZenzGenerationTermination::INVALID_REQUEST
                        : ZenzGenerationTermination::CONTEXT_LIMIT);
        return outcome;
    }

    {
        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data(),
                (int32_t) prompt_tokens.size()
        );
        int rc = llama_decode(ctx, batch);
        if (rc != 0) {
            LOGE("llama_decode(prompt) failed: %d", rc);
            if (stale()) {
                LOGI("pure_greedy_decoding aborted while decoding prompt");
            }
            clear_abort_callback();
            outcome.result = no_zenz_result(
                    stale()
                            ? ZenzGenerationTermination::CANCELLED
                            : ZenzGenerationTermination::DECODE_ERROR);
            return outcome;
        }
    }

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    ZenzOutputDecoder decoder;

    for (int i = 0; i < generation_limit; ++i) {
        if (stale()) {
            clear_abort_callback();
            outcome.result = no_zenz_result(ZenzGenerationTermination::CANCELLED);
            return outcome;
        }

        g_dump_step = i;
        float *logits = llama_get_logits_ith(ctx, -1);
        if (!logits) {
            LOGE("logits is null");
            clear_abort_callback();
            outcome.result = no_zenz_result(ZenzGenerationTermination::DECODE_ERROR);
            return outcome;
        }

#ifdef ZENZ_LOGIT_DUMP
        dump_step_logits(logits, n_vocab, is_npu ? 1 : 0, i);
        if (i == 0) {
            dump_top_logits(logits, n_vocab, is_npu ? 1 : 0, 0);
        }
#endif

        // One pass finds the top two, so the margin costs nothing extra.
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
        if (gap < outcome.min_gap) {
            outcome.min_gap = gap;
            outcome.min_gap_step = i;
        }

        llama_token next = (llama_token) best_id;
        if (llama_vocab_is_eog(vocab, next)) {
            outcome.result = finish_zenz_generation(
                    decoder,
                    ZenzGenerationTermination::EOG);
            clear_abort_callback();
            return outcome;
        }

        const std::string piece = token_to_piece_str(next);
        const ZenzOutputDecoder::AppendResult append_result =
                decoder.append_piece(piece);
        if (append_result == ZenzOutputDecoder::AppendResult::PROTOCOL_MARKER) {
            outcome.result = finish_zenz_generation(
                    decoder,
                    ZenzGenerationTermination::PROTOCOL_MARKER);
            clear_abort_callback();
            return outcome;
        }
        if (append_result == ZenzOutputDecoder::AppendResult::INVALID_UTF8) {
            clear_abort_callback();
            outcome.result = no_zenz_result(ZenzGenerationTermination::INVALID_UTF8);
            return outcome;
        }

        // The last allowed token does not need to be decoded again.  Reaching
        // this branch means the model did not provide a clean terminator.
        if (i + 1 >= generation_limit) {
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&next, 1);
        int rc = llama_decode(ctx, next_batch);
        if (rc != 0) {
            if (stale()) {
                LOGI("pure_greedy_decoding aborted during token generation");
                clear_abort_callback();
                outcome.result = no_zenz_result(ZenzGenerationTermination::CANCELLED);
                return outcome;
            } else {
                LOGE("llama_decode(step) failed: %d", rc);
            }
            clear_abort_callback();
            outcome.result = no_zenz_result(ZenzGenerationTermination::DECODE_ERROR);
            return outcome;
        }
    }

    clear_abort_callback();
    outcome.result = no_zenz_result(ZenzGenerationTermination::TOKEN_LIMIT);
    return outcome;
}

// Runs the same conversion on the CPU session. Called with g_session.mutex held.
static GreedyDecodeOutcome cpu_arbitrated_decode_locked(
        const std::string &leftSideContext,
        int maxCount,
        uint64_t request_seq
) {
    GreedyDecodeOutcome outcome;
    if (!ensure_cpu_arb_model_locked()) {
        outcome.result = no_zenz_result(ZenzGenerationTermination::MODEL_UNAVAILABLE);
        return outcome;
    }
    llama_context *cpu_ctx = ensure_cpu_arb_context_locked();
    if (!cpu_ctx) {
        outcome.result = no_zenz_result(ZenzGenerationTermination::DECODE_ERROR);
        return outcome;
    }
    return greedy_decode_with_margin(
            g_cpu_arb.model,
            g_cpu_arb.vocab,
            cpu_ctx,
            leftSideContext,
            maxCount,
            request_seq,
            /*is_npu=*/false,
            /*ignore_stale=*/true);
}

static ZenzGenerationResult pure_greedy_decoding(
        const std::string &leftSideContext,
        int maxCount,
        uint64_t request_seq
) {
    std::unique_lock<std::mutex> session_lock(g_session.mutex);
    if (!g_model || !g_vocab) {
        return no_zenz_result(ZenzGenerationTermination::MODEL_UNAVAILABLE);
    }
    llama_context *ctx = ensure_session_context_locked();
    if (!ctx) {
        return no_zenz_result(ZenzGenerationTermination::DECODE_ERROR);
    }

    const bool on_npu = g_model_is_npu.load(std::memory_order_relaxed);
    const GreedyDecodeOutcome primary = greedy_decode_with_margin(
            g_model, g_vocab, ctx, leftSideContext, maxCount, request_seq, on_npu,
            /*ignore_stale=*/false);

    // Only the NPU path arbitrates. The CPU path is already the reference, and
    // the fallback path has latched the CPU for the rest of the process.
    const float tau = g_margin_tau.load(std::memory_order_relaxed);
    if (!on_npu || tau <= 0.0f || primary.min_gap >= tau) {
#ifdef ZENZ_LOGIT_DUMP
        log_margin_decision(0, primary.min_gap, primary.min_gap_step, false);
#endif
        return primary.result;
    }

    LOGI("margin arbitration: gap=%.4f step=%d < tau=%.2f, recomputing on cpu",
         primary.min_gap, primary.min_gap_step, tau);
    const GreedyDecodeOutcome arbitrated = cpu_arbitrated_decode_locked(
            leftSideContext, maxCount, request_seq);
#ifdef ZENZ_LOGIT_DUMP
    log_margin_decision(1, primary.min_gap, primary.min_gap_step,
                        arbitrated.result.accepted());
#endif
    if (arbitrated.result.accepted()) {
        LOGI("margin arbitration: cpu answer accepted");
        return arbitrated.result;
    }
    // A failed CPU recompute must not lose the NPU answer.
    LOGE("margin arbitration: cpu recompute failed (%s), keeping npu result",
         zenz_generation_termination_name(arbitrated.result.termination));
    return primary.result;
}

static CandidateEvaluationResult draft_mismatch(
        const std::vector<llama_token> &tokens,
        size_t start,
        size_t mismatch,
        llama_token predicted
) {
    const CandidateEvaluationResult error{CandidateEvaluationResultType::ERROR, 0.0f};
    bool valid = false;
    const auto prefix = decode_token_range(tokens, start, mismatch, &valid);
    if (!valid) return error;

    const auto piece = token_to_piece_str(predicted);
    ZenzOutputDecoder piece_decoder;
    const auto piece_result = piece_decoder.append_piece(piece);
    if (llama_vocab_is_eog(g_vocab, predicted) ||
        piece_result == ZenzOutputDecoder::AppendResult::PROTOCOL_MARKER) {
        if (prefix.empty()) return error;
        return {CandidateEvaluationResultType::WHOLE_RESULT, 0.0f, "", prefix};
    }

    ZenzOutputDecoder decoder;
    if (decoder.append_piece(prefix) != ZenzOutputDecoder::AppendResult::CONTINUE) {
        return error;
    }
    if (!llama_vocab_is_control(g_vocab, predicted) || !piece.empty()) {
        if (piece_result != ZenzOutputDecoder::AppendResult::CONTINUE ||
            decoder.append_piece(piece) != ZenzOutputDecoder::AppendResult::CONTINUE) {
            return error;
        }
    }
    if (!decoder.finish() || !decoder.has_text()) return error;
    return {CandidateEvaluationResultType::FIX_REQUIRED, 0.0f, decoder.text()};
}

// Swift の evaluate_candidate 相当
static CandidateEvaluationResult candidate_evaluate(
        const std::string &prompt,
        const std::string &candidate_text,
        uint64_t request_seq,
        const std::string &draft_review_id
) {
    const CandidateEvaluationResult error{CandidateEvaluationResultType::ERROR, 0.0f};
    std::unique_lock<std::mutex> session_lock(g_session.mutex);
    if (is_request_stale(request_seq) || !g_model || !g_vocab) return error;

    auto *ctx = ensure_session_context_locked();
    if (!ctx) return error;

    auto &cache = g_session.draft_review;
    const auto fail = [&] {
        cache = {};
        return error;
    };
    const auto pre_candidate = preprocess_text(candidate_text);
    const auto prompt_tokens = tokenize_text(preprocess_text(prompt), false, false);
    const auto candidate_tokens = tokenize_text(pre_candidate, false, false);
    const auto config = get_runtime_config();
    if (prompt_tokens.empty() || candidate_tokens.empty() || pre_candidate.empty() ||
        !is_safe_user_text(pre_candidate) || config.n_ctx <= 0 ||
        prompt_tokens.size() + candidate_tokens.size() > static_cast<size_t>(config.n_ctx)) {
        return fail();
    }

    // GPT-2 uses causal attention and supports trimming the KV sequence at a token boundary.
    char architecture[16];
    const bool scoped = !draft_review_id.empty() &&
                        llama_model_meta_val_str(g_model, "general.architecture",
                                                 architecture, sizeof(architecture)) >= 0 &&
                        std::string_view(architecture) == "gpt2";
    const auto generation = g_draft_review_generation.load(std::memory_order_relaxed);
    const bool reuse = scoped && cache.id == draft_review_id &&
                       cache.generation == generation && cache.prompt == prompt_tokens;
    if (!reuse) clear_session_kv_locked(ctx);

    std::vector<llama_token> all_tokens = prompt_tokens;
    all_tokens.insert(all_tokens.end(), candidate_tokens.begin(), candidate_tokens.end());
    const size_t start = prompt_tokens.size();
    const auto pass = [&](float score) -> CandidateEvaluationResult {
        if (is_request_stale(request_seq)) return fail();
        cache = {};
        LOGI("candidate_evaluate: PASS, score=%f", score);
        return {CandidateEvaluationResultType::PASS, score};
    };
    const auto mismatch = [&](size_t j, llama_token token) {
        if (is_request_stale(request_seq)) return fail();
        auto result = draft_mismatch(all_tokens, start, start + j, token);
        if (scoped && result.type == CandidateEvaluationResultType::FIX_REQUIRED &&
            llama_memory_seq_rm(llama_get_memory(ctx), 0, static_cast<llama_pos>(start + j), -1)) {
            // Keep the verified KV prefix and the prediction that produced the repair.
            cache.predictions.resize(j + 1);
            cache.prompt = prompt_tokens;
            cache.generation = generation;
            cache.id = draft_review_id;
        } else {
            cache = {};
        }
        if (result.type == CandidateEvaluationResultType::FIX_REQUIRED) {
            LOGI("candidate_evaluate: FIX_REQUIRED at pos %zu, prefix=%s", start + j, result.prefix.c_str());
        } else if (result.type == CandidateEvaluationResultType::WHOLE_RESULT) {
            LOGI("candidate_evaluate: WHOLE_RESULT at pos %zu, result=%s", start + j, result.whole_result.c_str());
        }
        return result;
    };

    size_t checked = 0;
    size_t decode_from = 0;
    float total_score = 0.0f;
    if (reuse) {
        // The last saved prediction is at the boundary and may differ from the old draft.
        const size_t verified = cache.predictions.size() - 1;
        while (checked < verified && checked < candidate_tokens.size() &&
               cache.predictions[checked].token == candidate_tokens[checked]) {
            ++checked;
        }
        const auto prediction = cache.predictions[checked];
        if (checked == candidate_tokens.size()) return pass(prediction.prefix_score);
        if (prediction.token != candidate_tokens[checked]) return mismatch(checked, prediction.token);
        total_score = prediction.prefix_score + prediction.log_prob;
        decode_from = start + checked;
        if (++checked == candidate_tokens.size()) return pass(total_score);
    }

    const size_t logits_start = start + checked - 1;
    auto batch = llama_batch_init(static_cast<int32_t>(all_tokens.size() - decode_from), 0, 1);
    for (size_t pos = decode_from; pos < all_tokens.size(); ++pos) {
        const int i = batch.n_tokens++;
        batch.token[i] = all_tokens[pos];
        batch.pos[i] = static_cast<llama_pos>(pos);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = pos >= logits_start;
    }
    AbortRequestState abort_state{request_seq};
    llama_set_abort_callback(ctx, abort_if_stale, &abort_state);
    const int rc = llama_decode(ctx, batch);
    llama_set_abort_callback(ctx, never_abort, nullptr);
    llama_batch_free(batch);
    if (is_request_stale(request_seq)) return fail();
    if (rc != 0) {
        LOGE("candidate_evaluate: llama_decode failed: %d", rc);
        return fail();
    }

    const int32_t n_vocab = llama_vocab_n_tokens(g_vocab);
    const float *all_logits = llama_get_logits(ctx);
    if (!all_logits) return fail();
    for (size_t j = checked; j < candidate_tokens.size(); ++j) {
        const float *logits = all_logits + (j - checked) * static_cast<size_t>(n_vocab);
        llama_token max_id = 0;
        float max_logit = logits[0];
        for (int32_t tid = 1; tid < n_vocab; ++tid) {
            if (logits[tid] > max_logit) {
                max_logit = logits[tid];
                max_id = tid;
            }
        }
        float sum_exp = 0.0f;
        for (int32_t tid = 0; tid < n_vocab; ++tid) {
            sum_exp += expf(logits[tid] - max_logit);
        }
        const float log_sum_exp = logf(sum_exp);
        if (scoped) cache.predictions.push_back({max_id, -log_sum_exp, total_score});
        if (max_id != candidate_tokens[j]) return mismatch(j, max_id);
        total_score += logits[candidate_tokens[j]] - max_logit - log_sum_exp;
    }
    return pass(total_score);
}

static bool prefill_prompt_prefix_locked(
        llama_context *ctx,
        const std::vector<llama_token> &prompt_tokens
) {
    clear_session_kv_locked(ctx);

    if (prompt_tokens.size() <= 1) {
        return true;
    }

    const int32_t cap = (int32_t) (prompt_tokens.size() - 1);
    llama_batch batch = llama_batch_init(cap, 0, 1);
    for (size_t i = 0; i + 1 < prompt_tokens.size(); ++i) {
        batch.token[batch.n_tokens] = prompt_tokens[i];
        batch.pos[batch.n_tokens] = (llama_pos) i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = 0;
        batch.n_tokens++;
    }

    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc == 0;
}

static bool extend_candidate_logprobs_locked(
        llama_context *ctx,
        const std::vector<llama_token> &prompt_tokens,
        const std::vector<llama_token> &candidate_tokens,
        size_t shared_tokens,
        uint64_t request_seq,
        std::vector<float> &prefix_logprobs
) {
    // The first unscored token is predicted by the last shared token, or by the
    // last prompt token when the candidates have no common prefix.
    const auto suffix_start = static_cast<llama_pos>(prompt_tokens.size() + shared_tokens - 1);
    if (!llama_memory_seq_rm(llama_get_memory(ctx), 0, suffix_start, -1)) {
        return false;
    }

    // Keep the final token in the decode batch even though its logits are
    // unused. A single-row batch can select a different quantized CPU kernel.
    llama_batch batch = llama_batch_init(static_cast<int32_t>(candidate_tokens.size() - shared_tokens + 1), 0, 1);
    for (size_t i = shared_tokens; i <= candidate_tokens.size(); ++i) {
        batch.token[batch.n_tokens] = i == 0 ? prompt_tokens.back() : candidate_tokens[i - 1];
        batch.pos[batch.n_tokens] = suffix_start + batch.n_tokens;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = 1;
        batch.n_tokens++;
    }

    const int rc = llama_decode(ctx, batch);
    if (rc != 0) {
        if (!is_request_stale(request_seq)) {
            LOGE("extend_candidate_logprobs_locked: llama_decode failed: %d", rc);
        }
        llama_batch_free(batch);
        return false;
    }

    const int32_t n_vocab = llama_vocab_n_tokens(g_vocab);
    float *all_logits = llama_get_logits(ctx);
    if (!all_logits) {
        LOGE("extend_candidate_logprobs_locked: all_logits is null");
        llama_batch_free(batch);
        return false;
    }

    prefix_logprobs.resize(candidate_tokens.size() + 1);
    for (size_t i = shared_tokens; i < candidate_tokens.size(); ++i) {
        llama_token expected_token = candidate_tokens[i];
        const float *logits = all_logits + (i - shared_tokens) * static_cast<size_t>(n_vocab);

        float max_logit = logits[0];
        for (int32_t tid = 1; tid < n_vocab; ++tid) {
            if (logits[tid] > max_logit) {
                max_logit = logits[tid];
            }
        }

        double sum_exp = 0.0;
        for (int32_t tid = 0; tid < n_vocab; ++tid) {
            sum_exp += exp((double) logits[tid] - (double) max_logit);
        }
        const float logprob = logits[expected_token] - max_logit - static_cast<float>(log(sum_exp));
        prefix_logprobs[i + 1] = prefix_logprobs[i] + logprob;
    }

    llama_batch_free(batch);
    return true;
}

// ------- NPU (Hexagon) 判定 -------
// ggmlのバックエンドレジストリにHexagonデバイスがあるかを返す。
// スケルトンの読み込み成否まではここでは確認しない(モデルロード時に判定する)。
#ifdef ZENZ_ENABLE_NPU
static bool zenz_hexagon_available() {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) {
            continue;
        }
        ggml_backend_dev_props props;
        ggml_backend_dev_get_props(dev, &props);
        if (props.type == GGML_BACKEND_DEVICE_TYPE_GPU && props.description &&
            strstr(props.description, "Hexagon") != nullptr) {
            return true;
        }
    }
    return false;
}
#endif

// ------- JNI: モデル初期化・キャンセル・解放 -------
// package com.kazumaproject.zenz; class ZenzEngine

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_initModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jModelPath
) {
    if (!jModelPath) {
        LOGE("initModel: model path is null");
        return JNI_FALSE;
    }

    const char *c_model_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!c_model_path) {
        LOGE("initModel: failed to read model path");
        return JNI_FALSE;
    }
    LOGI("initModel: %s", c_model_path);

    // A reload must also stop a decode that currently owns the session mutex.
    g_request_seq.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_session.mutex);
    destroy_session_context_locked();

    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }
    g_model_path.assign(c_model_path);

    const std::string native_lib_dir = zenz_native_lib_dir();
    if (!native_lib_dir.empty()) {
        setenv("ADSP_LIBRARY_PATH", native_lib_dir.c_str(), 1);
        LOGI("initModel: ADSP_LIBRARY_PATH=%s", native_lib_dir.c_str());
    }

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    bool use_npu = false;
#ifdef ZENZ_ENABLE_NPU
    if (g_npu_enabled.load(std::memory_order_relaxed)) {
        use_npu = zenz_hexagon_available();
        if (!use_npu) {
            LOGI("initModel: npu requested but no Hexagon device found, using cpu");
        }
    }
#endif

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = use_npu ? -1 : 0; // -1: 全レイヤーをNPUへオフロード
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    g_model_is_npu.store(false);
    g_model = llama_model_load_from_file(c_model_path, mparams);
    if (!g_model && use_npu) {
        // NPU指定でロードに失敗したときはCPUにフォールバックする。
        LOGE("initModel: npu load failed, falling back to cpu");
        use_npu = false;
        mparams.n_gpu_layers = 0;
        g_model = llama_model_load_from_file(c_model_path, mparams);
    }
    if (!g_model) {
        LOGE("Failed to load model");
        env->ReleaseStringUTFChars(jModelPath, c_model_path);
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("Failed to get vocab");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(jModelPath, c_model_path);
        return JNI_FALSE;
    }
    g_model_is_npu.store(use_npu);
    LOGI("initModel: loaded use_npu=%d", (int) use_npu);

    env->ReleaseStringUTFChars(jModelPath, c_model_path);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_cancelCurrent(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    // Do not take g_session.mutex here. This method must remain callable from a Binder thread
    // while the actor thread is blocked inside llama_decode with that mutex held.
    g_draft_review_generation.fetch_add(1, std::memory_order_relaxed);
    g_request_seq.fetch_add(1, std::memory_order_relaxed);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_interruptCurrent(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    g_request_seq.fetch_add(1, std::memory_order_relaxed);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_finishDraftReview(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jDraftReviewId
) {
    const auto id = jstring_to_string(env, jDraftReviewId);
    std::lock_guard<std::mutex> lock(g_session.mutex);
    if (g_session.draft_review.id == id) g_session.draft_review = {};
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_closeModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    g_request_seq.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_session.mutex);
    destroy_session_context_locked();
    close_cpu_arb_locked();

    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }
    g_model_is_npu.store(false);
    g_model_path.clear();

    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}

// ------- JNI: ランタイム設定 (n_ctx / n_threads) -------

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setRuntimeConfig(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jint jNCtx,
        jint jNThreads
) {
    int n_ctx = jNCtx > 0 ? jNCtx : 512;
    int n_threads = jNThreads > 0 ? jNThreads : 4;

    if (n_ctx < 128) n_ctx = 128;
    if (n_ctx > 4096) n_ctx = 4096;

    if (n_threads < 1) n_threads = 1;
    if (n_threads > 8) n_threads = 8;

    RuntimeConfig new_config{
            n_ctx,
            n_threads,
            n_threads,
            n_ctx
    };

    {
        std::lock_guard<std::mutex> lock(g_param_mutex);
        g_param_n_ctx = new_config.n_ctx;
        g_param_n_threads = new_config.n_threads;
        g_param_n_threads_batch = new_config.n_threads_batch;
        g_param_n_batch = new_config.n_batch;
    }

    {
        std::lock_guard<std::mutex> session_lock(g_session.mutex);
        if (g_session.ctx && !same_runtime_config(g_session.config, new_config)) {
            destroy_session_context_locked();
        }
    }

    LOGI("setRuntimeConfig: n_ctx=%d, n_threads=%d", n_ctx, n_threads);
}

// ------- JNI: NPU (Hexagon) トグル -------
// 明示トグル。次回のinitModelで反映される(ロード済みのモデルには影響しない)。

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setNpuEnabled(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jboolean jEnabled
) {
    bool enabled = jEnabled == JNI_TRUE;
    if (enabled && g_npu_fallback_latched.load(std::memory_order_relaxed)) {
        LOGI("setNpuEnabled: ignored, cpu fallback latched");
        g_npu_enabled.store(false, std::memory_order_relaxed);
        return;
    }
    g_npu_enabled.store(enabled, std::memory_order_relaxed);
    LOGI("setNpuEnabled: %d", (int) enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setLogitDumpPath(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jPath
) {
#ifdef ZENZ_LOGIT_DUMP
    const char *c_path = env->GetStringUTFChars(jPath, nullptr);
    if (c_path) {
        setenv("ZENZ_LOGIT_DUMP_PATH", c_path, 1);
        LOGI("setLogitDumpPath: %s", c_path);
        env->ReleaseStringUTFChars(jPath, c_path);
    }
#else
    (void) env;
    (void) jPath;
#endif
}

// Diagnostic hook: names the file that receives per-node tensors.
// Must be set before initModel, because the callback is wired at context creation.
extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setTensorDumpPath(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jPath
) {
    const char *c_path = env->GetStringUTFChars(jPath, nullptr);
    if (c_path) {
        if (c_path[0]) {
            setenv("ZENZ_TENSOR_DUMP", c_path, 1);
        } else {
            unsetenv("ZENZ_TENSOR_DUMP");
        }
        LOGI("setTensorDumpPath: %s", c_path);
        env->ReleaseStringUTFChars(jPath, c_path);
    }
}

// Diagnostic hook: sets GGML_HEXAGON_OPFILTER before the backend initialises,
// so a single op family can be pushed back to the CPU for A/B comparison.
extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setHexagonOpFilter(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jPattern
) {
    const char *c_pattern = env->GetStringUTFChars(jPattern, nullptr);
    if (c_pattern) {
        if (c_pattern[0]) {
            setenv("GGML_HEXAGON_OPFILTER", c_pattern, 1);
        } else {
            unsetenv("GGML_HEXAGON_OPFILTER");
        }
        LOGI("setHexagonOpFilter: %s", c_pattern);
        env->ReleaseStringUTFChars(jPattern, c_pattern);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_setMarginTau(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jfloat jTau
) {
    const float tau = jTau > 0.0f ? (float) jTau : 0.0f;
    g_margin_tau.store(tau, std::memory_order_relaxed);
    LOGI("setMarginTau: %.2f", tau);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_isNpuAvailable(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
#ifdef ZENZ_ENABLE_NPU
    return zenz_hexagon_available() ? JNI_TRUE : JNI_FALSE;
#else
    // このビルドにはHexagonバックエンドが含まれていない。
    return JNI_FALSE;
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_getBackendDeviceSummary(
        JNIEnv *env,
        jobject /*thiz*/
) {
    std::string summary;
    const size_t n = ggml_backend_dev_count();
    summary += "count=" + std::to_string(n);
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) {
            continue;
        }
        ggml_backend_dev_props props;
        ggml_backend_dev_get_props(dev, &props);
        summary += "; ";
        summary += props.name ? props.name : "?";
        summary += " / ";
        summary += props.description ? props.description : "?";
        summary += " / type=" + std::to_string(static_cast<int>(props.type));
    }

    void *cdsprpc = dlopen("libcdsprpc.so", RTLD_NOW);
    if (cdsprpc) {
        summary += "; dlopen(libcdsprpc.so)=ok";
        dlclose(cdsprpc);
    } else {
        const char *err = dlerror();
        summary += "; dlopen(libcdsprpc.so)=fail: ";
        summary += err ? err : "?";
    }
    return env->NewStringUTF(summary.c_str());
}

// ------- JNI: 「後半の変換結果」を返す（v1 型） -------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_generate(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jPrompt,
        jint maxTokens
) {
    const char *c_prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(c_prompt ? c_prompt : "");
    env->ReleaseStringUTFChars(jPrompt, c_prompt);

    uint64_t request_seq = g_request_seq.fetch_add(1, std::memory_order_relaxed) + 1;
    const ZenzGenerationResult result = pure_greedy_decoding(
            prompt,
            /*maxCount=*/maxTokens,
            request_seq);

    // ★ NewStringUTFは禁止（不正UTF-8の可能性）
    return toJString(env, accepted_zenz_text(result));
}

// ------- JNI: 文脈 + 読み で変換 -------

static jstring generate_with_context_and_conditions(
        JNIEnv *env,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jint maxTokens
) {
    std::string profile = jstring_to_string(env, jProfile);
    std::string topic = jstring_to_string(env, jTopic);
    std::string style = jstring_to_string(env, jStyle);
    std::string preference = jstring_to_string(env, jPreference);
    std::string left = jstring_to_string(env, jLeftContext);
    std::string right = jstring_to_string(env, jRightContext);
    std::string input = jstring_to_string(env, jInput);

    std::string prompt = build_zenz_prompt(
            profile,
            topic,
            style,
            preference,
            left,
            right,
            input
    );

    uint64_t request_seq = g_request_seq.fetch_add(1, std::memory_order_relaxed) + 1;
    ZenzGenerationResult result = pure_greedy_decoding(
            prompt,
            /*maxCount=*/maxTokens,
            request_seq);

    const bool npu_failure =
            result.termination == ZenzGenerationTermination::DECODE_ERROR ||
            result.termination == ZenzGenerationTermination::EMPTY_OUTPUT ||
            result.termination == ZenzGenerationTermination::MODEL_UNAVAILABLE ||
            result.termination == ZenzGenerationTermination::INVALID_UTF8;

    if (npu_failure && g_model_is_npu.load(std::memory_order_relaxed)) {
        LOGE("generate: npu generation failed (%s), falling back to cpu",
             zenz_generation_termination_name(result.termination));
        bool reloaded = false;
        {
            std::lock_guard<std::mutex> lock(g_session.mutex);
            reloaded = reload_cpu_model_locked();
        }
        if (reloaded) {
            const ZenzGenerationResult retry = pure_greedy_decoding(
                    prompt,
                    /*maxCount=*/maxTokens,
                    request_seq);
            if (retry.accepted()) {
                LOGI("generate: cpu fallback succeeded");
                return toJString(env, accepted_zenz_text(retry));
            }
            LOGE("generate: cpu fallback failed (%s)",
                 zenz_generation_termination_name(retry.termination));
        }
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        if (exception_class) {
            env->ThrowNew(exception_class,
                          "zenz generation failed on npu and cpu fallback");
        }
        return nullptr;
    }

    return toJString(env, accepted_zenz_text(result));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_generateWithContext(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jLeftContext,
        jstring jInput,
        jint maxTokens
) {
    return generate_with_context_and_conditions(
            env,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            jLeftContext,
            nullptr,
            jInput,
            maxTokens
    );
}

// ------- JNI: 条件 + 文脈 + 読み で変換 -------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_generateWithContextAndConditions(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jInput,
        jint maxTokens
) {
    return generate_with_context_and_conditions(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            nullptr,
            jInput,
            maxTokens
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_generateWithContextAndConditionsV32(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jint maxTokens
) {
    return generate_with_context_and_conditions(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            jRightContext,
            jInput,
            maxTokens
    );
}

// ------- JNI: 投機的デコーディングによる候補評価 -------

static jstring candidate_evaluate_with_context(
        JNIEnv *env,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jstring jCandidate,
        jstring jDraftReviewId = nullptr
) {
    std::string profile = jstring_to_string(env, jProfile);
    std::string topic = jstring_to_string(env, jTopic);
    std::string style = jstring_to_string(env, jStyle);
    std::string preference = jstring_to_string(env, jPreference);
    std::string left = jstring_to_string(env, jLeftContext);
    std::string right = jstring_to_string(env, jRightContext);
    std::string input = jstring_to_string(env, jInput);
    std::string candidate = jstring_to_string(env, jCandidate);
    const auto draft_review_id = jstring_to_string(env, jDraftReviewId);
    if (candidate.empty() && draft_review_id.empty()) return toJString(env, "ERROR");

    std::string prompt = build_zenz_prompt(
            profile,
            topic,
            style,
            preference,
            left,
            right,
            input
    );

    uint64_t request_seq = g_request_seq.fetch_add(1, std::memory_order_relaxed) + 1;
    CandidateEvaluationResult eval_result = candidate_evaluate(
            prompt, candidate, request_seq, draft_review_id);

    std::string result_str;
    switch (eval_result.type) {
        case CandidateEvaluationResultType::PASS:
            result_str = "PASS:" + std::to_string(eval_result.score);
            break;
        case CandidateEvaluationResultType::FIX_REQUIRED:
            result_str = "FIX:" + eval_result.prefix;          // ここも不正UTF-8が混ざり得るので toJString 必須
            break;
        case CandidateEvaluationResultType::WHOLE_RESULT:
            result_str = "WHOLE:" + eval_result.whole_result;  // 同上
            break;
        case CandidateEvaluationResultType::ERROR:
        default:
            result_str = "ERROR";
            break;
    }

    return toJString(env, result_str);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_candidateEvaluate(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jInput,
        jstring jCandidate
) {
    return candidate_evaluate_with_context(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            nullptr,
            jInput,
            jCandidate
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_candidateEvaluateV32(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jstring jCandidate
) {
    return candidate_evaluate_with_context(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            jRightContext,
            jInput,
            jCandidate
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_candidateEvaluateDraftV32(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jstring jCandidate,
        jstring jDraftReviewId
) {
    return candidate_evaluate_with_context(
            env, jProfile, jTopic, jStyle, jPreference, jLeftContext,
            jRightContext, jInput, jCandidate, jDraftReviewId);
}

static jfloatArray score_candidates_with_context(
        JNIEnv *env,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jobjectArray jCandidates
) {
    const jsize candidate_count = jCandidates ? env->GetArrayLength(jCandidates) : 0;
    jfloatArray result_array = env->NewFloatArray(candidate_count);
    if (!result_array) {
        return nullptr;
    }

    std::vector<jfloat> scores((size_t) candidate_count, -INFINITY);
    if (candidate_count <= 0) {
        return result_array;
    }

    std::string profile = jstring_to_string(env, jProfile);
    std::string topic = jstring_to_string(env, jTopic);
    std::string style = jstring_to_string(env, jStyle);
    std::string preference = jstring_to_string(env, jPreference);
    std::string left = jstring_to_string(env, jLeftContext);
    std::string right = jstring_to_string(env, jRightContext);
    std::string input = jstring_to_string(env, jInput);

    std::string prompt = build_zenz_prompt(
            profile,
            topic,
            style,
            preference,
            left,
            right,
            input
    );

    const std::string pre_prompt = preprocess_text(prompt);
    uint64_t request_seq = g_request_seq.fetch_add(1, std::memory_order_relaxed) + 1;

    std::vector<std::string> candidate_strings((size_t) candidate_count);
    for (jsize i = 0; i < candidate_count; ++i) {
        auto *j_candidate = (jstring) env->GetObjectArrayElement(jCandidates, i);
        if (!j_candidate) {
            continue;
        }
        candidate_strings[(size_t) i] = jstring_to_string(env, j_candidate);
        env->DeleteLocalRef(j_candidate);
    }

    {
        std::unique_lock<std::mutex> session_lock(g_session.mutex);
        if (!g_model || !g_vocab) {
            LOGE("scoreCandidates: model not initialized");
            env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
            return result_array;
        }

        llama_context *ctx = ensure_session_context_locked();
        if (!ctx) {
            LOGE("scoreCandidates: failed to create context");
            env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
            return result_array;
        }

        AbortRequestState abort_state{request_seq};
        llama_set_abort_callback(ctx, abort_if_stale, &abort_state);

        auto prompt_tokens = tokenize_text(pre_prompt, /*add_bos=*/false, /*add_eos=*/false);
        if (prompt_tokens.empty()) {
            llama_set_abort_callback(ctx, never_abort, nullptr);
            env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
            return result_array;
        }

        const RuntimeConfig config = get_runtime_config();
        if (config.n_ctx <= 0 || prompt_tokens.size() >= static_cast<size_t>(config.n_ctx)) {
            LOGE("scoreCandidates: prompt exceeds context window: prompt=%zu n_ctx=%d",
                 prompt_tokens.size(), config.n_ctx);
            llama_set_abort_callback(ctx, never_abort, nullptr);
            env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
            return result_array;
        }

        if (!prefill_prompt_prefix_locked(ctx, prompt_tokens)) {
            LOGE("scoreCandidates: failed to prefill prompt prefix");
            llama_set_abort_callback(ctx, never_abort, nullptr);
            env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
            return result_array;
        }

        std::vector<std::vector<llama_token>> candidate_tokens_list((size_t) candidate_count);
        std::vector<jsize> score_order;
        score_order.reserve(candidate_count);
        for (jsize i = 0; i < candidate_count; ++i) {
            const auto &candidate = candidate_strings[i];
            if (candidate.empty() || !is_safe_user_text(candidate)) {
                continue;
            }
            auto &tokens = candidate_tokens_list[i];
            tokens = tokenize_text(
                    preprocess_text(candidate),
                    /*add_bos=*/false,
                    /*add_eos=*/false
            );
            if (!tokens.empty() && prompt_tokens.size() + tokens.size() <= static_cast<size_t>(config.n_ctx)) {
                score_order.push_back(i);
            }
        }
        std::sort(score_order.begin(), score_order.end(), [&](jsize a, jsize b) {
            return candidate_tokens_list[a] < candidate_tokens_list[b];
        });

        const std::vector<llama_token> *previous_tokens = nullptr;
        std::vector<float> prefix_logprobs{0.0f};
        for (jsize i : score_order) {
            if (is_request_stale(request_seq)) {
                break;
            }
            const auto &tokens = candidate_tokens_list[i];
            size_t shared_tokens = 0;
            if (previous_tokens) {
                shared_tokens = std::mismatch(
                        tokens.begin(), tokens.end(), previous_tokens->begin(), previous_tokens->end()
                ).first - tokens.begin();
            }

            if (shared_tokens < tokens.size()) {
                if (!extend_candidate_logprobs_locked(
                        ctx, prompt_tokens, tokens, shared_tokens, request_seq, prefix_logprobs
                )) {
                    previous_tokens = nullptr;
                    if (is_request_stale(request_seq) || !prefill_prompt_prefix_locked(ctx, prompt_tokens)) {
                        break;
                    }
                    continue;
                }
                previous_tokens = &tokens;
            }
            scores[i] = prefix_logprobs[tokens.size()] / static_cast<float>(tokens.size());
        }
        llama_set_abort_callback(ctx, never_abort, nullptr);
    }

    env->SetFloatArrayRegion(result_array, 0, candidate_count, scores.data());
    return result_array;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_scoreCandidates(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jInput,
        jobjectArray jCandidates
) {
    return score_candidates_with_context(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            nullptr,
            jInput,
            jCandidates
    );
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_kazumaproject_zenz_ZenzEngine_scoreCandidatesV32(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jProfile,
        jstring jTopic,
        jstring jStyle,
        jstring jPreference,
        jstring jLeftContext,
        jstring jRightContext,
        jstring jInput,
        jobjectArray jCandidates
) {
    return score_candidates_with_context(
            env,
            jProfile,
            jTopic,
            jStyle,
            jPreference,
            jLeftContext,
            jRightContext,
            jInput,
            jCandidates
    );
}
