import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { UsageError, parsePublish } from './args.ts';
import {
  BUNDLE_MANIFEST_FILE, BUNDLE_SIGNATURE_FILE, BundleBuildError, buildBundle,
} from './bundle.ts';
import type { ConnectorMap } from './connector.ts';
import { htmlConnector } from './html.ts';
import { parseManifest } from './manifest.ts';
import { pdfConnector } from './pdf.ts';
import { hostAllowlistPolicy, hostOf, type SourcePolicy } from './policy.ts';
import { keyInsideRepository } from './repoGuard.ts';
import { loadPublicKey, verify } from './signing.ts';

export const USAGE = `usage: publisher publish --manifest <file> --out <dir> --key <path> [--public-key <path>]
  --manifest    approved corpus manifest (YAML)
  --out         directory for the bundle; must not exist yet
  --key         Ed25519 private key (PKCS#8, DER or PEM); must be outside the repository
  --public-key  Ed25519 public key (X.509, DER or PEM); the built bundle is verified with it`;

export const EXIT_OK = 0;
export const EXIT_FAILURE = 1;
export const EXIT_USAGE = 2;

/** Where the command prints. process.stdout and process.stderr fit; tests pass collectors. */
export interface Output {
  println(line: string): void;
}

/** The connectors used against the network. */
export function defaultConnectors(policy: SourcePolicy): ConnectorMap {
  return { pdf: pdfConnector(policy), html: htmlConnector(policy) };
}

const messageOf = (e: unknown): string => (e instanceof Error ? e.message || e.name : String(e));

/**
 * Runs the publisher command line and returns the exit code: 0 on success, 1 when the build or verification fails
 * (failed entries are listed on [err]), 2 for a malformed command line. [cwd] is the directory relative paths and
 * the repository search start from. [connectorsFor] builds the source connectors from the source policy; tests
 * replace it to serve fixtures without a network.
 */
export async function runPublisher(
  args: readonly string[],
  cwd: string,
  out: Output,
  err: Output,
  connectorsFor: (policy: SourcePolicy) => ConnectorMap = defaultConnectors,
): Promise<number> {
  let command;
  try {
    command = parsePublish(args);
  } catch (e) {
    if (!(e instanceof UsageError)) throw e;
    err.println(`publisher: ${e.message}`);
    err.println(USAGE);
    return EXIT_USAGE;
  }
  const keyPath = resolve(cwd, command.key);
  const refusal = keyInsideRepository(keyPath, cwd);
  if (refusal !== null) {
    err.println(`publisher: ${refusal}`);
    return EXIT_FAILURE;
  }
  const manifestPath = resolve(cwd, command.manifest);
  let manifest;
  try {
    manifest = parseManifest(readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    err.println(`publisher: cannot read manifest ${manifestPath}: ${messageOf(e)}`);
    return EXIT_FAILURE;
  }
  // Load the public key before the build, so a bad path fails before any download or write.
  let publicKey = null;
  if (command.publicKey !== null) {
    const path = resolve(cwd, command.publicKey);
    try {
      publicKey = loadPublicKey(path);
    } catch (e) {
      err.println(`publisher: cannot read public key ${path}: ${messageOf(e)}`);
      return EXIT_FAILURE;
    }
  }
  // Every host in the approved manifest may be fetched; nothing else may.
  const policy = hostAllowlistPolicy(manifest.documents.flatMap((d) => hostOf(d.sourceUri) ?? []));
  let result;
  try {
    result = await buildBundle(manifest, connectorsFor(policy), policy, resolve(cwd, command.out), keyPath);
  } catch (e) {
    if (e instanceof BundleBuildError) for (const f of e.failures) err.println(`FAILED ${f.sourceUri}: ${f.reason}`);
    err.println(`publisher: build failed: ${messageOf(e)}`);
    return EXIT_FAILURE;
  }
  for (const f of result.failures) err.println(`FAILED ${f.sourceUri}: ${f.reason}`);
  let verified = false;
  if (publicKey !== null) {
    const bundleBytes = readFileSync(resolve(result.outputDir, BUNDLE_MANIFEST_FILE));
    const signature = readFileSync(resolve(result.outputDir, BUNDLE_SIGNATURE_FILE));
    if (!verify(publicKey, bundleBytes, signature)) {
      err.println(`publisher: bundle signature does not verify with ${command.publicKey}`);
      return EXIT_FAILURE;
    }
    verified = true;
  }
  out.println(`Bundle version: ${result.manifest.bundleVersion}`);
  out.println(`Documents: ${result.manifest.documents.length}`);
  out.println(verified ? 'Signature: verified' : 'Signature: not verified (no --public-key given)');
  out.println(`Written to: ${result.outputDir}`);
  if (result.failures.length > 0) {
    err.println(`publisher: ${result.failures.length} entries failed and were left out of the bundle`);
    return EXIT_FAILURE;
  }
  return EXIT_OK;
}
