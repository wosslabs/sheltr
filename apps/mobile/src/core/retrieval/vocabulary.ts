import rules from '../../../../../contract/retrieval-rules.json';

/**
 * Hazard words the keyword retriever recognises, and colloquial synonyms that map onto them. Lets a query such as
 * "quake safety" match documents about earthquakes. Loaded from contract/retrieval-rules.json.
 */
export const HAZARD_TERMS: ReadonlySet<string> = new Set(rules.hazardTerms);

/** Colloquial or regional word to the hazard term it stands for. Every value is a member of {@link HAZARD_TERMS}. */
export const SYNONYMS: Readonly<Record<string, string>> = rules.synonyms;

export function synonymOf(word: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(SYNONYMS, word) ? SYNONYMS[word] : undefined;
}

/** The twelve DisasterType names, in the order the Home screen shows them. */
export const DISASTER_TYPES = rules.disasterTypes as unknown as readonly [
  'EARTHQUAKE', 'FLOOD', 'TROPICAL_CYCLONE', 'TORNADO', 'TSUNAMI', 'WILDFIRE',
  'EXTREME_HEAT', 'WINTER_STORM', 'LANDSLIDE', 'GLACIAL_LAKE_FLOOD', 'VOLCANO', 'DROUGHT',
];
export type DisasterType = (typeof DISASTER_TYPES)[number];
