package cloud.cleo.squareup;

import io.qameta.allure.Allure;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.extension.*;

@Log4j2
public class TimingExtension implements
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE
            = ExtensionContext.Namespace.create(TimingExtension.class);

    private static final String START_TIME_KEY = "start-time";
    private static final String RESULTS_KEY = "timing-results";

    @Value
    public static class TestTiming {

        String testDisplayName;
        String testId;     // class + method (for stability)
        long durationMs;
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        long now = System.nanoTime();
        getStore(context).put(START_TIME_KEY, now);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long startNanos = getStore(context).remove(START_TIME_KEY, long.class);
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // Skip warmup tests from metrics
        if (context.getTags().contains("warmup")) {
            log.info("Warmup {} took {} ms (ignored in metrics)",
                    context.getDisplayName(), durationMs);
            return;
        }

        String testId = context.getRequiredTestClass().getSimpleName()
                + "." + context.getRequiredTestMethod().getName();

        log.info("### TEST {} took {} ms", testId, durationMs);

        // Store result
        List<TestTiming> results = getOrInitResults(context);
        results.add(new TestTiming(context.getDisplayName(), testId, durationMs));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        List<TestTiming> results = getOrInitResults(context);
        if (results.isEmpty()) {
            return;
        }

        // Build a simple Markdown summary and attach to Allure
        String model = System.getenv("SPRING_AI_MODEL");
        String provider = System.getenv("SPRING_AI_PROVIDER");

        StringBuilder sb = new StringBuilder();
        sb.append("# Test Performance Summary\n\n");

        if (provider != null || model != null) {
            sb.append("**Provider/Model:** ");
            if (provider != null) {
                sb.append(provider);
            }
            if (provider != null && model != null) {
                sb.append(" / ");
            }
            if (model != null) {
                sb.append(model);
            }
            sb.append("\n\n");
        }

        sb.append("| # | Test | Duration (ms) | Approx RPS |\n");
        sb.append("|---|------|---------------|-----------:|\n");

        int i = 1;
        for (TestTiming timing : results) {
            // For now assume each test = 1 Lex call
            double seconds = timing.durationMs / 1000.0;
            double rps = seconds > 0 ? 1.0 / seconds : 0.0;

            sb.append("| ")
                    .append(i++)
                    .append(" | `")
                    .append(timing.testId)
                    .append("` | ")
                    .append(timing.durationMs)
                    .append(" | ")
                    .append(String.format("%.2f", rps))
                    .append(" |\n");
        }

        Allure.addAttachment("Performance Summary", "text/markdown", sb.toString());
        log.info("Performance summary attached to Allure for {}", context.getDisplayName());
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    @SuppressWarnings("unchecked")
    private List<TestTiming> getOrInitResults(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);
        List<TestTiming> results = (List<TestTiming>) store.get(RESULTS_KEY);
        if (results == null) {
            results = new ArrayList<>();
            store.put(RESULTS_KEY, results);
        }
        return results;
    }
}
