package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.model.ChatContext
import com.ritesh.cashiro.data.preferences.AiDataAccess
import com.ritesh.cashiro.data.preferences.AiPromptPreferences
import com.ritesh.cashiro.utils.CurrencyFormatter
import java.math.RoundingMode

internal fun buildAiSystemPrompt(settings: AiPromptPreferences, context: ChatContext, currency: String): String = buildString {
    append(settings.instruction)
    if (settings.dataAccess.isEmpty()) return@buildString
    appendLine()
    appendLine()
    appendLine("Cashiro data shared by the user (as of ${context.currentDate}):")

    fun money(amount: java.math.BigDecimal, code: String = currency): String =
        CurrencyFormatter.formatCurrency(amount, code)

    if (AiDataAccess.MONTHLY_SUMMARY in settings.dataAccess) {
        context.monthSummary?.let { summary ->
            val dailyAverage = summary.totalExpense.divide(summary.currentDay.toBigDecimal(), 2, RoundingMode.HALF_UP)
            appendLine("This month: income ${money(summary.totalIncome)}, spent ${money(summary.totalExpense)}, " +
                "${summary.transactionCount} transactions, daily average ${money(dailyAverage)}.")
        }
        appendLine("Top spending categories:")
        context.topCategories.forEach { category ->
            appendLine("- ${category.category}: ${money(category.amount)} (${category.percentage.toInt()}%)")
        }
    }

    if (AiDataAccess.RECENT_TRANSACTIONS in settings.dataAccess) {
        appendLine("Recent transactions (up to 10 in the last 14 days):")
        context.recentTransactions.take(10).forEach { transaction ->
            val direction = when (transaction.transactionType) {
                TransactionType.INCOME, TransactionType.BORROWED -> "+"
                else -> "-"
            }
            val amount = money(transaction.amount, transaction.originalCurrency)
            val converted = transaction.convertedAmount?.let { " (≈ ${money(it)})" }.orEmpty()
            appendLine("- ${transaction.dateTime.toLocalDate()} ${transaction.merchantName}: $direction$amount$converted " +
                "[${transaction.transactionType.name}, ${transaction.category}]")
        }
    }

    if (AiDataAccess.ACCOUNTS in settings.dataAccess) {
        appendLine("Account balances:")
        context.accountBalances.forEach { account ->
            val type = when {
                account.isCreditCard -> "credit card"
                account.isWallet -> "wallet"
                else -> "bank account"
            }
            val credit = account.creditLimit?.let { ", limit ${money(it, account.currency)}" }.orEmpty()
            appendLine("- ${account.bankName} (••••${account.accountLast4}, $type): ${money(account.balance, account.currency)}$credit")
        }
    }

    if (AiDataAccess.BUDGETS in settings.dataAccess) {
        appendLine("Active budgets:")
        context.budgets.forEach { budget ->
            appendLine("- ${budget.name}: spent ${money(budget.currentSpending, budget.currency)} of " +
                "${money(budget.amount, budget.currency)}. Remaining: ${money(budget.remaining, budget.currency)}.")
        }
    }

    if (AiDataAccess.SUBSCRIPTIONS in settings.dataAccess) {
        appendLine("Active subscriptions:")
        context.activeSubscriptions.forEach { subscription ->
            appendLine("- ${subscription.merchantName}: ${money(subscription.amount, subscription.currency)}, next payment in ${subscription.nextPaymentDays} days.")
        }
    }

    if (AiDataAccess.CATEGORIES in settings.dataAccess) {
        appendLine("Available categories and subcategories:")
        context.categories.forEach { category ->
            val children = category.subcategories.take(4).joinToString(", ")
            appendLine(if (children.isEmpty()) "- ${category.name}" else "- ${category.name}: $children")
        }
    }
}
