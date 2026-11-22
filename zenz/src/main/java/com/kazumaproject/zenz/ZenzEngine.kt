package com.kazumaproject.zenz

object ZenzEngine {

    init {
        // CMake の add_library(zenz SHARED ...) と一致させる
        System.loadLibrary("zenz")
    }

    external fun initModel(modelPath: String): Boolean
    external fun cancelCurrent()
    external fun interruptCurrent()
    external fun closeModel()
    external fun finishDraftReview(draftReviewId: String)

    external fun setRuntimeConfig(
        nCtx: Int,
        nThreads: Int
    )

    /**
     * NPU(Hexagon)を使用するかどうかの明示トグル。
     * 次回のinitModelで反映される。既定は無効(CPU)。
     */
    external fun setNpuEnabled(enabled: Boolean)

    /** Hexagonデバイスが利用可能か。CPUビルドでは常にfalse。 */
    external fun isNpuAvailable(): Boolean

    /**
     * Margin threshold for the CPU arbitration. 0 disables it.
     *
     * When the NPU's own top-1 and top-2 are closer than this, the conversion is
     * recomputed on the CPU. Higher is safer and slower.
     */
    external fun setMarginTau(tau: Float)

    /**
     * Diagnostic: push matching ops back to the CPU before the backend starts.
     *
     * Takes a case-insensitive regex over the ggml op name, for example "MUL_MAT".
     * Empty clears it. Must be called before initModel.
     */
    external fun setHexagonOpFilter(pattern: String)

    /**
     * Diagnostic: write selected graph nodes to this file.
     *
     * Empty clears it. Must be called before initModel.
     */
    external fun setTensorDumpPath(path: String)

    /** 診断用: ggmlバックエンドのデバイス一覧。 */
    external fun getBackendDeviceSummary(): String

    /** Round 19 measurement hook. No-op unless the build sets ZENZ_LOGIT_DUMP. */
    external fun setLogitDumpPath(path: String)

    external fun generate(
        prompt: String,
        maxTokens: Int
    ): String

    external fun generateWithContext(
        leftContext: String,
        input: String,
        maxTokens: Int
    ): String

    external fun generateWithContextAndConditions(
        profile: String,
        topic: String,
        style: String,
        preference: String,
        leftContext: String,
        input: String,
        maxTokens: Int
    ): String

    external fun generateWithContextAndConditionsV32(
        profile: String,
        topic: String,
        style: String,
        preference: String,
        leftContext: String,
        rightContext: String,
        input: String,
        maxTokens: Int
    ): String

    external fun candidateEvaluate(
        profile: String?,
        topic: String?,
        style: String?,
        preference: String?,
        leftContext: String?,
        input: String,
        candidate: String
    ): String

    external fun candidateEvaluateV32(
        profile: String?,
        topic: String?,
        style: String?,
        preference: String?,
        leftContext: String?,
        rightContext: String?,
        input: String,
        candidate: String
    ): String

    external fun candidateEvaluateDraftV32(
        profile: String?,
        topic: String?,
        style: String?,
        preference: String?,
        leftContext: String?,
        rightContext: String?,
        input: String,
        candidate: String,
        draftReviewId: String,
    ): String

    external fun scoreCandidates(
        profile: String?,
        topic: String?,
        style: String?,
        preference: String?,
        leftContext: String?,
        input: String,
        candidates: Array<String>
    ): FloatArray

    external fun scoreCandidatesV32(
        profile: String?,
        topic: String?,
        style: String?,
        preference: String?,
        leftContext: String?,
        rightContext: String?,
        input: String?,
        candidates: Array<String>
    ): FloatArray
}
