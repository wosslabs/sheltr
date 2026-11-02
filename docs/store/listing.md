# Store listing drafts

Drafts for the maintainer to adapt. Creating the Google Play ($25, one-time) and Apple Developer ($99/year) accounts, screenshots and the review submissions are manual steps that need the maintainer's accounts. Do not submit before a signed bundle is published and hosted (`docs/publishing.md`) and the iOS app exists.

## Name

Sheltr: Disaster Preparedness

## Short description (80 characters)

Free offline guide to prepare for and respond to disasters, with sources.

## Full description

Sheltr is a free, non-profit guide to disaster preparedness and response that works with no internet connection.

- Works offline. The guidance is stored on your phone.
- Every answer shows its source: the agency, the document title, the date and a link to the original.
- Answers only from approved official documents. If nothing reliable matches your question, Sheltr says so instead of guessing.
- No account, no ads, no analytics, no tracking. Your searches never leave your phone.
- Guidance on the hazards covered by the published documents (the approved list targets earthquake, flood, tropical cyclone, tornado, tsunami, wildfire, extreme heat, winter storm, landslide, glacial lake flood, volcano and drought). List only the hazards the published bundle actually covers.
- English, Spanish and French menus. Large text and high-contrast options. Read answers aloud.
- Open source (Apache License 2.0).

Sheltr is an independent project. It is not an official government or agency service and it does not send alerts. In an emergency, follow the instructions of your local authorities.

## Category and rating

- Category: Education (or Weather and Reference, per store).
- Content rating: Everyone. No user-generated content, no purchases.

## Data safety / App Privacy answers

- Data collected: none. Data shared: none.
- No account, no tracking, no ads, no third-party SDKs that collect data.
- Data is encrypted in transit (HTTPS). Users cannot request deletion because nothing is stored on a server; app data is removed by uninstalling.
- Privacy policy URL: the raw `PRIVACY.md` page of this repository, or a Pages copy of it.

## Release notes (first version)

First release. Offline guidance with cited sources, in English, Spanish and French.

## Checklist before submitting

- [ ] A signed bundle is published and the release build has the base URL and public key (`docs/publishing.md`).
- [ ] The corpus licences are shown in the About screen and `THIRD_PARTY_CONTENT.md` is current. IFRC text is non-commercial only: confirm that is acceptable for a free app in each store.
- [ ] Screenshots (phone and tablet) in each language.
- [ ] Privacy policy URL is live.
- [ ] Release build signed with an upload key kept outside the repository.
