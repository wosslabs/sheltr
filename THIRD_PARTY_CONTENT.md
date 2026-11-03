# Third-party content

The Apache-2.0 licence in `LICENSE` covers Sheltr's source code only. The document text that Sheltr bundles and quotes belongs to its publishers and keeps its own licence. The per-document licence and attribution are recorded in `corpus/manifest.yaml` and carried into every bundle. The apps show them to users.

The phone does not fetch these sites. The publisher (`tools/publisher`) downloads an approved entry, keeps the text, and drops images, logos, crests, emblems, coats of arms and wordmarks. A sentence that merely mentions one of those words is kept. The maintainer uploads the signed bundle. The app downloads that bundle, checks the signature, and quotes the stored passages.

## In the sample bundle

`corpus/sample-bundle` was rebuilt from the manifest on 2026-11-03. It holds the same documents as the publishable manifest below, and the tests search it. The 2026-10-05 extract, the UK Prepare page and the 2013 IFRC short guide, is no longer in the sample.


## In the publishable manifest

Reviewed 2026-11-03. Checksums are in `corpus/manifest.yaml`.

| Document | Publisher | Licence |
|---|---|---|
| Be ready for emergencies; Flooding; Wildfires; Cold weather, snow and ice (https://beready.campaign.gov.uk/) | UK Government (Cabinet Office) | [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/). Each page footer says all content is available under the OGL v3.0, except where otherwise stated. Contains public sector information licensed under the OGL v3.0. |
| "Public awareness and public education for disaster risk reduction: Action-oriented key messages for households and schools", second edition, 11 October 2018 (https://www.ifrc.org/sites/default/files/PAPE-2.0-English.pdf) | IFRC and Save the Children | The text, for noncommercial use, with the source acknowledged. Commercial requests go to secretariat@ifrc.org. Photographs stay under IFRC copyright unless a photo says otherwise. The publisher stores the text layer and drops images. |

## Cleared to add

These are not in the manifest and not in the sample bundle. Adding one means a maintainer records the licence, attribution and SHA-256 checksum in `corpus/manifest.yaml`, then updates this file to move the row into the table above. Checked 2026-11-03.

| Document | Publisher | What may be stored | What stays out |
|---|---|---|---|
| Get Ready, including the earthquake and flood pages (https://getready.govt.nz/about-this-site) | National Emergency Management Agency, New Zealand | Site text, under [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Attribute the National Emergency Management Agency. | Logos, emblems, trade marks and the site design. A page that states a different licence keeps that licence. |
| Severe Weather Safety Guide, July 2025 (https://www.ses.qld.gov.au/sites/default/files/2025-12/1019_GRQ_Severe_Weather_Safety_Guide_Digital.pdf) | State of Queensland (Queensland Reconstruction Authority) | The guide text, under [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Attribute the State of Queensland (Queensland Reconstruction Authority). Covers heatwaves, floods, storms and cyclones. | The coat of arms, logos, and third-party material inside the PDF. |
| Ready.gov, including the pages FEMA.gov hosts for it (https://www.fema.gov/about/website-information) | Federal Emergency Management Agency, United States | Most FEMA text may be copied and distributed without permission. Cite FEMA.gov and link back. Do not imply FEMA or United States Government endorsement. A United States government work is not copyrighted inside the United States. That waiver does not by itself apply in other countries. | The FEMA seal, agency logos, and any photo or graphic that carries its own credit. Ready.gov print files, if reproduced as a publication, may not have their content or figures altered. Quoting a passage is the use this row allows. |
| Your Emergency Preparedness Guide (https://www.getprepared.gc.ca/cnt/rsrcs/pblctns/yprprdnssgd/index-en.aspx) and other Canada.ca preparedness pages | Government of Canada | Verbatim quotations for non-commercial use, under the [Canada.ca terms](https://www.canada.ca/en/transparency/terms.html): keep the quote accurate, and give the full title, the author organisation, and the original URL. | Rewritten or adapted text. Canadian Heritage requires permission before a work is revised or adapted. The Open Government Licence – Canada does not apply to these pages unless the page itself says so. Material owned by the Canadian Red Cross or the guide's other partners stays out. |

## Fonts in the Flutter app

`apps/flutter/assets/fonts` ships IBM Plex Sans and Source Serif 4 under the [SIL Open Font License 1.1](https://openfontlicense.org/) (`OFL.txt` in that folder).

## If you fork or redistribute

- Keep the attribution with the text.
- The 2018 IFRC edition in the manifest and the sample bundle is **non-commercial only**. A fork that distributes it commercially needs IFRC permission, or must remove it.
- The 2013 short guide is not in the manifest or the sample. Its regional mirror answers HTTP 403, and no file with the old checksum was found.
- Logos, crests, emblems, coats of arms and wordmarks are not part of the corpus. The publisher removes them during ingestion.

Sheltr is an independent project. It is not an official government or agency service.
