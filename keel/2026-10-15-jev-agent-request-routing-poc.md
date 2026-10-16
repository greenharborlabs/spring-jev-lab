# Jev agent request routing proof of concept
Status: closed
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
- [x] Add the `liveTest` source set, the corpus, and the task. Check: `./gradlew liveTest` passes against the real API, with latency and token usage recorded. If the key is unavailable, record `blocked`; if the run times out or stops partway, record `incomplete`.
- [x] Write `README.md`: setup with the key in the environment only, how to run, the live results table, observed latency, accuracy against the labels, cost per call, the limits from Constraints, and a fit assessment for paygate and Harbor Proxy. Check: every number in the README traces to the recorded live run.
- [x] Run `git diff --check 1803554..HEAD`. Then run `git grep -nE "TYPESAFE_API_KEY=|Bearer "` and confirm that every match is either an environment-variable reference or the fixed fake test key `test-key-not-real`. Check: both pass.

## Review
WORK on 2026-10-15 with Java 25.0.1 and Gradle 9.4.1. The implementation is committed at `bc01ea9a6993683f2a963e1770751b181a36303b`; the scope also includes the root commit's `.gitignore`. The first pass left the task at `working` because `liveTest` was blocked. The live run below unblocked it.

- **pass:** `./gradlew build` on the empty scaffold, exit 0.
- **pass:** `./gradlew clean build --rerun-tasks` at `bc01ea9`, exit 0, 8 tasks executed. The test XML records 38 tests, all with zero skips, failures, or errors:
  - `JevClientTest`: 14;
  - `AgentRequestRouterTest`: 22;
  - `RouteControllerTest`: 2, a full context without a key that returns `REVIEW` / `jev_not_configured`.

  Every case in the client and router task lists is covered. This includes the read timeout (not retried), 529 retries running out with 200 ms and 400 ms backoff, a 422 body that echoes the key being redacted, the header allowlist, and the 2 KB body bound. All of it runs against `MockRestServiceServer` or a Mockito stub. The real wire contract is unverified until `liveTest` runs.
- **blocked (first attempt):** `./gradlew liveTest`. `TYPESAFE_API_KEY` was not set, so the task stopped with `BLOCKED: TYPESAFE_API_KEY is not set` and `BUILD FAILED`. No live call was made.
- **pass:** `TYPESAFE_API_KEY="$JEV_API_KEY" ./gradlew liveTest` at `ffbf1a8`, 2026-10-16 02:16:47 UTC, model `jev-1.13.0`, `BUILD SUCCESSFUL`. The key lives in `JEV_API_KEY` on this machine and was passed under the name the code reads; it was not printed. Both required assertions held:
  - usable answers: 12/12;
  - abusive requests allowed: 0/3, all three `BLOCK` / `abuse_high`.

  Recorded, not gated: intent matched the label 12/12; latency median 87 ms and maximum 295 ms (the first call); 561–594 input tokens per call, 6,904 in total. No case fell in the 0.3–0.8 `abuse_possible` band.
- **pass:** `README.md`. The live results table is copied from `build/live-results.md`, and every number in the results and fit sections traces to that run. Cost per call is given in tokens only, because the run records no price.
- **pass:** `git diff --check 1803554..HEAD`, exit 0.
- **pass:** `git grep -nE "TYPESAFE_API_KEY=|Bearer "`. Every match is the README's `export TYPESAFE_API_KEY=...` placeholder, plan text, the client's `"Bearer " + properties.apiKey()`, or the fake test key `KEY = "test-key-not-real"`.

### Attempt 1
Reviewed revision: 819bf58f2fb0aaa878f481548480a4b749029172
Verdict: pass
Findings and dispositions:
1. No correctness, security, data-loss, or acceptance failures found. Fail-closed behavior, the threshold table, the header allowlist, body-excerpt bound, key redaction, and the `liveTest` fail-on-missing-key behavior all match the Decisions and Constraints.
2. Non-blocking: the explicit `spring-boot-starter-test` test dependency is redundant, because `spring-boot-starter-webmvc-test` already brings it in transitively (confirmed with `dependencyInsight`). It adds no dependency beyond the named stack, so it does not violate "no further dependencies". No rework.
3. Non-blocking: the plan lists a typed rate-limiting exception, but 429/529 are handled by the retry path and surface as `Unavailable` once attempts run out. Behavior and README text are correct, so no rework.
4. Non-blocking: `AgentRequest.excerpt` bounds by Java chars (2048) while the README says "the first 2 KB of the body"; a non-ASCII body excerpt can exceed 2 KB as UTF-8 bytes. Small and bounded; no rework.
Verification:
- `./gradlew clean build --rerun-tasks` at `819bf58` — pass, exit 0, 8 tasks executed. Test XML: 38 tests (JevClientTest 14, AgentRequestRouterTest 22, RouteControllerTest 2), 0 skipped, 0 failures, 0 errors. Runs without network access.
- `TYPESAFE_API_KEY="$JEV_API_KEY" ./gradlew liveTest` at `819bf58` — pass, `BUILD SUCCESSFUL`, against model `jev-1.13.0`. Usable answers 12/12; intent matched label 12/12; abusive requests not allowed 3/3, all `BLOCK` / `abuse_high`. The key was passed through the environment and was not printed. Fresh run, so latency and per-call token counts differ slightly from the recorded run, but every decision and intent matches.
- README numbers — pass. Checked against the pre-existing `build/live-results.md` from the recorded 2026-10-16 02:16:47 UTC run before rerunning `liveTest`; the results table matched row-for-row, and the summary numbers (median 87 ms, max 295 ms; min/max/mean tokens 561/594/575; total 6,904; abuse 0.92–0.99 benign 0.02–0.07; 10 answers at confidence ≥ 0.95) all trace to that run. Rerunning `liveTest` overwrote that artifact with a new run, which is a gitignored build output and not part of the reviewed revision.
- `git diff --check 1803554..HEAD` — pass, exit 0, no whitespace errors.
- `git grep -nE "TYPESAFE_API_KEY=|Bearer "` — pass. Every match is the README `export TYPESAFE_API_KEY=...` placeholder, plan/record prose, `"Bearer " + properties.apiKey()` in the client, or the fixed fake key `test-key-not-real`.
- Limits: the live run exercises only Choice and Noul success responses. Score and the documented error responses (400, 401, 413, 415, 422, 429, 529) are covered offline but not against the live API; the record and README state this.

## Outcome
Closed on 2026-10-15 after review Attempt 1 passed at `819bf58f2fb0aaa878f481548480a4b749029172`.

What changed:
- **A typed Java client for Jev's `/v1/systemone`.**
  - It covers all three question types (Choice, Score, Noul) and maps errors to typed failures.
  - Timeouts are 2 s to connect and 5 s to read. Retries happen only on 429, 529, and connection failures, with at most 3 attempts.
  - Responses are validated strictly, and the key is redacted everywhere.
- **A fail-closed agent request router and a `POST /route` demo endpoint.** One Jev call asks for an intent and an abuse probability, and plain Java thresholds turn the answers into `ALLOW`, `REVIEW`, or `BLOCK`.
- **A `liveTest` task with a 12-case labelled corpus.**
- **The [README](../README.md),** covering setup, routing rules, client behaviour, live results, known limits, and a fit assessment for paygate and Harbor Proxy.

Verification at `819bf58`:
- **Offline build:** `./gradlew clean build --rerun-tasks` passed, with 38 tests, 0 skipped, and 0 failures.
- **Live run:** `./gradlew liveTest` passed against `jev-1.13.0`.
  - Usable answers: 12/12.
  - Intent matched the label: 12/12.
  - Abusive requests allowed: 0 of 3. All three were blocked.
- **Hygiene:** the whitespace check and the committed-secret check both passed.
- **README numbers:** every number traces to the recorded run of 2026-10-16 02:16:47 UTC.

Findings from the fit assessment ([README](../README.md#fit-for-paygate-and-harbor-proxy)):
- Median latency was 87 ms, and each call used about 575 input tokens.
- Jev flagged an injection payload hidden in a request body, which paygate's checks would not catch.
- Jev stays an advisory signal. The recommended first deployment in Harbor Proxy is background scoring, where Jev's answers are only logged, not inline gating.

The review raised three non-blocking points, none needing rework:
- an explicit test dependency that is redundant;
- rate limits surface as `Unavailable` rather than as a separate rate-limit exception;
- the body excerpt is limited to 2048 characters, not 2 KB of UTF-8, so a non-ASCII body can exceed 2 KB.

Limits and open questions:
- Score and the documented error responses are covered only by offline tests, not against the live API.
- The corpus is small and its abusive cases are blatant, so these results do not measure accuracy.
- No request fell in the `abuse_possible` band (0.3–0.8), so that rule was not tested live.
- The thresholds have not been tuned.
- Cost per call is known in tokens only, because the run recorded no price.
- The key on this machine lives in `JEV_API_KEY`. It was passed to the code under the name `TYPESAFE_API_KEY`, which is the only name the code reads.

## Next action
Get TypeSafe's per-token price for `jev-1.13`, then complete the cost comparison in the [README fit assessment](../README.md#fit-for-paygate-and-harbor-proxy).
