package cloud.cleo.wahkon.model;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class IngestMetadata {

    // Identity / traceability
    URI sourceUrl;              // where fetched from
    String contentHashSha256;   // de-dupe key across URLs (optional, recommended)

    // Classification
    ContentKind contentKind;    // PDF / WEB_PAGE / FACEBOOK_POST / ...
    String sourceSystem;        // "cityofwahkon", "facebook", "manual"

    // Human / display fields
    String title;
    String summary;             // short description if available
    String author;              // person/org

    // Time fields (raw)
    Instant publishedAt;        // true publish timestamp if known
    Instant createdAt;          // creation timestamp (PDF XMP create, FB created_time, etc.)
    Instant modifiedAt;         // last modified (HTTP Last-Modified, PDF XMP modify, etc.)
    Instant fetchedAt;          // when we fetched
    Instant observedAt;         // when we observed on page feed (optional; useful for FB feeds)

    // Optional content attributes
    Integer pageCount;          // PDFs
    Long contentLengthBytes;
    String mimeType;            // "application/pdf", "text/html", ...

    // Derived fields (computed)
    public Instant bestModifiedTs() {
        // Your “best guess” for recency ranking:
        // Prefer explicit modifiedAt, then publishedAt, then createdAt, then observedAt, then fetchedAt
        if (modifiedAt != null) {
            return modifiedAt;
        }
        if (publishedAt != null) {
            return publishedAt;
        }
        if (createdAt != null) {
            return createdAt;
        }
        if (observedAt != null) {
            return observedAt;
        }
        return fetchedAt;
    }

    public Instant bestPublishedTs() {
        // “Best guess” publish moment
        if (publishedAt != null) {
            return publishedAt;
        }
        if (createdAt != null) {
            return createdAt;
        }
        return null;
    }

    public Map<String, Object> toVectorMetadata() {
        // Store raw + derived (important: store derived as strings for portability)
        Map<String, Object> m = new LinkedHashMap<>();

        put(m, "sourceUrl", sourceUrl != null ? sourceUrl.toString() : null);
        put(m, "contentSha256", contentHashSha256);

        put(m, "kind", contentKind != null ? contentKind.name() : null);
        put(m, "sourceSystem", sourceSystem);

        put(m, "title", title);
        put(m, "summary", summary);
        put(m, "author", author);

        put(m, "publishedAt", publishedAt != null ? publishedAt.toString() : null);
        put(m, "createdAt", createdAt != null ? createdAt.toString() : null);
        put(m, "modifiedAt", modifiedAt != null ? modifiedAt.toString() : null);
        put(m, "observedAt", observedAt != null ? observedAt.toString() : null);
        put(m, "fetchedAt", fetchedAt != null ? fetchedAt.toString() : Instant.now().toString());
        put(m, "fetchedAtEpoch", fetchedAt != null ? fetchedAt.toEpochMilli() : Instant.now().toEpochMilli());

        Instant bestMod = bestModifiedTs();
        put(m, "bestModifiedTs", bestMod != null ? bestMod.toString() : null);
        put(m, "bestModifiedTsEpoch", bestMod != null ? bestMod.toEpochMilli() : null);

        Instant bestPub = bestPublishedTs();
        put(m, "bestPublishedTs", bestPub != null ? bestPub.toString() : null);

        put(m, "pageCount", pageCount);
        put(m, "contentLengthBytes", contentLengthBytes);
        put(m, "mimeType", mimeType);

        return Map.copyOf(m);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    public static enum ContentKind {
        WEB_PAGE,
        PDF,
        IMAGE,
        FACEBOOK_POST,
        OTHER
    }
}
