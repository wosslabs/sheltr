import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { chunkDraft } from './chunker.ts';
import { clean, isBlank, javaTrim, pageText } from './text.ts';

describe('clean', () => {
  it('collapses repeated whitespace', () => assert.equal(clean('Boil   water    for one   minute.'), 'Boil water for one minute.'));
  it('collapses excessive blank lines', () => assert.equal(clean('Line one.\n\n\n\n\nLine two.'), 'Line one.\n\nLine two.'));
  it('trims leading and trailing whitespace', () => assert.equal(clean('   Boil water.   '), 'Boil water.'));
  it('trims control characters like Java', () => assert.equal(clean('\u0001\u0002 text \u0003'), 'text'));
});

describe('javaTrim and isBlank', () => {
  it('trims every character up to the space', () => assert.equal(javaTrim('\u0000\t x \n'), 'x'));
  it('keeps non-breaking spaces, as Java does', () => assert.equal(javaTrim(' x '), ' x '));
  it('treats whitespace but not a non-breaking space as blank', () => {
    assert.equal(isBlank('  \n\t '), true);
    assert.equal(isBlank(' '), false);
    assert.equal(isBlank(''), true);
    assert.equal(isBlank('a'), false);
  });
});

describe('pageText', () => {
  it('accepts page one', () => assert.equal(pageText(1, '').pageNumber, 1));
  it('rejects page zero', () => assert.throws(() => pageText(0, 'text'), /pageNumber must be >= 1/));
});

describe('chunkDraft', () => {
  it('rejects blank content', () => assert.throws(() => chunkDraft('  \n ', 1), /ChunkDraft content must not be blank/));
  it('allows a null page number for HTML', () => assert.equal(chunkDraft('Some HTML text.', null).pageNumber, null));
  it('rejects page zero', () => assert.throws(() => chunkDraft('text', 0), /pageNumber must be >= 1/));
});
