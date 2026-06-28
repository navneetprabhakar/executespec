# Backend Dependency Security Audit - 2026-06-28

## Scope

This audit covers backend Maven dependencies for the main ExecuteSpec Spring Boot application. It
does not cover frontend npm dependencies, container base images, generated customer workspaces,
`node_modules`, or infrastructure images.

## Commands Run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:analyze-only

JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B \
  org.owasp:dependency-check-maven:12.1.8:check \
  -Dformat=ALL \
  -DfailBuildOnCVSS=7 \
  -DdataDirectory="$HOME/.cache/owasp-dependency-check"
```

## Result

OWASP Dependency-Check failed the build because one or more dependencies have CVSS scores greater
than or equal to 7.0.

- Vulnerable dependency rows: 27
- CVE matches: 134
- Generated reports:
  - `target/dependency-check-report.html`
  - `target/dependency-check-report.json`
  - `target/dependency-check-report.xml`
  - `target/dependency-check-report.csv`
  - `target/dependency-check-report.sarif`

## Scanner Caveats

- No `NVD_API_KEY` was configured, so the first NVD update was slow.
- Dependency-Check logged database insert errors for `CVE-2026-6785` and `CVE-2026-6786` because
  two NVD reference URLs exceeded its local H2 column length. The scan still completed analysis and
  wrote reports.
- The .NET assembly analyzer could not initialize because `dotnet` is not installed. This is not
  expected to affect Maven JAR analysis materially.
- OSS Index analysis was disabled because Sonatype OSS Index now requires credentials.
- `osv-scanner`, `trivy`, `syft`, and `grype` were not installed locally, so this audit uses
  Dependency-Check plus Maven dependency analysis only.
- Dependency-Check CPE matching can produce false positives, especially for broad names like
  `web`, alpha OpenTelemetry artifacts, and shaded/transitive libraries. Treat this as a required
  triage queue, not as proof that every listed CVE is exploitable.

## Vulnerability Findings

| Dependency | Max CVSS | CVEs |
| --- | ---: | --- |
| `netty-transport-4.1.132.Final.jar` | 10.0 | CVE-2026-45674 (10.0), CVE-2026-47691 (10.0), CVE-2026-42581 (9.8), CVE-2026-42579 (9.1), CVE-2026-42584 (9.1), CVE-2026-48006 (7.5), CVE-2026-48059 (7.5), CVE-2026-44249 (8.1), CVE-2026-42582 (7.5), CVE-2026-42583 (7.5), CVE-2026-42585 (7.5), CVE-2026-42587 (7.5), CVE-2026-44248 (7.5), CVE-2026-44250 (7.5), CVE-2026-44890 (7.5), CVE-2026-44893 (7.5), CVE-2026-45416 (7.5), CVE-2026-46340 (7.5), CVE-2026-48043 (7.5), CVE-2026-50010 (7.5), CVE-2026-50011 (7.5), CVE-2026-42586 (7.1), CVE-2026-50560 (5.3), CVE-2026-45673 (6.8), CVE-2026-42580 (6.5), CVE-2026-41417 (5.3), CVE-2026-47244 (5.3), CVE-2026-50020 (5.3), CVE-2026-45536 (4.0), CVE-2026-42578 (7.5) |
| `google-cloud-build-3.94.0.jar` | 9.8 | CVE-2026-3136 (9.8) |
| `kotlin-stdlib-1.9.25.jar` | 9.8 | CVE-2026-53914 (9.8), CVE-2020-29582 (5.3) |
| `proto-google-cloud-build-v1-3.94.0.jar` | 9.8 | CVE-2026-3136 (9.8) |
| `tomcat-embed-core-10.1.54.jar` | 9.8 | CVE-2026-41293 (9.8), CVE-2026-43512 (9.8), CVE-2026-43515 (9.1), CVE-2026-41284 (7.5), CVE-2026-43513 (7.5), CVE-2026-42498 (7.3), CVE-2026-43514 (3.7) |
| `posthog-1.2.0.jar` | 8.3 | CVE-2024-9710 (8.3), CVE-2022-0645 (6.1), CVE-2023-46746 (4.3) |
| `jackson-databind-2.21.2.jar` | 8.1 | CVE-2026-54512 (8.1), CVE-2026-54513 (8.1), CVE-2026-54518 (6.5), CVE-2026-54514 (5.3), CVE-2026-54516 (5.3), CVE-2026-54517 (5.3) |
| `spring-security-core-6.5.10.jar` | 8.1 | CVE-2026-47838 (8.1), CVE-2026-40988 (7.5), CVE-2026-41706 (6.1), CVE-2026-41003 (5.4), CVE-2026-41694 (5.3) |
| `spring-security-oauth2-core-6.5.10.jar` | 8.1 | CVE-2026-47838 (8.1), CVE-2026-40988 (7.5), CVE-2026-41706 (6.1), CVE-2026-41003 (5.4), CVE-2026-41694 (5.3) |
| `spring-security-web-6.5.10.jar` | 8.1 | CVE-2026-47838 (8.1), CVE-2026-40988 (7.5), CVE-2026-41706 (6.1), CVE-2026-41003 (5.4), CVE-2026-41694 (5.3) |
| `exporter-trace-0.36.0.jar` | 7.5 | CVE-2023-43810 (7.5), CVE-2023-45142 (7.5), CVE-2023-47108 (7.5), CVE-2026-41078 (5.9), CVE-2026-39882 (5.3), CVE-2026-40894 (5.3), CVE-2026-41178 (5.3), CVE-2026-44967 (5.3) |
| `junrar-7.5.8.jar` | 7.5 | CVE-2026-41245 (7.5) |
| `log4j-api-2.24.3.jar` | 7.5 | CVE-2026-34479 (7.5), CVE-2026-34477 (5.9) |
| `opentelemetry-gcp-resources-1.37.0-alpha.jar` | 7.5 | CVE-2026-29181 (7.5), CVE-2026-39883 (7.0), CVE-2026-24051 (7.0), CVE-2026-39882 (5.3), CVE-2026-41178 (5.3) |
| `postgresql-42.7.10.jar` | 7.5 | CVE-2026-42198 (7.5) |
| `protobuf-java-4.33.2.jar` | 7.5 | CVE-2026-0994 (7.5) |
| `spring-ai-model-1.1.6.jar` | 7.5 | CVE-2026-47835 (7.5), CVE-2026-41863 (6.5) |
| `spring-core-6.2.18.jar` | 7.5 | CVE-2026-41838 (7.5), CVE-2026-41842 (7.5), CVE-2026-41848 (7.5), CVE-2026-41850 (7.5), CVE-2026-41851 (7.5), CVE-2026-41854 (6.5), CVE-2026-41844 (6.1), CVE-2026-41845 (6.1), CVE-2026-41846 (6.1), CVE-2026-41840 (5.9), CVE-2026-41841 (5.9), CVE-2026-41843 (5.9), CVE-2026-41852 (5.3), CVE-2026-41853 (5.3) |
| `spring-web-6.2.18.jar` | 7.5 | CVE-2026-41838 (7.5), CVE-2026-41842 (7.5), CVE-2026-41848 (7.5), CVE-2026-41850 (7.5), CVE-2026-41851 (7.5), CVE-2026-41854 (6.5), CVE-2026-41844 (6.1), CVE-2026-41845 (6.1), CVE-2026-41846 (6.1), CVE-2026-41840 (5.9), CVE-2026-41841 (5.9), CVE-2026-41843 (5.9), CVE-2026-41852 (5.3), CVE-2026-41853 (5.3) |
| `opentelemetry-semconv-1.29.0-alpha.jar` | 7.0 | CVE-2026-39883 (7.0), CVE-2026-24051 (7.0), CVE-2026-39882 (5.3), CVE-2026-41178 (5.3) |
| `opentelemetry-semconv-incubating-1.32.0-alpha.jar` | 7.0 | CVE-2026-39883 (7.0), CVE-2026-24051 (7.0), CVE-2026-39882 (5.3), CVE-2026-41178 (5.3) |
| `opentelemetry-proto-1.5.0-alpha.jar` | 5.9 | CVE-2026-41078 (5.9), CVE-2026-39882 (5.3), CVE-2026-40894 (5.3), CVE-2026-41178 (5.3), CVE-2026-44967 (5.3) |
| `commons-lang3-3.17.0.jar` | 5.3 | CVE-2025-48924 (5.3) |
| `jbig2-imageio-3.0.4.jar` | 5.3 | CVE-2026-23907 (5.3), CVE-2026-33929 (4.3) |
| `org.eclipse.jgit-6.9.0.202403050737-r.jar` | 5.3 | CVE-2025-4949 (5.3) |
| `pdfbox-3.0.7.jar` | 5.3 | CVE-2026-23907 (5.3), CVE-2026-33929 (4.3) |
| `xmpbox-3.0.7.jar` | 5.3 | CVE-2026-23907 (5.3), CVE-2026-33929 (4.3) |

## Dependency Hygiene Findings

`maven-dependency-plugin:analyze-only` succeeded but reported many declared dependency hygiene
warnings:

- Used undeclared dependencies include core Spring, Spring Security, Spring AI, Jackson, Tomcat,
  Google Cloud, OpenTelemetry, Hibernate, validation, JUnit, Mockito, AssertJ, Testcontainers, and
  other transitive artifacts used directly in code.
- Unused declared dependencies include several starter dependencies, runtime JJWT artifacts,
  PostgreSQL, Flyway PostgreSQL support, Tika parser package, Micrometer/OTel registry/exporter
  artifacts, Logstash encoder, and test starter dependencies.
- `flyway-core` and `spring-ai-commons` were reported as non-test-scoped test-only dependencies.

Do not blindly remove these. Spring Boot starters and runtime-only libraries often appear unused to
static dependency analysis while still being required at runtime. Use this list as a cleanup prompt
only after checking boot/runtime behavior.

## Immediate Remediation Queue

1. Triage likely real runtime dependencies first: Tomcat, Spring Framework, Spring Security, Netty,
   Jackson, PostgreSQL JDBC, Protobuf, Spring AI, and Google Cloud Build.
2. Check whether the CPE matches are false positives for broad project names, especially
   OpenTelemetry alpha artifacts and `posthog`.
3. Add a repeatable CI gate. Current CI has no backend vulnerability scan; Dependabot alone does
   not block vulnerable builds.
4. Add second-opinion scanners (`osv-scanner`, `trivy`, `syft`/`grype`) to reduce Dependency-Check
   false-positive risk and cover container/image surfaces separately.
