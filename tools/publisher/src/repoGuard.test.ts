import assert from 'node:assert/strict';
import { mkdirSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { REPO_MARKER, findRepoRoot, keyInsideRepository } from './repoGuard.ts';
import { scratchDir } from './testHelpers.ts';

describe('repo guard', () => {
  let scratch: ReturnType<typeof scratchDir>;
  let fakeRepo: string;
  let outside: string;
  beforeEach(() => {
    scratch = scratchDir('repo-guard-');
    fakeRepo = join(scratch.dir, 'fake-repo');
    outside = join(scratch.dir, 'outside');
    mkdirSync(join(fakeRepo, REPO_MARKER), { recursive: true });
    mkdirSync(join(fakeRepo, 'tools/publisher/src'), { recursive: true });
    mkdirSync(outside);
  });
  afterEach(() => scratch.cleanup());

  it('finds the root by walking up from a subdirectory', () =>
    assert.equal(findRepoRoot(join(fakeRepo, 'tools/publisher/src')), realpathSync(fakeRepo)));

  it('does not restrict anything when there is no repository', () => {
    assert.equal(findRepoRoot(outside), null);
    assert.equal(keyInsideRepository(join(outside, 'key.der'), outside), null);
  });

  it('refuses a key inside the repository', () => {
    const key = join(fakeRepo, 'tools/publisher/key.der');
    writeFileSync(key, 'k');
    assert.match(keyInsideRepository(key, join(fakeRepo, 'tools/publisher')) ?? '', /inside the repository/);
  });

  it('refuses a key path inside the repository before the file exists', () =>
    assert.notEqual(keyInsideRepository(join(fakeRepo, 'secrets/new-key.der'), fakeRepo), null));

  it('refuses a symlink from outside into the repository', () => {
    const target = join(fakeRepo, 'key.der');
    writeFileSync(target, 'k');
    const link = join(outside, 'link.der');
    symlinkSync(target, link);
    assert.notEqual(keyInsideRepository(link, fakeRepo), null);
  });

  it('allows a key outside the repository', () => {
    const key = join(outside, 'key.der');
    writeFileSync(key, 'k');
    assert.equal(keyInsideRepository(key, join(fakeRepo, 'tools/publisher')), null);
  });

  it('allows a key in a sibling whose name starts with the repository name', () => {
    const sibling = join(scratch.dir, 'fake-repo-sibling');
    mkdirSync(sibling);
    const key = join(sibling, 'key.der');
    writeFileSync(key, 'k');
    assert.equal(keyInsideRepository(key, fakeRepo), null);
  });
});
