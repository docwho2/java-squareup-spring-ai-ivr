package cloud.cleo.wahkon.service;

import java.io.InputStream;
import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Log4j2
public class PdfTextExtractorService {

    @Autowired
    @Qualifier("pdfRestClient")
    private RestClient restClient;

    // tune for your lambda limits
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024; // 20MB

    public Optional<PdfText> fetchAndExtract(String url) {
        try {
            byte[] bytes = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
                    .retrieve()
                    .body(byte[].class);

            if (bytes == null || bytes.length == 0) {
                log.debug("PDF download empty: {}", url);
                return Optional.empty();
            }
            if (bytes.length > MAX_PDF_BYTES) {
                log.info("Skipping PDF too large ({} bytes): {}", bytes.length, url);
                return Optional.empty();
            }
            
            return Optional.of(extract(bytes));

        } catch (Exception e) {
            log.warn("PDF fetch/extract failed: {}", url, e);
            return Optional.empty();
        }
    }

    private PdfText extract(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            return new PdfText(
                    normalize(stripper.getText(doc)), 
                    resolveTitle(doc)
            );
        }
    }

    private String resolveTitle(PDDocument doc) {
        // 1) Prefer XMP dc:title (metadata stream)
        var xmpTitle = readXmpDcTitle(doc).orElse(null);
        if (isNotBlank(xmpTitle)) {
            return xmpTitle;
        }

        // 2) Fallback to PDF Info dictionary Title
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null && isNotBlank(info.getTitle())) {
            return info.getTitle();
        }

        
        return null;
    }

    private Optional<String> readXmpDcTitle(PDDocument doc) {
        try {
            PDMetadata meta = doc.getDocumentCatalog() != null ? doc.getDocumentCatalog().getMetadata() : null;
            if (meta == null) {
                return null;
            }

            try (InputStream in = meta.exportXMPMetadata()) {
                if (in == null) {
                    return null;
                }

                XMPMetadata xmp = new DomXmpParser().parse(in);
                DublinCoreSchema dc = xmp.getDublinCoreSchema();
                if (dc == null) {
                    return null;
                }

                // This is the key point: no LangAlt class needed in your code.
                // dc.getTitle() already resolves the lang-alt title value.
                var title = dc.getTitle();
                return isNotBlank(title) ? Optional.of(title) : Optional.empty();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeTitle(String s) {
        if (s == null) return null;
        return s.replace('\u0000', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
    
    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public record PdfText(String text, String title) {

    }
}
