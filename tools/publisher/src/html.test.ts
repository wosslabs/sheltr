import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { charsetOf, decodeBody, extractMainContent, htmlConnector, MAX_REDIRECTS } from './html.ts';
import { hostAllowlistPolicy, type SourcePolicy } from './policy.ts';
import { sha256Hex } from './sha256.ts';
import { startServer } from './testHelpers.ts';

const fixture = (name: string): string => readFileSync(fileURLToPath(new URL(`../test/fixtures/${name}`, import.meta.url)), 'utf8');

describe('extractMainContent', () => {
  it('prefers main and drops chrome and the cookie banner', () => {
    const text = extractMainContent(fixture('guide-with-chrome.html'));
    assert.match(text, /Prepare for floods/);
    assert.match(text, /Move valuables and important papers to an upper floor\./);
    assert.match(text, /Sign up to flood warnings from the Environment Agency\./);
    for (const gone of ['Home', 'Footer links', 'Site header text', 'We use cookies', 'tracking', 'window.dataLayer', 'Enable JavaScript']) {
      assert.ok(!text.includes(gone), `should not contain ${gone}`);
    }
  });

  it('falls back to article when there is no main', () =>
    assert.equal(extractMainContent(fixture('article-page.html')), 'Winter storms\nCheck the forecast before you travel.'));

  it('falls back to body and drops nav and footer', () =>
    assert.equal(extractMainContent(fixture('body-only-page.html')), 'Keep a torch and spare batteries at home.'));

  it('has no whitespace runs or blank lines', () => {
    const text = extractMainContent(fixture('guide-with-chrome.html'));
    assert.doesNotMatch(text, /[ \t]{2,}/);
    assert.doesNotMatch(text, /\n\s*\n/);
    assert.ok(!/^[ \n]|[ \n]$/.test(text));
  });

  it('is stable for the same input and ignores source formatting', () => {
    const html = fixture('guide-with-chrome.html');
    const reformatted = html.replaceAll('\n', '\n\n   ').replaceAll('   ', '\t \t');
    const first = extractMainContent(html);
    assert.equal(extractMainContent(html), first);
    assert.equal(extractMainContent(reformatted), first);
  });

  it('returns an empty string when there is no text', () =>
    assert.equal(extractMainContent('<html><body><nav>Only navigation</nav></body></html>'), ''));

  it('drops cookie, nav and header elements inside main and article', () => {
    const text = extractMainContent(fixture('chrome-inside-main.html'));
    for (const kept of ['Prepare for floods', 'Move valuables to an upper floor.', 'Article paragraph kept.']) assert.match(text, new RegExp(kept));
    for (const gone of ['We use cookies to track you.', 'Accept all cookies.', 'Cookie settings.', 'Site header inside main.', 'Article header.', 'Main navigation links.', 'Related links.']) {
      assert.ok(!text.includes(gone), `should not contain ${gone}`);
    }
  });

  it('drops cookie, nav and header elements inside an article', () =>
    assert.equal(extractMainContent(fixture('chrome-inside-article.html')), 'Winter storms\nCheck the forecast before you travel.'));

  it('falls through to article when main has no text after cleaning', () =>
    assert.equal(extractMainContent('<html><body><main><nav>Menu only</nav></main><article><p>Article text</p></article></body></html>'), 'Article text'));

  it('falls through to body when main and article have no text after cleaning', () =>
    assert.equal(
      extractMainContent('<html><body><main><nav>Menu only</nav></main><article><header>Site header</header></article><p>Body text</p></body></html>'),
      'Body text',
    ));

  it('does not remove a cookie-classed body or main itself', () => {
    assert.equal(extractMainContent('<html><body class="cookie-page"><p>Body text</p></body></html>'), 'Body text');
    assert.equal(extractMainContent('<html><body><main class="cookie-wall"><p>Kept text</p></main></body></html>'), 'Kept text');
  });

  it('puts table cells on one line separated by a space', () =>
    assert.equal(extractMainContent('<body><table><tr><td>A</td><td>B</td></tr></table></body>'), 'A B'));
});

describe('charset handling', () => {
  it('reads the declared charset and falls back to UTF-8', () => {
    assert.equal(charsetOf(null), 'utf-8');
    assert.equal(charsetOf('text/html'), 'utf-8');
    assert.equal(charsetOf('text/html; charset=ISO-8859-1'), 'ISO-8859-1');
    assert.equal(charsetOf('text/html; charset="utf-8"'), 'utf-8');
    assert.equal(charsetOf('text/html; charset=not-a-charset'), 'utf-8');
  });
  it('decodes the body with it', () => {
    const latin1 = Uint8Array.from([0x43, 0x61, 0x66, 0xe9]);
    assert.equal(decodeBody(latin1, 'text/html; charset=ISO-8859-1'), 'Café');
    assert.equal(decodeBody(new TextEncoder().encode('Café'), null), 'Café');
  });
});

describe('htmlConnector', () => {
  const page = fixture('guide-with-chrome.html');
  let server: Awaited<ReturnType<typeof startServer>>;
  const html = (res: import('node:http').ServerResponse, body: string | Uint8Array, contentType = 'text/html; charset=utf-8') => {
    res.writeHead(200, { 'content-type': contentType });
    res.end(body);
  };
  const redirect = (res: import('node:http').ServerResponse, status: number, location: string | null) => {
    res.writeHead(status, location === null ? {} : { location });
    res.end();
  };

  before(async () => {
    server = await startServer((req, res) => {
      switch (req.url) {
        case '/page': return html(res, page);
        case '/moved': return redirect(res, 301, '/page');
        case '/hop1': return redirect(res, 301, '/hop2');
        case '/hop2': return redirect(res, 302, '/hop3');
        case '/hop3': return redirect(res, 307, '/page');
        case '/r1': return redirect(res, 301, '/r2');
        case '/r2': return redirect(res, 302, '/r3');
        case '/r3': return redirect(res, 307, '/r4');
        case '/r4': return redirect(res, 308, '/page');
        case '/noloc': return redirect(res, 302, null);
        case '/offsite': return redirect(res, 302, server.origin.replace('127.0.0.1', 'localhost') + '/page');
        case '/to-evil': return redirect(res, 302, 'https://evil.example/page');
        case '/to-http': return redirect(res, 302, 'http://127.0.0.1/page');
        case '/to-userinfo': return redirect(res, 302, 'https://user:secret@www.ready.gov/page');
        case '/to-allowed': return redirect(res, 302, 'https://www.ready.gov/page');
        case '/empty': return html(res, '<html><body><nav>Only nav</nav></body></html>');
        case '/latin1': return html(res, Uint8Array.from([...Buffer.from('<body><p>Caf'), 0xe9, ...Buffer.from(' écoles advice.</p></body>', 'latin1')]), 'text/html; charset=ISO-8859-1');
        case '/bogus': return html(res, '<body><p>Café advice.</p></body>', 'text/html; charset=not-a-charset');
        case '/nocharset': return html(res, '<body><p>Café advice.</p></body>', 'text/html');
        case '/slow': return void setTimeout(() => html(res, page), 3_000);
        default: res.writeHead(404); res.end('not here');
      }
    });
  });
  after(() => server.close());

  const loopback: SourcePolicy = { allows: (uri) => uri?.startsWith('http://127.0.0.1') === true };
  /** Loopback may be requested; everything else, including redirect targets, goes through the real policy. */
  const mixed = (...hosts: string[]): SourcePolicy => {
    const real = hostAllowlistPolicy(hosts);
    return { allows: (uri) => (uri?.startsWith('http://127.0.0.1:') ?? false) || real.allows(uri) };
  };
  const url = (path: string) => `${server.origin}${path}`;
  const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes);
  const expected = extractMainContent(page);
  const reset = () => { server.paths.length = 0; };

  it('returns the extracted text and the checksum of that text', async () => {
    reset();
    const content = await htmlConnector(loopback).fetch(url('/page'));
    assert.equal(text(content.bytes), expected);
    assert.equal(content.checksum, sha256Hex(new TextEncoder().encode(expected)));
  });

  it('follows a redirect on the same host', async () => {
    reset();
    assert.equal(text((await htmlConnector(loopback).fetch(url('/moved'))).bytes), expected);
    assert.deepEqual(server.paths, ['/moved', '/page']);
  });

  it('follows exactly three redirects', async () => {
    reset();
    assert.equal(text((await htmlConnector(loopback).fetch(url('/hop1'))).bytes), expected);
    assert.deepEqual(server.paths, ['/hop1', '/hop2', '/hop3', '/page']);
    assert.equal(MAX_REDIRECTS, 3);
  });

  it('refuses after three redirects', async () => {
    reset();
    await assert.rejects(htmlConnector(loopback).fetch(url('/r1')), /Too many redirects/);
    assert.deepEqual(server.paths, ['/r1', '/r2', '/r3', '/r4']);
  });

  it('refuses a redirect to a host off the allowlist without a second request', async () => {
    reset();
    await assert.rejects(htmlConnector(loopback).fetch(url('/offsite')), /Redirect.*localhost/s);
    assert.deepEqual(server.paths, ['/offsite']);
  });

  it('fails when a redirect has no Location header', async () => {
    reset();
    await assert.rejects(htmlConnector(loopback).fetch(url('/noloc')), /Redirect without a Location header/);
    assert.deepEqual(server.paths, ['/noloc']);
  });

  it('makes no request when the real policy denies the initial URL', async () => {
    reset();
    const connector = htmlConnector(hostAllowlistPolicy(['www.ready.gov']));
    await assert.rejects(connector.fetch(url('/page')), /Source host not allowed: 127\.0\.0\.1/);
    await assert.rejects(connector.fetch('https://evil.example/page'), /Source host not allowed: evil\.example/);
    assert.deepEqual(server.paths, []);
  });

  it('rejects redirect targets the real policy denies: other host, plain http, credentials', async () => {
    const connector = htmlConnector(mixed('www.ready.gov'));
    await assert.rejects(connector.fetch(url('/to-evil')), /Redirect to a disallowed target.*evil\.example/s);
    await assert.rejects(connector.fetch(url('/to-http')), /Redirect to a disallowed target/);
    await assert.rejects(connector.fetch(url('/to-userinfo')), (e: Error) => {
      assert.match(e.message, /must not contain credentials/);
      assert.ok(!e.message.includes('secret'));
      return true;
    });
  });

  it('accepts a redirect target on an allowed host (and then fails to connect, not on policy)', async () => {
    const connector = htmlConnector(mixed('www.ready.gov'), 2_000);
    await assert.rejects(connector.fetch(url('/to-allowed')), (e: Error) => !/disallowed/.test(e.message));
  });

  it('fails on not found', async () => assert.rejects(htmlConnector(loopback).fetch(url('/missing')), /HTTP 404/));

  it('returns empty content for a page with no main text', async () => {
    const content = await htmlConnector(loopback).fetch(url('/empty'));
    assert.equal(content.bytes.length, 0);
    assert.equal(content.checksum, sha256Hex(new Uint8Array(0)));
  });

  it('decodes ISO-8859-1, and falls back to UTF-8 for an unknown or missing charset', async () => {
    assert.equal(text((await htmlConnector(loopback).fetch(url('/latin1'))).bytes), 'Café écoles advice.');
    assert.equal(text((await htmlConnector(loopback).fetch(url('/bogus'))).bytes), 'Café advice.');
    assert.equal(text((await htmlConnector(loopback).fetch(url('/nocharset'))).bytes), 'Café advice.');
  });

  it('times out a slow server', async () => {
    await assert.rejects(htmlConnector(loopback, 300).fetch(url('/slow')), /Failed to download/);
  });
});
