package com.scheduler.job;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires running PostgreSQL instance or Testcontainers")
class JobSchedulerApplicationTests {

    @Test
    void contextLoads() {
    }
}
