# Spring Boot 4.1 / Spring AI 2.0 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bump Sheltr's backend from Spring Boot 3.5.0/Spring AI 1.1.2 to Spring Boot 4.1.0/Spring AI 2.0.0 with zero behavior change, so the subsequent Lumina chat UI work (a separate plan) can depend on Lumina's required versions.

**Architecture:** No architectural change. Bump `apps/backend/pom.xml`'s parent version and `spring-ai.version` property, then work top-down through compile errors, then `local`-profile test failures, then `gemini`-profile test failures, fixing only what's needed to restore identical behavior.

**Tech Stack:** Spring Boot 4.1.0 (from 3.5.0), Spring AI 2.0.0 (from 1.1.2), Java 25 (unchanged), jOOQ 3.19.11 (unchanged, explicitly pinned), Testcontainers 1.20.4 (unchanged unless Task 2 finds a JUnit 6 incompatibility — see Task 2), Flyway (unpinned in Sheltr's own pom, will float from the Boot-managed 11.7.2 to 12.4.0 as a direct consequence of the parent bump).

## Global Constraints

- Target versions, exact: `spring-boot-starter-parent` → `4.1.0`; `spring-ai.version` property → `2.0.0`. (Spec: "Sheltr backend compiles and its full test suite passes on Spring Boot 4.1.0 and Spring AI 2.0.0.")
- No functional change. Any code change is the minimum needed to keep existing behavior identical. (Spec Goals.)
- No new features, no refactoring beyond what the upgrade forces. (Spec Non-Goals.)
- Full test suite (`mvn test`) must pass on **both** the `local` profile (default, no env vars needed beyond the standard `env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD` workaround for this machine's stray unrelated env vars) and the `gemini` profile (`GEMINI_API_KEY` set — already present in this shell's environment). (Spec Gate.)
- Preserve these existing, deliberately-pinned values exactly as they are — do not "helpfully" update them as part of this bump:
  - `org.postgresql:postgresql` pinned at exactly `42.7.3` in `apps/backend/pom.xml` (see the comment directly above it: pgjdbc 42.7.4+ changed `DatabaseMetaData.getImportedKeys()/getExportedKeys()` column casing, which breaks jOOQ 3.19.11's codegen).
  - `<jooq.version>3.19.11</jooq.version>` property.
  - `<testcontainers.version>1.20.4</testcontainers.version>` property, *unless* Task 2 finds it's incompatible with the JUnit Jupiter version Spring Boot 4.1 now manages (see Task 2, Step 3) — in which case bump only as far as needed to restore compatibility, and record why in the commit message.
- Re-verify (not assume) the `spring.autoconfigure.exclude` class-name lists in both `application-local.yml` and `application-gemini.yml` against the real Spring AI 2.0.0 jars. (Spec Approach step 4 / Gate.)

---

### Task 1: Bump dependency versions and get a clean compile

**Files:**
- Modify: `apps/backend/pom.xml`

**Interfaces:**
- Produces: a compiling `apps/backend` module on Spring Boot 4.1.0 / Spring AI 2.0.0. Later tasks depend on this compiling before they can run tests.

- [ ] **Step 1: Bump the parent version and Spring AI property**

In `apps/backend/pom.xml`, change:

```xml
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version>
    <relativePath/>
  </parent>
```

to:

```xml
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>
```

And change:

```xml
    <spring-ai.version>1.1.2</spring-ai.version>
```

to:

```xml
    <spring-ai.version>2.0.0</spring-ai.version>
```

Do not change `<jooq.version>`, `<testcontainers.version>`, or the hardcoded `42.7.3` on the `org.postgresql:postgresql` dependency — see Global Constraints.

- [ ] **Step 2: Attempt a clean compile**

Run (from `apps/backend/`):
```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD mvn -q -DskipTests compile test-compile
```

Expected: exit code 0, no compiler errors.

If this fails: the two most-checked-and-confirmed-stable surfaces are (a) the Spring AI autoconfiguration class names Sheltr's YAML config references (confirmed identical between 1.1.2 and 2.0.0 for both `org.springframework.ai.model.ollama.autoconfigure.*` and `org.springframework.ai.model.google.genai.autoconfigure.*` — verified by inspecting both jars' class listings) and (b) `ChatModel.call(String)`'s signature (confirmed unchanged). A compile failure is therefore more likely a different Spring AI class Sheltr's own Java code touches directly — grep the compiler error for the exact class/method, then check that class's javadoc/signature in the 2.0.0 jar with `javap -classpath ~/.m2/repository/org/springframework/ai/<module>/2.0.0/<module>-2.0.0.jar <fully.qualified.ClassName>` before changing any call site, so the fix matches the real 2.0.0 signature rather than a guess.

- [ ] **Step 3: Commit**

```bash
git add apps/backend/pom.xml
git commit -m "chore(deps): bump Spring Boot to 4.1.0, Spring AI to 2.0.0"
```

---

### Task 2: Get the `local` profile's full test suite green

**Files:**
- Modify: whatever Task 1's compile step revealed needs fixing, plus any files this task's test run reveals (exact paths not knowable until the suite runs — see Step 2's diagnostic guidance for the specific, verified-likely failure modes to check first).

**Interfaces:**
- Consumes: the compiling module from Task 1.
- Produces: a fully green `mvn test` run on the `local` profile. Task 3 (gemini profile) and Task 4 (autoconfigure re-verification) both depend on this being true first, since a `local`-profile failure is cheaper to diagnose in isolation before adding the `gemini` profile's own variables.

- [ ] **Step 1: Confirm Ollama is reachable and both pinned models are pulled**

Run:
```bash
curl -s http://localhost:11434/api/tags
```
Expected: JSON listing both `qwen2.5:3b-instruct` and `nomic-embed-text:v1.5`. If Ollama isn't running, start it with `make up` from the repo root first.

- [ ] **Step 2: Run the full suite on the `local` profile**

Run (from `apps/backend/`):
```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD OLLAMA_AVAILABLE=true mvn test 2>&1 | tee /tmp/sheltr-boot4-local-test.log
grep -E "Tests run: [0-9]+, Fail|BUILD SUCCESS|BUILD FAILURE" /tmp/sheltr-boot4-local-test.log
```
Expected: `BUILD SUCCESS`, final summary line reads `Tests run: 62, Failures: 0, Errors: 0, Skipped: 0` (62 is the test count as of this plan being written — if it differs, that's fine as long as Failures/Errors are both 0; don't force the count to match if new tests were added independently of this plan).

- [ ] **Step 3: If Testcontainers' JUnit5 extension fails (not a normal test assertion failure, but errors like `NoClassDefFoundError`, `NoSuchMethodError`, or "extension could not be resolved" around `@Testcontainers`/`@Container`)**

This is a known, verified risk: Spring Boot 4.1 manages `junit-jupiter.version=6.0.3` (up from `5.12.2` under Boot 3.5.0), and Sheltr's pinned `testcontainers.version=1.20.4` was released against JUnit Jupiter 5.x. Do not fix this by downgrading JUnit — Boot manages that centrally and fighting it will cause other inconsistencies. Instead:

1. Check what Testcontainers version declares JUnit Jupiter 6 support: `mvn dependency:get -Dartifact=org.testcontainers:junit-jupiter:1.20.6` (a same-major patch release already available; try this first — it is a minimal, same-major bump). If test failures persist after bumping to `1.20.6`, check Testcontainers' own release notes for the actual minimum version with confirmed JUnit Jupiter 6 support (their JUnit 6 support may have landed in a 2.x line; verify the exact artifact resolves — e.g. `org.testcontainers:junit-jupiter:2.0.5` was checked during planning and does **not** currently resolve from Maven Central, so don't assume that specific version without re-checking).
2. Update only the `<testcontainers.version>` property in `apps/backend/pom.xml` to whatever version you confirm actually resolves and fixes the issue. Note the specific reason in the commit message (see Step 5).

- [ ] **Step 4: For any other test failure, diagnose from the actual stack trace**

Don't guess — read `/tmp/sheltr-boot4-local-test.log` for the specific exception. The two other known risk areas from planning (both checked and found NOT to be a problem, so a failure is unlikely to be caused by these, but check first since they're fast to rule out):
- Spring AI autoconfiguration exclude lists in `application-local.yml` — confirmed identical class names exist in 2.0.0 during planning; Task 4 re-verifies this formally, but if a context-loading test fails with a "no bean found" or "conflicting beans" error mentioning `Ollama` or `GoogleGenAi`, check this first.
- Flyway: Boot 4.1 manages `flyway.version=12.4.0` (up from `11.7.2`). If `MigrationIntegrationTest` or any Testcontainers-Postgres test fails with a Flyway-specific error (checksum mismatch, "schema history table" error, or a changed default like `cleanDisabled`), that's the cause — check Flyway's own migration guide for 11→12 breaking changes before changing anything in `apps/backend/src/main/resources/db/migration/`.

Fix whatever the actual failure is, re-run Step 2's command, repeat until green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix: restore local-profile test suite under Spring Boot 4.1/Spring AI 2.0

<one line per actual fix made, e.g.:>
<- bump testcontainers.version to X.Y.Z for JUnit Jupiter 6 compatibility>
<- <whatever else Step 4 actually found and fixed>"
```

(If Steps 2-4 required no fixes at all — the suite was green on the first try — skip this commit; there's nothing to commit beyond Task 1's.)

---

### Task 3: Get the `gemini` profile's full test suite green

**Files:**
- Modify: whatever this task's test run reveals (see Task 2's Step 4 diagnostic approach — same method applies here).

**Interfaces:**
- Consumes: the green `local`-profile suite from Task 2.
- Produces: a fully green `mvn test` run with `GEMINI_API_KEY` set, proving the `gemini` profile also survived the upgrade. This profile is exercised less often locally (per ADR-0011), so it needs its own explicit check rather than relying on the `local` profile's green run as a proxy.

- [ ] **Step 1: Confirm `GEMINI_API_KEY` is set**

Run: `echo ${GEMINI_API_KEY:+SET}` — expected output: `SET`. If not set, this task cannot be completed; report BLOCKED rather than skipping the gemini-profile verification (the spec's Gate requires both profiles green).

- [ ] **Step 2: Run the full suite with `GEMINI_API_KEY` set**

Run (from `apps/backend/`):
```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD OLLAMA_AVAILABLE=true mvn test 2>&1 | tee /tmp/sheltr-boot4-gemini-test.log
grep -E "Tests run: [0-9]+, Fail|BUILD SUCCESS|BUILD FAILURE" /tmp/sheltr-boot4-gemini-test.log
```

(`GEMINI_API_KEY` being present in the environment is what activates the `@EnabledIfEnvironmentVariable`-gated Gemini tests — e.g. `ConversationControllerGeminiAskIntegrationTest`, `RagLoopEndToEndTest` — there is no separate Maven profile flag to pass.)

Expected: `BUILD SUCCESS`, `Tests run: 62, Failures: 0, Errors: 0, Skipped: 0` (same caveat on the exact count as Task 2).

- [ ] **Step 3: Diagnose and fix any failure using Task 2's Step 4 method**

Read the actual stack trace from `/tmp/sheltr-boot4-gemini-test.log`; don't guess. If it's specific to the `gemini` profile (e.g. a `GoogleGenAi`-prefixed autoconfiguration or client class), check that class's real 2.0.0 signature with `javap` before changing any call site, same as Task 1 Step 2's guidance.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix: restore gemini-profile test suite under Spring Boot 4.1/Spring AI 2.0

<one line per actual fix made>"
```

(Skip if Steps 2-3 required no fixes.)

---

### Task 4: Re-verify the autoconfiguration exclude lists against the real 2.0.0 jars

**Files:**
- Read-only verification task. No file changes expected unless verification fails (see Step 3).

**Interfaces:**
- Consumes: the green test suites from Tasks 2 and 3.
- Produces: a documented, explicit confirmation (not an assumption) that `application-local.yml`'s and `application-gemini.yml`'s `spring.autoconfigure.exclude` lists still reference real classes in Spring AI 2.0.0. This is called out as its own gate item in the spec, separate from "tests pass," because a redundant-but-harmless autoconfiguration could theoretically still pass tests while silently being wrong.

- [ ] **Step 1: List the actual classes in the 2.0.0 Ollama autoconfigure jar**

Run:
```bash
unzip -l ~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-model-ollama/2.0.0/spring-ai-autoconfigure-model-ollama-2.0.0.jar | grep "AutoConfiguration.class"
```
Expected output (order may vary):
```
org/springframework/ai/model/ollama/autoconfigure/OllamaApiAutoConfiguration.class
org/springframework/ai/model/ollama/autoconfigure/OllamaChatAutoConfiguration.class
org/springframework/ai/model/ollama/autoconfigure/OllamaEmbeddingAutoConfiguration.class
```

Compare against `application-gemini.yml`'s exclude list (it excludes all three Ollama autoconfigurations, since `gemini` profile shouldn't activate Ollama at all):
```bash
grep -A5 "autoconfigure:" apps/backend/src/main/resources/application-gemini.yml
```
All three class names must appear verbatim in both the jar listing and the YAML.

- [ ] **Step 2: List the actual classes in the 2.0.0 Google GenAI autoconfigure jar**

Run:
```bash
unzip -l ~/.m2/repository/org/springframework/ai/spring-ai-autoconfigure-model-google-genai/2.0.0/spring-ai-autoconfigure-model-google-genai-2.0.0.jar | grep "AutoConfiguration.class"
```
Expected output:
```
org/springframework/ai/model/google/genai/autoconfigure/chat/GoogleGenAiChatAutoConfiguration.class
org/springframework/ai/model/google/genai/autoconfigure/embedding/GoogleGenAiEmbeddingConnectionAutoConfiguration.class
org/springframework/ai/model/google/genai/autoconfigure/embedding/GoogleGenAiTextEmbeddingAutoConfiguration.class
```

Compare against `application-local.yml`'s exclude list (it excludes all three GenAI autoconfigurations, since `local` profile shouldn't activate Gemini at all):
```bash
grep -A6 "autoconfigure:" apps/backend/src/main/resources/application-local.yml
```
All three class names must appear verbatim in both the jar listing and the YAML.

- [ ] **Step 3: If any class name doesn't match**

Update the corresponding YAML's `spring.autoconfigure.exclude` entry to the new fully-qualified class name found in Step 1 or 2's jar listing, then re-run both Task 2's Step 2 and Task 3's Step 2 test commands to confirm nothing regressed.

- [ ] **Step 4: Commit (only if Step 3 made a change)**

```bash
git add apps/backend/src/main/resources/application-local.yml apps/backend/src/main/resources/application-gemini.yml
git commit -m "fix: update autoconfigure exclude class names for Spring AI 2.0.0"
```

---

### Task 5: Final combined verification

**Files:**
- None — verification only.

**Interfaces:**
- Consumes: Tasks 1-4, all complete.
- Produces: the plan's done state — both profiles green in the same session, back to back, proving no cross-profile interference was introduced by any fix made along the way.

- [ ] **Step 1: Run the full suite once more, `local` profile**

```bash
cd apps/backend
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD OLLAMA_AVAILABLE=true mvn test
```
Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 2: Run the full suite once more with `GEMINI_API_KEY` set**

```bash
env -u SPRING_CONFIG_IMPORT -u SPRING_PROFILES_ACTIVE -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_DRIVER_CLASS_NAME -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD OLLAMA_AVAILABLE=true mvn test
```
Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 3: Confirm no unintended dependency drift**

```bash
git diff main -- apps/backend/pom.xml
```
Read the full diff. Expected changes: the parent version, `spring-ai.version`, and — only if Task 2 Step 3 or Task 3 needed it — `testcontainers.version`. Nothing else should have changed (no accidental removal of the `postgresql` version pin, no accidental `jooq.version` bump). If anything else changed, that's a mistake from an earlier task — fix it and re-run Steps 1-2.

- [ ] **Step 4: This plan is complete.** Hand off to `superpowers:finishing-a-development-branch` per the repo's usual workflow (see how the local-model-migration and its follow-up cleanup were merged) — do not merge or push as part of this plan's own steps.
