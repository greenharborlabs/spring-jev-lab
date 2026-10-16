# Jev agent request routing proof of concept
Status: working
Base revision: 344b66afe45dd651dae3e85305da36a04ffa078d
Review scope: settings.gradle.kts, build.gradle.kts, gradlew, gradlew.bat, gradle/wrapper/, src/main/, src/test/, src/liveTest/, README.md, .gitignore

## Goal
Find out how TypeSafe AI's Jev model could fit into Green Harbor services such as paygate and Harbor Proxy. Build a small Spring Boot app with two parts:
- **A typed Java client** for `POST https://api.typesafe.ai/v1/systemone`. TypeSafe ships SDKs only for Python and JavaScript.
- **An agent request router.** One Jev call per request asks for an intent (a Choice) and an abuse signal (a Noul). Deterministic code then turns the answers and their confidence into `ALLOW`, `REVIEW`, or `BLOCK`.

Acceptance has three parts:
- **Offline tests pass.** They cover the client's wire format, error handling, and retries, plus the router's decision table, without network access.
- **A live run completes.** A labelled request corpus runs against the real API, and its decisions, latency, and token usage are recorded.
- **The README records the findings.** It covers setup, results, limits, and a fit assessment for paygate and Harbor Proxy.

## Constraints
- Use the same stack as `spring-boot-starter-paygate`: Java 25.0.1 toolchain, Gradle 9.4.1 wrapper with its `distributionSha256Sum`, and Spring Boot 4.0.8 with `spring-boot-starter-web` and `spring-boot-starter-webmvc-test`. Use one module with no further dependencies.
- **API contract** (TypeSafe [API reference](https://docs.typesafe.ai/api.md), read 2026-10-15):
  - Request: a Bearer token, `state` (a string or JSON), `model: "jev-latest"`, and named questions.
  - Choice: up to 255 options. Score: 2–10 levels. Noul: optional `true`/`false` criteria.
  - Responses carry typed answers, with `probabilities` and `confidence` for Choice and Score.
  - Errors: 400, 401, 413, 415, 422, 429, and 529. The documentation says to back off exponentially on 429 and 529.
  - These details come from the documentation and have not been verified against the live API.
- **The API key comes only from the `TYPESAFE_API_KEY` environment variable.** It is never logged, committed, or echoed in error messages or responses.
- **Data sent to Jev leaves the machine.** The router sends only request metadata and a bounded excerpt of the body. It never sends `Authorization` headers, macaroons, preimages, invoices, or cookies.
- **Known model limits** (jev-1.13 [limitations](https://docs.typesafe.ai/model-jaggedness/jev-1.13.md)):
  - Adversarial state can steer answers.
  - Choice leans toward the first option.
  - Negations are read literally.
  - Irrelevant state is a distractor.

  So Jev is an advisory signal and never the sole security gate.
- **Live checks need the network and a valid key.** A missing key is recorded as `blocked`. A timeout or partial run is recorded as `incomplete`. Neither counts as `pass`.

## Decisions
- **Fail closed.** No path returns `ALLOW` unless Jev answered successfully and the thresholds are met:
  - A missing key, timeout, error, retries running out, or a malformed answer gives `REVIEW` with a reason code.
  - The app still starts without a key, so the demo can show this behaviour.
- **One Jev call per request, with two questions.**
  - `intent`: a Choice over `admin_or_destructive`, `payment_or_credential`, `write`, `read`, and `other`. The most restrictive option comes first, so Jev's first-option lean errs toward caution.
  - `abuse`: a Noul that asks whether the request tries prompt injection, credential extraction, or actions beyond its stated purpose.
- **Starting thresholds, set in `jev.routing.*`:**
  - abuse ≥ 0.8 gives `BLOCK`;
  - abuse ≥ 0.3 gives `REVIEW`;
  - `admin_or_destructive`, `payment_or_credential`, and `other` always give `REVIEW`;
  - `read` gives `ALLOW` at confidence ≥ 0.6, and `write` at confidence ≥ 0.85;
  - anything else gives `REVIEW`.

  These are conservative guesses to measure against, not tuned values. TypeSafe says thresholds depend on the domain.
- **Client shape:**
  - Plain records with a sealed `Question` type (Choice, Score, Noul) and a sealed answer type.
  - A `RestClient` built directly on the JDK HTTP client, with a 2 s connect timeout and a 5 s read timeout. TypeSafe claims 70–500 ms per call.
  - Up to 3 attempts with exponential backoff, only on 429, 529, and connect timeouts.
  - Typed exceptions for authentication, validation, rate limiting, and unavailability.
  - Score is modelled so the client covers the whole API, but the router does not use it.
- **Demo endpoint:** `POST /route` takes an `AgentRequest` (method, path, agent ID, declared purpose, body excerpt of up to 2 KB) and returns the decision, the answers, the reasons, the model version, and the latency. It does no real payment gating.
- **Live tests:** a separate `liveTest` source set and Gradle task.
  - They run about 12 labelled corpus requests: benign reads and writes, payment, admin, and prompt-injection or exfiltration attempts.
  - The task fails, rather than skips, when `TYPESAFE_API_KEY` is unset.
  - It asserts that every answer is well formed and that no labelled abuse request returns `ALLOW`.
  - It prints a per-request table for the README.
- **Rejected:**
  - *A Spring Boot starter or auto-configuration module.* That is premature until a second consumer exists.
  - *Spring AI.* It is not needed for a typed HTTP call and would add a second provider.
  - *WireMock or Testcontainers.* `MockRestServiceServer` covers the client offline with no extra dependency.
  - *Letting Jev's `ALLOW` bypass deterministic checks.* The documented adversarial steering rules it out.

Approved: 2026-10-15 by Mark at plan revision 344b66afe45dd651dae3e85305da36a04ffa078d.

## Tasks
- [x] Scaffold the build: copy the Gradle 9.4.1 wrapper from paygate with its checksum, then add a Java 25 toolchain, Spring Boot 4.0.8, and the app class. Check: `./gradlew build` passes on the empty app.
- [x] Implement the typed client, error mapping, and bounded retry. Write offline tests with `MockRestServiceServer` for:
  - the request JSON for all three question types and the Bearer header;
  - parsing of all three answer types;
  - the mapping of 401 and 422 errors;
  - a 429 followed by success, and retries running out;
  - a read timeout;
  - a check that the key never appears in exception messages.

  Check: `./gradlew test` passes.
- [x] Implement the router, its configuration, and `POST /route`. Write decision-table tests covering every threshold boundary, every fail-closed reason, and the exclusion of credential headers from Jev's `state`, plus a controller test with `spring-boot-starter-webmvc-test`. Check: `./gradlew build` passes.
- [ ] Add the `liveTest` source set, the corpus, and the task. Check: `./gradlew liveTest` passes against the real API, with latency and token usage recorded. If the key is unavailable, record `blocked`; if the run times out or stops partway, record `incomplete`.
- [ ] Write `README.md`: setup with the key in the environment only, how to run, the live results table, observed latency, accuracy against the labels, cost per call, the limits from Constraints, and a fit assessment for paygate and Harbor Proxy. Check: every number in the README traces to the recorded live run.
- [x] Run `git diff --check 1803554..HEAD`. Then run `git grep -nE "TYPESAFE_API_KEY=|Bearer "` and confirm that every match is either an environment-variable reference or the fixed fake test key `test-key-not-real`. Check: both pass.

## Review
WORK on 2026-10-15 with Java 25.0.1 and Gradle 9.4.1. The implementation is committed at `bc01ea9a6993683f2a963e1770751b181a36303b`; the scope also includes the root commit's `.gitignore`. The task stays at `working` because a required check is blocked.

- **pass:** `./gradlew build` on the empty scaffold, exit 0.
- **pass:** `./gradlew clean build --rerun-tasks` at `bc01ea9`, exit 0, 8 tasks executed. The test XML records 38 tests, all with zero skips, failures, or errors:
  - `JevClientTest`: 14;
  - `AgentRequestRouterTest`: 22;
  - `RouteControllerTest`: 2, a full context without a key that returns `REVIEW` / `jev_not_configured`.

  Every case in the client and router task lists is covered. This includes the read timeout (not retried), 529 retries running out with 200 ms and 400 ms backoff, a 422 body that echoes the key being redacted, the header allowlist, and the 2 KB body bound. All of it runs against `MockRestServiceServer` or a Mockito stub. The real wire contract is unverified until `liveTest` runs.
- **blocked:** `./gradlew liveTest`. `TYPESAFE_API_KEY` is not set in this session's environment or in an interactive zsh profile. The task stopped before any test ran, with `BLOCKED: TYPESAFE_API_KEY is not set`, and Gradle reported `BUILD FAILED`. No live call was made, so latency, token usage, accuracy, and the "no abuse allowed" assertion are all unverified.
- **incomplete:** `README.md`. Setup, running, routing rules, client behaviour, and known limits are written. The live results and the paygate/Harbor Proxy fit assessment are placeholders that depend on the blocked live run.
- **pass:** `git diff --check 1803554..HEAD`, exit 0.
- **pass:** `git grep -nE "TYPESAFE_API_KEY=|Bearer "`. Every match is the README's `export TYPESAFE_API_KEY=...` placeholder, plan text, the client's `"Bearer " + properties.apiKey()`, or the fake test key `KEY = "test-key-not-real"`.

No review attempt yet.

## Outcome
Pending.

## Next action
Set `TYPESAFE_API_KEY` in the environment, run `./gradlew liveTest`, and fill the README's live results and fit assessment from `build/live-results.md`. Then commit and set `Status: review`.
