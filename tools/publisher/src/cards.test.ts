import assert from 'node:assert/strict';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { buildBundle, documentId, type BundleManifest } from './bundle.ts';
import { rawContent, type ConnectorMap } from './connector.ts';
import { parseManifest } from './manifest.ts';
import { hostAllowlistPolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { generateKeyPair } from './signing.ts';
import { scratchDir } from './testHelpers.ts';

/** The boil-water fixture chunk. A step may quote it and nothing else. */
const QUOTE = 'Boil water for one minute before drinking.';
const URI = 'https://example.org/boil-water.html';
const bytes = new TextEncoder().encode(QUOTE);

interface CardStep {
  readonly text: string;
  readonly documentId: string;
  readonly chunkId: string;
}

interface ActionCard {
  readonly id: string;
  readonly hazard: string;
  readonly phase: 'before' | 'during' | 'after';
  readonly country: string | null;
  readonly title: string;
  readonly steps: readonly CardStep[];
}

function card(step: CardStep): ActionCard {
  return {
    id: 'flood-before',
    hazard: 'FLOOD',
    phase: 'before',
    country: 'NO',
    title: 'Boil water',
    steps: [step],
  };
}

describe('action cards', () => {
  let scratch: ReturnType<typeof scratchDir>;
  let keyPath: string;
  const fixedNow = () => new Date('2026-10-05T12:00:00.000Z');
  const out = () => join(scratch.dir, 'bundle');
  const boilId = documentId(URI);
  const chunkId = `${boilId}-0`;

  beforeEach(() => {
    scratch = scratchDir();
    const keys = generateKeyPair();
    keyPath = join(scratch.dir, 'key.pem');
    writeFileSync(keyPath, keys.privateKey.export({ format: 'pem', type: 'pkcs8' }));
  });
  afterEach(() => scratch.cleanup());

  it('refusesStepWithNoCitation', async () => {
    const citedNowhere = card({
      text: QUOTE,
      documentId: boilId,
      chunkId: 'not-in-the-bundle',
    });
    await assert.rejects(
      () => buildWith([citedNowhere]),
      /no citation/,
    );
    assert.ok(!existsSync(out()));
  });

  it('refusesStepNotQuotedFromItsChunk', async () => {
    const paraphrased = card({
      text: 'Boil wat3r for one minute before drinking.',
      documentId: boilId,
      chunkId,
    });
    await assert.rejects(
      () => buildWith([paraphrased]),
      /verbatim substring/,
    );
    assert.ok(!existsSync(out()));
  });

  it('acceptsVerbatimCitedStep', async () => {
    const quoted = card({ text: QUOTE, documentId: boilId, chunkId });
    const result = await buildWith([quoted]);

    assert.deepEqual(result.failures, []);
    assert.equal(result.manifest.cards.length, 1);
    const step = result.manifest.cards[0].steps[0];
    assert.equal(step.text, QUOTE);
    assert.equal(step.documentId, boilId);
    assert.equal(step.chunkId, chunkId);
    assert.equal(result.manifest.cards[0].phase, 'before');

    const written = JSON.parse(readFileSync(join(out(), 'bundle.json'), 'utf8')) as BundleManifest;
    assert.equal(written.cards[0].steps[0].text, QUOTE);
    const file = JSON.parse(readFileSync(join(out(), 'documents', `${boilId}.json`), 'utf8')) as {
      chunks: { id: string; content: string }[];
    };
    const chunk = file.chunks.find((item) => item.id === chunkId);
    assert.ok(chunk !== undefined);
    assert.ok(chunk.content.includes(QUOTE));
  });

  function buildWith(cards: readonly ActionCard[]) {
    const yaml = `
documents:
  - source_uri: ${URI}
    source_type: html
    title: Boil water
    source_agency: Example Agency
    hazards: [FLOOD]
    published_date: 2024-03-15
    reviewed_on: 2026-10-05
    expires_on: 2027-11-02
    reviewed_by: corpus maintainer
    license: CC-BY-4.0
    country: "NO"
    checksum: ${sha256Hex(bytes)}
`;
    const connectors: ConnectorMap = {
      html: {
        async fetch(uri: string) {
          if (uri !== URI) throw new Error(`No fixture body for ${uri}`);
          return rawContent(bytes);
        },
      },
    };
    return buildBundle(
      parseManifest(yaml),
      connectors,
      hostAllowlistPolicy(['example.org']),
      out(),
      keyPath,
      fixedNow,
      null,
      cards,
    );
  }
});
