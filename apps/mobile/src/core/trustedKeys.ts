import { hexToBytes } from './bytes';

/** One key compiled into the app. A list holds at most two. */
export interface TrustedKey {
  readonly id: string;
  readonly publicKeyHex: string;
  readonly notAfter: string;
}

export type TrustedKeyDecision =
  | { readonly kind: 'accept'; readonly publicKey: Uint8Array; readonly datePassed: boolean }
  | { readonly kind: 'rejectedSignature' };

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const PUBLIC_KEY_HEX = /^[0-9a-f]{64}$/;

/** Rejects a list longer than two keys, a bad hex key, a bad date, or a duplicate id. */
export function compileTrustedKeys(keys: readonly TrustedKey[]): readonly TrustedKey[] {
  if (keys.length > 2) throw new Error('A compiled key list holds at most two keys');
  const ids = new Set<string>();
  for (const key of keys) {
    if (key.id === '' || ids.has(key.id)) throw new Error('A trusted key id must be present and unique');
    ids.add(key.id);
    if (!PUBLIC_KEY_HEX.test(key.publicKeyHex)) throw new Error('publicKeyHex must be 64 lower-case hex characters');
    if (!isIsoDate(key.notAfter)) throw new Error(`notAfter '${key.notAfter}' is not a calendar date`);
  }
  return keys;
}

/**
 * Accepts when [signedBy] names a key on the list. An unknown id is rejectedSignature.
 * datePassed is true when that key's notAfter is before [today]. The signature is still checked.
 */
export function decideTrustedKey(
  signedBy: string | null,
  keys: readonly TrustedKey[],
  today: string,
): TrustedKeyDecision {
  if (!isIsoDate(today)) throw new Error(`today '${today}' is not a calendar date`);
  const match = signedBy === null ? undefined : compileTrustedKeys(keys).find((key) => key.id === signedBy);
  if (match === undefined) return { kind: 'rejectedSignature' };
  return {
    kind: 'accept',
    publicKey: hexToBytes(match.publicKeyHex),
    datePassed: match.notAfter < today,
  };
}

/** True when any compiled key's notAfter is before [today]. The notAfter day itself is not past. */
export function keyDatePassed(keys: readonly TrustedKey[], today: string): boolean {
  if (!isIsoDate(today)) throw new Error(`today '${today}' is not a calendar date`);
  return compileTrustedKeys(keys).some((key) => key.notAfter < today);
}

/** UTC calendar day, YYYY-MM-DD. */
export function utcDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function isIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value);
  if (match === null) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}
