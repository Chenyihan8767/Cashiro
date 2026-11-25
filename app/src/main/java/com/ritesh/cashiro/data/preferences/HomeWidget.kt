package com.ritesh.cashiro.data.preferences

import androidx.annotation.StringRes
import com.ritesh.cashiro.R

enum class HomeWidget(@StringRes val label: Int, val defaultOrder: Int) {
    NETWORTH_SUMMARY(R.string.net_worth, 0),
    LOANS(R.string.lend_borrow_title, 1),
    ACCOUNT_CAROUSEL(R.string.accounts, 2),
    UPCOMING_SUBSCRIPTIONS(R.string.widget_upcoming_subscriptions, 3),
    RECENT_TRANSACTIONS(R.string.widget_recent_transactions, 4),
    BUDGET_CAROUSEL(R.string.budgets, 5),
    TRANSACTION_HEATMAP(R.string.widget_activity_heatmap, 6);
    
    companion object {
        fun fromName(name: String): HomeWidget? {
            return entries.find { it.name == name }
        }
    }
}
