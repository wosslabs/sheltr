/** Multiplicative boost for passages whose document country equals the user's country. */
export const COUNTRY_BOOST = 0.15;

/**
 * Multiplicative boost for passages whose document covers one of the active hazards. There is no related-hazard
 * table yet, so only active hazards are boosted.
 */
export const HAZARD_BOOST = 0.15;

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
