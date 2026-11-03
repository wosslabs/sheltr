import { type KeyObject } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { assertCitedSteps, type ActionCard } from './cards.ts';
import { buildBundle, documentId, encodeBundleJson, type BundleManifest } from './bundle.ts';
import { rawContent, type ConnectorMap } from './connector.ts';
import { parseManifest } from './manifest.ts';
import { hostAllowlistPolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { generateKeyPair, rawPublicKey, sign, signingKeyId } from './signing.ts';

const MOBILE = fileURLToPath(new URL('../../../apps/mobile/src/', import.meta.url));

interface SampleDocument {
  readonly uri: string;
  readonly title: string;
  readonly hazards: string;
  readonly text: string;
}

interface FixtureExpect {
  readonly prior: 'empty' | 'valid-signed';
  readonly result: 'updated' | 'rejectedSignature' | 'failed';
  readonly reasonContains: string | null;
  readonly documentsDownloaded: number | null;
}

const BOIL: SampleDocument = {
  uri: 'https://example.org/boil-water.html',
  title: 'Boil water',
  hazards: 'FLOOD',
  text: 'Boil water for one minute before drinking.',
};

const EVACUATE: SampleDocument = {
  uri: 'https://example.org/evacuate.html',
  title: 'Evacuate',
  hazards: 'EARTHQUAKE',
  text: 'Evacuate immediately if instructed by officials.',
};

const EVACUATE_CHANGED: SampleDocument = {
  ...EVACUATE,
  text: 'Move to high ground when the warning sounds.',
};

const WORD_CASES = `left\tright\tmatch
aftershocks\taftershock\ttrue
evacuate\tevacuation\ttrue
disasters\tdisaster\ttrue
heat\theating\ttrue
programming\tprogrammes\tfalse
pet\tpetrol\tfalse
flood\tfloor\tfalse
heat\theal\tfalse
`;

/**
 * Writes the shared contract into [outDir]: retrieval rules, word cases, English string keys, the bundle
 * format note, and the signed fixture directories.
 *
 * buildBundle can only read a signing key from a file. The key is generated in memory, written as PKCS#8 PEM
 * under the system temp directory (outside the repository), and deleted before this returns. Fixture
 * directories receive the raw public key and the signature only.
 */
export async function generateFixtures(outDir: string, now: () => Date): Promise<void> {
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, 'retrieval-rules.json'), `${JSON.stringify(retrievalRules(), null, 2)}\n`);
  writeFileSync(join(outDir, 'word-cases.tsv'), WORD_CASES);
  writeFileSync(join(outDir, 'strings.tsv'), stringsTsv());
  writeFileSync(join(outDir, 'bundle-format.md'), BUNDLE_FORMAT_MD);
  await writeFixtureBundles(join(outDir, 'fixtures'), now);
}

async function writeFixtureBundles(fixturesDir: string, now: () => Date): Promise<void> {
  const keyDir = mkdtempSync(join(tmpdir(), 'sheltr-fixture-key-'));
  const keyPath = join(keyDir, 'key.pem');
  const { privateKey, publicKey } = generateKeyPair();
  writeFileSync(keyPath, privateKey.export({ format: 'pem', type: 'pkcs8' }));
  const publicHex = rawPublicKeyHex(publicKey);
  try {
    const base = now();
    const signedDir = join(fixturesDir, 'valid-signed');
    const signed = await buildSample(signedDir, keyPath, () => base, [BOIL, EVACUATE]);
    finish(signedDir, publicHex, {
      prior: 'empty',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: signed.documents.length,
    });

    const unchangedDir = join(fixturesDir, 'valid-unchanged-doc');
    const unchanged = await buildSample(
      unchangedDir,
      keyPath,
      () => new Date(base.getTime() + 1000),
      [BOIL, EVACUATE_CHANGED],
      join(signedDir, 'bundle.json'),
    );
    finish(unchangedDir, publicHex, {
      prior: 'valid-signed',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: changedDocumentCount(signed, unchanged),
    });

    deriveCopy(signedDir, join(fixturesDir, 'tampered-manifest'), (dir) => {
      editFile(join(dir, 'bundle.json'), 'Boil water', 'Boil wat3r');
    }, { prior: 'empty', result: 'rejectedSignature', reasonContains: null, documentsDownloaded: null });

    deriveCopy(signedDir, join(fixturesDir, 'wrong-key'), (dir) => {
      writeFileSync(join(dir, 'public-key.hex'), `${rawPublicKeyHex(generateKeyPair().publicKey)}\n`);
    }, { prior: 'empty', result: 'rejectedSignature', reasonContains: null, documentsDownloaded: null });

    deriveCopy(signedDir, join(fixturesDir, 'bad-file-checksum'), (dir) => {
      const id = readManifest(dir).documents[0].id;
      editFile(join(dir, 'documents', `${id}.json`), 'one minute', 'two minutes');
    }, { prior: 'empty', result: 'failed', reasonContains: 'does not match the manifest', documentsDownloaded: null });

    deriveCopy(signedDir, join(fixturesDir, 'missing-document'), (dir) => {
      const id = readManifest(dir).documents.at(-1)?.id;
      if (id === undefined) throw new Error('missing-document has no document to remove');
      rmSync(join(dir, 'documents', `${id}.json`));
    }, { prior: 'empty', result: 'failed', reasonContains: null, documentsDownloaded: null });

    // The app verifies the signature before it reads formatVersion, so the edited manifest is signed again.
    // Otherwise this fixture would be rejectedSignature and the format error would never be reached.
    deriveCopy(signedDir, join(fixturesDir, 'unsupported-format'), (dir) => {
      const manifestPath = join(dir, 'bundle.json');
      editFile(manifestPath, '"formatVersion": 2', '"formatVersion": 3');
      writeFileSync(join(dir, 'bundle.json.sig'), sign(privateKey, readFileSync(manifestPath)));
    }, { prior: 'empty', result: 'failed', reasonContains: 'format', documentsDownloaded: null });

    // The bytes are a normal signed bundle. The app refuses this fixture because its sync base URL is
    // http://example.org/bundle, which is not part of the bundle files. See contract/README.md.
    deriveCopy(signedDir, join(fixturesDir, 'plain-http-url'), () => {}, {
      prior: 'empty',
      result: 'failed',
      reasonContains: 'https',
      documentsDownloaded: null,
    });

    // A signed bundle whose sequence is lower than valid-signed. The app refuses it; expiry is not involved.
    deriveCopy(signedDir, join(fixturesDir, 'downgrade-older-sequence'), (dir) => {
      const manifestPath = join(dir, 'bundle.json');
      editFile(manifestPath, '"sequence": 1', '"sequence": 0');
      writeFileSync(join(dir, 'bundle.json.sig'), sign(privateKey, readFileSync(manifestPath)));
    }, { prior: 'valid-signed', result: 'failed', reasonContains: 'older', documentsDownloaded: null });

    // Same sequence as valid-signed, but a different bundleVersion, so it is not the installed publication.
    deriveCopy(signedDir, join(fixturesDir, 'same-sequence'), (dir) => {
      const manifestPath = join(dir, 'bundle.json');
      const version = readManifest(dir).bundleVersion;
      const bumped = new Date(Date.parse(version) + 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
      editFile(manifestPath, `"bundleVersion": "${version}"`, `"bundleVersion": "${bumped}"`);
      writeFileSync(join(dir, 'bundle.json.sig'), sign(privateKey, readFileSync(manifestPath)));
    }, { prior: 'valid-signed', result: 'failed', reasonContains: 'older', documentsDownloaded: null });

    // expiresOn is in the past. The publisher will not emit that, so the signed bytes are edited.
    // The app still installs it: expiry is a warning, not a sync failure.
    deriveCopy(signedDir, join(fixturesDir, 'expired-document'), (dir) => {
      const manifestPath = join(dir, 'bundle.json');
      const text = readFileSync(manifestPath, 'utf8');
      const from = `"expiresOn": "${FIXTURE_EXPIRES_ON}"`;
      if (!text.includes(from)) throw new Error(`expired-document has no ${from}`);
      writeFileSync(manifestPath, text.replaceAll(from, '"expiresOn": "2020-01-01"'));
      writeFileSync(join(dir, 'bundle.json.sig'), sign(privateKey, readFileSync(manifestPath)));
    }, {
      prior: 'empty',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: readManifest(signedDir).documents.length,
    });

    await generateKeyRotationFixtures(fixturesDir, now);
    await generateCardFixtures(fixturesDir, now);
  } finally {
    rmSync(keyDir, { recursive: true, force: true });
  }
}

const FIXTURE_REVIEWED_ON = '2026-10-05';
const FIXTURE_EXPIRES_ON = '2027-11-02';
/** Before [FIXTURE_EXPIRES_ON], so `npm run fixtures` does not read the wall clock. */
const fixtureNow = (): Date => new Date('2026-10-05T12:00:00.000Z');

async function buildSample(
  outputDir: string,
  keyPath: string,
  now: () => Date,
  documents: readonly SampleDocument[],
  previousBundlePath: string | null = null,
  cards: readonly ActionCard[] = [],
): Promise<BundleManifest> {
  const bytes = new Map(documents.map((doc) => [doc.uri, new TextEncoder().encode(doc.text)]));
  const yaml = [
    'documents:',
    ...documents.map((doc) => `  - source_uri: ${doc.uri}
    source_type: html
    title: ${doc.title}
    source_agency: Example Agency
    hazards: [${doc.hazards}]
    published_date: 2024-03-15
    reviewed_on: ${FIXTURE_REVIEWED_ON}
    expires_on: ${FIXTURE_EXPIRES_ON}
    reviewed_by: corpus maintainer
    license: CC-BY-4.0
    country: "NO"
    checksum: ${sha256Hex(bytes.get(doc.uri)!)}`),
  ].join('\n');
  const connectors: ConnectorMap = {
    html: {
      async fetch(uri: string) {
        const body = bytes.get(uri);
        if (body === undefined) throw new Error(`No fixture body for ${uri}`);
        return rawContent(body);
      },
    },
  };
  const result = await buildBundle(
    parseManifest(yaml),
    connectors,
    hostAllowlistPolicy(['example.org']),
    outputDir,
    keyPath,
    now,
    previousBundlePath,
    cards,
  );
  return result.manifest;
}

function finish(dir: string, publicHex: string, expected: FixtureExpect): void {
  writeFileSync(join(dir, 'public-key.hex'), `${publicHex}\n`);
  writeExpect(dir, expected);
}

function deriveCopy(from: string, to: string, edit: (dir: string) => void, expected: FixtureExpect): void {
  cpSync(from, to, { recursive: true });
  edit(to);
  writeExpect(to, expected);
}

function writeExpect(dir: string, expected: FixtureExpect): void {
  writeFileSync(join(dir, 'expect.json'), `${JSON.stringify(expected, null, 2)}\n`);
}

function editFile(path: string, from: string, to: string): void {
  const text = readFileSync(path, 'utf8');
  const at = text.indexOf(from);
  if (at < 0 || text.indexOf(from, at + from.length) >= 0) {
    throw new Error(`${path} does not contain exactly one "${from}"`);
  }
  writeFileSync(path, text.slice(0, at) + to + text.slice(at + from.length));
}

function readManifest(dir: string): BundleManifest {
  return JSON.parse(readFileSync(join(dir, 'bundle.json'), 'utf8')) as BundleManifest;
}

function changedDocumentCount(prior: BundleManifest, next: BundleManifest): number {
  const installed = new Map(prior.documents.map((doc) => [doc.id, doc.fileChecksum]));
  return next.documents.filter((doc) => installed.get(doc.id) !== doc.fileChecksum).length;
}

function rawPublicKeyHex(publicKey: KeyObject): string {
  return rawPublicKey(publicKey).toString('hex');
}

interface TrustedKeyRecord {
  readonly id: string;
  readonly publicKeyHex: string;
  readonly notAfter: string;
}

/**
 * Writes only rotated-key-valid and revoked-key. Other fixture directories are left as they are.
 * The private keys stay in a temp directory and are deleted before this returns.
 */
export async function generateKeyRotationFixtures(fixturesDir: string, now: () => Date): Promise<void> {
  const keyDir = mkdtempSync(join(tmpdir(), 'sheltr-fixture-key-'));
  const previous = generateKeyPair();
  const rotated = generateKeyPair();
  const removed = generateKeyPair();
  const rotatedPath = join(keyDir, 'rotated.pem');
  const removedPath = join(keyDir, 'removed.pem');
  writeFileSync(rotatedPath, rotated.privateKey.export({ format: 'pem', type: 'pkcs8' }));
  writeFileSync(removedPath, removed.privateKey.export({ format: 'pem', type: 'pkcs8' }));
  try {
    const previousKey: TrustedKeyRecord = {
      id: signingKeyId(previous.publicKey),
      publicKeyHex: rawPublicKeyHex(previous.publicKey),
      notAfter: '2027-11-02',
    };
    const rotatedKey: TrustedKeyRecord = {
      id: signingKeyId(rotated.publicKey),
      publicKeyHex: rawPublicKeyHex(rotated.publicKey),
      notAfter: '2028-11-02',
    };

    const rotatedDir = join(fixturesDir, 'rotated-key-valid');
    rmSync(rotatedDir, { recursive: true, force: true });
    const rotatedManifest = await buildSample(rotatedDir, rotatedPath, now, [BOIL, EVACUATE]);
    finish(rotatedDir, rotatedKey.publicKeyHex, {
      prior: 'empty',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: rotatedManifest.documents.length,
    });
    writeTrustedKeys(rotatedDir, [previousKey, rotatedKey]);

    const revokedDir = join(fixturesDir, 'revoked-key');
    rmSync(revokedDir, { recursive: true, force: true });
    await buildSample(revokedDir, removedPath, now, [BOIL, EVACUATE]);
    finish(revokedDir, rawPublicKeyHex(removed.publicKey), {
      prior: 'empty',
      result: 'rejectedSignature',
      reasonContains: null,
      documentsDownloaded: null,
    });
    writeTrustedKeys(revokedDir, [rotatedKey]);
  } finally {
    rmSync(keyDir, { recursive: true, force: true });
  }
}

/**
 * Writes only cards-valid and cards-uncited-step. Other fixture directories are left as they are.
 * The private key stays in a temp directory and is deleted before this returns.
 * cards-valid quotes the boil-water fixture chunk. cards-uncited-step names a chunk that is not in the bundle.
 */
export async function generateCardFixtures(fixturesDir: string, now: () => Date): Promise<void> {
  const keyDir = mkdtempSync(join(tmpdir(), 'sheltr-fixture-key-'));
  const keyPath = join(keyDir, 'key.pem');
  const { privateKey, publicKey } = generateKeyPair();
  writeFileSync(keyPath, privateKey.export({ format: 'pem', type: 'pkcs8' }));
  const publicHex = rawPublicKeyHex(publicKey);
  const boilId = documentId(BOIL.uri);
  const quoted: ActionCard = {
    id: 'flood-before',
    hazard: 'FLOOD',
    phase: 'before',
    country: 'NO',
    title: 'Boil water',
    steps: [{
      text: BOIL.text,
      documentId: boilId,
      chunkId: `${boilId}-0`,
    }],
  };
  const uncited: ActionCard = {
    id: 'flood-before',
    hazard: 'FLOOD',
    phase: 'before',
    country: 'NO',
    title: 'Boil water',
    steps: [{
      text: BOIL.text,
      documentId: boilId,
      chunkId: 'not-in-the-bundle',
    }],
  };
  try {
    const validDir = join(fixturesDir, 'cards-valid');
    rmSync(validDir, { recursive: true, force: true });
    const valid = await buildSample(validDir, keyPath, now, [BOIL, EVACUATE], null, [quoted]);
    finish(validDir, publicHex, {
      prior: 'empty',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: valid.documents.length,
    });

    const uncitedDir = join(fixturesDir, 'cards-uncited-step');
    rmSync(uncitedDir, { recursive: true, force: true });
    const built = await buildSample(uncitedDir, keyPath, now, [BOIL, EVACUATE]);
    let refused = false;
    try {
      assertCitedSteps([uncited], chunkIndex(uncitedDir));
    } catch (e) {
      if (!(e instanceof Error) || !e.message.includes('no citation')) throw e;
      refused = true;
    }
    if (!refused) throw new Error('cards-uncited-step was accepted');
    const manifestPath = join(uncitedDir, 'bundle.json');
    const parsed = JSON.parse(readFileSync(manifestPath, 'utf8')) as BundleManifest;
    parsed.cards = [uncited];
    const bundleBytes = encodeBundleJson(parsed);
    writeFileSync(manifestPath, bundleBytes);
    writeFileSync(join(uncitedDir, 'bundle.json.sig'), sign(privateKey, bundleBytes));
    finish(uncitedDir, publicHex, {
      prior: 'empty',
      result: 'updated',
      reasonContains: null,
      documentsDownloaded: built.documents.length,
    });
  } finally {
    rmSync(keyDir, { recursive: true, force: true });
  }
}

function chunkIndex(dir: string): Map<string, { documentId: string; content: string }> {
  const manifest = readManifest(dir);
  const chunks = new Map<string, { documentId: string; content: string }>();
  for (const doc of manifest.documents) {
    const file = JSON.parse(readFileSync(join(dir, 'documents', `${doc.id}.json`), 'utf8')) as {
      chunks: { id: string; content: string }[];
    };
    for (const chunk of file.chunks) {
      chunks.set(chunk.id, { documentId: doc.id, content: chunk.content });
    }
  }
  return chunks;
}

function writeTrustedKeys(dir: string, keys: readonly TrustedKeyRecord[]): void {
  writeFileSync(join(dir, 'trusted-keys.json'), `${JSON.stringify(keys, null, 2)}\n`);
}

function retrievalRules(): {
  relevanceThreshold: number;
  minTermCoverage: number;
  countryBoost: number;
  hazardBoost: number;
  poolMultiplier: number;
  stopwords: string[];
  synonyms: Record<string, string>;
  hazardTerms: string[];
  disasterTypes: string[];
} {
  // The Expo app reads this file. Regenerating copies it so the committed rules stay the source.
  return JSON.parse(readFileSync(new URL('../../../contract/retrieval-rules.json', import.meta.url), 'utf8'));
}

function readMobile(path: string): string {
  return readFileSync(join(MOBILE, path), 'utf8');
}

function stringsTsv(): string {
  const locale = JSON.parse(readMobile('i18n/locales/en.json')) as Record<string, unknown>;
  const lines = ['key\tplaceholders'];
  for (const [key, value] of Object.entries(locale)) {
    if (typeof value !== 'string') throw new Error(`English locale value for ${key} is not a string`);
    lines.push(`${key}\t${[...value.matchAll(/%\{p\d+\}/g)].map((found) => found[0]).join(' ')}`);
  }
  return `${lines.join('\n')}\n`;
}

const BUNDLE_FORMAT_MD = `# Bundle format

Version 2. The app reads exactly this version. Any other \`formatVersion\` is rejected before the rest of the file is used, with a message that names the format.

This is the corpus bundle from section 4 of \`docs/design/offline-mobile-app.md\`, with the field names and nullability in \`apps/mobile/src/core/bundle/format.ts\`.

## Files

\`\`\`
bundle.json
bundle.json.sig
documents/<id>.json
\`\`\`

\`bundle.json.sig\` is the raw 64-byte Ed25519 signature of the exact bytes of \`bundle.json\`. The app verifies that signature before it trusts the manifest. An invalid signature rejects the update and leaves the last good content in place.

JSON is UTF-8 with four-space indentation, explicit nulls, and no trailing newline. The signature covers those exact bytes. Every checksum is lower-case hex SHA-256.

## bundle.json

- \`formatVersion\` (number): \`2\`.
- \`sequence\` (integer): strictly increasing per publication. The first bundle is \`1\`. Each later bundle is one greater than the \`sequence\` in the previous \`bundle.json\`.
- \`bundleVersion\` (string): UTC build time, ISO-8601 to the second, for example \`2026-10-05T12:00:00Z\`.
- \`signedBy\` (string): lower-case hex SHA-256 of the raw 32-byte Ed25519 public key that signed this file. The app accepts the bundle when this id names a key on its compiled list of at most two keys and the signature verifies under that key. An unknown id is rejected. Old bundles without this field still verify under the single compiled public key.
- \`documents\` (array): one entry per document.
- \`cards\` (array): quoted action cards. Each card is \`{ id, hazard, phase, country, title, steps }\`. \`phase\` is \`before\`, \`during\`, or \`after\`. Each step is \`{ text, documentId, chunkId }\`. A step is included only when \`chunkId\` is a chunk in the bundle and \`text\` is a verbatim substring of that chunk. Empty when the bundle has no cards. Old bundles without this field have no cards.
- \`model\` (object, optional): omitted when the bundle has no model file. The publisher does not write it until a model is part of the publication. When present:
  - \`file\` (string): bundle-relative path of the model file.
  - \`sha256\` (string): lower-case hex SHA-256 of that file.
  - \`byteSize\` (number): size in bytes.
  - \`modelType\` (string): one of \`gemma4\`, \`gemmaIt\`, \`qwen\`, \`qwen3\`, \`general\`.

Each document:

- \`id\` (string): first 32 hex characters of the SHA-256 of the source URI. Stable across builds. Not blank.
- \`title\` (string).
- \`agency\` (string).
- \`publishedDate\` (string): \`YYYY-MM-DD\`.
- \`reviewedOn\` (string): \`YYYY-MM-DD\`. When the source was last reviewed.
- \`expiresOn\` (string): \`YYYY-MM-DD\`. A document past this date still installs. Expiry is a warning, not a sync failure.
- \`licence\` (string).
- \`country\` (string or null): ISO 3166-1 alpha-2, or null for global guidance.
- \`hazards\` (array of strings): disaster type names.
- \`sourceUri\` (string).
- \`sourceType\` (string): \`pdf\` or \`html\`.
- \`checksum\` (string): SHA-256 of the approved PDF bytes, or of the extracted HTML text.
- \`fileChecksum\` (string): SHA-256 of the exact bytes of \`documents/<id>.json\`.
- \`chunks\` (array of \`{ id, checksum }\`): \`id\` is \`<documentId>-<index>\` with \`index\` starting at 0. \`checksum\` is the SHA-256 of the chunk content in UTF-8.

## documents/<id>.json

- \`id\` (string): the document id. Not blank.
- \`chunks\` (array of \`{ id, content, pageNumber }\`): \`pageNumber\` is the 1-based PDF page, or null for HTML.

The phone does not parse PDFs. The publisher ships the extracted text, and for an HTML source it ships the extracted text. Chunking of that text is already done.

A document file whose SHA-256 is not the manifest \`fileChecksum\` is a format error: the message says the file does not match the manifest. Compute the hash over the bytes read, not over a re-encoded string.

## Sync

The app compares each document's \`fileChecksum\` with the installed copy. It downloads only documents whose file checksum changed, or that are new, and drops documents the manifest no longer lists. The new content replaces the old in one database transaction, which is rolled back unless every document and chunk is stored. A failed or interrupted sync leaves the previous content in place.

A bundle whose \`sequence\` is lower than the installed sequence is rejected. A different bundle with the same \`sequence\` is rejected too. The failure reason contains \`older\`. The same publication already installed (the same \`sequence\` and the same \`bundleVersion\`) is up to date. A document past \`expiresOn\` still installs.

A compiled list holds at most two keys, each \`{ id, publicKeyHex, notAfter }\`. \`notAfter\` is \`YYYY-MM-DD\` and is not part of the bundle. A bundle is accepted when \`signedBy\` names a key on that list and the signature verifies under it. An unknown id is \`rejectedSignature\`. A listed key past \`notAfter\` still verifies. Removing a key from the list is what revokes it.
`;

const isDirectRun = process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isDirectRun) {
  try {
    if (process.argv[2] === '--key-rotation') {
      const fixturesDir = fileURLToPath(new URL('../../../contract/fixtures', import.meta.url));
      await generateKeyRotationFixtures(fixturesDir, fixtureNow);
    } else if (process.argv[2] === '--cards') {
      const fixturesDir = fileURLToPath(new URL('../../../contract/fixtures', import.meta.url));
      await generateCardFixtures(fixturesDir, fixtureNow);
    } else {
      const outDir = process.argv[2] === undefined
        ? fileURLToPath(new URL('../../../contract', import.meta.url))
        : resolve(process.argv[2]);
      await generateFixtures(outDir, fixtureNow);
    }
  } catch (e) {
    console.error(e instanceof Error ? e.message : e);
    process.exitCode = 1;
  }
}
