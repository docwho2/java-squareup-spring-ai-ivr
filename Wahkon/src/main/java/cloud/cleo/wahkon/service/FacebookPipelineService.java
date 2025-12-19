package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.FacebookProperties;
import cloud.cleo.wahkon.model.IngestMetadata;
import cloud.cleo.wahkon.model.IngestMetadata.ContentKind;
import cloud.cleo.wahkon.util.ContentHash;
import cloud.cleo.wahkon.util.Sha256Hex;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

@Service
@Log4j2
@RequiredArgsConstructor
public class FacebookPipelineService {

    private final FacebookIngestService facebookIngestService;
    private final FacebookProperties props;

    private final VectorStore vectorStore;
    private final QdrantLookupService qdrant;

    private static final String SOURCE_PREFIX = "facebook:";

    public void ingestAllConfiguredPages() {
        var splitter = new TokenTextSplitter();

        for (var page : props.pages()) {
            try {
                ingestOnePage(page, splitter);
            } catch (Exception e) {
                log.warn("Facebook ingest failed for page: {}", page, e);
            }
        }
    }

    private void ingestOnePage(FacebookProperties.Page page, TokenTextSplitter splitter) {
        log.info("Facebook ingest starting page={}", page);

        int maxApiPages = props.maxApiPages();
        var posts = facebookIngestService.fetchRecentPosts(page, maxApiPages);

        log.info("Facebook fetched {} posts for page={}", posts.size(), page.name());

        for (var post : posts) {
            try {
                ingestOnePost(page, post, splitter);
            } catch (Exception e) {
                log.warn("Facebook post ingest failed page={} postId={}", page.name(), post.id(), e);
            }
        }
    }

    private void ingestOnePost(
            FacebookProperties.Page page,
            FacebookIngestService.FbPost post,
            TokenTextSplitter splitter
    ) {
        var text = normalize(post.message());
        if (text == null || text.isBlank()) {
            log.debug("Skipping FB post with empty message page={} id={}", page.name(), post.id());
            return;
        }

        final Instant fetchedAt = Instant.now();
        final String url = post.permalinkUrl() != null ? post.permalinkUrl() : "fb://" + post.id();

        // cheap change detector (keep as-is for compatibility with your existing qdrant sidecar)
        final String contentHash = ContentHash.md5Hex(text);

        final String source = SOURCE_PREFIX + page.name();

        boolean unchanged = qdrant.findExistingContentSha256(source, url)
                .map(existing -> existing.equals(contentHash))
                .orElse(false);

        if (unchanged) {
            log.info("FB unchanged, skipping embed/upsert: {}", url);

            qdrant.touchCrawled(source, url, fetchedAt);
            return;
        }

        log.info("FB indexing {} ({} chars)", url, text.length());

        deleteBySourceAndUrl(source, url);

        // ---- Build IngestMetadata contract ----
        // FB created_time is effectively "publishedAt" for posts.
        Instant createdTime = coerceInstant(post.createdTime()).orElse(null);

        // For FB, I like:
        // publishedAt = createdTime (when the post happened)
        // createdAt = createdTime (same)
        // observedAt = fetchedAt (when we saw it)
        IngestMetadata md = IngestMetadata.builder()
                .sourceUrl(safeUri(url).orElse(null))
                .contentHashSha256(Sha256Hex.toSha256(text.getBytes(StandardCharsets.UTF_8)))
                .contentKind(ContentKind.FACEBOOK_POST)
                .sourceSystem(source)
                .title(null) // you could derive a title from first N chars if desired
                .summary(null) // optional
                .author(page.name()) // or null; depends if you want page name here
                .publishedAt(createdTime)
                .createdAt(createdTime)
                .modifiedAt(null) // FB has edited_time sometimes; add later if you fetch it
                .fetchedAt(fetchedAt)
                .observedAt(fetchedAt)
                .pageCount(null)
                .contentLengthBytes((long) text.getBytes(StandardCharsets.UTF_8).length)
                .mimeType("text/plain")
                .build();

        var baseId = deterministicUuid("facebook|" + page.pageId() + "|" + post.id());

        var base = new Document(
                baseId,
                text,
                md.toVectorMetadata()
        );

        var splitDocs = splitter.split(base);
        var docs = new ArrayList<Document>(splitDocs.size());

        for (int i = 0; i < splitDocs.size(); i++) {
            var d = splitDocs.get(i);
            var chunkId = deterministicUuid("facebook|" + page.pageId() + "|" + post.id() + "|chunk|" + i);

            var splitMeta = new HashMap<String, Object>(d.getMetadata());
            splitMeta.put("chunk", i);

            docs.add(new Document(chunkId, d.getText(), splitMeta));
        }

        vectorStore.add(docs);
    }

    private void deleteBySourceAndUrl(String source, String url) {
        var b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(b.eq("sourceSystem", source), b.eq("sourceUrl", url)).build());
    }

    private static String deterministicUuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        return s.replace('\u00A0', ' ')
                .replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Optional<Instant> coerceInstant(Object v) {
        if (v == null) return Optional.empty();

        if (v instanceof Instant i) {
            return Optional.of(i);
        }

        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return Optional.empty();

            // First try Instant.parse (expects Z or offset)
            try {
                return Optional.of(Instant.parse(t));
            } catch (Exception ignored) {
            }

            // Then try OffsetDateTime.parse (handles offsets)
            try {
                return Optional.of(OffsetDateTime.parse(t).toInstant());
            } catch (Exception ignored) {
            }

            return Optional.empty();
        }

        return Optional.empty();
    }

    private static Optional<java.net.URI> safeUri(String s) {
        try {
            return s == null ? Optional.empty() : Optional.of(java.net.URI.create(s));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}