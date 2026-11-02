import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { UsageError, parsePublish } from './args.ts';
import { rawContent, type ConnectorMap } from './connector.ts';
import { EXIT_FAILURE, EXIT_OK, EXIT_USAGE, USAGE, runPublisher } from './run.ts';
import { sha256Hex } from './sha256.ts';
import { generateKeyPair } from './signing.ts';
import { collector, makePdf, scratchDir } from './testHelpers.ts';

describe('parsePublish', () => {
  it('parses a full valid command', () =>
    assert.deepEqual(parsePublish(['publish', '--manifest', 'm.yaml', '--out', 'o', '--key', 'k.pem', '--public-key', 'p.pem']),
      { manifest: 'm.yaml', out: 'o', key: 'k.pem', publicKey: 'p.pem' }));
  it('treats the public key as optional', () =>
    assert.equal(parsePublish(['publish', '--manifest', 'm', '--out', 'o', '--key', 'k']).publicKey, null));
  it('rejects missing arguments', () => {
    assert.throws(() => parsePublish([]), UsageError);
    assert.throws(() => parsePublish(['publish', '--manifest', 'm', '--out', 'o']), /missing --key/);
  });
  it('rejects unknown commands and arguments', () => {
    assert.throws(() => parsePublish(['build']), /unknown command 'build'/);
    assert.throws(() => parsePublish(['publish', '--nope', 'x']), /unknown argument '--nope'/);
  });
  it('needs exactly one value per flag', () => {
    assert.throws(() => parsePublish(['publish', '--manifest']), /--manifest needs a value/);
    assert.throws(() => parsePublish(['publish', '--manifest', '--out', 'o']), /--manifest needs a value/);
    assert.throws(() => parsePublish(['publish', '--manifest', ' ', '--out', 'o', '--key', 'k']), /needs a value/);
    assert.throws(() => parsePublish(['publish', '--manifest', 'a', '--manifest', 'b']), /given more than once/);
  });
});

describe('runPublisher', () => {
  const PDF_URI = 'https://example.org/flood.pdf';
  const pdfBytes = new TextEncoder().encode('%PDF fake bytes for the fixture connector');
  let scratch: ReturnType<typeof scratchDir>;
  let work: string;
  let keys: ReturnType<typeof generateKeyPair>;
  const run = async (args: string[], connectorsFor?: () => ConnectorMap) => {
    const out = collector();
    const err = collector();
    const code = await runPublisher(args, work, out, err, connectorsFor);
    return { code, out: out.text(), err: err.text() };
  };
  // A fixture connector: no network, and a "PDF" that the real extractor cannot read, so entries fail at extraction.
  const fixture = (): ConnectorMap => ({ pdf: { fetch: async () => rawContent(pdfBytes) } });
  const writeKeys = () => {
    writeFileSync(join(scratch.dir, 'key.pem'), keys.privateKey.export({ format: 'pem', type: 'pkcs8' }));
    writeFileSync(join(scratch.dir, 'pub.pem'), keys.publicKey.export({ format: 'pem', type: 'spki' }));
  };
  const writeManifest = (checksum = sha256Hex(pdfBytes)) =>
    writeFileSync(join(work, 'manifest.yaml'), `documents:
  - source_uri: ${PDF_URI}
    title: Flood guide
    source_agency: Example Agency
    hazards: [FLOOD]
    published_date: 2024-03-15
    license: CC-BY-4.0
    checksum: ${checksum}
`);

  beforeEach(() => {
    scratch = scratchDir();
    work = join(scratch.dir, 'work');
    mkdirSync(work);
    keys = generateKeyPair();
    writeKeys();
  });
  afterEach(() => scratch.cleanup());

  const args = (extra: string[] = []) => ['publish', '--manifest', 'manifest.yaml', '--out', 'bundle', '--key', join(scratch.dir, 'key.pem'), ...extra];

  it('prints the usage and exits 2 for bad arguments', async () => {
    const r = await run(['publish', '--manifest']);
    assert.equal(r.code, EXIT_USAGE);
    assert.match(r.err, /publisher: --manifest needs a value/);
    assert.ok(r.err.includes(USAGE));
    assert.equal((await run([])).code, EXIT_USAGE);
    assert.match(USAGE, /^usage: publisher publish/);
  });

  it('exits 1 when the manifest is missing', async () => {
    const r = await run(args());
    assert.equal(r.code, EXIT_FAILURE);
    assert.match(r.err, /cannot read manifest/);
  });

  it('reports a failed entry, leaves nothing behind, and exits 1', async () => {
    writeManifest();
    const r = await run(args(), fixture);
    assert.equal(r.code, EXIT_FAILURE);
    assert.match(r.err, /FAILED https:\/\/example.org\/flood.pdf: /);
    assert.match(r.err, /build failed: No document built/);
    assert.ok(!existsSync(join(work, 'bundle')));
  });

  it('exits 1 for a checksum mismatch and names the entry', async () => {
    writeManifest('0'.repeat(64));
    const r = await run(args(), fixture);
    assert.equal(r.code, EXIT_FAILURE);
    assert.match(r.err, /Checksum mismatch for https:\/\/example.org\/flood.pdf/);
  });

  it('fails before any build when the public key cannot be read', async () => {
    writeManifest();
    let connectorsBuilt = false;
    const r = await run(args(['--public-key', join(scratch.dir, 'nope.pem')]), () => { connectorsBuilt = true; return fixture(); });
    assert.equal(r.code, EXIT_FAILURE);
    assert.match(r.err, /cannot read public key/);
    assert.equal(connectorsBuilt, false);
    assert.ok(!existsSync(join(work, 'bundle')));
  });

  it('refuses a key inside the repository and writes nothing', async () => {
    mkdirSync(join(scratch.dir, '.git'));
    writeManifest();
    writeFileSync(join(scratch.dir, 'inside.pem'), readFileSync(join(scratch.dir, 'key.pem')));
    const out = collector();
    const err = collector();
    const code = await runPublisher(
      ['publish', '--manifest', 'manifest.yaml', '--out', 'bundle', '--key', join(scratch.dir, 'inside.pem')],
      work, out, err, fixture,
    );
    assert.equal(code, EXIT_FAILURE);
    assert.match(err.text(), /Refusing to read the signing key from inside the repository/);
    assert.ok(!existsSync(join(work, 'bundle')));
  });

  it('builds, verifies the signature and reports on a manifest served by a fixture', async () => {
    const pdf = makePdf([['Boil water for one minute.']]);
    writeManifest(sha256Hex(pdf));
    const r = await run(args(['--public-key', join(scratch.dir, 'pub.pem')]), () => ({ pdf: { fetch: async () => rawContent(pdf) } }));
    assert.equal(r.code, EXIT_OK);
    assert.match(r.out, /^Bundle version: \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/m);
    assert.match(r.out, /^Documents: 1$/m);
    assert.match(r.out, /^Signature: verified$/m);
    assert.match(r.out, /^Written to: .*bundle$/m);
    assert.ok(existsSync(join(work, 'bundle', 'bundle.json.sig')));
  });

  it('says so when no public key was given', async () => {
    const pdf = makePdf([['Boil water for one minute.']]);
    writeManifest(sha256Hex(pdf));
    const r = await run(args(), () => ({ pdf: { fetch: async () => rawContent(pdf) } }));
    assert.equal(r.code, EXIT_OK);
    assert.match(r.out, /Signature: not verified \(no --public-key given\)/);
  });

  it('fails verification with the wrong public key', async () => {
    const pdf = makePdf([['Boil water for one minute.']]);
    writeManifest(sha256Hex(pdf));
    writeFileSync(join(scratch.dir, 'other.pem'), generateKeyPair().publicKey.export({ format: 'pem', type: 'spki' }));
    const r = await run(args(['--public-key', join(scratch.dir, 'other.pem')]), () => ({ pdf: { fetch: async () => rawContent(pdf) } }));
    assert.equal(r.code, EXIT_FAILURE);
    assert.match(r.err, /bundle signature does not verify/);
  });

  it('never prints the key', async () => {
    writeManifest();
    const r = await run(args(['--public-key', join(scratch.dir, 'pub.pem')]), fixture);
    const secret = keys.privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64');
    assert.ok(!r.out.includes(secret) && !r.err.includes(secret));
  });
});
