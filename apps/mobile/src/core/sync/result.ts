/** The outcome of one sync run. Only 'updated' changes the content. */
export type SyncResult =
  /** The published bundle is the one installed. Nothing was downloaded beyond the manifest and signature. */
  | { readonly kind: 'upToDate'; readonly bundleVersion: string }
  /** bundleVersion is now active; documentsDownloaded document files were fetched, the rest reused. */
  | { readonly kind: 'updated'; readonly bundleVersion: string; readonly documentsDownloaded: number }
  /** bundle.json did not verify against the public key. The content was not touched. */
  | { readonly kind: 'rejectedSignature' }
  /** Network, format, checksum or store error. The previous content stays active and unchanged. */
  | { readonly kind: 'failed'; readonly reason: string };
