package app.sprout.statements;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Statements built from stand-ins for the books: accounts, the order service's executions, the
 * ledger and the depository. Every response is checked against statements-v1.yaml.
 */
@SpringBootTest(properties = "spring.config.name=statements")
@AutoConfigureMockMvc
class StatementsApiTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID USER = UUID.fromString("5d1f2b8e-2a47-4c39-9e1b-7c0f6a2d4e90");
    static final List<Map<String, Object>> EXECUTIONS = new ArrayList<>();
    static final AtomicBoolean LEDGER_DOWN = new AtomicBoolean();
    static final HttpServer BOOKS = books();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + BOOKS.getAddress().getPort();
        r.add("sprout.statements.marketdata.url", () -> base);
        r.add("sprout.statements.oms.url", () -> base);
        r.add("sprout.statements.ledger.url", () -> base);
        r.add("sprout.statements.accounts.url", () -> base);
        r.add("sprout.statements.depository.url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-07T06:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.STATEMENTS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;

    @BeforeEach
    void aCustomerWhoTraded() {
        LEDGER_DOWN.set(false);
        EXECUTIONS.clear();
        EXECUTIONS.add(execution("2026-10-05", "HARBOR", "BUY", "CNC", 10, "1000.00", "5.94", null));
        EXECUTIONS.add(execution("2026-10-05", "KOSHA", "BUY", "MIS", 20, "300.00", "4.50", null));
        EXECUTIONS.add(execution("2026-10-05", "KOSHA", "SELL", "MIS", 20, "305.00", "6.20", "100.00"));
        EXECUTIONS.add(execution("2026-10-06", "HARBOR", "SELL", "CNC", 4, "1100.00", "6.10", "400.00"));
    }

    static Map<String, Object> execution(String day, String symbol, String side, String product, long qty, String price, String charges,
                                         String realised) {
        long value = Long.parseLong(price.replace(".", "")) * qty;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("orderId", UUID.randomUUID().toString());
        e.put("userId", USER.toString());
        e.put("tradeId", UUID.randomUUID().toString());
        e.put("tradeDate", day);
        e.put("filledAt", day + "T05:00:00Z");
        e.put("symbol", symbol);
        e.put("side", side);
        e.put("product", product);
        e.put("quantity", qty);
        e.put("price", price);
        e.put("value", value / 100 + "." + String.format("%02d", value % 100));
        e.put("charges", Map.of("brokerage", "0.00", "stt", "0.00", "exchangeCharges", "0.00", "sebiFees", "0.00", "stampDuty", "0.00",
                "gst", "0.00", "total", charges));
        if (realised != null) {
            e.put("realisedPnl", realised);
        }
        e.put("autoSquareOff", false);
        return e;
    }

    ResultActions as(String path) throws Exception {
        return mvc.perform(get(path).header("X-User-Id", USER.toString()));
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void contractNotesListEveryDayAndShowEveryTradeAndCharge() throws Exception {
        JsonNode list = body(as("/v1/contract-notes").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("contractNotes");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("tradeDate").asText()).as("newest first").isEqualTo("2026-10-06");
        JsonNode note = body(as("/v1/contract-notes/2026-10-05").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(note.path("number").asText()).isEqualTo("CN-20261005-5D1F2B8E");
        assertThat(note.path("trades").size()).isEqualTo(3);
        assertThat(note.path("client").path("panMasked").asText()).isEqualTo("XXXXX1234K");
        assertThat(note.path("bought").asText()).isEqualTo("16000.00");      // 10,000 + 6,000
        assertThat(note.path("sold").asText()).isEqualTo("6100.00");
        assertThat(note.path("charges").path("total").asText()).isEqualTo("16.64");
        assertThat(note.path("net").asText()).isEqualTo("-9916.64");
        as("/v1/contract-notes/2026-10-04").andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theFundsStatementHasTheOpeningBalanceAndEveryMovement() throws Exception {
        JsonNode st = body(as("/v1/funds-statement?from=2026-10-05&to=2026-10-06").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(st.path("openingBalance").asText()).isEqualTo("50000.00");
        assertThat(st.path("lines").get(0).path("debit").asText()).isEqualTo("10005.94");
        assertThat(st.path("lines").get(1).path("credit").asText()).isEqualTo("4393.90");
        assertThat(st.path("closingBalance").asText()).isEqualTo("44387.96");
        as("/v1/funds-statement?from=2026-10-06&to=2026-10-05").andExpect(status().isBadRequest());
        as("/v1/funds-statement?from=2025-01-01&to=2026-10-05").andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
    }

    @Test
    void profitAndLossSplitsIntradayFromDeliveryAndShowsChargesBeside() throws Exception {
        JsonNode pnl = body(as("/v1/pnl?from=2026-04-01&to=2026-10-07").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(pnl.path("intraday").asText()).isEqualTo("100.00");
        assertThat(pnl.path("shortTerm").asText()).isEqualTo("400.00");      // 4 sold at 1100, bought at 1000
        assertThat(pnl.path("longTerm").asText()).isEqualTo("0.00");
        assertThat(pnl.path("total").asText()).isEqualTo("500.00");
        assertThat(pnl.path("charges").asText()).isEqualTo("22.74");
        assertThat(pnl.path("lines").size()).isEqualTo(2);
    }

    @Test
    void theHoldingsStatementIsTheDepositorysRecord() throws Exception {
        JsonNode h = body(as("/v1/holdings-statement").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(h.path("dematAccount").asText()).isEqualTo("1208160000000042");
        assertThat(h.path("holdings").get(0).path("quantity").asLong()).isEqualTo(6);
    }

    @Test
    void anUnreadableBookIsA503NeverAPartialStatementAndStrangersNeedAnAccount() throws Exception {
        LEDGER_DOWN.set(true);
        as("/v1/funds-statement?from=2026-10-05&to=2026-10-06").andExpect(status().isServiceUnavailable()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        mvc.perform(get("/v1/contract-notes").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
        mvc.perform(get("/v1/contract-notes")).andExpect(status().isUnauthorized());
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer books() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/accounts/", ex -> {
                String id = ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length());
                if (!id.equals(USER.toString())) {
                    reply(ex, 404, Map.of("code", "NO_ACCOUNT"));
                    return;
                }
                reply(ex, 200, Map.of("userId", id, "legalName", "Meera Iyer", "panMasked", "XXXXX1234K", "dematAccount", "1208160000000042",
                        "status", "ACTIVE", "openedAt", "2026-10-01T04:00:00Z"));
            });
            s.createContext("/internal/v1/executions", ex -> {
                Map<String, String> q = new LinkedHashMap<>();
                for (String kv : ex.getRequestURI().getQuery().split("&")) {
                    String[] p = kv.split("=", 2);
                    q.put(p[0], p[1]);
                }
                LocalDate from = LocalDate.parse(q.get("from"));
                LocalDate to = LocalDate.parse(q.get("to"));
                List<Map<String, Object>> in = EXECUTIONS.stream().filter(e -> {
                    LocalDate d = LocalDate.parse((String) e.get("tradeDate"));
                    return !d.isBefore(from) && !d.isAfter(to);
                }).toList();
                reply(ex, 200, Map.of("executions", in));
            });
            s.createContext("/v1/accounts/", ex -> {
                if (LEDGER_DOWN.get()) {
                    reply(ex, 503, Map.of());
                    return;
                }
                reply(ex, 200, Map.of("account", "customer:" + USER + ":cash", "from", "2026-10-05", "to", "2026-10-07",
                        "openingBalance", "50000.00", "closingBalance", "44387.96", "complete", true, "lines", List.of(
                                Map.of("entryId", UUID.randomUUID().toString(), "postedAt", "2026-10-05T05:00:00Z", "description", "Bought 10 HARBOR",
                                        "side", "DEBIT", "amount", "10005.94", "balanceAfter", "39994.06"),
                                Map.of("entryId", UUID.randomUUID().toString(), "postedAt", "2026-10-06T05:00:00Z", "description", "Sales settled",
                                        "side", "CREDIT", "amount", "4393.90", "balanceAfter", "44387.96"))));
            });
            // the market runs ahead of the wall clock here, as pre-prod's accelerated one does
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", "OPEN", "sessionDate", "2026-10-09")));
            s.createContext("/participant/v1/accounts/", ex -> reply(ex, 200, Map.of("boId", "1208160000000042",
                    "holdings", List.of(Map.of("symbol", "HARBOR", "quantity", 6)))));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
