package com.sheltr.shared.settings

/**
 * User settings (spec §5, §7). Immutable; change a value with `copy`.
 *
 * [country] is an ISO 3166-1 alpha-2 code in uppercase ASCII, or null when the user has not chosen one.
 */
data class AppSettings(
    val syncFrequency: SyncFrequency = SyncFrequency.WEEKLY,
    /** Sync on metered connections too. Off by default: sync runs only on unmetered networks. */
    val allowMobileData: Boolean = false,
    val country: String? = null,
    val textScale: TextScale = TextScale.NORMAL,
) {
    init {
        require(country == null || isValidCountry(country)) {
            "country must be null or two uppercase ASCII letters"
        }
    }

    private companion object {
        fun isValidCountry(code: String): Boolean =
            code.length == 2 && code.all { it in 'A'..'Z' }
    }
}

enum class SyncFrequency(val intervalSeconds: Long?) {
    MANUAL(null),
    DAILY(86_400),
    WEEKLY(604_800),
    /** 30 days, not a calendar month. */
    MONTHLY(2_592_000),
}

enum class TextScale {
    NORMAL,
    LARGE,
    EXTRA_LARGE,
}
