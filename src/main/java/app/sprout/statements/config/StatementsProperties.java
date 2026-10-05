package app.sprout.statements.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.statements} in statements.yml. */
@ConfigurationProperties("sprout.statements")
public record StatementsProperties(String serviceKey, String brokerName, String brokerRegistration, Url oms, Url ledger, Url accounts,
                                   Depository depository) {

    public record Url(String url) {}

    public record Depository(String url, String participantKey) {}
}
