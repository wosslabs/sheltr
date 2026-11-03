// Dynamic part of the Expo config. The bundle host and the Ed25519 keys it is verified with are build-time
// settings, never source: SHELTR_PUBLISHER_BASE_URL, SHELTR_PUBLISHER_PUBLIC_KEY_HEX, and an optional
// SHELTR_PUBLISHER_TRUSTED_KEYS JSON array of at most two { id, publicKeyHex, notAfter } objects.
// With the base URL or the single key missing, sync is off. See docs/publishing.md.
module.exports = ({ config }) => ({
  ...config,
  extra: {
    ...config.extra,
    publisherBaseUrl: process.env.SHELTR_PUBLISHER_BASE_URL ?? '',
    publisherPublicKeyHex: process.env.SHELTR_PUBLISHER_PUBLIC_KEY_HEX ?? '',
    publisherTrustedKeys: process.env.SHELTR_PUBLISHER_TRUSTED_KEYS ?? '',
  },
});
