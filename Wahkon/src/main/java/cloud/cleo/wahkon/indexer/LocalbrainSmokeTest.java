package cloud.cleo.wahkon.indexer;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LocalbrainSmokeTest implements CommandLineRunner {

    private final VectorStore vectorStore;              // also a VectorStoreRetriever

    public LocalbrainSmokeTest(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        // 1) Add a test doc
        var doc = new Document(
                "Muggs is running a Vikings game special this Sunday in Wahkon.",
                Map.of(
                        "source", "muggs-test",
                        "created_at", "2025-12-11T20:55:00Z",
                        "url", "https://example.com/muggs/vikings"
                )
        );
        vectorStore.add(List.of(doc));

        // 2) Retrieve with a semantic query
        // You can use the simple String overload:
        var hits = vectorStore.similaritySearch("Any Vikings game specials in town?");

        // or the SearchRequest API:
        // var hits = vectorStore.retrieve(
        //         SearchRequest.query("Any Vikings game specials in town?")
        //                       .withTopK(5)
        // );

        System.out.println("Retrieved " + hits.size() + " docs:");
        hits.forEach(d -> System.out.println("- " + d.getFormattedContent() + " " + d.getMetadata()));
    }
}

