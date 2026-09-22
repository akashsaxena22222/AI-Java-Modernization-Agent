# Acme Payroll — demo fixture #2 (Ant / Struts 1 / EJB 2)

**This project is deliberately terrible. Do not fix it, do not build it, do not copy from it.**

A second analysis target, chosen to share almost nothing with
[`legacy-sample-app`](../legacy-sample-app/README.md). Where that one is Java 8 / Maven /
Spring 4.3 / JAX-WS, this is **Java 1.4 / Ant / Struts 1 / EJB 2 / JBoss 4** — an older stratum
of Java entirely, and the kind of system where "just upgrade Spring" is not even a sentence that
applies.

It is never compiled and never deployed. The analyzer only reads these files as text.

> **Every credential here is fabricated.** The passwords and tokens in `conf/database.properties`
> were invented for this fixture. They are not real and are not in use anywhere.

## Why a second fixture

One sample proves the tool runs. Two prove it is reading the project rather than recognising it.
Specifically, this one exercises paths the first cannot:

| Exercised here, not in sample #1 | Why it matters |
|---|---|
| `BuildTool.ANT` | The first fixture is Maven. Ant has no dependency metadata at all. |
| A top-level `test/` source root | Ant convention, not `src/test/java`. |
| No machine-readable dependency list | Nothing to parse: versions exist only in jar filenames and a hand-written text file. |
| Container-managed security in `web.xml` | vs. Spring Security XML in the first fixture. |
| EJB 2 home/remote/bean triples | vs. annotated Spring beans. |
| Vendor lock-in descriptors (`jboss.xml`) | Deployment tied to one app server and version. |

## What has been planted here, and why

| Planted problem | Where | Should be detected as |
|---|---|---|
| Java 1.4 source/target | `build.xml` | `JAVA_VERSION` |
| Ant build, no dependency management | `build.xml` | `BUILD` |
| Hardcoded machine paths in the build | `build.xml` | `BUILD`, `CLOUD_READINESS` |
| `debug="off"`, warnings suppressed | `build.xml` | `BUILD` |
| Tests excluded from the default target | `build.xml` | `TESTING` |
| `haltonfailure="no"`, result never checked | `build.xml` | `TESTING` |
| Dependencies as unversioned jars in `lib/` | `lib/DEPENDENCIES.txt` | `DEPENDENCY_HEALTH`, `BUILD` |
| Struts 1 (end of life 2013) | `struts-config.xml`, actions | `DEPENDENCY_HEALTH` |
| commons-collections 3.2.1, log4j 1.2.14 | `lib/DEPENDENCIES.txt` | `DEPENDENCY_HEALTH` |
| EJB 2 home/remote/bean triples | `src/.../ejb/` | `DEPRECATED_API`, `CODE_QUALITY` |
| `Supports` transaction on a write method | `ejb-jar.xml` | `CODE_QUALITY` |
| JBoss-specific JNDI binding | `jboss.xml` | `CLOUD_READINESS` |
| Servlet 2.3 DTD descriptor | `web/WEB-INF/web.xml` | `XML_CONFIGURATION` |
| BASIC auth with no transport constraint | `web/WEB-INF/web.xml` | `SECURITY` |
| Reports servlet outside the security constraint | `web/WEB-INF/web.xml` | `SECURITY` |
| Authorization from a client-supplied form field | `PayrollSubmitAction.java`, `index.jsp` | `SECURITY` |
| SQL injection by string concatenation | `EmployeeDao.java` | `SECURITY` |
| Oracle-only SQL (`ROWNUM`, `NVL`, `to_date`) | `EmployeeDao.java` | `CLOUD_READINESS` |
| JDBC resources never closed | `EmployeeDao.java` | `CODE_QUALITY` |
| SHA-1 unsalted password hashing | `PayrollUtils.java` | `SECURITY` |
| Money as `double` throughout | `PayrollSubmitAction.java`, `PayrollSessionBean.java` | `CODE_QUALITY` |
| Static `SimpleDateFormat` shared across threads | `PayrollUtils.java`, `PayrollSubmitAction.java` | `CODE_QUALITY` |
| Raw `Vector`/`Hashtable`, no generics | throughout | `CODE_QUALITY` |
| Unbounded static cache, no eviction | `EmployeeAction.java`, `PayrollUtils.java` | `CODE_QUALITY` |
| Session-held search state | `EmployeeAction.java` | `CLOUD_READINESS` |
| Exceptions swallowed, zero returned on failure | `PayrollSessionBean.java` | `CODE_QUALITY` |
| Stack traces rendered to the browser | `EmployeeAction.java`, `error.jsp` | `SECURITY` |
| `System.out.println` as an audit trail | `EmployeeAction.java` | `CODE_QUALITY` |
| Log4j `DEBUG` writing salary data to disk | `conf/log4j.properties` | `SECURITY` |
| Appender pointed at a decommissioned host | `conf/log4j.properties` | `CLOUD_READINESS` |
| Plaintext DB and SMTP passwords | `conf/database.properties` | `SECURITY` |
| Credentials embedded in a JDBC URL | `conf/database.properties` | `SECURITY` |
| Hardcoded absolute output paths | `conf/database.properties` | `CLOUD_READINESS` |
| JUnit 3, one test class, trivial coverage | `test/.../PayrollUtilsTest.java` | `TESTING` |
| Scriptlets and unescaped output in JSPs | `web/*.jsp` | `CODE_QUALITY`, `SECURITY` |

## What is deliberately absent

The `lib/*.jar` files and the Eclipse `.project` / `.classpath` are **not committed**. The
analyzer's own `.gitignore` excludes `*.jar`, `.project` and `.classpath`, and that exclusion is
correct — a demo fixture is not a reason to start committing binaries. `lib/DEPENDENCIES.txt`
records what the real project keeps there.

That absence is faithful to the problem rather than a gap in it: the point of this fixture is that
**no machine-readable dependency list exists anywhere in the project**. A Maven analyzer has a
`pom.xml` to parse; here there is nothing, and the report should say so plainly instead of
reporting zero dependencies as though that were good news.

`docs/payslip-layout.png` is the one committed binary, so `FileKind.NOT_READ` is exercised.

## What Phase 1 will and will not find here

Being honest about the current analyzers, so the demo does not oversell:

- **Will find:** the Servlet 2.3 descriptor, the JSP view tier, plaintext credentials, hardcoded
  absolute paths, SHA-1 hashing, the JUnit 3 test, the file inventory and `BuildTool.ANT`.
- **Will not find yet:** anything requiring a dependency list, because there is no `pom.xml` —
  `MavenPomAnalyzer` stands down via `supports()` rather than pretending. Struts and EJB
  descriptors are inventoried as `OTHER`; a Struts/EJB analyzer is future work, and adding one
  means adding one `@Component` and touching no existing file.

## Scanning it

From the repository root:

```bash
curl -X POST http://localhost:8080/api/v1/scan \
     -H "Content-Type: application/json" \
     -d '{"path":"demo/legacy-payroll-ant"}'
```
