/** Hazards a corpus document can cover, in the order the app shows them. */
export const DISASTER_TYPES = [
  'EARTHQUAKE', 'FLOOD', 'TROPICAL_CYCLONE', 'TORNADO', 'TSUNAMI', 'WILDFIRE',
  'EXTREME_HEAT', 'WINTER_STORM', 'LANDSLIDE', 'GLACIAL_LAKE_FLOOD', 'VOLCANO', 'DROUGHT',
] as const;

export type DisasterType = (typeof DISASTER_TYPES)[number];
