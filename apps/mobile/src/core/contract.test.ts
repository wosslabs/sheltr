import * as fs from 'node:fs';
import * as path from 'node:path';
import { parseBundleManifest, parseDocumentFileBytes } from './bundle/parser';
import { hexToBytes } from './bytes';
import { Retriever, sameWord } from './retrieval/retriever';
import { ContentStore } from './store/contentStore';
import { NodeSqliteDatabase } from './testing/nodeSqlite';
import { FetchSyncClient } from './sync/client';
import { SyncEngine } from './sync/engine';
import type { SyncResult } from './sync/result';
import { compileTrustedKeys, keyDatePassed, type TrustedKey } from './trustedKeys';

/**
 * The Expo app is the oracle for the shared contract. Flutter asserts the same fixture table, the same word
 * cases, and the same retrieval bars (docs/evaluation.md).
 */
const contractDir = path.join(__dirname, '..', '..', '..', '..', 'contract');
const fixturesDir = path.join(contractDir, 'fixtures');
const sampleBundleDir = path.join(__dirname, '..', '..', 'assets', 'sample-bundle');
const HTTPS_BASE = 'https://example.org/bundle';

interface FixtureExpect {
  prior: 'empty' | 'valid-signed';
  result: SyncResult['kind'];
  reasonContains: string | null;
  documentsDownloaded: number | null;
}

interface RetrievalCase {
  answerable: boolean;
  country: string | null;
  question: string;
  term: string;
}

function fixtureNames(): string[] {
  return fs
    .readdirSync(fixturesDir)
    .filter((name) => fs.statSync(path.join(fixturesDir, name)).isDirectory())
    .sort();
}

function readExpect(name: string): FixtureExpect {
  return JSON.parse(fs.readFileSync(path.join(fixturesDir, name, 'expect.json'), 'utf8')) as FixtureExpect;
}

function trustedKeysOf(name: string): readonly TrustedKey[] | undefined {
  const file = path.join(fixturesDir, name, 'trusted-keys.json');
  if (!fs.existsSync(file)) return undefined;
  return JSON.parse(fs.readFileSync(file, 'utf8')) as TrustedKey[];
}

function publicKey(name: string): Uint8Array {
  return hexToBytes(fs.readFileSync(path.join(fixturesDir, name, 'public-key.hex'), 'utf8').trim());
}

/** plain-http-url is served from base-url.txt. Every other fixture is served over https. */
function baseUrl(name: string): string {
  const file = path.join(fixturesDir, name, 'base-url.txt');
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim() : HTTPS_BASE;
}

function fileBytes(file: string): Uint8Array {
  return new Uint8Array(fs.readFileSync(file));
}

/** Serves one fixture directory. Paths are the bundle-relative names the sync client requests. */
class FixtureHost {
  private served = new Map<string, Uint8Array>();

  load(name: string): void {
    const dir = path.join(fixturesDir, name);
    this.served.clear();
    this.served.set('bundle.json', fileBytes(path.join(dir, 'bundle.json')));
    this.served.set('bundle.json.sig', fileBytes(path.join(dir, 'bundle.json.sig')));
    const documents = path.join(dir, 'documents');
    if (!fs.existsSync(documents)) return;
    for (const file of fs.readdirSync(documents)) {
      if (!file.endsWith('.json')) continue;
      this.served.set(`documents/${file}`, fileBytes(path.join(documents, file)));
    }
  }

  fetch = async (url: string) => {
    const parts = new URL(url).pathname.split('/').filter((part) => part !== '');
    const rel = parts.slice(1).join('/');
    const body = this.served.get(rel);
    if (body === undefined) return { ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) };
    return {
      ok: true,
      status: 200,
      arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer,
    };
  };
}

function reasonOf(result: SyncResult): string | null {
  return result.kind === 'failed' ? result.reason : null;
}

function documentsDownloaded(result: SyncResult): number | null {
  return result.kind === 'updated' ? result.documentsDownloaded : null;
}

function loadWordCases(): Array<{ left: string; right: string; match: boolean }> {
  return fs
    .readFileSync(path.join(contractDir, 'word-cases.tsv'), 'utf8')
    .split('\n')
    .filter((line) => line.trim() !== '' && !line.startsWith('left\t'))
    .map((line) => {
      const [left, right, match] = line.split('\t');
      return { left, right, match: match === 'true' };
    });
}

function loadRetrievalCases(): RetrievalCase[] {
  return fs
    .readFileSync(path.join(contractDir, 'retrieval-cases.tsv'), 'utf8')
    .split('\n')
    .filter((line) => line.trim() !== '' && !line.startsWith('#'))
    .map((line) => {
      const [kind, country, question, term] = line.split('\t');
      return { answerable: kind === 'answerable', country: country || null, question, term: term ?? '' };
    });
}

async function sampleRetriever(): Promise<Retriever> {
  const store = await ContentStore.open(new NodeSqliteDatabase());
  const manifest = parseBundleManifest(fs.readFileSync(path.join(sampleBundleDir, 'bundle.json'), 'utf8'));
  await store.install(manifest, (document) =>
    parseDocumentFileBytes(fileBytes(path.join(sampleBundleDir, 'documents', `${document.id}.json`))),
  );
  return new Retriever(store);
}

describe('contract fixtures', () => {
  it.each(fixtureNames())('%s matches expect.json', async (name) => {
    const expected = readExpect(name);
    const store = await ContentStore.open(new NodeSqliteDatabase());
    const host = new FixtureHost();
    const engine = new SyncEngine((url) => new FetchSyncClient(url, host.fetch));
    const key = publicKey(name);

    if (expected.prior === 'valid-signed') {
      host.load('valid-signed');
      const prior = await engine.run(HTTPS_BASE, key, store);
      expect(prior.kind).toBe('updated');
    }

    host.load(name);
    const result = await engine.run(baseUrl(name), key, store, trustedKeysOf(name));

    expect(result.kind).toBe(expected.result);
    if (expected.reasonContains !== null) {
      expect(reasonOf(result)).toEqual(expect.stringContaining(expected.reasonContains));
    }
    if (typeof expected.documentsDownloaded === 'number') {
      expect(documentsDownloaded(result)).toBe(expected.documentsDownloaded);
    }
  });
});

describe('contract word cases', () => {
  it.each(loadWordCases())('$left / $right', ({ left, right, match }) => {
    expect(sameWord(left, right)).toBe(match);
  });
});

describe('contract retrieval rules', () => {
  const ruleFiles = ['stopwords.ts', 'vocabulary.ts', 'boost.ts', 'retriever.ts'];

  it.each(ruleFiles)('%s loads its numbers and lists from retrieval-rules.json', (file) => {
    const source = fs.readFileSync(path.join(__dirname, 'retrieval', file), 'utf8');
    expect(source).toMatch(/from ['"][^'"]*contract\/retrieval-rules\.json['"]/);
  });
});

describe('contract retrieval cases', () => {
  it('answers no off-topic question', async () => {
    const retriever = await sampleRetriever();
    const falseAccepts: string[] = [];
    for (const c of loadRetrievalCases().filter((row) => !row.answerable)) {
      if ((await retriever.retrieve({ text: c.question, country: c.country })).kind === 'passages') {
        falseAccepts.push(c.question);
      }
    }
    expect(falseAccepts).toEqual([]);
  });

  it('answers at least 90% of answerable questions and keeps the expected term', async () => {
    const retriever = await sampleRetriever();
    const cases = loadRetrievalCases().filter((row) => row.answerable);
    const refused: string[] = [];
    const missed: string[] = [];
    for (const c of cases) {
      const result = await retriever.retrieve({ text: c.question, country: c.country });
      if (result.kind !== 'passages') refused.push(c.question);
      else if (!result.passages.some((p) => p.chunk.content.toLowerCase().includes(c.term.toLowerCase()))) {
        missed.push(c.question);
      }
    }
    const answered = cases.length - refused.length;
    const found = answered - missed.length;
    // Bars from docs/evaluation.md. Refusing is the safe failure; a wrong answer is not.
    expect(answered).toBeGreaterThanOrEqual(0.9 * cases.length);
    expect(found).toBeGreaterThanOrEqual(0.9 * cases.length);
  });
});

const KEY_DATE_WARNING = 'A trusted signing key is past its date.';

interface TrustedKeyFile {
  id: string;
  publicKeyHex: string;
  notAfter: string;
}

function readTrustedKeys(name: string): TrustedKeyFile[] {
  return JSON.parse(fs.readFileSync(path.join(fixturesDir, name, 'trusted-keys.json'), 'utf8')) as TrustedKeyFile[];
}

function readSignedBy(name: string): string {
  const parsed = JSON.parse(fs.readFileSync(path.join(fixturesDir, name, 'bundle.json'), 'utf8')) as { signedBy?: unknown };
  if (typeof parsed.signedBy !== 'string' || parsed.signedBy === '') {
    throw new Error(`${name} bundle.json has no signedBy`);
  }
  return parsed.signedBy;
}

async function syncFixture(name: string, key: Uint8Array, trustedKeys: readonly TrustedKeyFile[], store: ContentStore): Promise<SyncResult> {
  const host = new FixtureHost();
  host.load(name);
  const engine = new SyncEngine((url) => new FetchSyncClient(url, host.fetch));
  return engine.run(HTTPS_BASE, key, store, trustedKeys);
}

describe('key rotation', () => {
  it('rotatedKeyBundleVerifies', async () => {
    const keys = readTrustedKeys('rotated-key-valid');
    const signedBy = readSignedBy('rotated-key-valid');
    const signer = keys.find((key) => key.id === signedBy);
    const other = keys.find((key) => key.id !== signedBy);
    expect(signer).toBeDefined();
    expect(other).toBeDefined();
    expect(keys).toHaveLength(2);

    const store = await ContentStore.open(new NodeSqliteDatabase());
    const result = await syncFixture('rotated-key-valid', hexToBytes(other!.publicKeyHex), keys, store);

    expect(result.kind).toBe('updated');
    expect(() => compileTrustedKeys([
      ...keys,
      { id: 'c'.repeat(64), publicKeyHex: 'ab'.repeat(32), notAfter: '2029-01-01' },
    ])).toThrow(/at most two/);
  });

  it('unknownKeyIdRejected', async () => {
    const keys = readTrustedKeys('revoked-key');
    const signedBy = readSignedBy('revoked-key');
    expect(keys.some((key) => key.id === signedBy)).toBe(false);

    const store = await ContentStore.open(new NodeSqliteDatabase());
    const result = await syncFixture('revoked-key', publicKey('revoked-key'), keys, store);

    expect(result.kind).toBe('rejectedSignature');
  });

  it('listedKeyPastNotAfterStillInstallsAndWarns', async () => {
    const signedBy = readSignedBy('rotated-key-valid');
    const keys = readTrustedKeys('rotated-key-valid').map((key) => ({ ...key, notAfter: '2020-01-01' }));
    const other = keys.find((key) => key.id !== signedBy);
    expect(other).toBeDefined();

    const store = await ContentStore.open(new NodeSqliteDatabase());
    const result = await syncFixture('rotated-key-valid', hexToBytes(other!.publicKeyHex), keys, store);
    expect(result.kind).toBe('updated');

    const documents = await store.documents();
    const boil = documents.find((document) => document.title === 'Boil water');
    expect(boil).toBeDefined();
    const chunks = await store.chunks(boil!.id);
    expect(chunks.some((chunk) => chunk.content.includes('Boil water'))).toBe(true);

    expect(keyDatePassed(keys, '2026-11-02')).toBe(true);
    expect(keyDatePassed(keys.map((key) => ({ ...key, notAfter: '2026-11-02' })), '2026-11-02')).toBe(false);

    for (const locale of ['en', 'es', 'fr']) {
      const catalog = JSON.parse(
        fs.readFileSync(path.join(__dirname, '..', 'i18n', 'locales', `${locale}.json`), 'utf8'),
      ) as Record<string, string>;
      expect(catalog.about_key_date_passed.trim().length).toBeGreaterThan(0);
    }
    const english = JSON.parse(
      fs.readFileSync(path.join(__dirname, '..', 'i18n', 'locales', 'en.json'), 'utf8'),
    ) as Record<string, string>;
    expect(english.about_key_date_passed).toBe(KEY_DATE_WARNING);
  });
});
