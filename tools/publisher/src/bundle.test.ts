import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  BUNDLE_FORMAT_VERSION, BundleBuildError, buildBundle, documentId, encodeBundleJson,
  type BundleManifest, type DocumentFile,
} from './bundle.ts';
import { rawContent, type ConnectorMap, type SourceConnector } from './connector.ts';
import { parseManifest, type CorpusManifest } from './manifest.ts';
import { hostAllowlistPolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { generateKeyPair, verify } from './signing.ts';
import { makePdf, scratchDir } from './testHelpers.ts';

const PDF_URI = 'https://example.org/flood.pdf';
const HTML_URI = 'https://example.org/storms';
const pdfBytes = makePdf([['Boil water for one minute.'], ['Evacuate immediately if instructed.']]);
const htmlText = 'Winter storms\nCheck the forecast before you travel.';

const htmlBytes = new TextEncoder().encode(htmlText);
const manifestYaml = (pdfChecksum: string, htmlChecksum: string, extra = '') => `
documents:
  - source_uri: ${PDF_URI}
    title: Flood guide
    source_agency: Example Agency
    hazards: [FLOOD]
    published_date: 2024-03-15
    license: CC-BY-4.0
    country: "NO"
    checksum: ${pdfChecksum}
  - source_uri: ${HTML_URI}
    source_type: html
    title: Storms
    source_agency: Example Agency
    hazards: [WINTER_STORM, FLOOD]
    published_date: 2025-01-02
    license: OGL
    checksum: ${htmlChecksum}
${extra}`;

const policy = hostAllowlistPolicy(['example.org']);
const connectors = (requests: string[] = []): ConnectorMap => {
  const serve = (bytes: Uint8Array): SourceConnector => ({
    async fetch(uri) {
      requests.push(uri);
      return rawContent(bytes);
    },
  });
  return { pdf: serve(pdfBytes), html: serve(htmlBytes) };
};

describe('buildBundle', () => {
  let scratch: ReturnType<typeof scratchDir>;
  let keyPath: string;
  let publicKey: ReturnType<typeof generateKeyPair>['publicKey'];
  let manifest: CorpusManifest;
  const fixedNow = () => new Date('2026-10-05T12:00:00.789Z');
  const out = () => join(scratch.dir, 'bundle');

  beforeEach(() => {
    scratch = scratchDir();
    const keys = generateKeyPair();
    publicKey = keys.publicKey;
    keyPath = join(scratch.dir, 'key.pem');
    writeFileSync(keyPath, keys.privateKey.export({ format: 'pem', type: 'pkcs8' }));
    manifest = parseManifest(manifestYaml(sha256Hex(pdfBytes), sha256Hex(htmlBytes)));
  });
  afterEach(() => scratch.cleanup());

  it('builds a signed bundle from one PDF and one HTML entry', async () => {
    const result = await buildBundle(manifest, connectors(), policy, out(), keyPath, fixedNow);

    assert.deepEqual(result.failures, []);
    assert.equal(result.outputDir, out());
    const bundleBytes = readFileSync(join(out(), 'bundle.json'));
    const signature = readFileSync(join(out(), 'bundle.json.sig'));
    assert.equal(signature.length, 64);
    assert.ok(verify(publicKey, bundleBytes, signature));

    const bundle = JSON.parse(bundleBytes.toString('utf8')) as BundleManifest;
    assert.equal(bundle.formatVersion, BUNDLE_FORMAT_VERSION);
    assert.equal(bundle.bundleVersion, '2026-10-05T12:00:00Z');
    assert.equal(bundle.documents.length, 2);

    const [flood, storms] = bundle.documents;
    assert.equal(flood.id, documentId(PDF_URI));
    assert.deepEqual([flood.title, flood.agency, flood.publishedDate, flood.licence, flood.country, flood.sourceType],
      ['Flood guide', 'Example Agency', '2024-03-15', 'CC-BY-4.0', 'NO', 'pdf']);
    assert.deepEqual(flood.hazards, ['FLOOD']);
    assert.equal(flood.checksum, sha256Hex(pdfBytes));
    assert.deepEqual(storms.hazards, ['FLOOD', 'WINTER_STORM']);
    assert.equal(storms.country, null);
    assert.equal(storms.sourceType, 'html');

    // Every document file matches its fileChecksum and chunk checksums, and carries the right page numbers.
    for (const doc of bundle.documents) {
      const fileBytes = readFileSync(join(out(), 'documents', `${doc.id}.json`));
      assert.equal(sha256Hex(fileBytes), doc.fileChecksum);
      const file = JSON.parse(fileBytes.toString('utf8')) as DocumentFile;
      assert.deepEqual(file.chunks.map((c) => c.id), doc.chunks.map((c) => c.id));
      file.chunks.forEach((c, i) => assert.equal(sha256Hex(c.content), doc.chunks[i].checksum));
    }
    const floodFile = JSON.parse(readFileSync(join(out(), 'documents', `${flood.id}.json`), 'utf8')) as DocumentFile;
    assert.deepEqual(floodFile.chunks.map((c) => c.pageNumber), [1, 2]);
    const stormFile = JSON.parse(readFileSync(join(out(), 'documents', `${storms.id}.json`), 'utf8')) as DocumentFile;
    assert.deepEqual(stormFile.chunks.map((c) => c.pageNumber), [null]);
    assert.equal(stormFile.chunks[0].content, htmlText);
  });

  it('compares the approved checksum case-insensitively', async () => {
    const upper = parseManifest(manifestYaml(sha256Hex(pdfBytes).toUpperCase(), sha256Hex(htmlBytes).toUpperCase()));
    const result = await buildBundle(upper, connectors(), policy, out(), keyPath, fixedNow);
    assert.deepEqual(result.failures, []);
    assert.equal(result.manifest.documents[0].checksum, sha256Hex(pdfBytes)); // stored lower-case
  });

  it('fails only the entry whose checksum does not match', async () => {
    const bad = parseManifest(manifestYaml('0'.repeat(64), sha256Hex(htmlBytes)));
    const result = await buildBundle(bad, connectors(), policy, out(), keyPath, fixedNow);
    assert.equal(result.manifest.documents.length, 1);
    assert.equal(result.failures.length, 1);
    assert.equal(result.failures[0].sourceUri, PDF_URI);
    assert.match(result.failures[0].reason, /Checksum mismatch.*needs re-approval/s);
  });

  it('refuses an entry the policy denies, without a request', async () => {
    const requests: string[] = [];
    const result = await buildBundle(manifest, connectors(requests), hostAllowlistPolicy(['other.example']), out(), keyPath, fixedNow)
      .catch((e: BundleBuildError) => e);
    assert.ok(result instanceof BundleBuildError);
    assert.equal(result.failures.length, 2);
    assert.match(result.failures[0].reason, /Source host not allowed: example\.org/);
    assert.deepEqual(requests, []);
  });

  it('fails an entry with no text', async () => {
    const empty: ConnectorMap = { ...connectors(), html: { fetch: async () => rawContent(new Uint8Array(0)) } };
    const m = parseManifest(manifestYaml(sha256Hex(pdfBytes), sha256Hex(new Uint8Array(0))));
    const result = await buildBundle(m, empty, policy, out(), keyPath, fixedNow);
    assert.equal(result.manifest.documents.length, 1);
    assert.match(result.failures[0].reason, /No extractable text in https:\/\/example.org\/storms\. The page has no main text\./);
  });

  it('fails the signature check when the bundle is tampered with', async () => {
    await buildBundle(manifest, connectors(), policy, out(), keyPath, fixedNow);
    const bytes = readFileSync(join(out(), 'bundle.json'));
    const signature = readFileSync(join(out(), 'bundle.json.sig'));
    bytes[bytes.length - 5] ^= 1;
    assert.ok(!verify(publicKey, bytes, signature));
  });

  it('leaves no output when the key is unreadable', async () => {
    await assert.rejects(buildBundle(manifest, connectors(), policy, out(), join(scratch.dir, 'missing.pem')), /Cannot read the signing key/);
    assert.ok(!existsSync(out()));
    assert.deepEqual(readdirSync(scratch.dir).filter((n) => n.includes('.tmp-')), []);
  });

  it('leaves no output when no entry builds', async () => {
    const bad = parseManifest(manifestYaml('0'.repeat(64), '1'.repeat(64)));
    await assert.rejects(buildBundle(bad, connectors(), policy, out(), keyPath), /No document built; nothing to publish/);
    assert.ok(!existsSync(out()));
    assert.deepEqual(readdirSync(scratch.dir).filter((n) => n.includes('.tmp-')), []);
  });

  it('does not overwrite an existing output', async () => {
    writeFileSync(out(), 'in the way');
    await assert.rejects(buildBundle(manifest, connectors(), policy, out(), keyPath), /Output already exists/);
    assert.equal(readFileSync(out(), 'utf8'), 'in the way');
  });

  it('gives the same document ids across builds', async () => {
    const first = await buildBundle(manifest, connectors(), policy, out(), keyPath, fixedNow);
    const second = await buildBundle(manifest, connectors(), policy, join(scratch.dir, 'bundle2'), keyPath, fixedNow);
    assert.deepEqual(first.manifest.documents.map((d) => d.id), second.manifest.documents.map((d) => d.id));
    // Same inputs and clock: the bundle is identical byte for byte (Ed25519 signing is deterministic too).
    assert.deepEqual(readFileSync(join(out(), 'bundle.json')), readFileSync(join(scratch.dir, 'bundle2', 'bundle.json')));
    assert.deepEqual(readFileSync(join(out(), 'bundle.json.sig')), readFileSync(join(scratch.dir, 'bundle2', 'bundle.json.sig')));
  });
});

describe('bundle format', () => {
  const sampleDir = fileURLToPath(new URL('../../../apps/mobile/assets/sample-bundle/', import.meta.url));

  it('re-encodes the files the Kotlin publisher wrote to the exact same bytes', () => {
    const manifestBytes = readFileSync(join(sampleDir, 'bundle.json'));
    const manifest = JSON.parse(manifestBytes.toString('utf8')) as BundleManifest;
    assert.deepEqual(Buffer.from(encodeBundleJson(manifest)), manifestBytes);
    for (const doc of manifest.documents) {
      const fileBytes = readFileSync(join(sampleDir, 'documents', `${doc.id}.json`));
      assert.deepEqual(Buffer.from(encodeBundleJson(JSON.parse(fileBytes.toString('utf8')) as DocumentFile)), fileBytes);
      assert.equal(sha256Hex(fileBytes), doc.fileChecksum);
      assert.equal(documentId(doc.sourceUri), doc.id);
    }
  });
});
