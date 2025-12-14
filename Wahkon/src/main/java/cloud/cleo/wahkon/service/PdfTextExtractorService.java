package cloud.cleo.wahkon.service;

import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;
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
            
            doc.getDocumentInformation().getTitle();
            
            String text = normalize(stripper.getText(doc));
            return new PdfText(text, doc.getDocumentInformation().getTitle(), doc.getNumberOfPages(), pdfBytes.length);
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record PdfText(String text, String title, int pages, int bytes) {}
}