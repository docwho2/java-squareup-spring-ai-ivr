package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.CrawlerProperties;
import cloud.cleo.wahkon.model.IngestMetadata;
import cloud.cleo.wahkon.model.IngestMetadata.ContentKind;
import cloud.cleo.wahkon.util.Sha256Hex;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import static java.nio.charset.StandardCharsets.UTF_8;

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

    private void crawlOne(
            CrawlerProperties.Site site,
            String url,
            int depth,
            Pattern include,
            Pattern exclude,
            UrlFrontier frontier,
            TokenTextSplitter splitter
    ) {
        if (!isAllowed(site, url, include, exclude)) {
            return;
        }

        log.debug("CrawlOne() Incoming URL = [{}]", url);

        final Instant fetchedAt = Instant.now();

        Document page = null;
        String extractedText;
        IngestMetadata ingestMd;

        try {
            PageFetch pf = fetchPage(url);
            if (pf == null || pf.doc() == null) {
                log.debug("Skipping {} because Jsoup could not fetch document", url);
                return;
            }

            page = pf.doc();
            extractedText = extractReadableText(page);
            if (extractedText == null || extractedText.isBlank()) {
                log.debug("Skipping {} because no readable text extracted", url);
                return;
            }

            ingestMd = buildHtmlMetadata(site, url, page, extractedText, fetchedAt, pf);

        } catch (UnsupportedMimeTypeException umt) {
            if (!looksLikePdf(url)) {
                log.debug("Skipping {} because unsupported file format", url);
                return;
            }

            var pdfOpt = pdfTextExtractorService.fetchAndExtract(url);
            if (pdfOpt.isEmpty()) {
                return;
            }

            var pdf = pdfOpt.get();
            extractedText = pdf.text();
            if (extractedText == null || extractedText.isBlank()) {
                log.info("PDF has no extractable text (likely scanned), skipping: {}", url);
                return;
            }

            ingestMd = pdf.metadata().toBuilder()
                    .sourceSystem(site.name())
                    .sourceUrl(safeUri(url).orElse(null)) // ensure canonical
                    .fetchedAt(firstNonNull(pdf.metadata().getFetchedAt(), fetchedAt))
                    .build();

        } catch (Exception e) {
            log.debug("Crawl failed {}", url, e);
            return;
        }

        // --- Clean-contract change detection: SHA-256 vs SHA-256
        final String sourceSystem = ingestMd.getSourceSystem();
        final String sourceUrl = ingestMd.getSourceUrl() != null ? ingestMd.getSourceUrl().toString() : url;
        final String sha256 = ingestMd.getContentHashSha256();

        boolean unchanged = sha256 != null
                && qdrant.findExistingContentSha256(sourceSystem, sourceUrl)
                        .map(existing -> existing.equals(sha256))
                        .orElse(false);

        if (unchanged) {
            log.info("Unchanged, skipping embed/upsert: {}", url);
            qdrant.touchCrawled(sourceSystem, sourceUrl, fetchedAt);
        } else {
            log.info("Indexing {} ({} chars)", url, extractedText.length());

            // Prevent stale chunks for this exact identity
            deleteByIdentity(ContentKind.valueOf(ingestMd.getContentKind().name()), sourceSystem, sourceUrl);

            Map<String, Object> baseMeta = new HashMap<>(ingestMd.toVectorMetadata());

            var base = new org.springframework.ai.document.Document(
                    deterministicUuid(sourceSystem + "|" + sourceUrl),
                    extractedText,
                    baseMeta
            );

            var splitDocs = splitter.split(base);

            var docs = new ArrayList<org.springframework.ai.document.Document>(splitDocs.size());
            for (int i = 0; i < splitDocs.size(); i++) {
                var d = splitDocs.get(i);
                var chunkId = deterministicUuid(sourceSystem + "|" + sourceUrl + "|chunk|" + i);

                var meta = new HashMap<String, Object>(d.getMetadata());
                meta.put("chunk", i);

                docs.add(new org.springframework.ai.document.Document(chunkId, d.getText(), meta));
            }

            vectorStore.add(docs);
        }

        if (page != null && depth < props.maxDepth()) {
            page.select("a[href]")
                    .eachAttr("abs:href")
                    .stream()
                    .filter(h -> h != null && !h.isBlank())
                    .filter(h -> isAllowed(site, h, include, exclude))
                    .forEach(h -> frontier.add(h, depth + 1));
        }
    }

    private void deleteByIdentity(ContentKind kind, String sourceSystem, String sourceUrl) {
        var b = new FilterExpressionBuilder();

        vectorStore.delete(
                b.and(
                        b.eq("kind", kind.name()),
                        b.and(
                                b.eq("sourceSystem", sourceSystem),
                                b.eq("sourceUrl", sourceUrl)
                        )
                ).build()
        );
    }

    private PageFetch fetchPage(String url) throws UnsupportedMimeTypeException {
        try {
            Connection.Response resp = Jsoup.connect(url)
                    .userAgent(props.userAgent())
                    .timeout(props.timeoutMs())
                    .followRedirects(true)
                    .ignoreContentType(false)
                    .execute();

            String contentType = resp.contentType();
            Instant lastModified = parseHttpDate(resp.header("Last-Modified"));
            Long contentLength = parseLong(resp.header("Content-Length"));

            Document doc = resp.parse();
            return new PageFetch(doc, contentType, lastModified, contentLength);

        } catch (UnsupportedMimeTypeException umt) {
            throw umt;
        } catch (Exception e) {
            log.debug("Fetch failed {}", url, e);
            return null;
        }
    }

    private IngestMetadata buildHtmlMetadata(
            CrawlerProperties.Site site,
            String url,
            Document page,
            String extractedText,
            Instant fetchedAt,
            PageFetch pf
    ) {
        URI sourceUrl = safeUri(url).orElse(null);

        String title = firstNonBlank(
                normalizeTitle(page.title()),
                normalizeTitle(meta(page, "property", "og:title")),
                normalizeTitle(meta(page, "name", "twitter:title"))
        );

        String author = firstNonBlank(
                normalizeTitle(meta(page, "name", "author")),
                normalizeTitle(meta(page, "property", "article:author"))
        );

        String summary = firstNonBlank(
                normalizeTitle(meta(page, "name", "description")),
                normalizeTitle(meta(page, "property", "og:description")),
                excerpt(extractedText, 240)
        );

        Instant publishedAt = firstNonNull(
                parseInstant(meta(page, "property", "article:published_time")),
                parseInstant(meta(page, "name", "publish_date")),
                parseInstant(meta(page, "name", "date"))
        );

        Instant modifiedAt = firstNonNull(
                pf.lastModified(),
                parseInstant(meta(page, "property", "article:modified_time")),
                parseInstant(meta(page, "property", "og:updated_time"))
        );

        String mimeType = firstNonBlank(pf.contentType(), "text/html");
        Long contentLengthBytes = pf.contentLength() != null
                ? pf.contentLength()
                : (long) extractedText.getBytes(UTF_8).length;

        String sha = Sha256Hex.toSha256(extractedText.getBytes(UTF_8));

        return IngestMetadata.builder()
                .sourceUrl(sourceUrl)
                .contentHashSha256(sha)
                .contentKind(ContentKind.WEB_PAGE)
                .sourceSystem(site.name())
                .title(title)
                .summary(summary)
                .author(author)
                .publishedAt(publishedAt)
                .createdAt(null)
                .modifiedAt(modifiedAt)
                .fetchedAt(fetchedAt)
                .observedAt(null)
                .pageCount(null)
                .contentLengthBytes(contentLengthBytes)
                .mimeType(mimeType)
                .build();
    }

    private String extractReadableText(Document doc) {
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

    private static boolean looksLikePdf(String url) {
        var u = url.toLowerCase(Locale.ROOT);
        return u.contains(".pdf");
    }

    private static Optional<URI> safeUri(String s) {
        try {
            return s == null ? Optional.empty() : Optional.of(URI.create(s));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String normalizeTitle(String s) {
        if (s == null) {
            return null;
        }
        String n = s.replace('\u0000', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return n.isBlank() ? null : n;
    }

    private static String meta(Document page, String attrKey, String attrVal) {
        var el = page.selectFirst("meta[" + attrKey + "=" + attrVal + "]");
        return el != null ? el.attr("content") : null;
    }

    private static String excerpt(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars).trim();
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Instant parseHttpDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim(), OffsetDateTime::from).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... vals) {
        if (vals == null) {
            return null;
        }
        for (T v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
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

    private record PageFetch(
            Document doc,
            String contentType,
            Instant lastModified,
            Long contentLength
            ) {

    }
}
