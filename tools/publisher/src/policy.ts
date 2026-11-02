/** Decides whether a source URI may be fetched. Checked before any network call. */
export interface SourcePolicy {
  /** True only when the URI may be fetched. Null, malformed or disallowed URIs return false. */
  allows(sourceUri: string | null | undefined): boolean;
}

/** Upper bound on the host echoed back in a rejection message. */
export const MAX_ECHOED_HOST_LENGTH = 255;

interface Authority {
  readonly scheme: string;
  readonly userInfo: string | null;
  readonly host: string;
  readonly port: string;
}

// Characters that may not appear unescaped in a URI (a space makes "a b.pdf" invalid, as in java.net.URI).
const ILLEGAL_CHARACTERS = /[\s<>"{}|\\^`]/;

/**
 * Splits "scheme://[userinfo@]host[:port]/..." strictly. Returns null for anything that is not that shape, so
 * "https:x.pdf" and "https:///x.pdf" are malformed here even though a browser URL parser would repair them.
 */
function parseAuthority(sourceUri: string): Authority | null {
  if (ILLEGAL_CHARACTERS.test(sourceUri)) return null;
  const match = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)/.exec(sourceUri);
  if (match === null) return null;
  let authority = match[2];
  let userInfo: string | null = null;
  const at = authority.lastIndexOf('@');
  if (at >= 0) {
    userInfo = authority.slice(0, at);
    authority = authority.slice(at + 1);
  }
  const portMatch = /^(.*?)(?::(\d*))?$/.exec(authority);
  const host = portMatch?.[1] ?? '';
  if (host === '') return null;
  return { scheme: match[1].toLowerCase(), userInfo, host, port: portMatch?.[2] ?? '' };
}

/** The host of [sourceUri], or null when it has none or is malformed. */
export function hostOf(sourceUri: string): string | null {
  return parseAuthority(sourceUri)?.host ?? null;
}

/**
 * Allows only https URIs whose host exactly matches one of [allowedHosts] (case-insensitive), with no explicit
 * port other than 443 and no userinfo. An empty list denies everything.
 */
export function hostAllowlistPolicy(allowedHosts: Iterable<string>): SourcePolicy {
  const allowed = new Set([...allowedHosts].map((h) => h.toLowerCase()));
  return {
    allows(sourceUri) {
      if (sourceUri === null || sourceUri === undefined) return false;
      const uri = parseAuthority(sourceUri);
      if (uri === null) return false;
      if (uri.scheme !== 'https' || uri.userInfo !== null) return false;
      if (uri.port !== '' && uri.port !== '443') return false;
      return allowed.has(uri.host.toLowerCase());
    },
  };
}

/**
 * The rejection message for a URI. A URI carrying credentials gets a fixed message that echoes nothing from it;
 * otherwise the message names only its host, truncated to {@link MAX_ECHOED_HOST_LENGTH} characters.
 */
export function rejectionMessage(sourceUri: string | null | undefined): string {
  if (sourceUri !== null && sourceUri !== undefined && parseAuthority(sourceUri)?.userInfo != null) {
    return 'Source URI must not contain credentials.';
  }
  if (sourceUri === null || sourceUri === undefined) return 'Source host not allowed: (none)';
  const parsed = parseAuthority(sourceUri);
  if (parsed === null) {
    return `Source host not allowed: ${/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(sourceUri) ? '(unparseable)' : '(none)'}`;
  }
  return `Source host not allowed: ${parsed.host.slice(0, MAX_ECHOED_HOST_LENGTH)}`;
}
