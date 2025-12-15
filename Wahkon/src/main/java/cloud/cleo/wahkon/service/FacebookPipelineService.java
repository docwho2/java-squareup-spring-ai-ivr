package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.FacebookProperties;
import cloud.cleo.wahkon.util.ContentHash;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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


    public void ingestAllConfiguredPages() {
        var splitter = new TokenTextSplitter();

        for (var page : props.pages()) {
            try {
                ingestOnePage(page, splitter);
            } catch (Exception e) {
                // Don’t kill the whole run due to one page
                log.warn("Facebook ingest failed for page: {}", page, e);
            }
        }
    }

    private void ingestOnePage(FacebookProperties.Page page, TokenTextSplitter splitter) {
        log.info("Facebook ingest starting page={}", page);

        // You probably want a *post count* max, not “maxPages” of paging.
        // Your ingest service currently uses "maxPages" loop count, so map it to something sane.
        // Example: fetch up to N pages of API results.
        int maxApiPages = props.maxApiPages(); // add to properties, e.g. 20
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

    private void ingestOnePost(FacebookProperties.Page page,
            FacebookIngestService.FbPost post,
            TokenTextSplitter splitter) {

        // Some entries won’t have message (image-only posts, etc). Decide policy:
        // 1) Skip blank
        // 2) Or store permalink + created_time anyway
        var text = normalize(post.message());
        if (text == null || text.isBlank()) {
            log.debug("Skipping FB post with empty message page={} id={}", page.name(), post.id());
            return;
        }

        final var fetchedAt = Instant.now();
        final var url = post.permalinkUrl() != null ? post.permalinkUrl() : "fb://" + post.id();
        final var contentHash = ContentHash.md5Hex(text);

        boolean unchanged = qdrant.findExistingContentHash("facebook:" + page.name(), url)
                .map(existing -> existing.equals(contentHash))
                .orElse(false);

        if (unchanged) {
            log.info("FB unchanged, skipping embed/upsert: {}", url);

            qdrant.touchCrawled(
                    "facebook:" + page.name(),
                    url,
                    fetchedAt
            );
            return;
        }

        log.info("FB indexing {} ({} chars)", url, text.length());

        deleteBySourceAndUrl("facebook:" + page.name(), url);

        var baseId = deterministicUuid("facebook|" + page.pageId() + "|" + post.id());

        Map<String, Object> meta = new HashMap<>();

        meta.put("source", "facebook:" + page.name());
        meta.put("page_id", page.pageId());
        meta.put("page_name", page.name());
        meta.put("post_id", post.id());
        meta.put("url", url);
        meta.put("created_time", post.createdTime());
        meta.put("content_hash", contentHash);
        meta.put("content_len", text.length());
        meta.put("crawled_at", fetchedAt.toString());
        meta.put("crawled_at_epoch", fetchedAt.toEpochMilli());
        meta.put("type", "facebook_post");

        var base = new Document(
                baseId,
                text,
                meta
        );

        var splitDocs = splitter.split(base);
        var docs = new ArrayList<Document>(splitDocs.size());

        for (int i = 0; i < splitDocs.size(); i++) {
            var d = splitDocs.get(i);
            var chunkId = deterministicUuid("facebook|" + page.pageId() + "|" + post.id() + "|chunk|" + i);

            var split_meta = new HashMap<String, Object>(d.getMetadata());
            split_meta.put("chunk", i);

            docs.add(new Document(chunkId, d.getText(), split_meta));
        }

        vectorStore.add(docs);
    }

    private void deleteBySourceAndUrl(String source, String url) {
        var b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(b.eq("source", source), b.eq("url", url)).build());
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
}
