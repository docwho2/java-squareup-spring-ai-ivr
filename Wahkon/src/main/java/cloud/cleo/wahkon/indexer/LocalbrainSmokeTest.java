package cloud.cleo.wahkon.indexer;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;


public class LocalbrainSmokeTest implements CommandLineRunner {

    private final VectorStore vectorStore;  

    public LocalbrainSmokeTest(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        // 1) Add a test doc
        var doc = new Document(
                UUID.nameUUIDFromBytes("Muggs is running a Vikings game special this Sunday in Wahkon.".getBytes()).toString(),
                "Muggs is running a Vikings game special this Sunday in Wahkon.",
                Map.of(
                        "source", "facebook",
                        "created_at", "2025-12-11T20:55:00Z",
                        "url", "https://example.com/muggs/vikings"
                )
        );
        vectorStore.add(List.of(doc));

        // 2) Retrieve with a semantic query
        // You can use the simple String overload:
        var hits = vectorStore.similaritySearch("Any Vikings game specials ?");

        System.out.println("Retrieved " + hits.size() + " docs:");
        hits.forEach(d -> System.out.println("- " + d.getFormattedContent() + " " + d.getMetadata()));
    }
}

