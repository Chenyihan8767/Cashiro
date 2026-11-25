package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.currency.CurrencyConversionService
import com.ritesh.cashiro.data.database.dao.SubscriptionDao
import com.ritesh.cashiro.data.database.dao.TransactionDao
import com.ritesh.cashiro.data.database.entity.SubscriptionState
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.model.*
import com.ritesh.cashiro.data.preferences.AiDataAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for gathering AI chat context from financial data
 */
@Singleton
class AiContextRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val subscriptionDao: SubscriptionDao,
    private val budgetRepository: BudgetRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val categoryRepository: CategoryRepository,
    private val subcategoryRepository: SubcategoryRepository
) {
    
    suspend fun getChatContext(access: Set<AiDataAccess>, currency: String): ChatContext = coroutineScope {
        val date = LocalDate.now()
        val summary = async {
            if (AiDataAccess.MONTHLY_SUMMARY in access) getMonthSummary(date) else null
        }
        val spending = async {
            if (AiDataAccess.MONTHLY_SUMMARY in access) getTopCategories(date) else emptyList()
        }
        val transactions = async {
            if (AiDataAccess.RECENT_TRANSACTIONS in access) getRecentTransactions(date, currency) else emptyList()
        }
        val subscriptions = async {
            if (AiDataAccess.SUBSCRIPTIONS in access) getActiveSubscriptions(date) else emptyList()
        }
        val budgets = async {
            if (AiDataAccess.BUDGETS in access) getBudgets() else emptyList()
        }
        val balances = async {
            if (AiDataAccess.ACCOUNTS in access) getAccountBalances() else emptyList()
        }
        val categories = async {
            if (AiDataAccess.CATEGORIES in access) getAllCategories() else emptyList()
        }

        ChatContext(
            currentDate = date,
            monthSummary = summary.await(),
            recentTransactions = transactions.await(),
            activeSubscriptions = subscriptions.await(),
            topCategories = spending.await(),
            budgets = budgets.await(),
            accountBalances = balances.await(),
            categories = categories.await()
        )
    }

    private suspend fun getMonthSummary(currentDate: LocalDate): MonthSummary {
        val yearMonth = YearMonth.from(currentDate)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()
        
        // Get all transactions for current month
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startOfMonth.atStartOfDay(),
            endOfMonth.atTime(23, 59, 59)
        )
        
        var totalIncome = BigDecimal.ZERO
        var totalExpense = BigDecimal.ZERO
        val transactionCount = transactions.size
        
        // Process in a single pass
        transactions.forEach { transaction ->
            when (transaction.transactionType) {
                TransactionType.INCOME -> totalIncome = totalIncome.add(transaction.amount)
                TransactionType.EXPENSE -> totalExpense = totalExpense.add(transaction.amount)
                TransactionType.CREDIT -> totalExpense = totalExpense.add(transaction.amount) // Credit counts as expense
                TransactionType.TRANSFER -> {} // Transfers don't affect income/expense totals
                TransactionType.INVESTMENT -> {} // Investments are asset reallocation, not expenses
                TransactionType.BALANCE_UPDATE -> {} // Balance updates track account balance, not income/expense
                TransactionType.BORROWED -> totalIncome = totalIncome.add(transaction.amount) // Borrowed money is inflow
                TransactionType.LENT -> totalExpense = totalExpense.add(transaction.amount) // Lent money is outflow
            }
        }
        
        return MonthSummary(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            transactionCount = transactionCount,
            daysInMonth = yearMonth.lengthOfMonth(),
            currentDay = currentDate.dayOfMonth
        )
    }
    
    private suspend fun getRecentTransactions(currentDate: LocalDate, effectiveCurrency: String, days: Int = 14): List<TransactionSummary> {
        val startDate = currentDate.minusDays(days.toLong())
        
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startDate.atStartOfDay(),
            currentDate.atTime(23, 59, 59)
        )
        
        return transactions
            .sortedByDescending { it.dateTime } // Most recent first
            .take(20) // Limit to 20 most recent
            .map { transaction ->
                val daysAgo = ChronoUnit.DAYS.between(
                    transaction.dateTime.toLocalDate(),
                    currentDate
                ).toInt()
                
                val converted = if (transaction.currency != effectiveCurrency) {
                    try {
                        currencyConversionService.convertAmount(transaction.amount, transaction.currency, effectiveCurrency)
                    } catch (_: Exception) { null }
                } else null
                
                TransactionSummary(
                    merchantName = transaction.merchantName,
                    amount = transaction.amount,
                    originalCurrency = transaction.currency,
                    convertedAmount = converted,
                    category = transaction.category ?: "Miscellaneous",
                    subcategory = transaction.subcategory,
                    daysAgo = daysAgo,
                    dateTime = transaction.dateTime,
                    transactionType = transaction.transactionType
                )
            }
    }
    
    private suspend fun getActiveSubscriptions(currentDate: LocalDate): List<SubscriptionSummary> {
        return subscriptionDao.getSubscriptionsByStateList(SubscriptionState.ACTIVE)
            .map { subscription ->
                val daysUntilPayment = ChronoUnit.DAYS.between(
                    currentDate,
                    subscription.nextPaymentDate
                ).toInt()
                
                SubscriptionSummary(
                    merchantName = subscription.merchantName,
                    amount = subscription.amount,
                    currency = subscription.currency,
                    nextPaymentDays = daysUntilPayment
                )
            }
            .sortedBy { it.nextPaymentDays }
            .take(10) // Limit to 10 subscriptions
    }
    
    private suspend fun getTopCategories(currentDate: LocalDate): List<CategorySpending> {
        val yearMonth = YearMonth.from(currentDate)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()
        
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startOfMonth.atStartOfDay(),
            endOfMonth.atTime(23, 59, 59)
        )
        
        // Group by category and calculate spending
        val categoryMap = mutableMapOf<String, MutableList<BigDecimal>>()
        var totalExpense = BigDecimal.ZERO
        
        transactions
            .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
            .forEach { transaction ->
                val category = transaction.category ?: "Miscellaneous"
                categoryMap.getOrPut(category) { mutableListOf() }.add(transaction.amount)
                totalExpense = totalExpense.add(transaction.amount)
            }
        
        return categoryMap.map { (category, amounts) ->
            val categoryTotal = amounts.reduce { acc, amount -> acc.add(amount) }
            val percentage = if (totalExpense > BigDecimal.ZERO) {
                categoryTotal.divide(totalExpense, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .toFloat()
            } else 0f
            
            CategorySpending(
                category = category,
                amount = categoryTotal,
                percentage = percentage,
                transactionCount = amounts.size
            )
        }
            .sortedByDescending { it.amount }
            .take(5) // Top 5 categories
    }
    
    private suspend fun getBudgets(): List<BudgetSummary> {
        val budgets = budgetRepository.getAllBudgets().first()
        return budgets
            .filter { it.isActive }
            .mapNotNull { budget ->
                try {
                    val withSpending = budgetRepository.getBudgetWithSpending(budget)
                    BudgetSummary(
                        name = budget.name,
                        amount = budget.amount,
                        currentSpending = withSpending.currentSpending,
                        remaining = withSpending.remaining,
                        percentUsed = withSpending.percentUsed,
                        currency = budget.currency
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending { it.percentUsed }
    }

    private suspend fun getAccountBalances(): List<AccountBalanceSummary> {
        return accountBalanceRepository.getAllLatestBalances()
            .first()
            .map { balance ->
                AccountBalanceSummary(
                    bankName = balance.bankName,
                    accountLast4 = balance.accountLast4,
                    balance = balance.balance,
                    currency = balance.currency,
                    isCreditCard = balance.isCreditCard,
                    isWallet = balance.isWallet,
                    creditLimit = if (balance.isCreditCard) balance.creditLimit else null
                )
            }
            .sortedBy { it.bankName }
    }

    private suspend fun getAllCategories(): List<CategoryInfo> {
        val cats = categoryRepository.getAllCategories().first()
        val subMap = subcategoryRepository.subcategoriesMap.value
        return cats.map { cat ->
            val subs = subMap[cat.id]?.map { it.name } ?: emptyList()
            CategoryInfo(name = cat.name, subcategories = subs)
        }
    }
}