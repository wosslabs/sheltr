import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseManifest } from './manifest.ts';

const DOC = (extra = '', uri = 'https://example.org/a.pdf') => `
  - source_uri: ${uri}
    title: Flood guide
    source_agency: Example Agency
    hazards: [FLOOD, EARTHQUAKE]
    published_date: 2021-03-15
    license: CC-BY-4.0 (https://example.org/licence)
    checksum: ABCDEF${extra}`;

const manifest = (body: string, related = '') => `documents:${body}\n${related}`;

describe('parseManifest', () => {
  it('parses documents with both quoted and unquoted dates, hazards in app order', () => {
    const quoted = DOC('', 'https://example.org/b.pdf').replace('published_date: 2021-03-15', 'published_date: "2022-01-02"');
    const m = parseManifest(manifest(DOC() + quoted));
    assert.equal(m.documents.length, 2);
    assert.equal(m.documents[0].publishedDate, '2021-03-15');
    assert.equal(m.documents[1].publishedDate, '2022-01-02');
    assert.deepEqual(m.documents[0].disasterTypes, ['EARTHQUAKE', 'FLOOD']);
    assert.equal(m.documents[0].title, 'Flood guide');
    assert.equal(m.documents[0].checksum, 'ABCDEF');
  });

  it('parses related hazards', () => {
    const m = parseManifest(
      manifest(DOC(), 'related_hazards:\n  - hazard: EARTHQUAKE\n    related_hazard: LANDSLIDE\n    source_uri: https://example.org/a.pdf\n    note: p. 4\n'),
    );
    assert.deepEqual(m.relatedHazards, [
      { hazard: 'EARTHQUAKE', relatedHazard: 'LANDSLIDE', sourceUri: 'https://example.org/a.pdf', note: 'p. 4' },
    ]);
  });

  it('accepts an empty manifest', () => {
    assert.deepEqual(parseManifest('documents: []\nrelated_hazards: []\n'), { documents: [], relatedHazards: [] });
    assert.deepEqual(parseManifest('documents:\n'), { documents: [], relatedHazards: [] });
  });

  it('rejects a duplicate source_uri and names it', () =>
    assert.throws(() => parseManifest(manifest(DOC() + DOC())), /Duplicate source_uri in manifest: https:\/\/example.org\/a.pdf/));

  it('rejects an unknown hazard and names the entry', () =>
    assert.throws(() => parseManifest(manifest(DOC().replace('FLOOD', 'FLOODS'))), /https:\/\/example.org\/a.pdf has unknown hazard 'FLOODS'/));

  it('rejects an entry without a licence', () =>
    assert.throws(() => parseManifest(manifest(DOC().replace(/\n    license:.*/, ''))), /missing 'license'/));

  it('rejects a related hazard whose source is not in the manifest', () =>
    assert.throws(
      () => parseManifest(manifest(DOC(), 'related_hazards:\n  - hazard: FLOOD\n    related_hazard: LANDSLIDE\n    source_uri: https://other.example/x\n')),
      /not a document in the manifest/,
    ));

  it('parses a country code and an html source type', () => {
    const m = parseManifest(manifest(DOC('\n    country: "NO"\n    source_type: html')));
    assert.equal(m.documents[0].country, 'NO');
    assert.equal(m.documents[0].sourceType, 'html');
  });

  it('treats GLOBAL and a missing country as no country, and defaults to pdf', () => {
    assert.equal(parseManifest(manifest(DOC('\n    country: GLOBAL'))).documents[0].country, null);
    const m = parseManifest(manifest(DOC()));
    assert.equal(m.documents[0].country, null);
    assert.equal(m.documents[0].sourceType, 'pdf');
  });

  it('rejects a malformed or unknown country and names the entry', () => {
    for (const bad of ['"no"', '"NOR"', '"ZZ"', '"EU"', '"1A"']) {
      assert.throws(() => parseManifest(manifest(DOC(`\n    country: ${bad}`))), /https:\/\/example.org\/a.pdf has country/);
    }
  });

  it('rejects a country that YAML reads as a number', () =>
    assert.throws(() => parseManifest(manifest(DOC('\n    country: 12'))), /quote the code/));

  it('rejects an unsupported source type', () =>
    assert.throws(() => parseManifest(manifest(DOC('\n    source_type: docx'))), /has source_type 'docx', expected pdf or html/));

  it('rejects an impossible date', () =>
    assert.throws(() => parseManifest(manifest(DOC().replace('2021-03-15', '2021-02-30'))), /expected YYYY-MM-DD/));

  it('rejects a manifest that is not a mapping', () => assert.throws(() => parseManifest('- a\n- b\n'), /must be a YAML mapping/));
});
