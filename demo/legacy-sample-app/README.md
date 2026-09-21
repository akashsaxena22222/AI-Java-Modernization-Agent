# Acme Legacy Order Service — demo fixture

**This project is deliberately terrible. Do not fix it, do not build it, do not copy from it.**

It exists as a realistic analysis target for the AI Java Modernization Agent, so the tool can be
demonstrated on any machine with nothing to install and no real customer code involved.

It is never compiled. Nothing here is on the analyzer's classpath — the analyzer only reads these
files as text.

> **Every credential in this fixture is fabricated.** The plaintext passwords, API keys, and tokens
> in `application.properties` are invented for this fixture. They exist so the analyzer has
> something to detect and the `Redactor` (Step 5) has something to redact.

## What has been planted here, and why

| Planted problem | Where | Should be detected as |
|---|---|---|
| Java 8 target | `pom.xml` properties | `JAVA_VERSION` |
| Spring 4.3.9 | `pom.xml` | `SPRING_MODERNIZATION` |
| Spring Security 3.2.9 with XML config | `spring-security.xml` | `SECURITY`, `XML_CONFIGURATION` |
| CSRF protection explicitly disabled | `spring-security.xml` | `SECURITY` |
| SOAP endpoint with `security="none"` | `spring-security.xml` | `SECURITY` |
| Static remember-me key in version control | `spring-security.xml` | `SECURITY` |
| XML bean definitions, no component scanning | `applicationContext.xml` | `XML_CONFIGURATION` |
| `hbm2ddl.auto=update` against production | `applicationContext.xml` | `SECURITY`, `CODE_QUALITY` |
| No connection pooling (`DriverManagerDataSource`) | `applicationContext.xml` | `CODE_QUALITY` |
| Hand-wired JSP view resolution | `dispatcher-servlet.xml` | `XML_CONFIGURATION` |
| `web.xml` with the Servlet 2.3 DTD | `WEB-INF/web.xml` | `XML_CONFIGURATION`, `BUILD` |
| Hand-rolled encoding filter | `LegacyEncodingFilter.java` | `CODE_QUALITY` |
| Query strings written to stdout as an access log | `LegacyEncodingFilter.java` | `SECURITY` |
| JAX-WS SOAP endpoint | `OrderService.java`, `wsdl/OrderService.wsdl` | `SOAP_WEBSERVICE`, `SOAP_TO_REST` |
| Unpaged unbounded SOAP response | `wsdl/OrderService.wsdl` | `CODE_QUALITY` |
| `javax.*` namespace throughout | all sources | `DEPRECATED_API` |
| log4j 1.2.17 (EOL) | `pom.xml` | `DEPENDENCY_HEALTH` |
| commons-collections 3.2.1 (deserialization CVE) | `pom.xml` | `DEPENDENCY_HEALTH` |
| jackson-databind 2.9.4 (multiple CVEs) | `pom.xml` | `DEPENDENCY_HEALTH` |
| Dependency with no version | `pom.xml` (`commons-lang`) | `BUILD` |
| Unpinned plugin versions | `pom.xml` | `BUILD` |
| JUnit 3, `extends TestCase` | `OrderServiceTest.java` | `TESTING` |
| One test file for the whole project | project-wide | `TESTING` |
| MD5 password hashing | `PasswordUtil.java` | `SECURITY` |
| Plaintext DB password | `application.properties` | `SECURITY` |
| Credentials embedded in a JDBC URL | `application.properties` | `SECURITY` |
| Hardcoded absolute filesystem paths | `application.properties` | `CLOUD_READINESS` |
| Filesystem session state | `application.properties`, `web.xml` | `CLOUD_READINESS` |
| Static `SimpleDateFormat` (not thread-safe) | `DateUtil.java` | `CODE_QUALITY` |
| `sun.misc.BASE64Encoder` (removed in Java 9) | `DateUtil.java` | `DEPRECATED_API` |
| Hibernate 4 session management | `OrderDao.java` | `SPRING_MODERNIZATION` |
| JSP view layer with scriptlets | `WEB-INF/jsp/order-list.jsp` | `CODE_QUALITY` |
| Unescaped request parameters in JSPs | `order-list.jsp`, `login.jsp` | `SECURITY` |
| Business logic duplicated into the view | `order-list.jsp` | `CODE_QUALITY` |
| Stack trace rendered to the browser | `WEB-INF/jsp/error.jsp` | `SECURITY` |
| Credentials posted over plain HTTP | `login.jsp` | `SECURITY` |
| ISO-8859-1 source encoding | `pom.xml`, throughout | tolerated by the scanner's decoder |
| Binary inventoried but never read | `webapp/images/acme-logo.png` | `FileKind.NOT_READ` |

### On the binary

The fixture uses a PNG for the never-read case rather than the `lib/legacy-util.jar` an earlier
draft of this file described. The repository's own `.gitignore` excludes `*.jar`, `*.pem`, `*.jks`,
`*.p12`, and `.env` — which is exactly the material worth planting, and exactly the material that
should not be committed. Those extensions are covered by `FileClassifierTest` instead; the PNG
carries the same `NOT_READ` behaviour in the committed tree.

## File kinds this fixture exercises

`MAVEN_POM`, `JAVA_SOURCE`, `JAVA_TEST`, `WEB_XML`, `SPRING_XML`, `PROPERTIES`, `WSDL`, `XSD`,
`JSP`, `NOT_READ`, `OTHER` — asserted by `ProjectScannerTest`, so the fixture and the test suite
cannot drift apart without one of them failing.

## Scanning it

From the repository root:

```bash
curl -X POST http://localhost:8080/api/v1/scan \
     -H "Content-Type: application/json" \
     -d '{"path":"demo/legacy-sample-app"}'
```
