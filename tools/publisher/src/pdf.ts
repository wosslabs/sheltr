import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs';
import { rawContent, getOnce, REQUEST_TIMEOUT_MS, type RawContent, type SourceConnector } from './connector.ts';
import { rejectionMessage, type SourcePolicy } from './policy.ts';
import { pageText, type PageText } from './text.ts';

/**
 * Downloads a PDF over HTTP. Redirects are never followed (any 3xx, like any other non-200 status, fails), and the
 * checksum is the SHA-256 of the downloaded bytes. The URI is checked against the policy before any request is
 * made.
 */
export function pdfConnector(policy: SourcePolicy, timeoutMs: number = REQUEST_TIMEOUT_MS): SourceConnector {
  return {
    async fetch(sourceUri: string): Promise<RawContent> {
      if (!policy.allows(sourceUri)) throw new Error(rejectionMessage(sourceUri));
      const response = await getOnce(sourceUri, sourceUri, timeoutMs);
      if (response.status !== 200) throw new Error(`Failed to download ${sourceUri}: HTTP ${response.status}`);
      try {
        return rawContent(new Uint8Array(await response.arrayBuffer()));
      } catch (e) {
        throw new Error(`Failed to download ${sourceUri}: ${(e as Error).message}`, { cause: e });
      }
    },
  };
}

/** Extracts text from a PDF, one {@link PageText} per page, numbered from 1. */
export async function extractPdfPages(pdfBytes: Uint8Array): Promise<PageText[]> {
  // pdf.js transfers the buffer to its worker, so give it a copy and keep the caller's bytes intact.
  const task = getDocument({ data: pdfBytes.slice(), useSystemFonts: false, verbosity: 0 });
  try {
    const document = await task.promise;
    const pages: PageText[] = [];
    for (let i = 1; i <= document.numPages; i++) {
      const page = await document.getPage(i);
      const content = await page.getTextContent();
      let text = '';
      for (const item of content.items) {
        if ('str' in item) text += item.str + (item.hasEOL ? '\n' : '');
      }
      pages.push(pageText(i, text));
      page.cleanup();
    }
    return pages;
  } catch (e) {
    throw new Error(`Failed to extract PDF content: ${(e as Error).message}`, { cause: e });
  } finally {
    await task.destroy();
  }
}
