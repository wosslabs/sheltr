// Dynamic part of the Expo config. The bundle host and the Ed25519 public key it is verified with are build-time
// settings, never source: SHELTR_PUBLISHER_BASE_URL=https://... SHELTR_PUBLISHER_PUBLIC_KEY_HEX=<64 hex chars>
// With either missing, sync is off and the app only has the bundled sample content. See docs/publishing.md.
module.exports = ({ config }) => ({
  ...config,
  extra: {
    ...config.extra,
    publisherBaseUrl: process.env.SHELTR_PUBLISHER_BASE_URL ?? '',
    publisherPublicKeyHex: process.env.SHELTR_PUBLISHER_PUBLIC_KEY_HEX ?? '',
  },
});
