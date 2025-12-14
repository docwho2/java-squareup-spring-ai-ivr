package cloud.cleo.wahkon.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.support.TaskExecutorAdapter;

@Configuration
public class ExecutorConfig {

    @Bean(name = "virtual", destroyMethod = "shutdown")
    public ExecutorService virtualThreadExecutor() {
        // one shared virtual-thread-per-task executor for the whole app
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("wahkon-crawler-", 0)
                        .factory()
        );
    }

    @Primary
    @Bean(name = "applicationTaskExecutor")
    public Executor applicationTaskExecutor(@Qualifier("virtual") ExecutorService virtualThreadExecutor) {
        // Wrap the ExecutorService so Spring can treat it as a TaskExecutor
        return new TaskExecutorAdapter(virtualThreadExecutor);
    }
}
