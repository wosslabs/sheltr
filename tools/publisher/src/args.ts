/** The arguments of `publish`, as given. Relative paths are resolved by the caller. */
export interface PublishArgs {
  readonly manifest: string;
  readonly out: string;
  readonly key: string;
  readonly publicKey: string | null;
}

/** A malformed command line. The caller prints the message and usage, then exits 2. */
export class UsageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UsageError';
  }
}

const VALUE_FLAGS = new Set(['--manifest', '--out', '--key', '--public-key']);

/**
 * Parses `publish --manifest <file> --out <dir> --key <path> [--public-key <path>]`. Every flag takes exactly one
 * value. Missing, unknown, repeated or valueless arguments are a {@link UsageError}. Nothing is read from disk.
 */
export function parsePublish(args: readonly string[]): PublishArgs {
  if (args.length === 0) throw new UsageError("missing command, expected 'publish'");
  if (args[0] !== 'publish') throw new UsageError(`unknown command '${args[0]}', expected 'publish'`);
  const values = new Map<string, string>();
  for (let index = 1; index < args.length; index += 2) {
    const flag = args[index];
    if (!VALUE_FLAGS.has(flag)) throw new UsageError(`unknown argument '${flag}'`);
    if (values.has(flag)) throw new UsageError(`${flag} given more than once`);
    const value = args[index + 1];
    if (value === undefined || value.trim() === '' || VALUE_FLAGS.has(value)) throw new UsageError(`${flag} needs a value`);
    values.set(flag, value);
  }
  for (const required of ['--manifest', '--out', '--key']) {
    if (!values.has(required)) throw new UsageError(`missing ${required}`);
  }
  return {
    manifest: values.get('--manifest')!,
    out: values.get('--out')!,
    key: values.get('--key')!,
    publicKey: values.get('--public-key') ?? null,
  };
}
