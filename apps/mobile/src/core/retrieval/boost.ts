import rules from '../../../../../contract/retrieval-rules.json';

/** Multiplicative boost for passages whose document country equals the user's country. Loaded from contract/retrieval-rules.json. */
export const COUNTRY_BOOST = rules.countryBoost;

/**
 * Multiplicative boost for passages whose document covers one of the active hazards. There is no related-hazard
 * table yet, so only active hazards are boosted.
 */
export const HAZARD_BOOST = rules.hazardBoost;

/**
 * Ranking factors. A factor only reorders: it is 1.0 or more, so no passage is ever removed by country or hazard.
 * Global documents (country null) and documents of another country keep 1.0.
 */
export function countryFactor(documentCountry: string | null, userCountry: string | null): number {
  return userCountry !== null && userCountry === documentCountry ? 1 + COUNTRY_BOOST : 1;
}

export function hazardFactor(documentHazards: readonly string[], activeHazards: ReadonlySet<string>): number {
  return documentHazards.some((h) => activeHazards.has(h)) ? 1 + HAZARD_BOOST : 1;
}
