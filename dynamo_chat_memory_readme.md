# DynamoDB chat memory

`SpringAI` uses a custom `DynamoDbChatMemoryRepository` with Spring AI's
`MessageWindowChatMemory`. The configured window is **50 messages**. Spring AI
evicts whole older turns when the window fills, while retaining system messages.
The DynamoDB repository must store that same replacement window; it is not a
permanent chat transcript.

## Where it is configured

- [ChatConfig](SpringAI/src/main/java/cloud/cleo/squareup/config/ChatConfig.java)
  creates the repository, sets the 50-message window, and installs
  `MessageChatMemoryAdvisor`.
- [DynamoDbChatMemoryRepository](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoDbChatMemoryRepository.java)
  performs the reads and writes.
- [DynamoChatMemoryItem](SpringAI/src/main/java/cloud/cleo/squareup/memory/DynamoChatMemoryItem.java)
  maps each message to a DynamoDB item.
- [template.yaml](template.yaml) creates the table and grants the Lambda access.
  [SpringAI/pom.xml](SpringAI/pom.xml) contains the DynamoDB Enhanced Client and
  AWS CRT dependencies.

The table has a string `conversationId` partition key and a numeric
`messageIndex` sort key. Each item also has a numeric `ttl` attribute containing
an epoch-second expiry time. The template enables DynamoDB TTL on `ttl` and uses
on-demand billing. `CHAT_MEMORY_DYNAMO_TABLE_NAME` selects the table; the
optional `CHAT_MEMORY_DYNAMO_TTL` property defaults to `24h`.
The SAM template creates a regional table; it does not configure Global Table
replication.

## Read and write behavior

On the first `findByConversationId` for a conversation in a Lambda container,
the repository queries its items in sort-key order. Later reads during the same
turn use the in-memory cache. A `saveAll` ending in a user message updates that
cache but does not write to DynamoDB. The assistant-ending `saveAll` compares
Spring AI's replacement window with the loaded items:

1. Existing messages still in the window keep their item keys and TTL values.
2. Evicted messages are deleted, including rows for unusable empty assistant
   generations.
3. New messages receive increasing sort keys and a fresh TTL.
4. Deletes and puts share a DynamoDB batch write request when the total is at
   most 25 actions. Larger changes use multiple batches. Unprocessed actions
   are retried with short backoff; a persistent failure fails the save.
5. The cache entry is evicted after a successful final save so a later Lambda
   invocation can load the current DynamoDB state.

For the normal two-message user/assistant turn, this is **one DynamoDB query and
one batch write request**. When the 50-message window rolls forward, the same
batch request normally deletes the evicted turn and puts the new one. The first
turn can write the system message as well. Direct repository calls, larger tool
exchanges, retries, and clearing a conversation can use more requests. DynamoDB
batch writes are not atomic across a full conversation, and simultaneous writers
for the same conversation require coordination by the caller.

Queries use DynamoDB's default eventually consistent reads to keep read cost
and latency low. An immediately following invocation can briefly see stale
data; use a strongly consistent query if that tradeoff is unacceptable.

`deleteByConversationId` queries and deletes the conversation's items. TTL is
background cleanup, not an exact 24-hour read cutoff: an expired item can remain
visible until DynamoDB removes it. `findConversationIds` scans the table and
should not be used on a latency-sensitive request path.

## Spring AI 2.1.0-M1 compatibility

Spring AI's `ChatMemoryRepository.saveAll` receives a **replacement list**.
Its `MessageWindowChatMemory` trims older complete turns at the configured
limit. The DynamoDB implementation preserves the fast append path while
deleting rows that leave this list; otherwise a long-running conversation would
stop advancing once its list reached 50 messages. The M1
[chat memory guide](https://docs.spring.io/spring-ai/reference/2.1/api/chat-memory.html)
describes the window and the distinction between chat memory and full history.

M1 also adds ordered `MessagePart` content to messages. This repository stores
the legacy text, tool-call, tool-response, and selected metadata fields; it does
**not** round-trip ordered parts, media, reasoning, or provider payloads.
Spring AI's M1 [upgrade notes](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html)
say the existing text and tool-call accessors continue to work. Our current
Bedrock chat path uses those fields. If a future model starts returning
provider-specific parts that must survive between turns, the item format and
serializer will need an explicit update before enabling that model.

The default `MessageChatMemoryAdvisor` records the final user/assistant exchange;
Spring AI's internal tool loop does not normally write its intermediate tool
messages to memory. The repository can store tool-call and tool-response fields
if a caller explicitly passes those messages, but that does not change the
advisor's default behavior.

## Validation

`DynamoDbChatMemoryRepositoryTest` exercises a conversation past the 50-message
limit and checks that older turns leave DynamoDB while the system message and
newest turns remain. It also checks the single batch request shape for ordinary
and rolling-window turns. This is a local behavior test; a deployed Bedrock and
DynamoDB invocation is still needed for runtime latency measurements.
