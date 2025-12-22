## Model Selection & Low-Latency Performance Summary

This project has gone through multiple production-grade model iterations, with a consistent focus on **low latency**, **deterministic behavior**, and **voice-first reliability**. [Early versions of the system](https://github.com/docwho2/java-squareup-chatgpt-ivr) ran almost exclusively on **OpenAI gpt-4.1-nano**, which proved to be a true workhorse and established the baseline for acceptable performance. As the system matured and multilingual and RAG complexity increased, model consistency and tail latency (p50 / p95) became just as important as raw speed.

To validate current model choices, a standardized **smoke test suite** was executed across multiple providers and models. Each test represents a real IVR path (inventory lookup, hours, staff routing, weather, city RAG, chat memory, etc.) and reflects **end-to-end request latency**, not just model inference time.

---

## Smoke Test Performance (End-to-End)

### BEDROCK – Meta Llama 4 Scout 17B Instruct
**Provider / Model:** BEDROCK / us.meta.llama4-scout-17b-instruct-v1:0  

| Test | Duration (ms) | Approx RPS |
|---|---:|---:|
| SmokeTests.chucklesCandyTest | 2131 | 0.47 |
| SmokeTests.restaurantTest | 1070 | 0.93 |
| SmokeTests.addressTest | 839 | 1.19 |
| SmokeTests.staffTest | 1658 | 0.60 |
| SmokeTests.weatherTest | 2259 | 0.44 |
| SmokeTests.cityRagTest | 1511 | 0.66 |
| SmokeTests.chatMemoryTest | 832 | 1.20 |

**Notes:**  
Good overall throughput, but earlier runs showed **language drift** in non-English scenarios.

---

### BEDROCK – Claude 3.5 Haiku
**Provider / Model:** BEDROCK / us.anthropic.claude-3-5-haiku-20241022-v1:0  

| Test | Duration (ms) | Approx RPS |
|---|---:|---:|
| SmokeTests.chucklesCandyTest | 2881 | 0.35 |
| SmokeTests.restaurantTest | 994 | 1.01 |
| SmokeTests.addressTest | 822 | 1.22 |
| SmokeTests.staffTest | 1650 | 0.61 |
| SmokeTests.weatherTest | 2453 | 0.41 |
| SmokeTests.cityRagTest | 2013 | 0.50 |
| SmokeTests.chatMemoryTest | 754 | 1.33 |

**Notes:**  
Strong reasoning quality, but consistently **slower p50** on conversational paths.

---

### ✅ BEDROCK – Amazon Nova 2 Lite (Current Default)
**Provider / Model:** BEDROCK / us.amazon.nova-2-lite-v1:0  

| Test | Duration (ms) | Approx RPS |
|---|---:|---:|
| SmokeTests.chucklesCandyTest | 1876 | 0.53 |
| SmokeTests.restaurantTest | 961 | 1.04 |
| SmokeTests.addressTest | 659 | 1.52 |
| SmokeTests.staffTest | 1481 | 0.68 |
| SmokeTests.weatherTest | 2327 | 0.43 |
| SmokeTests.cityRagTest | 2323 | 0.43 |
| SmokeTests.chatMemoryTest | 672 | 1.49 |

**Why this matters:**
- Fastest or near-fastest p50 across most conversational paths  
- Zero language leakage (no English responses when operating in Swedish or other locales)  
- Extremely consistent behavior under IVR load  
- Best balance of latency, determinism, and multilingual safety 

---

### OPENAI – GPT-5 Nano
**Provider / Model:** OPENAI / gpt-5-nano  

| Test | Duration (ms) | Approx RPS |
|---|---:|---:|
| SmokeTests.chucklesCandyTest | 2190 | 0.46 |
| SmokeTests.restaurantTest | 1154 | 0.87 |
| SmokeTests.addressTest | 825 | 1.21 |
| SmokeTests.staffTest | 1517 | 0.66 |
| SmokeTests.weatherTest | 2130 | 0.47 |
| SmokeTests.cityRagTest | 1737 | 0.58 |
| SmokeTests.chatMemoryTest | 766 | 1.31 |

**Notes:**  
Solid successor to 4.1-nano, but not materially faster for IVR-style workloads.

---

### OPENAI – GPT-4.1 Nano (Historical Baseline)
**Provider / Model:** OPENAI / gpt-4.1-nano  

| Test | Duration (ms) | Approx RPS |
|---|---:|---:|
| SmokeTests.chucklesCandyTest | 2146 | 0.47 |
| SmokeTests.restaurantTest | 1011 | 0.99 |
| SmokeTests.addressTest | 817 | 1.22 |
| SmokeTests.staffTest | 1686 | 0.59 |
| SmokeTests.weatherTest | 2194 | 0.46 |
| SmokeTests.cityRagTest | 1646 | 0.61 |
| SmokeTests.chatMemoryTest | 878 | 1.14 |

**Notes:**  
- This model carried the project for a long time and remains an excellent reference point.
- However, it showed **occasional multilingual missteps** under real-world conversational pressure.

---

## Key Takeaways

- **All tested models meet low-latency IVR requirements**  
  Most conversational paths consistently complete in **~700–2200 ms**, well within acceptable limits for voice interactions.

- **Nova 2 Lite stands out for consistency, not just speed**  
  While raw timings are comparable across providers, **behavioral correctness** (especially language fidelity) is where Nova 2 Lite clearly wins.

- **4.1-nano and Meta models failed edge-case language tests**  
  Emitting English responses in non-English sessions is unacceptable for production IVR, regardless of latency.

- **Bedrock provides tighter p50 stability**  
  Especially important for voice systems where perceived responsiveness matters more than peak throughput.

---

## Conclusion

The move from **gpt-4.1-nano** to **Amazon Bedrock Nova 2 Lite** was not driven by hype or vendor preference—it was driven by **measured performance, consistency, and real IVR correctness**. The numbers show that all models are fast enough; the behavior shows that Nova 2 Lite is the safest and most reliable choice today.

This system is designed to remain **provider-agnostic**, and continued benchmarking is part of the roadmap—but for now, **the data backs the decision**.