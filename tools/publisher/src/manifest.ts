import { parse } from 'yaml';
import { DISASTER_TYPES, type DisasterType } from './disasterType.ts';

export type SourceType = 'pdf' | 'html';

/** The approved corpus, as written in corpus/manifest.yaml. */
export interface CorpusManifest {
  readonly documents: readonly ManifestDocument[];
  readonly relatedHazards: readonly RelatedHazard[];
}

export interface ManifestDocument {
  readonly sourceUri: string;
  readonly title: string;
  readonly sourceAgency: string;
  /** Sorted in the order of {@link DISASTER_TYPES}. */
  readonly disasterTypes: readonly DisasterType[];
  /** YYYY-MM-DD. */
  readonly publishedDate: string;
  readonly license: string;
  /** ISO 3166-1 alpha-2 country, or null for global guidance. */
  readonly country: string | null;
  readonly sourceType: SourceType;
  readonly checksum: string;
}

export interface RelatedHazard {
  readonly hazard: DisasterType;
  readonly relatedHazard: DisasterType;
  readonly sourceUri: string;
  readonly note: string | null;
}

const GLOBAL = 'GLOBAL';
// Region codes ICU names that are not ISO 3166-1 countries.
const NOT_COUNTRIES = new Set(['AC', 'CP', 'DG', 'EA', 'EU', 'EZ', 'IC', 'TA', 'UN', 'XA', 'XB', 'XK', 'ZZ', 'QO']);
const regionNames = new Intl.DisplayNames(['en'], { type: 'region' });

function isIsoCountry(code: string): boolean {
  if (!/^[A-Z]{2}$/.test(code) || NOT_COUNTRIES.has(code)) return false;
  try {
    return regionNames.of(code) !== code;
  } catch {
    return false;
  }
}

type Entry = Record<string, unknown>;

/** Parses the approved manifest. Throws an Error naming the offending entry for any invalid content. */
export function parseManifest(yamlText: string): CorpusManifest {
  const root: unknown = parse(yamlText);
  if (typeof root !== 'object' || root === null || Array.isArray(root)) {
    throw new Error("Manifest must be a YAML mapping with a 'documents' list");
  }
  const rootMap = root as Record<string, unknown>;
  const documents: ManifestDocument[] = [];
  const uris = new Set<string>();
  for (const entry of mappings(rootMap.documents, 'documents')) {
    const uri = text(entry, 'source_uri', '(entry without source_uri)');
    if (uris.has(uri)) throw new Error(`Duplicate source_uri in manifest: ${uri}`);
    uris.add(uri);
    documents.push({
      sourceUri: uri,
      title: text(entry, 'title', uri),
      sourceAgency: text(entry, 'source_agency', uri),
      disasterTypes: hazards(entry.hazards, uri),
      publishedDate: date(entry.published_date, uri),
      license: text(entry, 'license', uri),
      country: country(entry.country, uri),
      sourceType: sourceType(entry.source_type, uri),
      checksum: text(entry, 'checksum', uri),
    });
  }
  const relatedHazards: RelatedHazard[] = [];
  for (const entry of mappings(rootMap.related_hazards, 'related_hazards')) {
    const uri = text(entry, 'source_uri', '(related hazard without source_uri)');
    if (!uris.has(uri)) throw new Error(`Related hazard cites ${uri}, which is not a document in the manifest`);
    relatedHazards.push({
      hazard: hazard(text(entry, 'hazard', uri), uri),
      relatedHazard: hazard(text(entry, 'related_hazard', uri), uri),
      sourceUri: uri,
      note: entry.note == null ? null : String(entry.note),
    });
  }
  return { documents, relatedHazards };
}

function mappings(value: unknown, key: string): Entry[] {
  if (value === null || value === undefined) return [];
  if (!Array.isArray(value)) throw new Error(`'${key}' must be a list`);
  return value.map((item) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      throw new Error(`Every item under '${key}' must be a mapping`);
    }
    return item as Entry;
  });
}

function text(entry: Entry, key: string, uri: string): string {
  const value = entry[key];
  if (value === null || value === undefined || String(value).trim() === '') {
    throw new Error(`Manifest entry ${uri} is missing '${key}'`);
  }
  return String(value).trim();
}

function hazards(value: unknown, uri: string): DisasterType[] {
  if (!Array.isArray(value) || value.length === 0) throw new Error(`Manifest entry ${uri} is missing 'hazards'`);
  const parsed = new Set(value.map((v) => hazard(String(v), uri)));
  return DISASTER_TYPES.filter((t) => parsed.has(t));
}

// Absent means global guidance. The literal GLOBAL also maps to null.
function country(value: unknown, uri: string): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') {
    throw new Error(`Manifest entry ${uri} has country '${String(value)}'; quote the code (for example "NO") so YAML keeps it as text`);
  }
  if (value === GLOBAL) return null;
  if (isIsoCountry(value)) return value;
  throw new Error(`Manifest entry ${uri} has country '${value}', expected an uppercase ISO 3166-1 alpha-2 code or GLOBAL`);
}

function sourceType(value: unknown, uri: string): SourceType {
  if (value === null || value === undefined || value === 'pdf') return 'pdf';
  if (value === 'html') return 'html';
  throw new Error(`Manifest entry ${uri} has source_type '${String(value)}', expected pdf or html`);
}

function hazard(name: string, uri: string): DisasterType {
  const found = DISASTER_TYPES.find((t) => t === name.trim());
  if (found === undefined) throw new Error(`Manifest entry ${uri} has unknown hazard '${name}'`);
  return found;
}

// An unquoted 2021-03-15 may arrive as a Date or as text depending on the YAML schema; accept both.
function date(value: unknown, uri: string): string {
  let iso: string;
  if (value instanceof Date) iso = value.toISOString().slice(0, 10);
  else if (typeof value === 'string') iso = value.trim();
  else throw new Error(`Manifest entry ${uri} is missing 'published_date'`);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(iso) && new Date(`${iso}T00:00:00Z`).toISOString().slice(0, 10) === iso;
  if (!valid) throw new Error(`Manifest entry ${uri} has published_date '${iso}', expected YYYY-MM-DD`);
  return iso;
}
