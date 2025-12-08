# 🚀 DynamoDB Chat Memory for Spring AI
**High‑Performance · Low‑Latency · AWS‑Native · Drop‑In Replacement for Spring AI ChatMemoryRepository**

This project provides a **DynamoDB-backed Chat Memory implementation for Spring AI**, engineered for the fastest possible performance when running on AWS infrastructure (Lambda, ECS, EKS, EC2, Fargate, etc.).

It replaces Spring AI’s default storage patterns—which can produce **multiple reads and writes per turn**—with a fully optimized design that performs:

### ✅ **Exactly 1 DynamoDB read + 1 DynamoDB write per full chat turn**  
### ✅ **Zero redundant I/O calls**  
### ✅ **Append‑only persistence**  
### ✅ **Built-in TTL cleanup**  
### ✅ **AWS CRT-powered ultra-fast HTTP client**  

This makes DynamoDB the **ideal** choice for chat memory when your application runs inside AWS.

---

# 🌟 Why DynamoDB is the Best AWS Store for Chat Memory

Spring AI chat memory requires:

- Frequent small reads  
- Frequent small writes  
- Ordering guarantees  
- Low latency  
- Low cost at scale  
- Zero operational burden  
- Ability to expire old conversations efficiently  

DynamoDB checks every box better than any alternative inside AWS.

---

## ⚡ 1. Lowest Latency (Especially with AWS CRT)

Using DynamoDB with the **AWS CRT HTTP client** (`AwsCrtHttpClient`) delivers:

- Lower syscall overhead  
- Faster TLS session reuse  
- Much faster HTTP request/response handling  
- Excellent performance in containers and Lambda  

This keeps ChatMemory overhead nearly invisible next to LLM latency.

---

## 💸 2. Lowest Cost for Chat Memory

Chat memory writes are:

- Small (tiny JSON fragments)
- Append-only
- Predictable
- Per-user-session

DynamoDB’s **PAY_PER_REQUEST** model means you only pay for the 1 read + 1 write per turn.  
Compared to:

| Store | Cost | Notes |
|-------|------|-------|
| **DynamoDB** | ⭐ Lowest | Per-item pricing; perfect for append-only writes |
| **Keyspaces (Cassandra)** | Higher | Multi-replica writes, more overhead |
| **Aurora/RDS** | Much higher | Heavy connections, compute-based billing |
| **S3** | Not suitable | Entire-object read/write, slow latency |

DynamoDB is the only AWS data store priced ideally for this workload.

---

## 🧩 3. Perfect Data Model for Append-Only Chat Threads

Each conversation maps naturally to:

- **Partition key:** `conversationId`
- **Sort key:** `messageIndex`

This supports efficient:

- Ordered reads  
- Atomic append writes  
- Unlimited conversation size  
- Contiguous message numbering  

No schema migrations, no clustering rules, no sharding.

---

## 🧹 4. Automatic TTL Cleanup

Every message receives a per-item TTL, enabling:

- Automatic expiration  
- Free cleanup of inactive sessions  
- Zero delete-write costs  
- Constant table size over time  

Just enable TTL on the `ttl` attribute.

---

## 🛠️ 5. Zero Operational Overhead

DynamoDB requires:

- No cluster to run  
- No patching  
- No tuning  
- No backups (unless you want PITR)  
- No provisioning  
- No scaling plan  

It simply works.

---

# 🧱 CloudFormation: Create the Table

```yaml
ChatSessionTable:
  Type: AWS::DynamoDB::Table
  UpdateReplacePolicy: Retain
  DeletionPolicy: Delete
  Properties:
    TableName: !Sub ${AWS::StackName}-chat-memory
    BillingMode: PAY_PER_REQUEST
    AttributeDefinitions:
      - AttributeName: conversationId
        AttributeType: S
      - AttributeName: messageIndex
        AttributeType: N
    KeySchema:
      - AttributeName: conversationId
        KeyType: HASH
      - AttributeName: messageIndex
        KeyType: RANGE
    TimeToLiveSpecification:
      AttributeName: ttl
      Enabled: true
```

---

# 🔧 Spring Boot Configuration

```java
@Bean
public ChatMemoryRepository chatMemoryRepository(
        DynamoDbEnhancedClient enhancedClient,
        ObjectMapper objectMapper,
        @Value("${chat.memory.dynamo.ttl:24h}") Duration ttlDuration,
        @Value("${chat.memory.dynamo.table-name:spring-ai-chat-memory}") String tableName
) {
    return new DynamoDbChatMemoryRepository(
            enhancedClient,
            objectMapper,
            ttlDuration,
            tableName
    );
}
```

Spring properties:

```properties
chat.memory.dynamo.table-name=spring-ai-chat-memory
chat.memory.dynamo.ttl=24h
```

Environment override:

```bash
CHAT_MEMORY_DYNAMO_TABLE_NAME=my-table
CHAT_MEMORY_DYNAMO_TTL=24h
```

---

# 🔧 How the Optimized Repository Works

### `findByConversationId()` behavior:
- First call per conversation per JVM → **loads from Dynamo**, stores in local cache  
- Subsequent calls → **cache hit**, no I/O  

### `saveAll()` behavior:
- If last message is **USER** → skip persistence, update cache only  
- If last message is **ASSISTANT** → persist new tail messages  
- Writes only incremental indexes  
- Sets per-item TTL  
- Evicts cache entry after write  

This means per completed turn:

✔ **One Dynamo read**  
✔ **One Dynamo write**  
✔ No noisy I/O  
✔ No full rewrites of the chat window  

---

# 📄 End of Document
