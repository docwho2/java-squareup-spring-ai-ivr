package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.CrawlerProperties;
import cloud.cleo.wahkon.util.ContentHash;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Jsoup;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.jsoup.UnsupportedMimeTypeException;

@Service
@Log4j2
@RequiredArgsConstructor
public class WahkonWebCrawlerService {

    private final VectorStore vectorStore;
    private final CrawlerProperties props;
    private final ExecutorService virtualThreadExecutor;
    private final QdrantLookupService qdrant;
    private final PdfTextExtractorService pdfTextExtractorService;

    public void crawlAll() {
        for (var site : props.sites()) {
            try {
                crawlSite(site);
            } catch (Exception e) {
                // Don’t kill the whole run due to one site
                log.warn("Site crawl failed: {}", site.name(), e);
            }
        }
    }

    void crawlSite(CrawlerProperties.Site site) throws Exception {
        var include = site.includeUrlRegex() != null ? Pattern.compile(site.includeUrlRegex()) : null;
        var exclude = site.excludeUrlRegex() != null ? Pattern.compile(site.excludeUrlRegex()) : null;

        var frontier = new UrlFrontier(props.maxPages());
        site.seeds().forEach(seed -> frontier.add(seed, 0));

        var splitter = new TokenTextSplitter();

        var inFlight = new ArrayList<Future<Void>>();

        while (frontier.hasNext()) {
            while (inFlight.size() < props.concurrency() && frontier.hasNext()) {
                var item = frontier.next();
                inFlight.add(virtualThreadExecutor.submit(() -> {
                    // Random jitter between 250ms and 10secs
                    int delayMs = ThreadLocalRandom.current().nextInt(250, 10001);
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }

                    crawlOne(site, item.url(), item.depth(), include, exclude, frontier, splitter);
                    return null;
                }));
            }

            // Wait for earliest submitted task
            var done = inFlight.remove(0);
            try {
                done.get();
            } catch (ExecutionException e) {
                // ignore per-page
            }
        }

        for (var f : inFlight) {
            try {
                f.get();
            } catch (ExecutionException e) {
                // ignore
            }
        }
    }

    private void crawlOne(CrawlerProperties.Site site,
            String url,
            int depth,
            Pattern include,
            Pattern exclude,
            UrlFrontier frontier,
            TokenTextSplitter splitter) {

        if (!isAllowed(site, url, include, exclude)) {
            return;
        }

        log.debug("CrawlOne() Incoming URL = [{}]", url);

        final var fetchedAt = Instant.now();
        org.jsoup.nodes.Document page = null;
        String extracted;
        String title;
        try {
            page = fetch(url);  // throws if not text document

            if (page == null) {
                log.debug("Skipping {} because we jsoup could not fetch document", url);
                return;
            }
            title = page.title();

            // At this point we are guranteed to have text page
            extracted = extractReadableText(page);
            if (extracted == null || extracted.isBlank()) {
                log.debug("Skipping {} because we could not extract any readable text", url);
                return;
            }
        } catch (UnsupportedMimeTypeException umt) {
            // We will try and process PDF's which Jsoup will not fetch
            if (looksLikePdf(url)) {
                var pdfOpt = pdfTextExtractorService.fetchAndExtract(url);
                if (pdfOpt.isEmpty()) {
                    return;  // service logs errors on this
                }

                var pdf = pdfOpt.get();
                if (pdf.text().isBlank()) {
                    log.info("PDF has no extractable text (likely scanned), skipping: {}", url);
                    return;
                }

                // All good, have some text to process
                extracted = pdf.text();
                title = pdf.title();
            } else {
                // Not a non-text file format we can deal with
                log.debug("Skipping {} because we cannot support this file format", url);
                return;
            }
        }

        final var contentHash = ContentHash.md5Hex(extracted);

        // ---- decide if we need to embed
        boolean unchanged = qdrant.findExistingContentHash(site.name(), url)
                .map(existing -> existing.equals(contentHash))
                .orElse(false);

        // If unchanged, skip expensive embedding + upsert
        if (unchanged) {
            log.info("Unchanged, skipping embed/upsert: {}", url);

            // still record freshness
            qdrant.touchCrawled(
                    site.name(),
                    url,
                    fetchedAt
            );

        } else {
            // New content to to store
            log.info("Indexing {} ({} chars)", url, extracted.length());

            // Always clear existing chunks for this URL to prevent stale chunk leftovers
            deleteBySourceAndUrl(site.name(), url);

            var base = new Document(
                    deterministicUuid(site.name() + "|" + url),
                    extracted,
                    Map.of(
                            "source", site.name(),
                            "url", url,
                            "title", title,
                            "content_hash", contentHash,
                            "content_len", extracted.length(),
                            "crawled_at", fetchedAt.toString(),
                            "crawled_at_epoch", fetchedAt.toEpochMilli()
                    )
            );

            var splitDocs = splitter.split(base);

            var docs = new ArrayList<Document>(splitDocs.size());
            for (int i = 0; i < splitDocs.size(); i++) {
                var d = splitDocs.get(i);
                var chunkId = deterministicUuid(site.name() + "|" + url + "|chunk|" + i);

                var meta = new HashMap<String, Object>(d.getMetadata());
                meta.put("chunk", i);

                docs.add(new Document(chunkId, d.getText(), meta));
            }

            vectorStore.add(docs);
        }

        // ---- ALWAYS continue traversal
        if (page != null && depth < props.maxDepth()) {
            page.select("a[href]")
                    .eachAttr("abs:href")
                    .stream()
                    .filter(h -> h != null && !h.isBlank())
                    .filter(h -> isAllowed(site, h, include, exclude))
                    .forEach(h -> frontier.add(h, depth + 1));
        }
    }

    private void deleteBySourceAndUrl(String source, String url) {
        var b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(b.eq("source", source), b.eq("url", url)).build());
    }

   

    private org.jsoup.nodes.Document fetch(String url) throws UnsupportedMimeTypeException {
        try {
            return Jsoup.connect(url)
                    .userAgent(props.userAgent())
                    .timeout(props.timeoutMs())
                    .followRedirects(true)
                    .get();
        } catch (UnsupportedMimeTypeException umt) {
            // Catch up higher so we can check for PDF's or other mime types we will process
            throw umt;
        } catch (Exception e) {
            log.debug("Fetch failed {}", url, e);
            return null;
        }
    }

    private String extractReadableText(org.jsoup.nodes.Document doc) {
        doc.select("script,style,noscript,svg,canvas,header,footer,nav,aside,form").remove();

        var main = doc.selectFirst("main");
        if (main != null) {
            return normalize(main.text());
        }

        var article = doc.selectFirst("article");
        if (article != null) {
            return normalize(article.text());
        }

        return normalize(doc.body() != null ? doc.body().text() : doc.text());
    }

    private String normalize(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    private boolean isAllowed(CrawlerProperties.Site site, String url, Pattern include, Pattern exclude) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return false;
        }

        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }

        var host = uri.getHost();
        if (host == null) {
            return false;
        }

        if (!site.allowedHosts().isEmpty()
                && site.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            return false;
        }

        if (include != null && !include.matcher(url).matches()) {
            return false;
        }

        return !(exclude != null && exclude.matcher(url).matches());
    }

    private static String deterministicUuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(UTF_8)).toString();
    }

    static final class UrlFrontier {

        record Item(String url, int depth) {

        }

        private final int maxPages;
        private final Deque<Item> q = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();

        UrlFrontier(int maxPages) {
            this.maxPages = maxPages;
        }

        synchronized void add(String url, int depth) {
            if (url == null) {
                return;
            }
            if (seen.size() >= maxPages) {
                return;
            }
            if (seen.add(url)) {
                q.addLast(new Item(url, depth));
            }
        }

        synchronized boolean hasNext() {
            return !q.isEmpty();
        }

        synchronized Item next() {
            return q.removeFirst();
        }
    }

    private static boolean looksLikePdf(String url) {
        var u = url.toLowerCase(Locale.ROOT);
        // handles .pdf, .pdf?download=1, .pdf#page=2, etc.
        return u.contains(".pdf");
    }
}
