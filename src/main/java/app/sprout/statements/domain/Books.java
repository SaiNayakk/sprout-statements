package app.sprout.statements.domain;

import app.sprout.statements.config.StatementsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The books statements are read from: the customer's account (accounts), their executions (the order
 * service), their cash (the ledger) and their demat account (the depository). Each call has a hard
 * deadline; a book that can't be read is a 503 for the customer, never a partial statement.
 */
@Component
public class Books {

    static final Duration DEADLINE = Duration.ofSeconds(5);

    private final StatementsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public Books(StatementsProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    /** The market's current session: trade dates are sessions, so "today" for trading records is this. */
    public LocalDate session() {
        return LocalDate.parse(ok(send(HttpRequest.newBuilder(URI.create(props.marketdata().url() + "/v1/market")).GET()))
                .path("sessionDate").asText());
    }

    /** The customer's Sprout account, or NO_ACCOUNT. */
    public JsonNode account(UUID userId) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(props.accounts().url() + "/internal/v1/accounts/" + userId))
                .header("X-Service-Key", props.serviceKey()).GET());
        if (r.statusCode() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        return ok(r);
    }

    /** Executions with trade dates from {@code from} to {@code to}, both inclusive, at most a year apart. */
    public JsonNode executions(UUID userId, LocalDate from, LocalDate to) {
        return ok(send(HttpRequest.newBuilder(URI.create(props.oms().url() + "/internal/v1/executions?from=" + from + "&to=" + to
                + "&userId=" + userId)).header("X-Service-Key", props.serviceKey()).GET())).path("executions");
    }

    /** A ledger account's statement from {@code from} (inclusive) to {@code to} (exclusive). */
    public JsonNode ledgerStatement(String account, LocalDate from, LocalDate to) {
        return ok(send(HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/accounts/" + URLEncoder.encode(account, StandardCharsets.UTF_8)
                + "/statement?from=" + from + "&to=" + to)).GET()));
    }

    public JsonNode dematHoldings(String boId) {
        return ok(send(HttpRequest.newBuilder(URI.create(props.depository().url() + "/participant/v1/accounts/" + boId + "/holdings"))
                .header("X-Participant-Key", props.depository().participantKey()).GET())).path("holdings");
    }

    private JsonNode ok(HttpResponse<String> r) {
        if (r.statusCode() / 100 != 2) {
            throw unavailable();
        }
        try {
            return json.readTree(r.body());
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder req) {
        try {
            return http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString()).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable();
        }
    }

    static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout's records can't be read right now. Try again shortly.", 5,
                Map.of());
    }
}
