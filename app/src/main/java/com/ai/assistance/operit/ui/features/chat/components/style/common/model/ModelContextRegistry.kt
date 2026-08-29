package com.ai.assistance.operit.ui.features.chat.components.style.common.model

/**
 * Registry pentru fereastra de context pe model (modelId -> numar maxim de tokeni).
 *
 * Ordinea de rezolutie:
 * 1. Valoare inregistrata runtime prin registerContextLength (ex. context_length OpenRouter).
 * 2. Potrivire exacta pe modelId normalizat.
 * 3. Potrivire pe prefix de familie.
 * 4. Fallback: DEFAULT_CONTEXT_WINDOW = 128_000.
 */
object ModelContextRegistry {

    const val DEFAULT_CONTEXT_WINDOW: Long = 128_000L

    /** Knowledge base static: modelId normalizat -> fereastra de context (tokeni). */
    private val KNOWLEDGE_BASE: Map<String, Long> = mapOf(
        "deepseek-chat" to 128_000L,
        "deepseek-reasoner" to 128_000L,
        "deepseek-coder" to 128_000L,
        "opencode/hy3-free" to 1_000_000L,
        "opencode-hy3-free" to 1_000_000L,
        "gpt-4o" to 128_000L,
        "gpt-4o-mini" to 128_000L,
        "llama-3.1-405b" to 128_000L,
        "llama-3.1-405b-instruct" to 128_000L,
        "llama-3.1-405b-instruct-fp8" to 128_000L,
    )

    /** Prefixe de familie aplicate pe modelId normalizat. */
    private val FAMILY_PREFIXES: Map<String, Long> = mapOf(
        "deepseek" to 128_000L,
        "opencode/" to 1_000_000L,
        "gpt-4o" to 128_000L,
        "llama-3.1-405b" to 128_000L,
    )

    /** Override-uri runtime (ex. context_length OpenRouter), key = modelId normalizat. */
    private val dynamicOverrides = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Inregistreaza fereastra reala de context daca obiectul model expune context_length. */
    fun registerContextLength(modelId: String, contextLength: Long) {
        if (contextLength > 0L && modelId.isNotBlank()) {
            dynamicOverrides[normalize(modelId)] = contextLength
        }
    }

    /** Sterge un override inregistrat pentru [modelId] (revine la knowledge base). */
    fun clearContextLength(modelId: String) {
        dynamicOverrides.remove(normalize(modelId))
    }

    /** Returneaza fereastra de context (in tokeni) pentru [modelId]. */
    fun getContextWindow(modelId: String): Long {
        val key = normalize(modelId)
        if (key.isEmpty()) return DEFAULT_CONTEXT_WINDOW

        dynamicOverrides[key]?.let { return it }
        KNOWLEDGE_BASE[key]?.let { return it }
        for ((prefix, value) in FAMILY_PREFIXES) {
            if (key.startsWith(prefix)) return value
        }
        return DEFAULT_CONTEXT_WINDOW
    }

    /** Normalizeaza modelId: lowercase + trim. */
    private fun normalize(modelId: String): String =
        modelId.trim().lowercase()
}