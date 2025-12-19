package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.model.IngestMetadata;
import cloud.cleo.wahkon.model.IngestMetadata.ContentKind;
import cloud.cleo.wahkon.util.Sha256Hex;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Log4j2
public class PdfTextExtractorService {

    @Autowired
    @Qualifier("pdfRestClient")
    private RestClient restClient;

    // tune for your lambda limits
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024; // 20MB

    /**
     * Download a PDF and extract:
     * - normalized text
     * - IngestMetadata contract (best-effort + HTTP header fallbacks)
     * @param url
     * @return 
     */
    public Optional<PdfExtraction> fetchAndExtract(String url) {
        final Instant fetchedAt = Instant.now();
        final URI sourceUri = safeUri(url).orElse(null);

        try {
            ResponseEntity<byte[]> resp = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] bytes = resp.getBody();

            if (bytes == null || bytes.length == 0) {
                log.debug("PDF download empty: {}", url);
                return Optional.empty();
            }
            if (bytes.length > MAX_PDF_BYTES) {
                log.info("Skipping PDF too large ({} bytes): {}", bytes.length, url);
                return Optional.empty();
            }

            // ---- HTTP header metadata (fallbacks) ----
            Instant httpLastModified = null;
            try {
                long lm = resp.getHeaders().getLastModified();
                if (lm > 0) {
                    httpLastModified = Instant.ofEpochMilli(lm);
                }
            } catch (Exception ignored) {
                // headers.getLastModified() can throw if value malformed
            }

            String httpContentType = resp.getHeaders().getContentType() != null
                    ? resp.getHeaders().getContentType().toString()
                    : null;

            long httpContentLength = resp.getHeaders().getContentLength(); // -1 if unknown

            HttpHints httpHints = new HttpHints(httpLastModified, httpContentType, httpContentLength);

            return Optional.of(extract(sourceUri, bytes, fetchedAt, httpHints));

        } catch (Exception e) {
            log.warn("PDF fetch/extract failed: {}", url, e);
            return Optional.empty();
        }
    }

    private PdfExtraction extract(URI sourceUrl, byte[] pdfBytes, Instant fetchedAt, HttpHints http) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            // -------- Text extraction --------
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = normalize(stripper.getText(doc));

            // -------- Metadata extraction (best-effort) --------
            PDDocumentInformation info = doc.getDocumentInformation();
            XmpFields xmp = readXmp(doc).orElseGet(XmpFields::empty);

            String title = firstNonBlank(
                    normalizeTitle(xmp.dcTitle()),
                    normalizeTitle(info != null ? info.getTitle() : null)
            );

            String author = firstNonBlank(
                    normalizeTitle(xmp.dcCreator()),
                    normalizeTitle(info != null ? info.getAuthor() : null)
            );

            // Summary: prefer XMP dc:description then PDF Info subject (optional)
            String summary = firstNonBlank(
                    normalizeTitle(xmp.dcDescription()),
                    normalizeTitle(info != null ? info.getSubject() : null)
            );

            // ---- Dates (with null safety) ----
            Instant infoCreated = toInstant(info != null ? info.getCreationDate() : null);
            Instant infoModified = toInstant(info != null ? info.getModificationDate() : null);

            Instant createdAt = firstNonNull(
                    xmp.createDate(),
                    xmp.dcDate(),
                    infoCreated
            );

            // Fallback chain for modifiedAt includes HTTP Last-Modified
            Instant modifiedAt = firstNonNull(
                    xmp.modifyDate(),
                    infoModified,
                    http.lastModified()
            );

            // PDFs rarely have explicit "publishedAt" distinct from createdAt.
            // If you later decide "publishedAt == createdAt for PDFs", do it in one place:
            Instant publishedAt = null;

            // Content length: prefer actual bytes length; header length is useful when body is absent (but we have body).
            long contentLengthBytes = pdfBytes.length > 0
                    ? pdfBytes.length
                    : (http.contentLength() > 0 ? http.contentLength() : 0L);

            // Mime type: prefer HTTP content-type, else application/pdf
            String mimeType = firstNonBlank(http.contentType(), MediaType.APPLICATION_PDF_VALUE);

            IngestMetadata md = IngestMetadata.builder()
                    .sourceUrl(sourceUrl)
                    .contentHashSha256(Sha256Hex.toSha256(pdfBytes))
                    .contentKind(ContentKind.PDF)
                    // Best set by caller/crawler; optional here
                    // .sourceSystem("cityofwahkon")
                    .title(title)
                    .summary(summary)
                    .author(author)
                    .publishedAt(publishedAt)
                    .createdAt(createdAt)
                    .modifiedAt(modifiedAt)
                    .fetchedAt(fetchedAt)
                    .observedAt(null)
                    .pageCount(doc.getNumberOfPages())
                    .contentLengthBytes(contentLengthBytes)
                    .mimeType(mimeType)
                    .build();

            return new PdfExtraction(text, md);
        }
    }

    /**
     * Pull a few useful XMP fields (best-effort). If parsing fails, caller falls back to PDF Info / HTTP headers.
     */
    private Optional<XmpFields> readXmp(PDDocument doc) {
        try {
            PDMetadata meta = doc.getDocumentCatalog() != null ? doc.getDocumentCatalog().getMetadata() : null;
            if (meta == null) {
                return Optional.empty();
            }

            try (InputStream in = meta.exportXMPMetadata()) {
                if (in == null) {
                    return Optional.empty();
                }

                XMPMetadata xmp = new DomXmpParser().parse(in);

                DublinCoreSchema dc = xmp.getDublinCoreSchema();
                XMPBasicSchema basic = xmp.getXMPBasicSchema();
                AdobePDFSchema pdf = xmp.getAdobePDFSchema(); // currently unused, but kept handy

                String dcTitle = dc != null ? dc.getTitle() : null;
                String dcCreator = (dc != null && dc.getCreators() != null && !dc.getCreators().isEmpty())
                        ? dc.getCreators().getFirst()
                        : null;
                String dcDescription = dc != null ? dc.getDescription() : null;

                Instant dcDate = null;
                if (dc != null && dc.getDates() != null && !dc.getDates().isEmpty()) {
                    dcDate = toInstant(dc.getDates().getFirst());
                }

                Instant createDate = toInstant(basic != null ? basic.getCreateDate() : null);
                Instant modifyDate = toInstant(basic != null ? basic.getModifyDate() : null);

                @SuppressWarnings("unused")
                String ignoredKeywords = pdf != null ? pdf.getKeywords() : null;

                return Optional.of(new XmpFields(dcTitle, dcCreator, dcDescription, dcDate, createDate, modifyDate));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Instant toInstant(Object maybeCalendarOrDate) {
        // pdfbox/xmpbox return types vary; safest is to support Calendar + OffsetDateTime + Date
        if (maybeCalendarOrDate == null) return null;

        if (maybeCalendarOrDate instanceof java.util.Calendar cal) {
            try { return cal.toInstant(); } catch (Exception ignored) { return null; }
        }
        if (maybeCalendarOrDate instanceof java.util.Date d) {
            return Instant.ofEpochMilli(d.getTime());
        }
        if (maybeCalendarOrDate instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }

        // XMPBasicSchema getters in some versions return String; if so, try parsing.
        if (maybeCalendarOrDate instanceof String s) {
            return tryParseInstant(s);
        }

        return null;
    }

    private static Instant tryParseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            // Sometimes it's like "2025-01-01T12:34:56-06:00" (still ISO) -> Instant.parse supports that.
            // If it isn't strictly parseable, ignore.
            return null;
        }
    }

    private static Optional<URI> safeUri(String s) {
        try {
            return s == null ? Optional.empty() : Optional.of(URI.create(s));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String normalizeTitle(String s) {
        if (s == null) return null;
        String n = s.replace('\u0000', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return n.isBlank() ? null : n;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... vals) {
        if (vals == null) return null;
        for (T v : vals) {
            if (v != null) return v;
        }
        return null;
    }

    public record PdfExtraction(String text, IngestMetadata metadata) {}

    private record XmpFields(
            String dcTitle,
            String dcCreator,
            String dcDescription,
            Instant dcDate,
            Instant createDate,
            Instant modifyDate
    ) {
        static XmpFields empty() {
            return new XmpFields(null, null, null, null, null, null);
        }
    }

    private record HttpHints(
            Instant lastModified,
            String contentType,
            long contentLength
    ) {}
}