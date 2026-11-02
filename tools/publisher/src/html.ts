import * as cheerio from 'cheerio';
import type { AnyNode, Element } from 'domhandler';
import { rawContent, getOnce, REQUEST_TIMEOUT_MS, type RawContent, type SourceConnector } from './connector.ts';
import { rejectionMessage, type SourcePolicy } from './policy.ts';
import { javaTrim } from './text.ts';

const REMOVED_TAGS = new Set(['nav', 'footer', 'header', 'script', 'style', 'noscript']);
const BLOCK_TAGS = new Set([
  'address', 'article', 'aside', 'blockquote', 'br', 'dd', 'div', 'dl', 'dt', 'figcaption', 'figure',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'li', 'main', 'ol', 'p', 'pre', 'section', 'table',
  'tbody', 'thead', 'tfoot', 'tr', 'ul',
]);
// Java's \s plus the non-breaking space, written out so the result does not depend on the engine's Unicode spaces.
const HORIZONTAL_WHITESPACE = /[ \t\n\v\f\r ]+/g;

/**
 * Extracts the main readable text of an HTML page. The first of `main`, `article` and `body` whose text is
 * non-empty after cleaning is used; a candidate that is missing or empty is skipped. Within the chosen root,
 * descendants that are navigation, header, footer, scripts, styles or cookie-consent elements are removed. The
 * root itself is never removed. Block elements become line breaks, whitespace is collapsed, and blank lines are
 * dropped, so the same HTML always yields the same text. Returns an empty string when no candidate has text.
 */
export function extractMainContent(html: string): string {
  const $ = cheerio.load(html);
  for (const selector of ['main', 'article', 'body']) {
    const candidate = $(selector).first();
    if (candidate.length === 0) continue;
    // Clean a copy so a candidate that turns out empty leaves the document intact for the next one.
    const text = textOf($, candidate.clone());
    if (text !== '') return text;
  }
  return '';
}

function textOf($: cheerio.CheerioAPI, root: cheerio.Cheerio<AnyNode>): string {
  // Descendants only (find excludes the root): a cookie-classed <body> or <main> must not blank the whole page.
  root.find('*').filter((_, el) => isUnwanted($(el) as cheerio.Cheerio<Element>)).remove();
  let raw = '';
  const visit = (node: AnyNode): void => {
    if (node.type === 'text') {
      raw += node.data.replace(HORIZONTAL_WHITESPACE, ' ');
    } else if (node.type === 'tag' || node.type === 'script' || node.type === 'style') {
      const element = node as Element;
      const block = BLOCK_TAGS.has(element.name);
      if (block) raw += '\n';
      for (const child of element.children) visit(child);
      if (block) raw += '\n';
      else if (element.name === 'td' || element.name === 'th') raw += ' ';
    }
  };
  visit(root[0]);
  return normalise(raw);
}

function isUnwanted(element: cheerio.Cheerio<Element>): boolean {
  const name = element.prop('tagName')?.toLowerCase() ?? '';
  if (REMOVED_TAGS.has(name)) return true;
  const hasCookie = (value: string | undefined) => (value ?? '').toLowerCase().includes('cookie');
  return hasCookie(element.attr('class')) || hasCookie(element.attr('id'));
}

function normalise(raw: string): string {
  const lines: string[] = [];
  for (const line of raw.split('\n')) {
    const collapsed = javaTrim(line.replace(HORIZONTAL_WHITESPACE, ' '));
    if (collapsed !== '') lines.push(collapsed);
  }
  return lines.join('\n');
}

/** Redirects followed before the fetch is refused. */
export const MAX_REDIRECTS = 3;
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);

/** Decodes the body with the charset named in the Content-Type header, falling back to UTF-8. */
export function decodeBody(body: Uint8Array, contentType: string | null): string {
  return new TextDecoder(charsetOf(contentType)).decode(body);
}

/** The charset label the header declares, or 'utf-8' when none is declared or the label is unknown. */
export function charsetOf(contentType: string | null): string {
  const match = contentType === null ? null : /charset\s*=\s*"?([^";\s]+)"?/i.exec(contentType);
  if (match === null) return 'utf-8';
  try {
    new TextDecoder(match[1]);
    return match[1];
  } catch {
    return 'utf-8';
  }
}

/**
 * Fetches an HTML page and returns its main text. The returned bytes are the extracted text in UTF-8, and the
 * checksum is the SHA-256 of those bytes, so a changed page changes the checksum.
 *
 * Redirects are followed by hand so every target can be checked against the policy before it is requested. At most
 * {@link MAX_REDIRECTS} redirects are followed. A disallowed target fails without a request to it. A page with no
 * extractable text returns empty bytes; the caller decides whether that fails the run.
 */
export function htmlConnector(policy: SourcePolicy, timeoutMs: number = REQUEST_TIMEOUT_MS): SourceConnector {
  return {
    async fetch(sourceUri: string): Promise<RawContent> {
      if (!policy.allows(sourceUri)) throw new Error(rejectionMessage(sourceUri));
      let current = sourceUri;
      let redirectsFollowed = 0;
      for (;;) {
        const response = await getOnce(current, sourceUri, timeoutMs);
        if (REDIRECT_STATUSES.has(response.status)) {
          if (redirectsFollowed === MAX_REDIRECTS) {
            throw new Error(`Too many redirects (more than ${MAX_REDIRECTS}) fetching ${sourceUri}`);
          }
          const next = redirectTarget(current, response);
          if (!policy.allows(next)) {
            throw new Error(`Redirect to a disallowed target. ${rejectionMessage(next)}`);
          }
          current = next;
          redirectsFollowed++;
          continue;
        }
        if (response.status !== 200) throw new Error(`Failed to download ${sourceUri}: HTTP ${response.status}`);
        const body = new Uint8Array(await response.arrayBuffer());
        const html = decodeBody(body, response.headers.get('content-type'));
        return rawContent(new TextEncoder().encode(extractMainContent(html)));
      }
    },
  };
}

function redirectTarget(current: string, response: Response): string {
  const host = new URL(current).host;
  const location = response.headers.get('location');
  if (location === null) throw new Error(`Redirect without a Location header from ${host}`);
  try {
    return new URL(location, current).toString();
  } catch (e) {
    throw new Error(`Redirect with an unparseable Location from ${host}`, { cause: e });
  }
}
