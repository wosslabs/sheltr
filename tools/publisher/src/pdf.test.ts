import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { extractPdfPages, pdfConnector } from './pdf.ts';
import { sha256Hex } from './sha256.ts';
import { makePdf, startServer } from './testHelpers.ts';

describe('extractPdfPages', () => {
  it('extracts text per page from a real PDF, numbered from 1', async () => {
    const pages = await extractPdfPages(makePdf([['Boil water for one minute.', 'Store it in a clean container.'], ['Evacuate when told to.']]));
    assert.equal(pages.length, 2);
    assert.deepEqual(pages.map((p) => p.pageNumber), [1, 2]);
    assert.match(pages[0].text, /Boil water for one minute\./);
    assert.match(pages[0].text, /Store it in a clean container\./);
    assert.match(pages[1].text, /Evacuate when told to\./);
    assert.ok(!pages[1].text.includes('Boil'));
  });

  it('keeps the caller bytes intact', async () => {
    const bytes = makePdf([['Text.']]);
    const copy = bytes.slice();
    await extractPdfPages(bytes);
    assert.deepEqual(bytes, copy);
  });

  it('fails clearly for bytes that are not a PDF', async () =>
    assert.rejects(extractPdfPages(new TextEncoder().encode('not a pdf')), /Failed to extract PDF content/));
});

describe('pdfConnector', () => {
  const pdf = makePdf([['Hello.']]);
  let server: Awaited<ReturnType<typeof startServer>>;
  before(async () => {
    server = await startServer((req, res) => {
      if (req.url === '/guide.pdf') { res.writeHead(200, { 'content-type': 'application/pdf' }); res.end(pdf); }
      else if (req.url === '/moved.pdf') { res.writeHead(301, { location: '/guide.pdf' }); res.end(); }
      else { res.writeHead(404); res.end(); }
    });
  });
  after(() => server.close());
  const loopback = { allows: (uri: string | null | undefined) => uri?.startsWith('http://127.0.0.1') === true };

  it('returns the bytes and their SHA-256', async () => {
    const content = await pdfConnector(loopback).fetch(`${server.origin}/guide.pdf`);
    assert.deepEqual(content.bytes, pdf);
    assert.equal(content.checksum, sha256Hex(pdf));
  });

  it('does not follow redirects', async () => {
    server.paths.length = 0;
    await assert.rejects(pdfConnector(loopback).fetch(`${server.origin}/moved.pdf`), /HTTP 301/);
    assert.deepEqual(server.paths, ['/moved.pdf']);
  });

  it('fails on not found and on a denied URI without a request', async () => {
    await assert.rejects(pdfConnector(loopback).fetch(`${server.origin}/missing.pdf`), /HTTP 404/);
    server.paths.length = 0;
    await assert.rejects(pdfConnector({ allows: () => false }).fetch(`${server.origin}/guide.pdf`), /Source host not allowed/);
    assert.deepEqual(server.paths, []);
  });
});
