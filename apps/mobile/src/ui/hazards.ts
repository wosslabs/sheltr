import type { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ComponentProps } from 'react';
import type { DisasterType } from '../core/retrieval/vocabulary';
import type { MessageKey } from '../i18n';

type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];

export const HAZARD_ICONS: Record<DisasterType, IconName> = {
  EARTHQUAKE: 'pulse',
  FLOOD: 'water',
  TROPICAL_CYCLONE: 'weather-hurricane',
  TORNADO: 'weather-tornado',
  TSUNAMI: 'waves',
  WILDFIRE: 'fire',
  EXTREME_HEAT: 'thermometer-high',
  WINTER_STORM: 'weather-snowy-heavy',
  LANDSLIDE: 'landslide',
  GLACIAL_LAKE_FLOOD: 'image-filter-hdr',
  VOLCANO: 'volcano',
  DROUGHT: 'water-off',
};

/** Translation key of a hazard's label, for example EXTREME_HEAT -> hazard_extreme_heat. */
export const hazardLabelKey = (hazard: string): MessageKey => `hazard_${hazard.toLowerCase()}` as MessageKey;
