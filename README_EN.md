# MyBatisProviderJump

Provides gutter navigation icons for annotation-based MyBatis Mappers (similar to the bird icon of MyBatisX in XML Mapper scenarios, but for the annotation style).

Author: Gavin Yang (miemiegyy@gmail.com)

- **Forward**: Mapper interface methods annotated with `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` (`type = X.class, method = "m"`) get a **Forward** icon on the left of the method name; click it to jump to method `m` in class `X`.
- **Back**: SQL builder methods in Provider classes get a **Back** icon on the left of the method name; click it to jump back to the Mapper method(s) referencing it (a popup list is shown when there are multiple references).

**中文版**: [README.md](README.md)

## Tech Stack

| Item | Version |
|---|---|
| Language | Kotlin |
| Gradle | 8.14 (Kotlin DSL) |
| intellij-platform-gradle-plugin | 2.5.0 |
| Target platform | IntelliJ IDEA 2025.2.4 (build 252) |
| Build JDK | 21 (IDEA 2025 platform jars are Java 21 bytecode; `jvmTarget` stays 17) |
| Compatibility | sinceBuild `251`, no untilBuild upper bound (IDEA 2025.1 and later, including future versions) |

## Mirrors (for users in China)

- Gradle distribution: `mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`
- Gradle plugin resolution: `maven.aliyun.com/repository/gradle-plugin`
- Maven dependencies: `maven.aliyun.com/repository/public`

## Build & Run

```bash
# 1. Generate/update the wrapper (already configured, usually unnecessary)
gradle wrapper --gradle-version 8.14

# 2. Build the plugin artifact (build/distributions/MyBatisProviderJump-1.0.0.zip)
./gradlew buildPlugin

# 3. Launch a sandbox IDE to verify: open any project containing a Mapper
#    with @SelectProvider — a Forward icon on the left of the method name
#    means success
./gradlew runIde
```

## Implementation Notes

- `SelectProviderLineMarkerProvider` (forward): in `collectSlowLineMarkers`, detects `PsiMethod`s carrying a Provider annotation, extracts the `type` class from the `PsiClassObjectAccessExpression` and the `method` string from the `PsiLiteralExpression`, then uses `findMethodsByName` and `NavigationGutterIconBuilder` with `AllIcons.Actions.Forward`, anchored to the `nameIdentifier`.
- `ProviderToMapperLineMarkerProvider` (back): on a provider method, uses `ReferencesSearch.search(providerClass)` to find references in `type = X.class` attribute values, walks up with `PsiTreeUtil.getParentOfType` to the annotation and the Mapper method, and — after matching the `method` string — attaches an `AllIcons.Actions.Back` icon (multiple targets automatically produce a popup list).
- `method`-omission semantics (fully aligned with MyBatis `ProviderSqlSource`): when omitted or empty, the plugin actually checks whether the provider implements `ProviderMethodResolver` — same-name matching when implemented (same-name convention), `provideSql` fallback (MyBatis default) otherwise; consistent in both directions.
- Precise overload matching: forward navigation filters same-named provider methods by the mapper method's parameter count; a unique hit jumps directly, multiple candidates show the popup list.
- Batched reverse lookup: one project-wide `ReferencesSearch` per provider class per collection pass (instead of one per method), then distributes by effective method name.
- Icons use `Alignment.LEFT`; both providers are registered in `plugin.xml` via `codeInsight.lineMarkerProvider language="JAVA"`.

## Known Limitations

- The `method` attribute is matched by name; same-name overloads are filtered heuristically by the mapper method's parameter count (no full signature/type matching).
- Java files only (`language="JAVA"`); Kotlin Mappers do not show icons.
- `verifyPlugin` binary-compatibility checking has not been run.
