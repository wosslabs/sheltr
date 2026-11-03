# On-device model evaluation

Procedure for a later run on one Android phone and one iPhone. `AppSettings.modelEnabled` stays false. This file does not change that default, and it records no phone run.

## Procedure

1. Place one `.litertlm` file outside this repository. `*.litertlm` is gitignored. The file stays out of the repo.
2. Publish that file through the bundle host with the signed checksum. The signed manifest names `file`, `sha256` (lower-case hex), `byteSize`, and `modelType`. The app downloads it from the bundle host over HTTPS and keeps it only when the length and SHA-256 match that signed manifest.
3. On one Android phone and one iPhone, run the answerable rows of `contract/retrieval-cases.tsv` (rows whose `kind` is `answerable`). For each row, record whether a lead was shown and whether `modelTextIsSupported` was true. The check is `modelTextIsSupported` in `apps/flutter/packages/sheltr_core/lib/model/rewriter.dart`. A lead is shown only when that check returns true.
4. Also run the unanswerable rows. Those are retrieval refusals. Each one must show no lead.
5. The run passes only when every shown lead passed the support check, retrieval refusals showed no lead, and the process was not killed.
6. After that run, fill one table row per phone. Until then the table stays empty.

The committed default of `modelEnabled` stays false. Enabling the model for a device run is a later change on the phones under test. Turning the flag on in the repository is a separate commit, and only after this table shows a pass on both phones.

## Android API level

The `flutter_edge_ai_litertlm` 1.19.1 README asks for Android `minSdk 30`. This app stays at `minSdk` 24 (`apps/flutter/android/app/build.gradle.kts`). The Android run has to note the device API level.

## Result

No device run has been recorded. The header is the whole table. A maintainer fills a row from a real run, including the file sha256, byte size, licence name, and pass or fail.

| device | OS | sha256 | byte size | licence name | result |
| --- | --- | --- | --- | --- | --- |

`result` is `pass` or `fail` for that phone. Leave the cell blank until the run above has happened.
