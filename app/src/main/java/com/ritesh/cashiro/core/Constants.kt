package com.ritesh.cashiro.core

/**
 * Application-wide constants to avoid hardcoded values
 */
object Constants {
    
    /**
     * SMS Processing Configuration
     */
    object SmsProcessing {
        const val DEFAULT_BATCH_SIZE = 100
        const val SMS_PREVIEW_LENGTH = 200
        const val QUERY_LIMIT = 100
        const val INITIAL_SCAN_MONTHS = 3
        const val SCANNING_DELAY_MS = 3000L
    }
    
    /**
     * UI Configuration - Moved to ui/theme/Dimensions.kt for better organization
     * Keeping only non-dimension constants here
     */
    object UI {
        const val BUTTON_WIDTH_RATIO = 0.8f
        const val PROGRESS_STROKE_WIDTH = 2f
    }
    
    /**
     * Database Configuration
     */
    object Database {
        const val DATABASE_NAME = "cashiro_database"
        const val CURRENT_VERSION = 2
        const val TRANSACTION_HASH_DEFAULT = ""
    }
    
    /**
     * WorkManager Configuration
     */
    object WorkManager {
        const val SMS_READER_WORK_NAME = "sms_reader_work"
        const val PERIODIC_SCAN_INTERVAL_HOURS = 24L
        const val INITIAL_DELAY_MINUTES = 15L
    }
    
    /**
     * Parsing Configuration
     */
    object Parsing {
        const val MIN_MERCHANT_NAME_LENGTH = 2
        const val AMOUNT_SCALE = 2
        const val CONFIDENCE_PATTERN_BASED = 0.7f
        const val CONFIDENCE_AI_BASED = 0.9f
    }
    
    /**
     * Navigation Routes
     */
    object Routes {
        const val HOME = "home"
        const val TRANSACTIONS = "transactions"
        const val ANALYTICS = "analytics"
        const val CHAT = "chat"
        const val SETTINGS = "settings"
    }
    
    /**
     * LLM Model Configuration
     */
    object Ai {
        const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct.litertlm"
        const val LEGACY_MODEL_FILE_NAME = "qwen2.5-1.5b-instruct.task"
        const val CONTEXT_TOKENS = 4096
        const val QWEN_MODEL_ID = "qwen"
        const val MODEL_SIZE_BYTES = 1_597_922_856L
        const val LEGACY_MODEL_SIZE_BYTES = 1_598_556_720L
    }

    /**
     * External Links
     */
    object Links {
        const val DISCORD_URL = "https://discord.gg/6qaYgpJTg"
        const val GITHUB_URL = "https://github.com/ritesh-kanwar/Cashiro"
        const val WEBSITE_URL = "https://ritesh-kanwar.github.io/cashiro.showcase"
        const val PRIVACY_POLICY_URL = "https://ritesh-kanwar.github.io/cashiro.showcase/privacy"
        const val TERMS_OF_SERVICE_URL = "https://ritesh-kanwar.github.io/cashiro.showcase/terms"
        const val FAQ_URL = "https://ritesh-kanwar.github.io/cashiro.showcase/faq"
        const val GUIDE_URL = "https://ritesh-kanwar.github.io/cashiro.showcase/guides"
        const val REPORT_BUG_URL = "https://github.com/ritesh-kanwar/Cashiro/issues/new/choose"
    }
    object Device {
        val is64Bit = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            ?.let { it == "arm64-v8a" || it == "x86_64" } == true
    }
}
