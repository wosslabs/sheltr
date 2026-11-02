import { existsSync, realpathSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';

/** The entry whose presence marks the repository root: a directory, or a file in a git worktree. */
export const REPO_MARKER = '.git';

/**
 * The directory that contains {@link REPO_MARKER}, found by walking up from [start] (symlinks resolved). Null when
 * no ancestor has the marker, in which case there is no repository to keep a key out of.
 */
export function findRepoRoot(start: string): string | null {
  let dir = realpathSync(start);
  for (;;) {
    if (existsSync(join(dir, REPO_MARKER))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

/**
 * The absolute, normalised path of [path] with every symlink in its existing part resolved. The part that does not
 * exist yet is kept as written, so a key path can be checked before the file is created.
 */
export function realPathOf(path: string): string {
  const absolute = resolve(path);
  let existing = absolute;
  while (!existsSync(existing)) existing = dirname(existing);
  return resolve(realpathSync(existing), relative(existing, absolute));
}

function isInside(path: string, root: string): boolean {
  return path === root || path.startsWith(root.endsWith(sep) ? root : root + sep);
}

/**
 * Returns a refusal message when [key] is inside the repository that contains [cwd], and null otherwise. The key
 * path is resolved with symlinks followed, so a link from outside into the repository is refused too.
 */
export function keyInsideRepository(key: string, cwd: string): string | null {
  const root = findRepoRoot(cwd);
  if (root === null) return null;
  if (!isInside(realPathOf(key), root)) return null;
  return `Refusing to read the signing key from inside the repository (${root}). Keep the private key outside the repository.`;
}
