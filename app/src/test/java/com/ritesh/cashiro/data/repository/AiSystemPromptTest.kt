package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.model.AccountBalanceSummary
import com.ritesh.cashiro.data.model.BudgetSummary
import com.ritesh.cashiro.data.model.CategoryInfo
import com.ritesh.cashiro.data.model.CategorySpending
import com.ritesh.cashiro.data.model.ChatContext
import com.ritesh.cashiro.data.model.MonthSummary
import com.ritesh.cashiro.data.model.SubscriptionSummary
import com.ritesh.cashiro.data.model.TransactionSummary
import com.ritesh.cashiro.data.preferences.AiDataAccess
import com.ritesh.cashiro.data.preferences.AiPromptPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiSystemPromptTest {
    private val context = ChatContext(
        currentDate = LocalDate.of(2026, 1, 5),
        monthSummary = MonthSummary(100.toBigDecimal(), 30.toBigDecimal(), 4, 31, 5),
        topCategories = listOf(CategorySpending("Monthly category", 30.toBigDecimal(), 100f, 4)),
        recentTransactions = listOf(TransactionSummary("Recent merchant", 10.toBigDecimal(), "USD", category = "Food", daysAgo = 1)),
        activeSubscriptions = listOf(SubscriptionSummary("Subscription merchant", 20.toBigDecimal(), "USD", 3)),
        budgets = listOf(BudgetSummary("Budget name", 90.toBigDecimal(), 30.toBigDecimal(), 60.toBigDecimal(), 1f / 3, "USD")),
        accountBalances = listOf(AccountBalanceSummary("Private bank", "1234", 80.toBigDecimal(), "USD")),
        categories = listOf(CategoryInfo("Custom category", listOf("Custom child")))
    )

    @Test
    fun noGrantSendsOnlyTheCustomInstruction() {
        val settings = AiPromptPreferences(instruction = "Reply in one sentence.")
        assertEquals(settings.instruction, buildAiSystemPrompt(settings, context, "USD"))
    }

    @Test
    fun eachGrantIncludesOnlyItsDataEvenIfOtherDataWasLoaded() {
        val privateData = mapOf(
            AiDataAccess.MONTHLY_SUMMARY to "Monthly category",
            AiDataAccess.RECENT_TRANSACTIONS to "Recent merchant",
            AiDataAccess.ACCOUNTS to "Private bank",
            AiDataAccess.BUDGETS to "Budget name",
            AiDataAccess.SUBSCRIPTIONS to "Subscription merchant",
            AiDataAccess.CATEGORIES to "Custom child"
        )

        privateData.forEach { (allowed, marker) ->
            val settings = AiPromptPreferences(dataAccess = setOf(allowed))
            val prompt = buildAiSystemPrompt(settings, context, "USD")
            assertTrue("Missing data for $allowed", marker in prompt)
            privateData.filterKeys { it != allowed }.values.forEach { deniedMarker ->
                assertFalse("Unexpected data for $allowed", deniedMarker in prompt)
            }
        }
    }
}
