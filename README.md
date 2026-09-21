# AI Java Modernization Agent

Scans a legacy Java project, derives facts deterministically, asks an LLM to judge them, and
returns a structured modernization assessment as JSON.

**Phase 1 is an analyzer only. It never modifies the project under analysis.**

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design, the report JSON contract, and the
step-by-step development plan.

---

## Current status

| Step | Deliverable | Status |
|---|---|---|
| 0 | Scaffold: build, app boots, `GET /api/v1/health` | ✅ done |
| 1 | Domain model (`Finding`, `ProjectContext`, `ModernizationReport`) | ✅ done |
| 2 | Project scanner + path safety guard + demo fixture | ✅ done |
| 3 | Static analyzers (Maven pom, Java source, XML config) | ⬜ next |
| 4 | `LlmClient` port + mock provider | ⬜ |
| 5 | Anthropic provider, prompt building, redaction, token budget | ⬜ |
| 6 | Report assembly, scoring, roadmap derivation | ⬜ |
| 7 | Error handling, timeouts, demo script | ⬜ |
| 8 | HTML UI (Phase 2) | ⬜ |

Steps 0–4 require no API key.

---

## Prerequisites

- **Java 21+** (`java -version`)
- **No Maven installation needed** — the repo ships the Maven Wrapper, which downloads Maven itself.

> The first build downloads Maven 3.9.16 and the whole Spring Boot dependency tree.
> Expect **3–10 minutes once**; every build after that takes seconds.

---

## Build and run

```bash
# build + run tests
./mvnw -f backend/pom.xml clean verify

# run the application
./mvnw -f backend/pom.xml spring-boot:run
```

On Windows PowerShell or cmd, use `.\mvnw.cmd` instead of `./mvnw`.

### Verify it works

```bash
curl http://localhost:8080/api/v1/health
```

```json
{
  "status" : "UP",
  "application" : "ai-java-modernization-agent",
  "analyzerVersion" : "0.1.0",
  "javaVersion" : "21.0.12.1",
  "timestamp" : "2026-09-19T09:41:12.430Z",
  "ai" : {
    "enabled" : false,
    "provider" : "anthropic",
    "model" : "claude-opus-5",
    "apiKeyPresent" : false
  }
}
```

The `ai` block reports the effective configuration, so "is it actually wired up to Claude?" is
answerable with one curl instead of by reading logs.

### Scan a project

Run this **from the repository root**, so the relative `./demo` allow-root resolves:

```bash
curl -X POST http://localhost:8080/api/v1/scan \
     -H "Content-Type: application/json" \
     -d '{"path":"demo/legacy-sample-app"}'
```

```json
{
  "project" : {
    "name" : "legacy-sample-app",
    "rootPath" : "legacy-sample-app",
    "buildTool" : "MAVEN",
    "modules" : [ "." ],
    "detectedFrameworks" : [ ],
    "metrics" : {
      "javaFiles" : 8, "testFiles" : 1, "linesOfCode" : 416,
      "xmlConfigFiles" : 4, "wsdlFiles" : 1, "jspFiles" : 5,
      "declaredDependencies" : 0, "testToSourceRatio" : 0.125
    }
  },
  "fileCountsByKind" : {
    "MAVEN_POM" : 1, "JAVA_SOURCE" : 8, "JAVA_TEST" : 1, "WEB_XML" : 1,
    "SPRING_XML" : 3, "PROPERTIES" : 2, "WSDL" : 1, "XSD" : 1, "JSP" : 5,
    "NOT_READ" : 1, "OTHER" : 2
  },
  "scan" : {
    "filesScanned" : 25, "filesSkipped" : 1, "bytesRead" : 60082,
    "durationMs" : 7, "limitsHit" : false, "warnings" : [ ]
  }
}
```

This is the inventory with **no interpretation** — `detectedFrameworks` and `declaredDependencies`
stay empty until Step 3 parses the build files. `POST /api/v1/analysis` is the interesting endpoint
once that lands.

Note the deliberate absences: no absolute path anywhere in the response, and `NOT_READ` for the one
binary in the fixture. Both are asserted by tests rather than left to inspection.

### The scan is refused unless it is safe

`modernizer.scan.allowed-roots` is a security boundary, not a convenience — the path arrives over
HTTP. Every rejection carries a machine-readable code:

```bash
curl -X POST http://localhost:8080/api/v1/scan \
     -H "Content-Type: application/json" -d '{"path":"C:/Users"}'
# 400  {"error":"OUTSIDE_ALLOWED_ROOTS", ...}
```

| Request | Code | HTTP |
|---|---|---|
| `C:/Users`, or anything outside the allowlist | `OUTSIDE_ALLOWED_ROOTS` | 400 |
| `demo/legacy-sample-app/../..` (traversal) | `OUTSIDE_ALLOWED_ROOTS` | 400 |
| a symlink pointing out of an allowed root | `OUTSIDE_ALLOWED_ROOTS` | 400 |
| `demo` (reachable, but not a Java project) | `NOT_A_JAVA_PROJECT` | 400 |
| a path that does not exist | `NOT_FOUND` | 400 |
| a file instead of a directory | `NOT_A_DIRECTORY` | 400 |
| **allowlist empty or unresolvable** | `ALLOWLIST_EMPTY` | 500 |

The last row is the fail-closed rule: with no usable allowlist there is no scanning at all, and it
reports as a server misconfiguration rather than a bad request — so an operator looks at their
config instead of their curl command.

---

## Configuration

Everything lives under the `modernizer.*` block of
[`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml), which
documents each value inline. The three you are most likely to change:

| Property | Default | Purpose |
|---|---|---|
| `modernizer.scan.allowed-roots` | `./demo` | Directories a scan target may sit under. **Empty list refuses every scan.** Add your own paths to analyze real projects. |
| `modernizer.ai.enabled` | `false` | Egress gate — see below |
| `modernizer.ai.model` | `claude-opus-5` | Switching Anthropic models is this one line, no code change |

### The API key

Read **only** from the `ANTHROPIC_API_KEY` environment variable. There is deliberately no
`api-key` property, so it cannot be committed in yaml or captured in a logged properties object.

```bash
# bash
export ANTHROPIC_API_KEY=sk-ant-...

# PowerShell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

Needed from Step 5 onward only.

---

## What gets sent to the LLM

`modernizer.ai.enabled` defaults to **`false`**: no project-derived data leaves the machine unless
you switch it on. Static analysis works fully air-gapped.

When enabled, we send **facts, not source code** — a 60k-LOC project becomes roughly 20 KB of JSON:

**Transmitted**
- Dependency coordinates and versions from `pom.xml`
- File-kind counts and aggregate metrics (LOC, test ratio, XML/WSDL/JSP counts)
- Detected framework and language versions
- Static findings, each with a project-relative `file:line` reference
- Up to 40 redacted code snippets, ≤ 3 lines and ≤ 200 chars each

**Never transmitted**
- Whole source files
- Property or YAML **values** whose key matches `pass|pwd|secret|token|key|credential|dsn` —
  replaced with `***REDACTED***`. The *presence* of a plaintext credential is reported as a
  finding; the value is not sent.
- Credential material: `.env`, `*.pem`, `*.jks`, `*.p12`, keystores, `id_rsa*`, AWS key patterns,
  `BEGIN PRIVATE KEY` blocks, JDBC URLs with embedded credentials
- Absolute filesystem paths (rewritten project-relative, so machine layout is not leaked)
- Binaries: jars, wars, class files, images, archives

If the payload would exceed `modernizer.ai.max-input-tokens`, it is reduced in a defined order
(snippets → low-severity findings → per-file detail collapsed to per-module counts) and the report
sets `meta.truncated: true` naming what was dropped. It is never truncated silently.

Full detail: [ARCHITECTURE.md § 9](ARCHITECTURE.md).

---

## Project layout

```
ARCHITECTURE.md          design, report JSON contract, development plan
mvnw / mvnw.cmd / .mvn/  Maven Wrapper (no local Maven required)
backend/
  pom.xml
  src/main/java/com/company/modernizer/
    controller/          REST API + response DTOs
    service/             orchestration            (Step 3+)
    scanner/             safe filesystem walk
    analyzer/            detection rules (SPI)    (Step 3)
    ai/                  provider-agnostic LLM port (Steps 4-5)
    model/               domain records
    config/              ModernizerProperties
  src/main/resources/
    application.yml
    prompts/             system prompts as resources (Step 5)
    static/              HTML UI (Phase 2)
demo/legacy-sample-app/  deliberately-legacy project to analyze
```

---

## Tech stack

| Component | Choice |
|---|---|
| Language | Java 21 (records, no Lombok) |
| Framework | Spring Boot 4.1.1 |
| Build | Maven via wrapper |
| LLM | Anthropic `claude-opus-5` behind a provider-agnostic `LlmClient` port |

Swapping to Gemini, Grok, or OpenAI means adding one class in `ai/` and changing one config
property — the Anthropic SDK import is confined to a single file.
