# spring-jev-lab

A Spring Boot proof of concept for [TypeSafe AI's Jev](https://docs.typesafe.ai/introduction), a "System One" decision model. You send Jev some state plus typed questions (Choice, Score, Noul), and it returns typed answers with probabilities and confidence.

The lab answers one question: **how could Jev fit into Green Harbor services such as paygate and Harbor Proxy?** It has two parts:

- **A typed Java client** (`com.greenharborlabs.jevlab.jev`) for `POST https://api.typesafe.ai/v1/systemone`. TypeSafe ships SDKs only for Python and JavaScript.
- **An agent request router** (`com.greenharborlabs.jevlab.routing`). One Jev call per request asks for an intent (a Choice) and an abuse probability (a Noul). Code then applies thresholds to produce `ALLOW`, `REVIEW`, or `BLOCK`.

Design and acceptance are recorded in [`keel/2026-10-15-jev-agent-request-routing-poc.md`](keel/2026-10-15-jev-agent-request-routing-poc.md).

## Setup

Requires Java 25. The Gradle wrapper (9.4.1) downloads everything else.

The API key is read only from the environment. Put it in your shell profile or another local secret store, never in this repository:

```sh
export TYPESAFE_API_KEY=...   # e.g. in ~/.zshrc
```

## Run

```sh
./gradlew build        # offline: client wire format, retries, routing decisions, controller
./gradlew liveTest     # calls the real API with a 12-case labelled corpus; needs TYPESAFE_API_KEY
./gradlew bootRun      # demo endpoint on :8080
```

```sh
curl -s localhost:8080/route -H 'Content-Type: application/json' -d '{
  "method": "GET", "path": "/api/articles/123", "agentId": "research-agent",
  "purpose": "Summarize this article for the user"}'
```

Without a key, the app still starts, and every request routes to `REVIEW` with reason `jev_not_configured`.

`liveTest` fails rather than skips when the key is missing. It writes its results table to `build/live-results.md`.

## How routing works

| Rule, in order | Result | Reason |
|---|---|---|
| Jev missing, failing, timing out, or answering outside the contract | `REVIEW` | `jev_*` |
| abuse ≥ 0.8 | `BLOCK` | `abuse_high` |
| abuse ≥ 0.3 | `REVIEW` | `abuse_possible` |
| intent `read`, confidence ≥ 0.6 | `ALLOW` | `read_confident` |
| intent `write`, confidence ≥ 0.85 | `ALLOW` | `write_confident` |
| intent `admin_or_destructive`, `payment_or_credential`, or `other` | `REVIEW` | `intent_requires_review` |
| anything else | `REVIEW` | `low_confidence` |

The thresholds live in `jev.routing.*`. They are conservative starting points, not tuned values.

Intent options are listed most restrictive first, because jev-1.13 leans toward the first Choice option.

Only `method`, `path`, `agent_id`, `declared_purpose`, the `accept`, `content-type` and `user-agent` headers, and the first 2 KB of the body are sent to Jev. Credentials, macaroons, invoices, and cookies never leave the process.

## Client behaviour

- **Timeouts:** 2 s connect and 5 s read, on the JDK HTTP client.
- **Retries:** up to 3 attempts with exponential backoff (200 ms, then 400 ms), only on HTTP 429, HTTP 529, and connection failures. A read timeout is not retried, because the call may already have been processed.
- **Typed failures:** `NotConfigured`, `Authentication` (401/403), `Rejected` (400/413/415/422), `Unavailable` (rate limit, overload, server error, timeout), and `Malformed`.
- **Strict validation:** a response that is missing fields, has probabilities outside [0, 1], or names a choice that isn't among the options is `Malformed`, never a guess.
- **Key handling:** the key never appears in exception messages, and `JevProperties.toString()` redacts it.

## Live results

Not yet recorded. The required `liveTest` run is blocked until `TYPESAFE_API_KEY` is available (see the task record). This section will be filled from `build/live-results.md` with:
- the per-case table;
- intent accuracy against the labels;
- how many abusive requests were held;
- median and maximum latency;
- input tokens per call.

## Known limits

The jev-1.13 [limitations](https://docs.typesafe.ai/model-jaggedness/jev-1.13.md) shape this design:

- **Adversarial state can steer answers.** Request bodies and declared purposes are written by the agent, so an attacker controls part of the state. Jev is therefore an advisory signal only. It can send a request to review, but it must never be the sole gate in front of payment, credential, or administrative operations.
- **Choice leans toward the first option,** and negations are read literally.
- **Irrelevant state is a distractor,** which is another reason the state is kept small.
- **The thresholds and corpus are tiny.** Twelve labelled cases show behaviour; they do not measure accuracy.

## Fit for paygate and Harbor Proxy

To be completed from the live results. The questions it will answer:
- Is the latency acceptable in the request path of a paid API?
- Is the per-call cost acceptable at the price of one paid call?
- Do the abuse signals add anything beyond paygate's deterministic checks?
- Would Jev belong inline, or asynchronously (flagging agents for review after the fact)?
