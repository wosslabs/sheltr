package com.sheltr.shared.retrieval

/**
 * Multiplicative boost for passages whose document country equals the user's country.
 */
const val COUNTRY_BOOST: Double = 0.15

/**
 * Multiplicative boost for passages whose document covers one of the active hazards. There is no related-hazard
 * table yet, so only active hazards are boosted.
 */
const val HAZARD_BOOST: Double = 0.15

/**
 * Ranking factors. A factor only reorders: it is 1.0 or more, so no passage is ever removed by country or hazard.
 * Global documents (country null) and documents of another country keep 1.0.
 */
internal object Boost {

    /** Country boost factor. */
    fun country(documentCountry: String?, userCountry: String?): Double =
        if (userCountry != null && userCountry == documentCountry) 1.0 + COUNTRY_BOOST else 1.0

    /** Hazard boost factor for active hazards (no related hazards on the phone). */
    fun hazard(documentHazards: Collection<String>, activeHazards: Set<String>): Double =
        if (documentHazards.any { it in activeHazards }) 1.0 + HAZARD_BOOST else 1.0
}
