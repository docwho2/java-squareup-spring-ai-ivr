package cloud.cleo.squareup;

import static cloud.cleo.squareup.AbstractLexAwsTestSupport.INTER_TEST_DELAY_MS;
import static cloud.cleo.squareup.AbstractLexAwsTestSupport.JUNIT_TAG_WARM_UP;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.extension.*;

@Log4j2
public class TimingExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final ExtensionContext.Namespace NAMESPACE
            = ExtensionContext.Namespace.create(TimingExtension.class);

    private static final String START_TIME_KEY = "start-time";
    private static final String RESULTS_KEY = "timing-results";

    private static final List<TestTiming> RESULTS = new CopyOnWriteArrayList<>();

    public static List<TestTiming> getResults() {
        return RESULTS;
    }

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
        if (context.getTags().contains(JUNIT_TAG_WARM_UP) ) {
            log.info("Warmup {} took {} ms (ignored in metrics)",
                    context.getDisplayName(), durationMs - INTER_TEST_DELAY_MS);
            return;
        }

        String testId = context.getRequiredTestClass().getSimpleName()
                + "." + context.getRequiredTestMethod().getName();

        RESULTS.add(new TestTiming(context.getDisplayName(), testId, durationMs));
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    // Helper for the base class to read results
    public static List<TestTiming> getResults(ExtensionContext context) {
        var store = context.getRoot().getStore(NAMESPACE);
        @SuppressWarnings("unchecked")
        var results = (List<TestTiming>) store.get(RESULTS_KEY);
        return results != null ? results : List.of();
    }

}
