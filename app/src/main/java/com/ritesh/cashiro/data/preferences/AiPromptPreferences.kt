package com.ritesh.cashiro.data.preferences

enum class AiDataAccess {
    MONTHLY_SUMMARY,
    RECENT_TRANSACTIONS,
    ACCOUNTS,
    BUDGETS,
    SUBSCRIPTIONS,
    CATEGORIES
}

data class AiPromptPreferences(
    val instruction: String = DEFAULT_INSTRUCTION,
    val dataAccess: Set<AiDataAccess> = emptySet(),
    val historyScope: String = DEFAULT_HISTORY_SCOPE
) {
    companion object {
        const val DEFAULT_INSTRUCTION = "You are a concise financial assistant. When answering about the user's finances, use only information shared in this conversation. Never guess or invent numbers. Preserve the currencies and amounts as provided."
        internal const val LEGACY_INSTRUCTION = "You are Cashiro AI, a concise financial assistant. When answering about the user's finances, use only information shared in this conversation. Never guess or invent numbers. Preserve the currencies and amounts as provided."
        private const val DEFAULT_HISTORY_SCOPE = "initial"
    }
}
