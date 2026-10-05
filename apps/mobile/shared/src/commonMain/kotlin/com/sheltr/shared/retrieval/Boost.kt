package com.sheltr.shared.retrieval

/**
 * Multiplicative boost for passages whose document country equals the user's country. Same value as the server
 * (sheltr.search.country-boost in apps/backend application.yml).
 */
const val COUNTRY_BOOST: Double = 0.15

/**
 * Multiplicative boost for passages whose document covers one of the active hazards. The server uses 0.3 for an
 * active hazard and 0.15 for a related hazard (sheltr.search.hazard-boost, related-hazard-boost); the phone has no
 * related-hazard table yet, so it applies one factor, 0.15, to active hazards only.
 */
const val HAZARD_BOOST: Double = 0.15

/**
 * Ranking factors. A factor only reorders: it is 1.0 or more, so no passage is ever removed by country or hazard.
 * Global documents (country null) and documents of another country keep 1.0.
 */
internal object Boost {

    /** Port of the server's CountryBoost.factor. */
    fun country(documentCountry: String?, userCountry: String?): Double =
        if (userCountry != null && userCountry == documentCountry) 1.0 + COUNTRY_BOOST else 1.0

    /** The server's HazardBoost.factor for active hazards (no related hazards on the phone). */
    fun hazard(documentHazards: Collection<String>, activeHazards: Set<String>): Double =
        if (documentHazards.any { it in activeHazards }) 1.0 + HAZARD_BOOST else 1.0
}
