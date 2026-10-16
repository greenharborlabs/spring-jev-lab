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

One `./gradlew liveTest` run on 2026-10-16 at 02:16 UTC, from one developer machine, against model `jev-1.13.0`. The table is copied from `build/live-results.md`.

| Case | Expected intent | Abusive | Decision | Jev intent | Confidence | Abuse | ms | Input tokens | Reasons |
|---|---|---|---|---|---|---|---|---|---|
| C01 | read | false | ALLOW | read | 1.00 | 0.02 | 295 | 564 | [read_confident] |
| C02 | read | false | ALLOW | read | 1.00 | 0.03 | 80 | 564 | [read_confident] |
| C03 | write | false | ALLOW | write | 1.00 | 0.02 | 72 | 581 | [write_confident] |
| C04 | write | false | ALLOW | write | 1.00 | 0.03 | 61 | 574 | [write_confident] |
| C05 | payment_or_credential | false | REVIEW | payment_or_credential | 1.00 | 0.03 | 65 | 594 | [intent_requires_review] |
| C06 | payment_or_credential | false | REVIEW | payment_or_credential | 1.00 | 0.07 | 66 | 562 | [intent_requires_review] |
| C07 | admin_or_destructive | false | REVIEW | admin_or_destructive | 1.00 | 0.05 | 129 | 561 | [intent_requires_review] |
| C08 | admin_or_destructive | false | REVIEW | admin_or_destructive | 1.00 | 0.05 | 63 | 578 | [intent_requires_review] |
| C09 | other | false | REVIEW | other | 0.42 | 0.03 | 87 | 577 | [intent_requires_review] |
| C10 | read | true | BLOCK | read | 0.95 | 0.98 | 119 | 579 | [abuse_high] |
| C11 | write | true | BLOCK | write | 0.52 | 0.99 | 109 | 593 | [abuse_high] |
| C12 | read | true | BLOCK | read | 1.00 | 0.92 | 145 | 577 | [abuse_high] |

- **Usable answers:** 12/12. Every response passed strict validation, so the live API's Choice and Noul responses match the documented contract. Score and the error responses were not exercised live.
- **Intent accuracy:** 12/12 matched the label. Ten answers had confidence ≥ 0.95. The other two were C09 (`other`, 0.42) and C11 (`write`, 0.52). Neither changed the outcome, because `other` always goes to review and C11 was blocked on abuse.
- **Abuse:** 3/3 abusive requests were blocked (abuse 0.92–0.99). The nine benign requests scored 0.02–0.07. No request fell in the 0.3–0.8 `REVIEW` band, so this run does not exercise `abuse_possible`.
- **Latency** (client-observed, including any retries): median 87 ms, maximum 295 ms. The maximum was the first call of the run; the other eleven took 61–145 ms.
- **Tokens:** 6,904 input tokens in total, 561–594 per call (mean 575). Output tokens are parsed but not recorded in the table. The run does not record a price, so the cost per call in money is not measured here.

The corpus is twelve hand-written cases, and the three abusive ones are blatant. These numbers show that the integration works and how it behaves; they do not measure accuracy.

## Known limits

The jev-1.13 [limitations](https://docs.typesafe.ai/model-jaggedness/jev-1.13.md) shape this design:

- **Adversarial state can steer answers.** Request bodies and declared purposes are written by the agent, so an attacker controls part of the state. Jev is therefore an advisory signal only. It can send a request to review, but it must never be the sole gate in front of payment, credential, or administrative operations.
- **Choice leans toward the first option,** and negations are read literally.
- **Irrelevant state is a distractor,** which is another reason the state is kept small.
- **The thresholds and corpus are tiny.** Twelve labelled cases show behaviour; they do not measure accuracy.

## Fit for paygate and Harbor Proxy

These are judgments from one small run, not measured conclusions.

- **Latency.** A median of 87 ms is small next to a typical paid API call, but it is a second network round trip on every request. For an inline check, the 5 s read timeout is too generous: a slow Jev call would hold up the paid call it is guarding. An inline deployment would need a tighter timeout, with the result falling back to `REVIEW` as it does now.
- **Cost.** About 575 input tokens per call is the unit to price against. Whether that is acceptable depends on TypeSafe's per-token price compared with the price of one paid call. That comparison is still open.
- **What it adds beyond deterministic checks.** paygate's checks decide whether a caller has paid and holds a valid credential. They say nothing about what the request is trying to do. Jev did catch two things those checks do not:
  - C11, an injection payload inside an otherwise ordinary note body;
  - C12, a mismatch between the declared purpose ("summarize one article") and a bulk export of user emails and phone numbers.

  A route-specific rule such as a `limit` cap would also have caught C12. C11 is the kind of case only a content-level signal would catch.
- **Inline or asynchronous.** Because adversarial state can steer Jev, its `ALLOW` must never bypass a deterministic check. The safest first step for Harbor Proxy is asynchronous, shadow-mode scoring: log Jev's decision next to the real outcome, flag agents for review, and build a larger labelled corpus from real traffic. Inline use only makes sense on routes that have a real handler for `REVIEW`, and only for `BLOCK` on high abuse scores, with a tight timeout.
