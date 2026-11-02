import assert from 'node:assert/strict';
import { createPublicKey, type KeyObject } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { generateFixtures } from './fixtures.ts';
import { sha256Hex } from './sha256.ts';
import { verify } from './signing.ts';
import { scratchDir } from './testHelpers.ts';

const FIXTURES = [
  'valid-signed',
  'valid-unchanged-doc',
  'tampered-manifest',
  'wrong-key',
  'bad-file-checksum',
  'missing-document',
  'unsupported-format',
  'plain-http-url',
] as const;

const EXPECTED: Record<(typeof FIXTURES)[number], { prior: string; result: string; reasonContains: string | null }> = {
  'valid-signed': { prior: 'empty', result: 'updated', reasonContains: null },
  'valid-unchanged-doc': { prior: 'valid-signed', result: 'updated', reasonContains: null },
  'tampered-manifest': { prior: 'empty', result: 'rejectedSignature', reasonContains: null },
  'wrong-key': { prior: 'empty', result: 'rejectedSignature', reasonContains: null },
  'bad-file-checksum': { prior: 'empty', result: 'failed', reasonContains: 'does not match the manifest' },
  'missing-document': { prior: 'empty', result: 'failed', reasonContains: null },
  'unsupported-format': { prior: 'empty', result: 'failed', reasonContains: 'format' },
  'plain-http-url': { prior: 'empty', result: 'failed', reasonContains: 'https' },
};

const WORD_ROWS = [
  'aftershocks\taftershock\ttrue',
  'evacuate\tevacuation\ttrue',
  'disasters\tdisaster\ttrue',
  'heat\theating\ttrue',
  'programming\tprogrammes\tfalse',
  'pet\tpetrol\tfalse',
  'flood\tfloor\tfalse',
  'heat\theal\tfalse',
];

/** The retrieval constants in apps/mobile/src/core/retrieval and the hazard order in ui/hazards.ts. */
const RETRIEVAL_RULES = {
  relevanceThreshold: 1,
  minTermCoverage: 0.6,
  countryBoost: 0.15,
  hazardBoost: 0.15,
  poolMultiplier: 5,
  stopwords: [
    'a', 'about', 'after', 'again', 'all', 'am', 'an', 'and', 'any', 'are', 'as', 'at', 'be', 'because', 'been',
    'before', 'being', 'but', 'by', 'can', 'could', 'did', 'do', 'does', 'doing', 'done', 'during', 'for', 'from',
    'get', 'got', 'had', 'has', 'have', 'having', 'he', 'her', 'here', 'him', 'his', 'how', 'i', 'if', 'in', 'into',
    'is', 'it', 'its', 'just', 'keep', 'know', 'let', 'make', 'me', 'more', 'most', 'my', 'need', 'no', 'nor', 'not',
    'now', 'of', 'off', 'on', 'once', 'only', 'or', 'other', 'our', 'out', 'over', 'own', 'same', 'she', 'should',
    'so', 'some', 'such', 'take', 'than', 'that', 'the', 'their', 'them', 'then', 'there', 'these', 'they', 'this',
    'those', 'through', 'to', 'too', 'under', 'until', 'up', 'use', 'very', 'was', 'we', 'were', 'what', 'when',
    'where', 'which', 'while', 'who', 'want', 'whom', 'why', 'will', 'with', 'would', 'you', 'your',
  ],
  synonyms: {
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
  },
  hazardTerms: [
    'earthquake', 'flood', 'tropical', 'cyclone', 'tornado', 'tsunami', 'wildfire', 'extreme', 'heat', 'winter',
    'storm', 'landslide', 'glacial', 'lake', 'volcano', 'drought',
  ],
  disasterTypes: [
    'EARTHQUAKE', 'FLOOD', 'TROPICAL_CYCLONE', 'TORNADO', 'TSUNAMI', 'WILDFIRE',
    'EXTREME_HEAT', 'WINTER_STORM', 'LANDSLIDE', 'GLACIAL_LAKE_FLOOD', 'VOLCANO', 'DROUGHT',
  ],
};

const ENGLISH_LOCALE = fileURLToPath(new URL('../../../apps/mobile/src/i18n/locales/en.json', import.meta.url));

interface BundleFile {
  formatVersion: number;
  bundleVersion: string;
  documents: { id: string; fileChecksum: string }[];
}

function publicKeyFromHex(hex: string): KeyObject {
  const raw = Buffer.from(hex.trim(), 'hex');
  return createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: raw.toString('base64url') }, format: 'jwk' });
}

function bundleVerifies(dir: string): boolean {
  const key = publicKeyFromHex(readFileSync(join(dir, 'public-key.hex'), 'utf8'));
  return verify(key, readFileSync(join(dir, 'bundle.json')), readFileSync(join(dir, 'bundle.json.sig')));
}

function readBundle(dir: string): BundleFile {
  return JSON.parse(readFileSync(join(dir, 'bundle.json'), 'utf8')) as BundleFile;
}

function documentsDownloaded(name: (typeof FIXTURES)[number], fixturesDir: string): number | null {
  if (name === 'valid-signed') return readBundle(join(fixturesDir, name)).documents.length;
  if (name === 'valid-unchanged-doc') {
    const installed = new Map(readBundle(join(fixturesDir, 'valid-signed')).documents.map((d) => [d.id, d.fileChecksum]));
    return readBundle(join(fixturesDir, name)).documents.filter((d) => installed.get(d.id) !== d.fileChecksum).length;
  }
  return null;
}

function placeholdersOf(value: string): string {
  return [...value.matchAll(/%\{p\d+\}/g)].map((match) => match[0]).join(' ');
}

/** Splits a trailing-newline text file without dropping a final empty column. `trim` would eat that tab. */
function linesOf(text: string): string[] {
  return (text.endsWith('\n') ? text.slice(0, -1) : text).split('\n');
}

function containsPrivateKey(dir: string): string | null {
  for (const entry of readdirSync(dir, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const path = join(entry.parentPath, entry.name);
    if (/\.(pem|key|p8)$/.test(entry.name)) return path;
    if (readFileSync(path).includes(Buffer.from('PRIVATE KEY'))) return path;
  }
  return null;
}

describe('fixtures', () => {
  it('writes the eight fixture directories and expect.json rows', async () => {
    const first = scratchDir();
    const second = scratchDir();
    try {
      const fixedNow = () => new Date('2026-10-05T12:00:00.000Z');
      await generateFixtures(first.dir, fixedNow);
      await generateFixtures(second.dir, fixedNow);

      const fixturesDir = join(first.dir, 'fixtures');
      assert.deepEqual(readdirSync(fixturesDir).sort(), [...FIXTURES].sort());
      for (const name of FIXTURES) {
        const dir = join(fixturesDir, name);
        assert.ok(existsSync(join(dir, 'bundle.json')), name);
        assert.ok(existsSync(join(dir, 'bundle.json.sig')), name);
        assert.ok(existsSync(join(dir, 'public-key.hex')), name);
        assert.ok(existsSync(join(dir, 'documents')), name);
        assert.deepEqual(JSON.parse(readFileSync(join(dir, 'expect.json'), 'utf8')), {
          ...EXPECTED[name],
          documentsDownloaded: documentsDownloaded(name, fixturesDir),
        });
      }

      const valid = join(fixturesDir, 'valid-signed');
      const unchanged = join(fixturesDir, 'valid-unchanged-doc');
      assert.equal(readFileSync(join(valid, 'bundle.json.sig')).length, 64);
      assert.match(readFileSync(join(valid, 'public-key.hex'), 'utf8').trim(), /^[0-9a-f]{64}$/);
      assert.ok(bundleVerifies(valid));
      assert.ok(!bundleVerifies(join(fixturesDir, 'tampered-manifest')));
      assert.ok(!bundleVerifies(join(fixturesDir, 'wrong-key')));

      const prior = readBundle(valid);
      const next = readBundle(unchanged);
      assert.notEqual(prior.bundleVersion, next.bundleVersion);
      assert.ok(next.documents.some((doc) => prior.documents.some((installed) => installed.id === doc.id && installed.fileChecksum === doc.fileChecksum)));
      assert.ok(prior.documents.every((doc) => sha256Hex(readFileSync(join(valid, 'documents', `${doc.id}.json`))) === doc.fileChecksum));

      const badChecksum = join(fixturesDir, 'bad-file-checksum');
      assert.ok(bundleVerifies(badChecksum));
      const bad = readBundle(badChecksum);
      assert.ok(bad.documents.some((doc) => sha256Hex(readFileSync(join(badChecksum, 'documents', `${doc.id}.json`))) !== doc.fileChecksum));

      const missing = join(fixturesDir, 'missing-document');
      assert.ok(bundleVerifies(missing));
      assert.ok(readBundle(missing).documents.some((doc) => !existsSync(join(missing, 'documents', `${doc.id}.json`))));

      const unsupported = join(fixturesDir, 'unsupported-format');
      assert.ok(bundleVerifies(unsupported));
      assert.notEqual(readBundle(unsupported).formatVersion, 1);

      assert.ok(bundleVerifies(join(fixturesDir, 'plain-http-url')));
      assert.equal(containsPrivateKey(first.dir), null);

      assert.notEqual(
        readFileSync(join(first.dir, 'fixtures', 'valid-signed', 'public-key.hex'), 'utf8').trim(),
        readFileSync(join(second.dir, 'fixtures', 'valid-signed', 'public-key.hex'), 'utf8').trim(),
      );
      assert.ok(bundleVerifies(join(second.dir, 'fixtures', 'valid-signed')));
      assert.ok(!bundleVerifies(join(second.dir, 'fixtures', 'tampered-manifest')));

      const rules = JSON.parse(readFileSync(join(first.dir, 'retrieval-rules.json'), 'utf8')) as typeof RETRIEVAL_RULES;
      assert.deepEqual(Object.keys(rules), Object.keys(RETRIEVAL_RULES));
      assert.deepEqual(rules, RETRIEVAL_RULES);
      const format = readFileSync(join(first.dir, 'bundle-format.md'), 'utf8');
      assert.match(format, /formatVersion/);
      assert.match(format, /Ed25519/);
      assert.match(format, /fileChecksum/);
      assert.match(format, /bundle\.json\.sig/);
    } finally {
      first.cleanup();
      second.cleanup();
    }
  });

  it('writes word-cases.tsv and strings.tsv with their header columns', async () => {
    const scratch = scratchDir();
    try {
      await generateFixtures(scratch.dir, () => new Date('2026-10-05T12:00:00.000Z'));

      const words = readFileSync(join(scratch.dir, 'word-cases.tsv'), 'utf8');
      const wordLines = linesOf(words);
      assert.ok(wordLines.length > 1);
      assert.equal(wordLines[0], 'left\tright\tmatch');
      assert.deepEqual(wordLines.slice(1), WORD_ROWS);

      const locale = JSON.parse(readFileSync(ENGLISH_LOCALE, 'utf8')) as Record<string, string>;
      const strings = readFileSync(join(scratch.dir, 'strings.tsv'), 'utf8');
      const stringLines = linesOf(strings);
      assert.ok(stringLines.length > 1);
      assert.equal(stringLines[0], 'key\tplaceholders');
      assert.deepEqual(
        stringLines.slice(1),
        Object.entries(locale).map(([key, value]) => `${key}\t${placeholdersOf(value)}`),
      );
    } finally {
      scratch.cleanup();
    }
  });
});
