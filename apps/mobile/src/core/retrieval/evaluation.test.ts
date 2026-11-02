import * as fs from 'node:fs';
import * as path from 'node:path';
import { parseBundleManifest, parseDocumentFileBytes } from '../bundle/parser';
import { ContentStore } from '../store/contentStore';
import { NodeSqliteDatabase } from '../testing/nodeSqlite';
import { Retriever } from './retriever';

/**
 * Measures keyword retrieval on the shipped sample bundle with a fixed question set
 * (src/core/testing/retrieval-eval.tsv). It is a regression gate for the two properties that matter:
 *
 * - false accepts (an off-topic question that gets an answer) must stay at zero: the app must refuse rather than
 *   present unrelated guidance;
 * - at least 90% of answerable questions must be answered, with the passage that contains the expected term in the
 *   top results. A refusal is the safe failure, a wrong answer is not.
 *
 * Grow the question set whenever the corpus grows. The set is small: it guards against regressions, it does not
 * prove quality on questions nobody has thought of. Results: docs/evaluation.md.
 */
interface Case { answerable: boolean; country: string | null; question: string; term: string }

const bundleDir = path.join(__dirname, '..', '..', '..', 'assets', 'sample-bundle');

function loadCases(): Case[] {
  return fs
    .readFileSync(path.join(__dirname, '..', 'testing', 'retrieval-eval.tsv'), 'utf8')
    .split('\n')
    .filter((line) => line.trim() !== '' && !line.startsWith('#'))
    .map((line) => {
      const [kind, country, question, term] = line.split('\t');
      return { answerable: kind === 'answerable', country: country || null, question, term: term ?? '' };
    });
}

async function setup(): Promise<Retriever> {
  const store = await ContentStore.open(new NodeSqliteDatabase());
  const manifest = parseBundleManifest(fs.readFileSync(path.join(bundleDir, 'bundle.json'), 'utf8'));
  await store.install(manifest, (document) =>
    parseDocumentFileBytes(new Uint8Array(fs.readFileSync(path.join(bundleDir, 'documents', `${document.id}.json`)))),
  );
  return new Retriever(store);
}

describe('retrieval evaluation on the sample bundle', () => {
  it('answers no off-topic question', async () => {
    const retriever = await setup();
    const falseAccepts: string[] = [];
    for (const c of loadCases().filter((x) => !x.answerable)) {
      if ((await retriever.retrieve({ text: c.question, country: c.country })).kind === 'passages') falseAccepts.push(c.question);
    }
    expect(falseAccepts).toEqual([]);
  });

  it('answers most answerable questions with the expected passage', async () => {
    const retriever = await setup();
    const cases = loadCases().filter((x) => x.answerable);
    const refused: string[] = [];
    const missed: string[] = [];
    for (const c of cases) {
      const result = await retriever.retrieve({ text: c.question, country: c.country });
      if (result.kind !== 'passages') refused.push(c.question);
      else if (!result.passages.some((p) => p.chunk.content.toLowerCase().includes(c.term.toLowerCase()))) missed.push(c.question);
    }
    const answered = cases.length - refused.length;
    const found = answered - missed.length;
    console.log(`EVAL answerable=${cases.length} answered=${answered} foundExpectedPassage=${found}`);
    console.log(`EVAL refused: ${JSON.stringify(refused)}`);
    console.log(`EVAL missed: ${JSON.stringify(missed)}`);
    // Refusing is the safe failure, so the bars are below 100%. They match docs/evaluation.md; raise them when
    // retrieval improves, and never lower them to make a change pass.
    expect(answered).toBeGreaterThanOrEqual(0.9 * cases.length);
    expect(found).toBeGreaterThanOrEqual(0.9 * cases.length);
  });
});
