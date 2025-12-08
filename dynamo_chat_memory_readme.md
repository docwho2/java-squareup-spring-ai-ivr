# 🚀 DynamoDB Chat Memory for Spring AI
**High-Performance · Low-Latency · AWS-Native · Drop-In Replacement for Spring AI `ChatMemoryRepository`**

This sub-project provides a **DynamoDB-backed Chat Memory implementation for Spring AI**, engineered for the fastest possible performance when running on AWS infrastructure (Lambda, ECS, EKS, EC2, Fargate, etc.).

It replaces Spring AI’s default storage patterns—which can produce **multiple reads and writes per turn**—with a fully optimized design that performs:

- ✅ Exactly **1 DynamoDB read + 1 DynamoDB batch write per full chat turn**  
- ✅ **Zero redundant I/O calls** (thanks to per-JVM caching)  
- ✅ **Append-only persistence** (tail-only writes)  
- ✅ **Built-in TTL cleanup** via DynamoDB Time To Live  
- ✅ **AWS CRT-powered ultra-fast HTTP client** for DynamoDB  

This makes DynamoDB the **ideal choice for chat memory** when your application runs inside AWS.

---

## 🌟 Why DynamoDB is the Best AWS Store for Chat Memory

Spring AI chat memory requires:

- Frequent small reads & writes  
- Ordering guarantees  
- Low latency  
- Low cost at scale  
- No-ops operationally  
- Automatic expiry of old conversations  

DynamoDB checks every box better than any alternative inside AWS.

### ⚡ Lowest Latency (with AWS CRT)

Using DynamoDB with the **AWS CRT HTTP client** (`AwsCrtHttpClient`) delivers:

- Lower syscall overhead  
- Faster TLS session reuse  
- Very fast request/response handling  
- Great cold/warm performance in containers and Lambda  

Chat memory I/O becomes nearly invisible compared to LLM latency.

### 💸 Lowest Cost for Chat Memory

Chat memory operations are:

- Tiny writes  
- Append-only  
- Proportional to chat turns  

With **PAY_PER_REQUEST**, you pay only for those 1–2 operations per turn.

**Compared to other AWS options:**

| Store                | Cost & Fit         | Notes                                                  |
|----------------------|-------------------|--------------------------------------------------------|
| **DynamoDB**         | ⭐ Best / Lowest   | Per-item billing, great for small append-only writes   |
| Keyspaces (Cassandra)| Higher / Overkill | Multi-replica writes, heavier drivers, higher latency  |
| Aurora/RDS           | Much higher       | Connection overhead, compute-based billing             |
| S3                   | Not suitable      | Whole-object read/write, high latency, not a database  |

### 🧩 Perfect Data Model

Each conversation maps to:

- **Partition key:** `conversationId`  
- **Sort key:** `messageIndex` (0..N-1)  

This supports:

- Ordered reads  
- Append-only writes  
- Natural conversation windowing  
- Simple, predictable schema  

### 🧹 Automatic TTL Cleanup

Each message gets a per-item TTL. DynamoDB:

- Deletes expired messages in the background  
- Eliminates manual cleanup jobs  
- Avoids delete-write costs  
- Keeps the table size under control  

### 🛠 Zero Operational Overhead

No servers.  
No version upgrades.  
No clusters.  
No replication configs.  

Just a table and IAM permissions.

---

## 🧱 DynamoDB Table Setup

You can create the table via **CloudFormation** or manually in the console.

### Option 1: CloudFormation Example

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

### Option 2: Manual Console Setup

1. Go to **DynamoDB → Tables → Create table**  
2. Set:
   - **Table name:** e.g. `spring-ai-chat-memory`  
   - **Partition key:** `conversationId` (String)  
   - **Sort key:** `messageIndex` (Number)  
3. Use **On-demand / PAY_PER_REQUEST** billing mode.  
4. After creation, go to **TTL settings**, and:
   - Set **TTL attribute** = `ttl`  
   - Enable TTL  

Your items must then include a numeric `ttl` (epoch seconds) for DynamoDB to expire them automatically.

---

## 📦 Maven Dependencies

Add these to your `pom.xml` (versions omitted here—use your BOM / dependency management):

```xml
<dependencies>
    <!-- Spring AI: choose the starter for your model provider -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>

    <!-- DynamoDB Enhanced Client (high-level SDK v2) -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>dynamodb-enhanced</artifactId>
        <exclusions>
            <!-- Use CRT instead of Netty/Apache to keep the HTTP stack lean -->
            <exclusion>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>netty-nio-client</artifactId>
            </exclusion>
            <exclusion>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>apache-client</artifactId>
            </exclusion>
        </exclusions>
    </dependency>

    <!-- AWS CRT HTTP client: fastest sync client for Java on AWS -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>aws-crt-client</artifactId>
    </dependency>
</dependencies>
```

Use your usual **Spring Boot BOM** / **Spring Cloud BOM** to align versions across the stack.

---

## 🧩 Spring Boot Configuration (Dynamo + CRT + ChatMemory)

Below is a complete example of wiring:

- AWS CRT HTTP client  
- DynamoDB v2 client  
- DynamoDB Enhanced client  
- The custom `DynamoDbChatMemoryRepository`  
- Spring AI `ChatMemory` (windowed)  

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

@Configuration
public class SpringConfig {

    @Bean(destroyMethod = "close")
    public SdkHttpClient crtSyncHttpClient() {
        return AwsCrtHttpClient.builder().build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(SdkHttpClient crtSyncHttpClient) {
        return DynamoDbClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

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

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        // Controls how many messages are sent to the model, not how many are stored in Dynamo.
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(50)
                .build();
    }
}
```

### Spring Configuration Properties

In `application.properties` or `application.yaml`:

```properties
# DynamoDB table name for chat memory
chat.memory.dynamo.table-name=spring-ai-chat-memory

# TTL for each stored message
chat.memory.dynamo.ttl=24h
```

Environment variable override (containers, Lambda, etc.):

```bash
export CHAT_MEMORY_DYNAMO_TABLE_NAME=spring-ai-chat-memory-prod
export CHAT_MEMORY_DYNAMO_TTL=24h
```

Spring Boot’s relaxed binding maps these directly to the properties.

---

## 🧠 Repository Behavior Details

The core implementation is in:

- [DynamoDbChatMemoryRepository.java](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoDbChatMemoryRepository.java) 
- [DynamoChatMemoryItem.java](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoChatMemoryItem.java)  


Spring AI calls `findByConversationId` and `saveAll` multiple times per turn. This repository is optimized to minimize I/O regardless of that pattern.

### `findByConversationId(conversationId)`

- **First call** per conversation per JVM:
  - Performs a DynamoDB query:
    - `PK = conversationId`
    - `SK` ascending by `messageIndex`
  - Converts rows to Spring AI `Message` objects
  - Stores them in an in-memory cache (`ConcurrentHashMap`), keyed by `conversationId`
- **Subsequent calls** in the same JVM:
  - Served from the cache
  - **No additional DynamoDB I/O**

### `saveAll(conversationId, messages)`

Spring AI typically does:

1. Pre-call `saveAll` → after USER message  
2. Post-call `saveAll` → after ASSISTANT message  

This implementation handles it as follows:

1. **If last message is `USER` (or not `ASSISTANT`)**  
   - Treat as **pre-call / intermediate** state  
   - Update the cached `ConversationState`  
   - **Do not write to DynamoDB**

2. **If last message is `ASSISTANT`**  
   - Treat as **end of a full turn** (USER → ASSISTANT)  
   - Use the cached state to know the `lastPersistedIndex`  
     - If cache is missing, do a tiny query (`scanIndexForward(false)`, `limit(1)`) to find the tail in Dynamo  
   - Compute which messages are new (indexes `lastPersistedIndex + 1`..N-1)  
   - Create `DynamoChatMemoryItem` instances with:
     - `conversationId`  
     - `messageIndex`  
     - `messageType`  
     - `text` or tool JSON  
     - `ttl = now + ttlDuration`  
   - Batch write them in groups of up to 25 items  
   - Evict the conversation from the in-memory cache

### Net Effect Per Turn

For each **USER → ASSISTANT** turn:

- ✔ 1 Dynamo read (first `findByConversationId`)  
- ✔ 1 Dynamo batch write (final `saveAll` with ASSISTANT at tail)  
- ✔ No full rewrites of the history  
- ✔ Pre-call `saveAll` becomes cache-only  

This pattern holds regardless of how many internal calls Spring AI makes.

---

## 📚 Files to Copy

To reuse this implementation in your own project, copy:

- [DynamoDbChatMemoryRepository.java](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoDbChatMemoryRepository.java) 
- [DynamoChatMemoryItem.java](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoChatMemoryItem.java)  

Then:

1. Create the DynamoDB table (via CloudFormation or console).  
2. Add the Maven dependencies.  
3. Add the Spring configuration shown above.  
4. Make sure your app uses a stable **conversation id** (user id, session id, phone number + date, etc.).  

Spring AI will then use DynamoDB as its backing `ChatMemoryRepository` transparently.


## 🔎 Example: Repository Behavior During a Single Chat Turn

Below is a real Lambda log demonstrating how the DynamoDB ChatMemoryRepository
handles one complete turn (USER → ASSISTANT) with only **1 read + 1 write**:

```text
1765186015864 | START RequestId: a2f162b1-d32b-43d7-a2b8-b1187b477ceb Version: 64
1765186015866 | Received Lex event: {"sessionId":"364253738352486","inputTranscript":"its been great working with you !"}

1765186015904 | [REPO] DEBUG findByConversationId(364253738352486) loaded 4 items from Dynamo, lastPersistedIndex=3
1765186015904 | [REPO] DEBUG findByConversationId(364253738352486) served from cache, 4 messages
1765186015904 | [REPO] DEBUG saveAll(364253738352486) called with last message type USER → skipping persistence this turn

-- Spring AI sends ChatCompletionRequest here --

1765186016668 | [REPO] DEBUG findByConversationId(364253738352486) served from cache, 5 messages
1765186016668 | [REPO] DEBUG saveAll(364253738352486) persisting 2 new items (indexes 4..5), then evicting cache entry

1765186016679 | Raw Bot Text Response: "Thank you so much! It's been a pleasure assisting you..."
1765186016680 | Lex Response created successfully
```

### ✔ What this shows
- Spring AI calls `findByConversationId` **twice** in a row  
- The repository returns cache on 2nd call → **zero additional DynamoDB reads**  
- Spring AI also calls `saveAll` twice:
  - Once for USER message → **skipped write**
  - Once after ASSISTANT message → **actual write**
- DynamoDB receives only **one read + one write** for the full turn

