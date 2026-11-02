import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { chunkPages, chunkUnpaged } from './chunker.ts';
import { pageText } from './text.ts';

const wordStartsOf = (text: string): Set<number> => {
  const starts = new Set<number>();
  for (let pos = 0; pos < text.length; pos++) {
    if ((pos === 0 || text[pos - 1] === ' ') && text[pos] !== ' ') starts.add(pos);
  }
  return starts;
};

const SENTENCES = [
  'The quick brown fox jumps over the lazy dog while onlookers murmur quietly under the afternoon sun.',
  'A programmer often refactors legacy code to remove duplication, improve readability, and reduce future maintenance costs.',
  'Meteorologists predict scattered thunderstorms across the valley tomorrow afternoon, with temperatures dropping sharply overnight.',
  'Historians frequently debate the causes of complex geopolitical conflicts, weighing economic, cultural, and ideological factors.',
  'Chefs carefully balance sweet, sour, salty, and bitter flavors when crafting an unforgettable tasting menu experience.',
  'Astronomers recently discovered an unusually bright exoplanet orbiting a distant binary star system many light-years away.',
];

/** Every chunk must appear in the source in order, and every chunk after the first must start on a word. */
function assertChunksAreWordSafe(longText: string, contents: string[], extra?: (index: number, foundAt: number) => void): void {
  const wordStarts = wordStartsOf(longText);
  let searchFrom = 0;
  contents.forEach((content, index) => {
    const foundAt = longText.indexOf(content, searchFrom);
    assert.ok(foundAt >= 0, `Chunk ${index} not found in source text from ${searchFrom}: [${content}]`);
    if (index > 0) assert.ok(wordStarts.has(foundAt), `Chunk ${index} starts mid-word at ${foundAt}: [${content.slice(0, 30)}...]`);
    extra?.(index, foundAt);
    searchFrom = foundAt + 1;
  });
}

describe('Chunker', () => {
  it('turns short text into one chunk tagged with its page', () => {
    const drafts = chunkPages([pageText(3, 'Boil water for one minute.')]);
    assert.equal(drafts.length, 1);
    assert.equal(drafts[0].content, 'Boil water for one minute.');
    assert.equal(drafts[0].pageNumber, 3);
  });

  it('splits long text without splitting words', () => {
    const drafts = chunkPages([pageText(1, 'word '.repeat(400))]);
    assert.ok(drafts.length > 1);
    for (const d of drafts) {
      assert.ok(!d.content.startsWith(' ') && !d.content.endsWith(' '));
      assert.ok(!d.content.includes('wor d') && !d.content.includes('wo rd'));
    }
  });

  it('never starts a chunk mid-word on realistic prose', () => {
    let text = '';
    for (let i = 0; text.length < 4500; i++) text += (text ? ' ' : '') + SENTENCES[i % SENTENCES.length];
    const drafts = chunkPages([pageText(1, text)]);
    assert.ok(drafts.length > 2);
    assertChunksAreWordSafe(text, drafts.map((d) => d.content));
  });

  it('does not start a chunk inside a long unbroken token near the overlap boundary', () => {
    const words = SENTENCES.join(' ').toLowerCase().replace(/[.,]/g, '').split(' ');
    const prefixLength = 840;
    let prefix = '';
    let wi = 0;
    while (prefix.length + words[wi % words.length].length + 1 <= prefixLength) prefix += words[wi++ % words.length] + ' ';
    const gap = prefixLength - prefix.length;
    if (gap > 0) prefix += 'x'.repeat(gap - 1) + ' ';
    assert.equal(prefix.length, prefixLength);
    const token = 'abcdefghij'.repeat(16).slice(0, 160);
    let suffix = '';
    for (let si = 0; suffix.length < 3500; si++) suffix += words[si % words.length] + ' ';
    const longText = `${prefix}${token} ${suffix.trim()}`;
    assert.equal(longText[839], ' ');
    assert.equal(longText.slice(840, 1000), token);
    assert.equal(longText[1000], ' ');

    const drafts = chunkPages([pageText(1, longText)]);
    assert.ok(drafts.length > 1);
    assert.equal(drafts[0].content, prefix + token); // the token survives intact as the tail of the first chunk
    assertChunksAreWordSafe(longText, drafts.map((d) => d.content), (index, foundAt) => {
      if (index > 0) assert.ok(foundAt < 840 || foundAt >= 1000, `chunk ${index} starts inside the long token at ${foundAt}`);
      if (index === 1) assert.equal(foundAt, 1001); // a bounded forward search would have given 900, mid-token
    });
  });

  it('never silently drops an unbroken run longer than the chunk size', () => {
    const longRun = 'x'.repeat(6000);
    const drafts = chunkPages([pageText(1, `${longRun} normal prose continues after the long run to prove no content was lost here today.`)]);
    assert.equal(drafts[0].content, longRun);
    assert.ok(drafts.length >= 2);
    assert.ok(drafts[1].content.startsWith('normal'));
  });

  it('produces no chunks for a blank page', () => assert.deepEqual(chunkPages([pageText(1, '   \n\n  ')]), []));

  it('keeps chunks from different pages tagged correctly', () => {
    const drafts = chunkPages([pageText(1, 'Page one content.'), pageText(2, 'Page two content.')]);
    assert.deepEqual(drafts.map((d) => d.pageNumber), [1, 2]);
  });

  it('gives unpaged text a null page number', () => {
    const drafts = chunkUnpaged(`Boil water for one minute. ${'word '.repeat(400)}`);
    assert.ok(drafts.length > 1);
    for (const d of drafts) assert.equal(d.pageNumber, null);
  });
});
