import { DISASTER_TYPES } from './disasterType.ts';

/** When the step applies. */
export type CardPhase = 'before' | 'during' | 'after';

/** One quoted action. [text] must be a verbatim substring of the cited chunk. */
export interface CardStep {
  readonly text: string;
  readonly documentId: string;
  readonly chunkId: string;
}

/**
 * A card of quoted steps from approved chunks.
 * The publisher does not invent the step text.
 */
export interface ActionCard {
  readonly id: string;
  readonly hazard: string;
  readonly phase: CardPhase;
  readonly country: string | null;
  readonly title: string;
  readonly steps: readonly CardStep[];
}

const PHASES = new Set<CardPhase>(['before', 'during', 'after']);

export interface CitedChunk {
  readonly documentId: string;
  readonly content: string;
}

/**
 * Accepts [cards] only when every step cites a chunk in [chunks] and its text
 * is a verbatim substring of that chunk. Throws an Error whose message says
 * `no citation` or `verbatim substring`.
 */
export function assertCitedSteps(
  cards: readonly ActionCard[],
  chunks: ReadonlyMap<string, CitedChunk>,
): void {
  const seen = new Set<string>();
  for (const card of cards) {
    if (card.id.trim() === '') throw new Error('Card id is blank');
    if (seen.has(card.id)) throw new Error(`Duplicate card id ${card.id}`);
    seen.add(card.id);
    if (!PHASES.has(card.phase)) {
      throw new Error(`Card ${card.id} phase must be before, during, or after`);
    }
    if (!(DISASTER_TYPES as readonly string[]).includes(card.hazard)) {
      throw new Error(`Card ${card.id} hazard is not a disaster type`);
    }
    if (card.title.trim() === '') throw new Error(`Card ${card.id} title is blank`);
    if (card.country !== null && card.country.trim() === '') {
      throw new Error(`Card ${card.id} country is blank`);
    }
    for (const step of card.steps) {
      const chunk = chunks.get(step.chunkId);
      if (chunk === undefined || chunk.documentId !== step.documentId) {
        throw new Error(`Card ${card.id} step has no citation`);
      }
      if (step.text === '' || !chunk.content.includes(step.text)) {
        throw new Error(`Card ${card.id} step is not a verbatim substring of its chunk`);
      }
    }
  }
}
