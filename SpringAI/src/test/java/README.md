## 🧪 Enterprise-Grade Testing & Model Validation

This directory contains the **enterprise-grade test harness** that validates every critical behavioral contract of the Amazon Chime SMA Spring AI IVR platform.

These tests are **not** simple unit checks or prompt demos. They are **proof-of-behavior tests** designed to demonstrate that the system works correctly across **models, languages, channels, and external integrations** — and continues to do so over time.

At the time of writing, this suite contains **87 fully automated tests** spanning voice, text, multilingual flows, RAG, memory, and external APIs.

---

## 🔐 Type-Safe, Contract-Driven Assertions

All tests are written to be **structurally aligned with production code**:

- Tool invocations reference **production constants** (no duplicated strings)
- Dialog state assertions validate **real Lex / Chime semantics**
- Language identifiers use shared enums
- Channel behavior is enforced via shared abstractions

If a production contract changes, tests **fail immediately and loudly**. There is no silent drift between tests and runtime behavior.

---

## 🌍 Multilingual Test Matrix (Voice + Text)

The same behavioral contract is validated across all supported languages:

- English
- Spanish
- German
- Dutch
- Finnish
- French (Canadian)
- Norwegian
- Polish
- Swedish

Each language suite validates:

- Language detection and explicit language switching
- Correct locale routing at the Lex + Chime level
- Translation of user intent into **English-only backend data sources**
- Correct execution of:
  - Square API calls
  - RAG document retrieval
  - Tool orchestration
- Correct localized response back to the user

This ensures English-only data sources remain fully usable in *any* language — a common failure point in multilingual AI systems.

---

## 🚦 Language Gate Pattern (Fail Fast, Skip Cleanly)

Each language suite begins with a **language gate test**:

- If the model fails to switch into the requested language
- All subsequent tests for that language are **skipped**, not failed

This design:

- Prevents cascading noise
- Keeps reports readable
- Makes adding experimental languages safe
- Clearly isolates the root cause of failures

Broken languages never pollute the rest of the regression suite.

---

## 🧠 Behavioral Testing (Not Text Matching)

Tests validate **what the system does**, not just what it says:

- Was the correct tool invoked?
- Did the dialog close when expected?
- Was the call transferred or hung up correctly?
- Was chat memory actually used?
- Was a welcome card rendered *only* for new sessions?
- Was it *not* rendered on subsequent turns?

Negative assertions (proving something **did not happen**) are first-class citizens in this suite and have already uncovered real production bugs.

---

## 📚 RAG + External API Validation Across Languages

Tests explicitly prove that:

- Non-English user input is translated correctly
- Queries against **English-only RAG content** return correct results
- Square inventory, hours, and staff queries execute correctly regardless of language
- The LLM orchestrates tools deterministically — not heuristically

This validates **end-to-end semantic integrity**, not just language fluency.

---

## 🧾 Full Evidence Trail via Allure Reports

Every test run produces a **fully navigable Allure report** containing:

- Model name and provider
- Spring Boot and Spring AI versions
- AWS region
- Channel under test
- Lex request and response payloads
- Tool invocation evidence
- Structured descriptions explaining *what is being proven*

Reports are published with history and can be used for:

- Model comparison
- Regression analysis
- Operational audits
- Stakeholder review

---

## ⏱ Smoke Tests for Continuous Uptime Validation

A dedicated **Smoke Test suite** validates:

- Model availability
- Tool wiring
- RAG access
- Square API connectivity
- Language switching
- Memory initialization

These tests are designed to run on a **schedule**, providing continuous confidence that the live system remains operational — not just that it passed CI during deployment.

---

## 🔁 Model Evaluation Without Code Changes

Because tests assert **behavioral contracts**, not model-specific phrasing:

- The same suite can run unchanged against:
  - AWS Bedrock models (Nova, Claude)
  - OpenAI GPT models
  - Future providers

Results can be compared objectively across models:

- Pass/fail rates
- Latency characteristics
- Tool correctness
- Memory behavior

This test suite doubles as a **model evaluation harness**, not just application validation.

---

## 🧠 Why This Matters

Most AI voice demos:

- Manually test one language
- Validate a single happy path
- Break silently when prompts or models change

This test suite:

- Proves correctness
- Catches regressions early
- Survives prompt and model refactors
- Scales cleanly across languages and channels
- Documents system behavior through executable evidence

The tests are not an afterthought.
They are a **first-class architectural component** of the platform.

---

## 🏁 Summary

This test harness turns the Amazon Chime SMA Spring AI IVR from a demo into a **verifiable, enterprise-grade system**.

It provides:

- Deterministic validation of AI behavior
- Confidence during refactors and model swaps
- Continuous uptime assurance
- Objective evidence of correctness

In short: **this test suite is a competitive advantage.**
