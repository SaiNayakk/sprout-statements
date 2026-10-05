package app.sprout.statements.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StatementsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
