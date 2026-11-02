# Security policy

Sheltr gives disaster guidance from signed bundles of official documents. A flaw that lets someone ship, alter or spoof that content could put people at risk, so please report it privately.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **Security → Report a vulnerability** on this repository. Do not open a public issue or pull request for a security problem.

Include what you found, how to reproduce it, and the impact you expect. You will get an acknowledgement within 7 days. We will tell you when it is fixed and credit you if you wish.

## In scope

- Bundle signature verification bypass (`apps/mobile/src/core/signing.ts`).
- Anything that makes the apps accept content not signed by the publisher key.
- Publisher issues: fetching from hosts outside the manifest, reading a signing key from inside the repository, path traversal in the output directory.
- Downgrade or rollback of bundle content, and insecure transport in sync.
- The answer logic showing a citation that does not match its source, or answering when it should refuse.

## Out of scope

- Content errors inside an official source document. Report those to the publishing agency.
- Vulnerabilities in third-party dependencies with no demonstrated impact on Sheltr.

## Signing key

The Ed25519 private key is held by the maintainer outside this repository. If you find a private key anywhere in the repository or its history, report it as a vulnerability.

Sheltr is not an official warning system. Follow alerts from your local authorities.
