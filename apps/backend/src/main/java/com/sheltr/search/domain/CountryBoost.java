package com.sheltr.search.domain;

/**
 * Multiplicative boost for passages whose document country equals the asker's country.
 * It only reorders results: passages of other countries and global passages (null country)
 * keep a factor of 1.0 and are never removed.
 */
public record CountryBoost(double boost) {

    public static final CountryBoost NONE = new CountryBoost(0.0);

    public double factor(String documentCountry, String queryCountry) {
        if (queryCountry != null && queryCountry.equals(documentCountry)) {
            return 1.0 + boost;
        }
        return 1.0;
    }
}
