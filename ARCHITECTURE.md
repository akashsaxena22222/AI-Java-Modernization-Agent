# AI Java Modernization Agent — Phase 1 Architecture & Development Plan

**Status:** approved design, implementation not started
**Last updated:** 2026-09-19
**Phase 1 goal:** a web-based application that scans a legacy Java project and produces an
AI-assisted modernization assessment as JSON.

---

## 1. Scope

### In scope for Phase 1 (the ANALYZER)

1. Java 21 + Spring Boot + Maven
2. REST API backend
3. Claude API integration via a provider-agnostic port
4. Accept and scan a Java project directory
5. Static analysis of project files
6. Detection of legacy technologies and patterns
7. Send collected facts to an LLM for assessment
8. Return a structured modernization report as JSON

### Explicitly NOT in scope for Phase 1

- **No autonomous source-code modification.** Nothing in Phase 1 writes to a target project.
  Read-only by construction: the scanner opens files with `Files.newBufferedReader`, and no
  `Files.write` call exists anywhere in the codebase.
- No agent loop, no tool-calling, no build/test validation, no code transformation.
- No HTML UI (Phase 2).
- No Gradle project analysis (detected and reported as unanalyzed, not silently ignored).

### Reserved for later phases

`agent/`, `tool/`, `planner/`, `transform/`, `validation/` — these packages are **not created**
in Phase 1. Empty packages are noise.

---

## 2. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Base package | `com.company.modernizer` | As specified; placeholder, single IDE refactor to change |
| Demo target | Build `demo/legacy-sample-app/` fixture | Repeatable demo + real test fixtures, no external dependency |
| Multi-provider scope | `LlmClient` port + Anthropic only | Seam is real and tested; additional providers are ~1 class each |
| Default model | `claude-opus-5` | Strongest on staged upgrade paths, blocking order, effort estimates |
| Model/provider config | `application.yml` property | Switching Anthropic models = one line, no code |
| LLM abstraction | Hand-rolled port, not Spring AI | Direct access to prompt caching / adaptive thinking; ~3 small files; the architecture principle made visible |
| Framework version | Spring Boot 4.1.1 | Initializr's current default; brings Jackson 3 and renamed starters — see §7 |
| Build invocation | Maven Wrapper (`mvnw`, `only-script`) | `mvn` is not installed on the dev machine; the script fetches Maven 3.9.16 itself, so no `maven-wrapper.jar` binary is committed |
| Boilerplate | Java 21 `record`s, no Lombok | Avoids an annotation-processor dependency |

---

## 3. Environment prerequisites

Verified on the development machine (2026-09-19):

| Item | Status | Consequence |
|---|---|---|
| Java | 21.0.12 LTS ✅ | Ready |
| `mvn` on PATH | ❌ not installed | Commit the Maven Wrapper; run `./mvnw` |
| `~/.m2` local repo | ❌ does not exist | **First build downloads Maven + the full Spring tree: expect 3–10 minutes.** Subsequent builds are seconds. |
| `ANTHROPIC_API_KEY` | ❌ not set | Required only from Step 5 onward. Steps 0–4 are fully testable without it. |
| Network to `start.spring.io` | ✅ HTTP 200 | Used once, to obtain a genuine `maven-wrapper.jar` (a binary that cannot be hand-authored) |

---

## 4. Core design principle

> **An LLM is the wrong tool for *finding* facts, and the right tool for *judging* them.**

"This pom declares `<java.version>1.8</java.version>`" is a deterministic fact. Asking a model to
discover it by reading raw files costs tokens, risks hallucination, and cannot be unit-tested.

"Given Java 8 + Spring 4.3 + 37 XML config files + 12 `@WebService` classes, what is the realistic
staged upgrade path, what breaks first, and how many person-days is this?" is genuine judgement that
no rule engine produces well.

Phase 1 is therefore a **two-stage pipeline**, not an "AI reads your code" tool:

```
project dir → SCAN (deterministic) → ProjectContext (compact JSON facts + static findings)
                                            ↓
                                   LLM ASSESSMENT (judgement)
                                            ↓
                            ModernizationReport (merged JSON)
```

### Why this shape

- Static findings are **always** present, with `file:line` evidence, even with the API down or unkeyed.
- We transmit ~15–30 KB of structured facts instead of a 60k-LOC codebase — cheap, fast, no truncation.
- Every finding carries `source: STATIC | AI | STATIC_AI`, so the AI's actual contribution is
  demonstrable rather than asserted.
- It is the shape the future autonomous agent needs: `ProjectContext` becomes the agent's world
  model, and `roadmap` becomes its task queue.

---

## 5. Architecture

```
                    ┌─────────────────────────────────────────┐
  POST /api/v1/     │  AnalysisController  (REST, validation) │
  analysis          └───────────────────┬─────────────────────┘
                                        ▼
                    ┌─────────────────────────────────────────┐
                    │  AnalysisOrchestrator (service)         │
                    │  scan → analyze → assess → merge        │
                    └───┬──────────────┬──────────────────┬───┘
                        ▼              ▼                  ▼
            ┌───────────────┐  ┌──────────────┐  ┌──────────────────┐
            │ ProjectScanner│  │ Analyzer SPI │  │ AssessmentService│
            │ (safe walk,   │  │  (N rules)   │  │ prompt + budget  │
            │  inventory)   │  │              │  │ + redaction      │
            └───────────────┘  └──────┬───────┘  └────────┬─────────┘
                                      │                   ▼
                     MavenPomAnalyzer ─┤          ┌──────────────────┐
                    JavaSourceAnalyzer─┤          │  LlmClient (SPI) │◄── swap point
                     XmlConfigAnalyzer─┤          └────┬────────┬────┘
                    (more, pluggable) ─┘               ▼        ▼
                                              Anthropic    Mock/Other
```

### Four architectural commitments

**(a) `Analyzer` is an SPI, not a class.**
One interface: `List<Finding> analyze(ProjectContext ctx)`. Spring injects `List<Analyzer>`.
Adding SOAP-to-REST detection later means adding one `@Component` and touching zero existing files.

**(b) `LlmClient` is a provider-agnostic port.**
The interface speaks only in our types (`LlmRequest` / `LlmResponse`), never Anthropic types. The
Anthropic SDK import is confined to exactly one class, selected by
`@ConditionalOnProperty(name="modernizer.ai.provider", havingValue="anthropic")`. A `MockLlmClient`
is the fallback bean — that is what makes Step 4 demoable with no API key.

**(c) Read-only by construction.** See §1.

**(d) Synchronous now, async-ready.**
Phase 1 returns the report on the HTTP response. The orchestrator returns `ModernizationReport` from
a plain method, so wrapping it in a job-id + polling API later is purely additive.

---

## 6. Package structure

```
AI-Java-Modernization-Agent/
├─ ARCHITECTURE.md                       ← this document
├─ .gitignore
├─ README.md
├─ mvnw / mvnw.cmd / .mvn/wrapper/       ← Maven not installed locally
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/company/modernizer/
│     │  ├─ ModernizerApplication.java
│     │  ├─ controller/
│     │  │  ├─ AnalysisController.java
│     │  │  ├─ HealthController.java
│     │  │  └─ dto/   AnalysisRequest, AnalysisResponse, ApiError
│     │  ├─ service/
│     │  │  ├─ AnalysisOrchestrator.java
│     │  │  ├─ AssessmentService.java
│     │  │  └─ ReportAssembler.java       ← merge + score + roadmap
│     │  ├─ scanner/
│     │  │  ├─ ProjectScanner.java
│     │  │  ├─ FileClassifier.java
│     │  │  └─ ScanGuard.java             ← path safety + limits
│     │  ├─ analyzer/
│     │  │  ├─ Analyzer.java              ← the SPI
│     │  │  ├─ build/    MavenPomAnalyzer
│     │  │  ├─ source/   JavaSourceAnalyzer
│     │  │  └─ xml/      XmlConfigAnalyzer
│     │  ├─ ai/
│     │  │  ├─ LlmClient, LlmRequest, LlmResponse, LlmException
│     │  │  ├─ anthropic/ AnthropicLlmClient   ← only SDK-aware class
│     │  │  ├─ mock/      MockLlmClient
│     │  │  └─ prompt/    PromptBuilder, ContextSerializer, Redactor
│     │  ├─ model/
│     │  │  ├─ ProjectContext, ProjectMetrics, ScannedFile, DependencyRef
│     │  │  ├─ Finding, FindingCategory, Severity, Confidence, Evidence
│     │  │  └─ ModernizationReport, ReportSummary, RoadmapPhase
│     │  └─ config/
│     │     ├─ ModernizerProperties.java  ← @ConfigurationProperties("modernizer")
│     │     ├─ AiConfig.java
│     │     └─ WebConfig.java
│     ├─ main/resources/
│     │  ├─ application.yml
│     │  ├─ prompts/assessment-system.md  ← prompt as a resource, not a string literal
│     │  └─ static/                        ← Phase 2 HTML UI drops in here
│     └─ test/java/...  +  test/resources/fixtures/
└─ demo/legacy-sample-app/                ← deliberately-legacy Maven project to analyze
```

### Three changes from the originally suggested layout

1. **`scanner/` split out of `analyzer/`** — "walk the filesystem safely" and "reason about what we
   found" are different jobs with different tests. Conflating them is the primary reason tools like
   this become untestable.
2. **`client/` → `ai/`** — `client` reads like "HTTP client for an upstream service." `ai/` with an
   SPI at its root makes the swap point self-documenting, and shows Anthropic is not privileged in
   the design.
3. **`prompts/` as classpath resources** — prompts are iterated on constantly. As `.md` files they
   are diffable and reviewable; as Java string concatenation they are not.

---

## 7. Dependencies

### Phase 1 total

| Dependency | Purpose | Arrives at |
|---|---|---|
| `spring-boot-starter-parent` **4.1.1** | Parent POM, Java 21 | Step 0 ✅ |
| `spring-boot-starter-webmvc` | REST + embedded Tomcat | Step 0 ✅ |
| `spring-boot-starter-validation` | `@Valid` on request DTOs | Step 0 ✅ |
| `spring-boot-starter-webmvc-test` (test) | JUnit 5, AssertJ, MockMvcTester | Step 0 ✅ |
| `spring-boot-starter-validation-test` (test) | Validation assertions | Step 0 ✅ |
| `org.apache.maven:maven-model` 3.9.x | Real `pom.xml` parsing (`MavenXpp3Reader`) | Step 3 |
| `com.anthropic:anthropic-java` 2.34.0 | Official Anthropic SDK | Step 5 |

Each is added at the step that first uses it, not up front.

### Spring Boot 4 deviations from the original plan

This plan was drafted assuming Boot 3.5.x. Initializr's current default is **4.1.1**, which was
adopted instead. Three concrete differences, all discovered by building rather than by assumption:

| Concern | Boot 3.x | Boot 4.1.1 |
|---|---|---|
| Web starter | `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Test starter | `spring-boot-starter-test` | split per module: `spring-boot-starter-webmvc-test`, `spring-boot-starter-validation-test` |
| `@AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet` | `org.springframework.boot.webmvc.test.autoconfigure` |

**Jackson 3 is the one with downstream consequences.** Boot 4 ships Jackson 3 under the
`tools.jackson.*` package, not `com.fasterxml.jackson.*`. Two things follow:

- `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` no longer exists; ISO-8601 date output is the
  new default. A `spring.jackson.serialization.write-dates-as-timestamps` property fails context
  startup outright (this is exactly how it was found).
- **Any Jackson annotation used from Step 1 onward** — `@JsonProperty`, `@JsonInclude`,
  `@JsonPropertyDescription` — must be imported from `tools.jackson.annotation.*`, not
  `com.fasterxml.jackson.annotation.*`. This matters most at Step 5: the Anthropic SDK's structured
  output derives its JSON schema from Jackson annotations, so a Boot 4 / SDK annotation mismatch is
  a real risk to check for when that step lands.

**`springdoc-openapi`** (the optional Swagger UI from §14) is now **deferred to Step 7**: springdoc
2.x targets Boot 3, and Boot 4 compatibility needs verifying rather than assuming. Not worth
risking the first green build over.

### Deliberately excluded, and why

- **Jackson** — transitive via `starter-web`; not declared.
- **Lombok** — Java 21 `record`s cover our DTOs; avoids an annotation-processor dependency that can
  surprise reviewers on a fresh clone.
- **JavaParser** — *not* in Phase 1. Our Java detections (`@WebService`, `javax.*` imports,
  `extends TestCase`, `HttpSecurity` patterns) are reliably line-scannable. JavaParser earns its
  place in Phase 2 for real complexity metrics and, later, AST-based transformation.
- **Hand-rolled pom XML parsing** — rejected in favour of `maven-model`, which handles `<parent>`,
  `<properties>` interpolation, `<dependencyManagement>`, and profiles correctly. Dependency
  analysis is the highest-value detector; regexing pom.xml degrades fast.
- **`springdoc-openapi`** — *optional, undecided*. One dependency buys a live Swagger UI at
  `/swagger-ui.html`, which is a good "click it" moment in a demo.

---

## 8. Project scanner design

A single pass, five stages. Guiding rule: **the scanner never decides what anything means.** It
produces an inventory; analyzers interpret it.

### Stage 1 — Validate the root (`ScanGuard`)

This is a security boundary, not a formality: we accept a filesystem path over HTTP.

- Canonicalize via `Path.toRealPath()` — collapses `..`, symlinks, and Windows 8.3 names.
- Assert the real path starts with one of `modernizer.scan.allowed-roots`. Without this,
  `POST {"path":"C:\\Users"}` reads the user's home directory. **With no allowlist configured,
  refuse to scan at all** rather than defaulting to permissive.
- Assert it is a directory, readable, and contains a project marker (`pom.xml` / `build.gradle` /
  `src/`). Fail fast with a clear 400 rather than returning an empty report for a typo'd path.

### Stage 2 — Walk with hard limits

`Files.walkFileTree` with a visitor:

- **Prune at the directory level** (never descend): `target/`, `build/`, `out/`, `bin/`, `.git/`,
  `.idea/`, `node_modules/`, `.mvn/`.
- **Caps**, all configurable and all *reported* when hit: max depth 25, max files 20 000, max file
  size 1 MB, max total bytes 200 MB, wall-clock timeout 60 s. Every breach appends to
  `report.meta.warnings` — a demo that silently analyzes half a project is worse than one that says so.
- **Never read**: `*.jar/.war/.class/.so/.exe`, images, archives, `*.pem/.jks/.p12/.keystore`,
  `.env`, `id_rsa*`. Inventoried by name only.

### Stage 3 — Classify (`FileClassifier`)

Each file → a `FileKind` by name and path: `MAVEN_POM`, `GRADLE_BUILD`, `JAVA_SOURCE`,
`JAVA_TEST` (under `src/test`), `WEB_XML`, `SPRING_XML`, `PROPERTIES`, `YAML`, `WSDL`, `XSD`,
`JSP`, `DOCKERFILE`, `CI_CONFIG`, `OTHER`.

Analyzers then ask for the kinds they care about — no analyzer re-walks the tree.

### Stage 4 — Cheap metrics per relevant file

Line count, blank/comment ratio, file size, and *for Java sources only*, the import block and
top-level annotations (a single streaming read, stopping at the first method body). Those two facts
drive most legacy detection: `javax.jws.WebService`, `javax.persistence`,
`org.springframework.web.servlet`, `junit.framework.TestCase`.

### Stage 5 — Emit `ProjectContext`

An immutable record holding: root path, detected build tool, module list, flat `List<ScannedFile>`,
aggregate `ProjectMetrics`, and the parsed dependency list.

This object is the **only** input every analyzer and the prompt builder receives, which means every
analyzer is unit-testable against a hand-built `ProjectContext` with no temp directories.

**Refinement made at Step 1.** The original wording — "nothing downstream touches the filesystem
again" — was too absolute: an analyzer inspecting `web.xml` content genuinely needs the file text,
which `ScannedFile` deliberately does not carry (holding content for 20 000 files would be
wasteful). The rule is therefore:

- `ScannedFile` carries metrics, the import block, and top-level annotations — enough for most
  detection without reading anything twice.
- An analyzer needing file text calls `ProjectContext.resolve(ScannedFile)` to get an absolute path
  for a file the scan **already discovered**. It resolves; it never re-walks, and it cannot reach
  outside the scanned root.

`ProjectContext` holds the absolute root path and is **never serialized to HTTP**. The report's
`project` block is a separate `ProjectInfo` record produced by `ProjectInfo.from(context)`, which
drops the absolute path and reduces the file inventory to `ProjectMetrics`. This makes §9.2 enforced
by the type system rather than by remembering to sanitize at the edge, and it is covered by a test
asserting no absolute path appears in serialized output.

### Three things found by building Step 2, not by planning it

1. **The depth cap reported the wrong thing.** `Files.walkFileTree` passes *directories* to
   `visitFile` once `maxDepth` is reached, rather than skipping them. The scanner was therefore
   trying to read a directory as a file and appending "Could not read `src/main`" to
   `report.meta.warnings` — technically a report, but one that blames the wrong thing. It now names
   the depth cap explicitly, once per scan.
2. **`keystore` and `truststore` were only matched as extensions.** `app.keystore` was correctly
   never read; a bare `keystore` with no extension fell through to being read as text. Both
   spellings occur in real projects, so both are now matched by name. This is the kind of gap that
   only shows up when someone writes the classification table out as a test.
3. **Tests and the running application resolved relative paths differently.** Surefire forks in
   `backend/`, where the shipped `./demo` allow-root does not exist, so any test exercising the real
   configuration would have failed closed with `ALLOWLIST_EMPTY`. Surefire's working directory is now
   the repository root, matching `spring-boot:run`. The alternative — overriding
   `modernizer.scan.allowed-roots` per test — would have hidden the discrepancy rather than removed
   it, and left the tests asserting against a path resolution that never happens in production.

### Known, accepted: the rejection message names the allowlist

`OUTSIDE_ALLOWED_ROOTS` deliberately reports the configured roots (`Allowed: [D:\...\demo]`) while
withholding the resolved target, so a probing caller learns nothing about what exists outside the
allowlist. The roots themselves are absolute host paths, which is a small disclosure to whoever can
reach the API, and is traded for an operator being able to diagnose a refused scan from the response
alone. Worth revisiting at Step 7 alongside the rest of the error-handling hardening; it is a
conscious choice rather than an oversight.

---

## 9. Safe transmission of project information

Six mechanisms, ordered roughly by how much their absence would embarrass us.

### 9.1 Send facts, not source

The default payload is the `ProjectContext` summary plus static findings: dependency coordinates and
versions, file-kind counts, framework/version detections, and a bounded list of `file:line` evidence
lines. **Not whole files.** This is the single biggest safety and cost win — a 60k-LOC project
becomes ~20 KB of JSON.

### 9.2 Redaction before serialization (`Redactor`)

Applied to every string that leaves the process:

- Property/YAML **values** whose key matches `(?i)pass|pwd|secret|token|key|credential|dsn` →
  `***REDACTED***`. We transmit `spring.datasource.password=***REDACTED***` because the *presence*
  of a plaintext credential is itself a finding worth reporting; the value never is.
- Known-prefix and high-entropy scrubbing: `AKIA…`, `-----BEGIN … PRIVATE KEY-----`, JDBC URLs with
  embedded credentials, bearer tokens.
- Absolute paths → project-relative. `D:\AI Projects\…` leaks machine layout and adds nothing.

`Redactor` gets its own dedicated test suite at Step 5.

### 9.3 Bounded evidence snippets

Where a snippet genuinely aids judgement (a hand-rolled security filter, a `@WebService` method
signature): **≤ 3 lines of context, max 40 snippets, max 200 chars each**, all redacted. Hard caps
in config, not conventions.

### 9.4 Measured token budget — no silent truncation

Before sending, call `client.messages().countTokens(...)` on the assembled request. If it exceeds
`modernizer.ai.max-input-tokens` (default 150 000), degrade **deliberately and visibly**:

1. drop snippets,
2. then low-severity findings,
3. then collapse per-file detail into per-module counts,

and set `report.meta.truncated = true` with a warning naming what was dropped. We never quietly cut
the payload mid-structure.

### 9.5 Explicit egress consent

`modernizer.ai.enabled` defaults to **`false`**. The API refuses AI assessment unless it is `true`
(or the request passes `"aiAssessment": true`). Source-derived data leaving the building is a real
decision; the static half works air-gapped. The README states plainly which fields are transmitted.

### 9.6 Structured output, not prose parsing

Use the SDK's schema-enforced structured output — `.outputConfig(AiAssessment.class)` — so the model
returns a typed object validated against a schema derived from our record, instead of regexing JSON
out of a markdown fence.

### API key handling

- Read **only** from the `ANTHROPIC_API_KEY` environment variable, via `AnthropicOkHttpClient.fromEnv()`.
- Never in `application.yml`, never a `@Value` default, never logged.
- `.gitignore` covers `.env`.
- Request/response logging redacts the payload body above DEBUG.

---

## 10. LLM provider abstraction

### Two levels of switching

**Level 1 — different Anthropic models** (`claude-opus-5` → `claude-sonnet-5` → `claude-haiku-4-5`):
one line in `application.yml`. No code.

**Level 2 — different providers** (Gemini, Grok, OpenAI): one new class implementing `LlmClient`, in
its own subpackage, selected by config property.

```
ai/
├─ LlmClient.java                    ← the port; speaks only our types
├─ anthropic/ AnthropicLlmClient     (com.anthropic:anthropic-java)   [Phase 1]
├─ mock/      MockLlmClient          (canned assessment, no key)      [Phase 1]
├─ gemini/    GeminiLlmClient        (Google official Java SDK)       [later, ~1 class]
└─ grok/      GrokLlmClient          (OpenAI-compatible REST via Spring RestClient, no new dep)
```

### Capability hints, not concrete parameters

A naive port would leak Anthropic's model into the interface. These three concerns differ across
providers and must be expressed as hints the adapter maps:

| Concern | Anthropic | Gemini | Grok | Port models it as |
|---|---|---|---|---|
| Structured JSON | `.outputConfig(Class)`, schema-enforced | `responseSchema` + JSON mime type | `response_format: json_schema` | `LlmRequest.jsonSchema` |
| Reasoning depth | adaptive thinking + `effort` | thinking budget | reasoning effort | `ReasoningLevel {LOW,MEDIUM,HIGH}` |
| Prompt caching | explicit breakpoints | context caching | automatic | `cacheSystemPrompt: boolean` hint |
| Token counting | `countTokens` endpoint | differs | differs | `estimateTokens()`, `chars/4` default method |

All three providers support schema-constrained JSON output, so the report contract holds across
providers.

### Types

- `LlmRequest(systemPrompt, userPrompt, jsonSchema, maxOutputTokens, reasoningLevel, cacheSystemPrompt)`
- `LlmResponse(rawJson, provider, modelId, inputTokens, outputTokens, cachedInputTokens, latencyMs, finishReason)`

Never accept and never return a provider type.

### Anthropic adapter specifics

- Model default `claude-opus-5`, adaptive thinking, `maxTokens` 16 000 non-streaming.
- Static system prompt carries a `CacheControlEphemeral` breakpoint, so repeated demo runs are
  cheaper and faster — relevant when running the demo five times for five colleagues.
- Typed SDK exceptions (`RateLimitException`, `NotFoundException`, `AnthropicServiceException`) map
  to clean HTTP status codes in a most-specific-first chain, not one broad catch.

### Future: `LlmClientRegistry`

A registry keyed by provider name lets the request body carry `"provider": "gemini"`, turning
provider switching into a live demo feature: same scan, same facts, several assessments side by side,
with `meta.llm.provider` recording which produced each.

### Rejected alternative: Spring AI

Spring AI already abstracts Anthropic, Gemini, and OpenAI-compatible providers. Rejected for three
reasons: our port is ~3 tiny files; wrapper libraries lag on the provider-specific features that
matter here (prompt caching, adaptive thinking — both directly affect cost and report quality); and
for a showcase about architecture, a clean 3-method seam demonstrates the point better than "a
library handles it."

### Honest caveat

Report quality will differ visibly between providers. Roadmap ordering, effort estimates, and risk
reasoning are the hardest part of this task, and that is where models separate. Provider switching
is a good demo; provider *parity* is not something to promise.

---

## 11. Modernization report JSON

```json
{
  "reportId": "b3f1c2a8-4e5d-4f21-9a77-2c0e5d1b8e44",
  "generatedAt": "2026-09-19T14:22:08Z",
  "project": {
    "name": "legacy-order-service",
    "rootPath": "legacy-order-service",
    "buildTool": "MAVEN",
    "modules": ["order-api", "order-core", "order-soap-gateway"],
    "detectedJavaVersion": "1.8",
    "detectedFrameworks": [
      { "name": "Spring Framework", "version": "4.3.9.RELEASE", "evidenceFile": "pom.xml" },
      { "name": "Hibernate",        "version": "4.2.21.Final",  "evidenceFile": "pom.xml" },
      { "name": "JUnit",            "version": "3.8.1",         "evidenceFile": "order-core/pom.xml" }
    ],
    "metrics": {
      "javaFiles": 412, "testFiles": 22, "linesOfCode": 58230,
      "xmlConfigFiles": 37, "wsdlFiles": 6, "jspFiles": 44,
      "declaredDependencies": 64, "testToSourceRatio": 0.053
    }
  },

  "summary": {
    "overallRiskLevel": "HIGH",
    "modernizationScore": 34,
    "estimatedEffort": { "size": "L", "personDays": 45, "confidence": "MEDIUM" },
    "headline": "Java 8 / Spring 4.3 monolith with XML-driven config and six SOAP endpoints. Spring Boot 3 requires a staged javax→jakarta migration; test coverage is too thin to refactor safely today.",
    "topPriorityFindingIds": ["F-001", "F-004", "F-011"],
    "categoryCounts": {
      "JAVA_VERSION": 1, "SPRING_MODERNIZATION": 3, "SOAP_WEBSERVICE": 4,
      "XML_CONFIGURATION": 2, "SECURITY": 3, "TESTING": 2,
      "DEPENDENCY_HEALTH": 6, "BUILD": 2, "CODE_QUALITY": 5, "CLOUD_READINESS": 4
    }
  },

  "findings": [
    {
      "id": "F-001",
      "category": "JAVA_VERSION",
      "title": "Project targets Java 8; five LTS releases behind",
      "severity": "HIGH",
      "confidence": "HIGH",
      "source": "STATIC_AI",
      "evidence": [
        { "file": "pom.xml", "line": 24, "snippet": "<maven.compiler.source>1.8</maven.compiler.source>" }
      ],
      "impact": "No virtual threads, records, or pattern matching; Spring Boot 3 requires Java 17+, so this blocks every other framework upgrade. Oracle public updates for 8 have ended.",
      "recommendation": "Move to Java 21 LTS in one step. Run jdeps to find internal-API and removed-API usage first; the common blockers in this codebase are sun.misc.BASE64Encoder and the removed JAXB/JAX-WS modules.",
      "currentState": "Java 8",
      "targetState": "Java 21 (LTS)",
      "effort": "M",
      "risk": "MEDIUM",
      "blockedBy": [],
      "blocks": ["F-004"],
      "references": ["https://docs.oracle.com/en/java/javase/21/migrate/"]
    }
  ],

  "roadmap": [
    {
      "phase": 1,
      "name": "Stabilize and measure",
      "goal": "Reach a state where upgrades are verifiable: reproducible build, JUnit 5, coverage on the order-pricing paths.",
      "findingIds": ["F-011", "F-014"],
      "estimatedEffort": "S", "personDays": 6, "dependsOn": []
    },
    {
      "phase": 2,
      "name": "Java 21 and jakarta namespace",
      "goal": "Compile and pass tests on Java 21 with javax→jakarta completed, still on Spring 4.",
      "findingIds": ["F-001", "F-007"],
      "estimatedEffort": "M", "personDays": 14, "dependsOn": [1]
    }
  ],

  "cloudReadiness": {
    "score": 25,
    "blockers": [
      { "issue": "Filesystem session state in /var/appdata", "findingId": "F-019" },
      { "issue": "Hardcoded JNDI datasource in context.xml", "findingId": "F-020" }
    ],
    "opportunities": [
      { "target": "AWS ECS Fargate", "rationale": "Stateless after phase 3; no OS-level dependencies detected.", "effort": "M" },
      { "target": "Amazon RDS for PostgreSQL", "rationale": "Oracle-specific SQL in 4 DAOs would need rewriting.", "effort": "L" }
    ]
  },

  "meta": {
    "analyzerVersion": "0.1.0",
    "aiAssessmentEnabled": true,
    "llm": {
      "provider": "anthropic", "model": "claude-opus-5",
      "inputTokens": 18422, "outputTokens": 6110,
      "cacheReadInputTokens": 17104, "latencyMs": 42310
    },
    "scan": { "filesScanned": 531, "filesSkipped": 12, "durationMs": 1840 },
    "truncated": false,
    "warnings": ["3 files exceeded the 1 MB size cap and were inventoried by name only"]
  }
}
```

### Four design points

- **`source`** on every finding (`STATIC` / `AI` / `STATIC_AI`) — demonstrates the AI's contribution
  honestly, and lets tests assert that static findings survive with AI disabled.
- **`blockedBy` / `blocks`** — the dependency graph between findings makes `roadmap` *derivable*
  rather than invented, and is exactly the input a future autonomous planner needs to order work.
- **`evidence` with `file:line`** — the difference between a report a developer trusts and one they
  dismiss. Clickable in the Phase 2 UI.
- **`meta.llm` token counts** — visible cost per run, for when someone asks "what does this cost on
  our real codebase?"

### Fixed enums

| Enum | Values |
|---|---|
| `FindingCategory` | `JAVA_VERSION`, `SPRING_MODERNIZATION`, `DEPENDENCY_HEALTH`, `DEPRECATED_API`, `SOAP_WEBSERVICE`, `SOAP_TO_REST`, `XML_CONFIGURATION`, `SECURITY`, `TESTING`, `BUILD`, `CODE_QUALITY`, `CLOUD_READINESS` |
| `Severity` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` |
| `RiskLevel` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `Confidence` | `HIGH`, `MEDIUM`, `LOW` |
| `FindingSource` | `STATIC`, `AI`, `STATIC_AI` |
| `EffortSize` | `XS`, `S`, `M`, `L`, `XL` |
| `BuildTool` | `MAVEN`, `GRADLE`, `ANT`, `UNKNOWN` |
| `FileKind` | `MAVEN_POM`, `GRADLE_BUILD`, `JAVA_SOURCE`, `JAVA_TEST`, `WEB_XML`, `SPRING_XML`, `PROPERTIES`, `YAML`, `WSDL`, `XSD`, `JSP`, `DOCKERFILE`, `CI_CONFIG`, `NOT_READ`, `OTHER` |

`FindingCategory` maps one-to-one onto the twelve requested detection areas.

**`Severity` and `RiskLevel` are separate axes, added at Step 1.** Severity is how bad the problem
is; risk is how dangerous the *fix* is. A finding can be `CRITICAL` severity but `LOW` risk (bump a
dependency with a known CVE) or `MEDIUM` severity but `HIGH` risk (rewrite the security filter
chain). Collapsing them into one enum would destroy the information the roadmap needs to sequence
cheap-and-safe work ahead of expensive-and-dangerous work. `RiskLevel` has no `INFO` value, since
"informational risk" is not a meaningful statement.

---

## 12. Development steps

Each step is one commit, ends in a runnable/testable state, and is preceded by a
*what / why / which files* explanation before any file is touched.

| # | Step | Deliverable | Needs API key? |
|---|---|---|---|
| 0 | **Scaffold** ✅ **done** | `pom.xml`, Maven Wrapper, `ModernizerApplication`, `application.yml`, `ModernizerProperties`, `HealthController`, `.gitignore`, `README`. `GET /api/v1/health` returns 200; 4/4 tests green. | No |
| 1 | **Domain model** ✅ **done** | 8 enums + 14 records in `model/`. 31 tests green, including a JSON contract test pinning §11's field names. | No |
| 2 | **Scanner + guard** ✅ **done** | `scanner/` package; `POST /api/v1/scan` returns a **response DTO** projected from `ProjectContext` (not the context itself - it holds an absolute path). Plus `demo/legacy-sample-app/` to scan. 164 tests green, including `ScanGuard` traversal rejection and an end-to-end assertion that no absolute path reaches the response. | No |
| 3 | **Static analyzers** | `Analyzer` SPI + `MavenPomAnalyzer`, `JavaSourceAnalyzer`, `XmlConfigAnalyzer`. `POST /api/v1/analysis` returns a real report with `findings[]`; `roadmap` empty. **Already a credible demo on its own.** | No |
| 4 | **LLM port + mock** | `ai/LlmClient`, `LlmRequest/Response`, `MockLlmClient` with a canned assessment. Full end-to-end report shape including `roadmap` and `cloudReadiness`. Proves the swap point before Anthropic exists in the codebase. | No |
| 5 | **Anthropic provider** | `AnthropicLlmClient`, `PromptBuilder`, `ContextSerializer`, `Redactor`, token budgeting, `prompts/assessment-system.md`. `Redactor` test suite. | **Yes** |
| 6 | **Assemble & score** | `ReportAssembler`: dedupe static vs AI findings, compute `modernizationScore`, derive `roadmap` from the blocking graph, populate `meta`. | Yes (to tune) |
| 7 | **Harden the demo** | `@ControllerAdvice`, typed SDK exception mapping, timeouts, `demo.http`/curl script, README walkthrough. Optional Swagger UI. | Yes |
| 7.5 | **Second provider** *(optional)* | `GrokLlmClient` or `GeminiLlmClient` + `LlmClientRegistry`, enabling side-by-side provider comparison. | Yes |
| 8 | **HTML UI** *(Phase 2)* | Single-page `static/index.html` + `app.js`: path input, findings table grouped by category with severity colouring, roadmap timeline. No build toolchain. | Yes |

**Steps 0–4 need no API key.** If the key or network is unavailable on demo day, there is still a
working product to show. Steps 5–7 are where Claude enters, and by then the seam it plugs into is
already tested.

**Recommended stopping point for a first showcase:** Step 7. Step 8 is a nice-to-have worth starting
only after seeing the real JSON and knowing which fields deserve screen space.

---

## 13. Verification per step

```bash
# build + test
./mvnw -f backend/pom.xml clean verify

# run
./mvnw -f backend/pom.xml spring-boot:run

# Step 0
curl http://localhost:8080/api/v1/health

# Step 2
curl -X POST http://localhost:8080/api/v1/scan \
     -H "Content-Type: application/json" \
     -d '{"path":"demo/legacy-sample-app"}'

# Step 3+
curl -X POST http://localhost:8080/api/v1/analysis \
     -H "Content-Type: application/json" \
     -d '{"path":"demo/legacy-sample-app","aiAssessment":false}'
```

---

## 14. Open items

| Item | Decision needed by | Status |
|---|---|---|
| Include `ModernizerProperties` in Step 0, or defer to Step 2? | Step 0 | ✅ included in Step 0 |
| Include `springdoc-openapi` for a live Swagger UI? | Step 7 | ⏸ deferred — Boot 4 compatibility needs verifying (see §7) |
| Verify Anthropic SDK structured output against Jackson 3 annotations | Step 5 | ⬜ open |
| Add a second LLM provider (Step 7.5)? | After Step 7 | ⬜ open |
| Move the HTML UI earlier than Step 8? | After Step 3 | ⬜ open |

---

## 15. Future phases (not designed in detail)

Phase 1 is deliberately shaped so these are additive rather than rewrites:

- **`agent/`** — the autonomous loop. Consumes `ProjectContext` as its world model and `roadmap` as
  its task queue.
- **`tool/`** — tool definitions for the agent (read file, search, apply patch, run build).
- **SOAP analyzer** — a deeper `Analyzer` implementation parsing WSDL/XSD to propose concrete REST
  resource mappings.
- **`planner/`** — turning the roadmap into ordered, verifiable work items.
- **`transform/`** — code modification. Requires JavaParser or OpenRewrite, plus a diff-preview and
  approval gate before any write.
- **`validation/`** — running the target project's build and tests to verify a transformation, which
  is what makes autonomous modernization safe at all.
