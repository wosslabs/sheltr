# CI workflows (placeholder)

No GitHub Actions workflows exist yet. Verification is manual until pipelines are built.

Run these from the repository root with Gradle 9.2.0 on JDK 25:

```bash
gradle :tools:publisher:test          # publisher tests (JVM)
gradle :apps:mobile:shared:jvmTest    # shared module tests (JVM)
```
