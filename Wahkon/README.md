## 📖 Retrieval-Augmented Generation (RAG) with Spring AI

This project includes a **first-class RAG subsystem** built on **Spring AI**, designed to ingest, normalize, and query **real-world, continuously changing data sources** — not static demo documents.

The RAG pipeline provides:

- **Automated content ingestion** from:
  - Public **web pages** (custom crawler)
  - **Facebook page posts** (Graph API)
  - City and community information portals
- **Deterministic document identity** and update semantics  
  (content is updated, not duplicated, as sources change)
- **Vector embedding via Spring AI** with provider-agnostic support  
  (AWS Bedrock Titan / OpenAI / future models)
- **Metadata-rich embeddings** enabling:
  - Source attribution
  - Time-based filtering
  - Content scoping per domain (city, store, social, etc.)
- **English-normalized storage** with **automatic query translation**, allowing:
  - Multilingual user queries
  - Retrieval from English-only knowledge bases
  - Correct localized responses back to the user

This ensures that **local knowledge, policies, events, and announcements** remain accurate and queryable in *any supported language*.

---

### 🕷 Custom Crawlers (Web + Facebook)

Rather than relying on manual uploads or static files, this system uses **purpose-built crawlers**:

- **Web crawler**
  - Discovers and fetches linked documents (HTML, PDFs, assets)
  - Extracts clean, LLM-friendly text
  - Skips non-content payloads automatically
- **Facebook crawler**
  - Pulls the most recent page posts via Graph API
  - Normalizes post text, timestamps, and permalinks
  - Keeps social content aligned with live business communications

Both crawlers are designed for **repeatable execution** and safe re-ingestion, enabling scheduled refresh without vector store pollution.

---

### 🧠 RAG as a Tool (Not a Prompt Hack)

RAG is exposed to the LLM exclusively via **Spring AI Tools**:

- The model must explicitly decide **when retrieval is required**
- Retrieved context is injected in a **controlled, bounded format**
- Hallucination risk is reduced by design
- Tool usage is **observable and testable**

This keeps RAG deterministic, auditable, and compatible with strict testing.

---

### 🧪 Fully Tested Across Languages and Channels

The RAG subsystem is validated by the same **enterprise-grade test harness** used for the rest of the platform:

- Multilingual queries retrieving English-only documents
- Correct tool invocation (RAG called when appropriate)
- Verified response grounding in retrieved content
- Regression coverage across multiple LLM providers

RAG behavior is **proven**, not assumed.

---

### 🏁 Why This Matters

Most RAG demos:
- Load a PDF once
- Assume content never changes
- Break under multilingual input

This system:
- Continuously ingests **live, external data**
- Keeps knowledge current without manual intervention
- Works reliably across **voice, SMS, and chat**
- Survives model swaps without re-architecture

RAG is not bolted on — it is a **core intelligence layer** of the platform.