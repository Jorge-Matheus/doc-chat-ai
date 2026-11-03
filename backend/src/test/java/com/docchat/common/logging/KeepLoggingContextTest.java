package com.docchat.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KeepLoggingContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void workOnAnotherThreadKeepsTheContextOfWhoStartedIt() throws Exception {
        MDC.put("correlationId", "abc");
        AtomicReference<String> seen = new AtomicReference<>();

        Runnable task = new KeepLoggingContext().decorate(() -> seen.set(MDC.get("correlationId")));
        Thread thread = new Thread(task);
        thread.start();
        thread.join();

        assertThat(seen).hasValue("abc");
    }

    @Test
    void aThreadThatIsReusedDoesNotKeepTheContextOfItsLastTask() {
        MDC.put("correlationId", "abc");
        Runnable task = new KeepLoggingContext().decorate(() -> {});

        task.run();

        assertThat(MDC.get("correlationId")).isNull();
    }
}
