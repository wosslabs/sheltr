// Fails unless every contract/fixtures directory name appears under apps/flutter.
// That tree includes packages/sheltr_core. Generated build output is ignored.
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const fixturesDir = join(repoRoot, 'contract', 'fixtures');
const flutterRoot = join(repoRoot, 'apps', 'flutter');

const skipDirectories = new Set([
  'build',
  '.dart_tool',
  '.gradle',
  'ephemeral',
  'Pods',
  '.idea',
  'node_modules',
]);

function fixtureNames() {
  return readdirSync(fixturesDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

function* filesUnder(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (skipDirectories.has(entry.name)) continue;
      yield* filesUnder(path);
      continue;
    }
    if (entry.isFile()) yield path;
  }
}

const names = fixtureNames();
const missing = new Set(names);

for (const file of filesUnder(flutterRoot)) {
  if (missing.size === 0) break;
  const source = readFileSync(file).toString('latin1');
  for (const name of [...missing]) {
    if (source.includes(name)) missing.delete(name);
  }
}

if (missing.size > 0) {
  const list = [...missing].sort().map((name) => `  ${name}`).join('\n');
  console.error(
    `contract/fixtures directories with no reference under apps/flutter:\n${list}`,
  );
  process.exit(1);
}

console.log(
  `${names.length} contract fixtures are referenced under apps/flutter`,
);
