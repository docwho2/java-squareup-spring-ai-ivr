package cloud.cleo.squareup;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Logs execution time for each test to System.out.
 */
public class TimingExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Namespace NAMESPACE = Namespace.create(TimingExtension.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        long start = System.nanoTime();
        context.getStore(NAMESPACE).put(context.getUniqueId(), start);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        var store = context.getStore(NAMESPACE);
        long start = store.remove(context.getUniqueId(), long.class);
        long durationNanos = System.nanoTime() - start;
        long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);

        System.out.printf(
                "### TEST %s.%s took %d ms%n",
                context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(),
                durationMs
        );
    }
}
