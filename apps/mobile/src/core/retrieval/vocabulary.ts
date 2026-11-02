/**
 * Hazard words the keyword retriever recognises, and colloquial synonyms that map onto them. Lets a query such as
 * "quake safety" match documents about earthquakes.
 *
 * Source of truth: the DisasterType enum in tools/publisher/src/main/kotlin/com/sheltr/publisher/DisasterType.kt;
 * each name is lower-cased and split on '_'.
 */
export const HAZARD_TERMS: ReadonlySet<string> = new Set([
  'earthquake', 'flood', 'tropical', 'cyclone', 'tornado', 'tsunami', 'wildfire', 'extreme', 'heat', 'winter',
  'storm', 'landslide', 'glacial', 'lake', 'volcano', 'drought',
]);

/** Colloquial or regional word to the hazard term it stands for. Every value is a member of {@link HAZARD_TERMS}. */
export const SYNONYMS: Readonly<Record<string, string>> = {
  quake: 'earthquake',
  twister: 'tornado',
  hurricane: 'cyclone',
  typhoon: 'cyclone',
  bushfire: 'wildfire',
  forestfire: 'wildfire',
  firestorm: 'wildfire',
  blizzard: 'storm',
  mudslide: 'landslide',
  dryspell: 'drought',
};

export function synonymOf(word: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(SYNONYMS, word) ? SYNONYMS[word] : undefined;
}

/** The twelve DisasterType names, in the order the Home screen shows them. */
export const DISASTER_TYPES = [
  'EARTHQUAKE', 'FLOOD', 'TROPICAL_CYCLONE', 'TORNADO', 'TSUNAMI', 'WILDFIRE',
  'EXTREME_HEAT', 'WINTER_STORM', 'LANDSLIDE', 'GLACIAL_LAKE_FLOOD', 'VOLCANO', 'DROUGHT',
] as const;
export type DisasterType = (typeof DISASTER_TYPES)[number];
