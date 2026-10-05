package app.sprout.statements.domain;

import app.sprout.statements.config.StatementsProperties;
import app.sprout.statements.domain.TaxLots.Category;
import app.sprout.statements.domain.TaxLots.Gain;
import app.sprout.statements.domain.TaxLots.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A customer's records, built from the books each time they're asked for. Shapes as statements-v1.yaml. */
@Service
public class Reports {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String[] CHARGES = {"brokerage", "stt", "exchangeCharges", "sebiFees", "stampDuty", "gst", "total"};

    private final Books books;
    private final Clock clock;
    private final StatementsProperties props;

    public Reports(Books books, Clock clock, StatementsProperties props) {
        this.books = books;
        this.clock = clock;
        this.props = props;
    }

    // ── contract notes ───────────────────────────────────────────────────────

    public List<Map<String, Object>> contractNotes(UUID user) {
        books.account(user);
        LocalDate today = books.session();
        Map<LocalDate, List<JsonNode>> byDay = new TreeMap<>((a, b) -> b.compareTo(a));
        for (JsonNode e : books.executions(user, today.minusYears(1), today)) {
            byDay.computeIfAbsent(LocalDate.parse(e.path("tradeDate").asText()), d -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var day : byDay.entrySet()) {
            if (out.size() == 100) {
                break;
            }
            long[] totals = totals(day.getValue());
            out.add(Map.of("tradeDate", day.getKey().toString(), "number", number(user, day.getKey()), "trades", day.getValue().size(),
                    "net", Money.rupees(totals[1] - totals[0] - totals[2])));
        }
        return out;
    }

    public Map<String, Object> contractNote(UUID user, LocalDate day) {
        JsonNode account = books.account(user);
        List<JsonNode> executions = new ArrayList<>();
        books.executions(user, day, day).forEach(executions::add);
        if (executions.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "You didn't trade on " + day + ".");
        }
        Map<String, Long> charges = new LinkedHashMap<>();
        List<Map<String, Object>> trades = new ArrayList<>();
        for (JsonNode e : executions) {
            for (String c : CHARGES) {
                charges.merge(c, Money.paise(e.path("charges").path(c).asText()), Long::sum);
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("orderId", e.path("orderId").asText());
            if (e.hasNonNull("tradeId")) {
                t.put("tradeId", e.path("tradeId").asText());
            }
            t.put("executedAt", e.path("filledAt").asText());
            t.put("symbol", e.path("symbol").asText());
            t.put("side", e.path("side").asText());
            t.put("product", e.path("product").asText());
            t.put("quantity", e.path("quantity").asLong());
            t.put("price", e.path("price").asText());
            t.put("value", e.path("value").asText());
            t.put("charges", e.path("charges").path("total").asText());
            trades.add(t);
        }
        long[] totals = totals(executions);
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", account.path("legalName").asText());
        client.put("clientCode", user.toString());
        client.put("panMasked", account.path("panMasked").asText());
        if (account.hasNonNull("dematAccount")) {
            client.put("dematAccount", account.path("dematAccount").asText());
        }
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("number", number(user, day));
        note.put("tradeDate", day.toString());
        note.put("broker", Map.of("name", props.brokerName(), "registration", props.brokerRegistration()));
        note.put("client", client);
        note.put("trades", trades);
        Map<String, Object> chargesOut = new LinkedHashMap<>();
        charges.forEach((k, v) -> chargesOut.put(k, Money.rupees(v)));
        note.put("charges", chargesOut);
        note.put("bought", Money.rupees(totals[0]));
        note.put("sold", Money.rupees(totals[1]));
        note.put("net", Money.rupees(totals[1] - totals[0] - totals[2]));
        return note;
    }

    /** [bought, sold, charges] in paise. */
    private static long[] totals(List<JsonNode> executions) {
        long[] t = new long[3];
        for (JsonNode e : executions) {
            long value = Money.paise(e.path("value").asText());
            t[e.path("side").asText().equals("BUY") ? 0 : 1] += value;
            t[2] += Money.paise(e.path("charges").path("total").asText());
        }
        return t;
    }

    static String number(UUID user, LocalDate day) {
        return "CN-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + user.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ── funds statement ──────────────────────────────────────────────────────

    public Map<String, Object> fundsStatement(UUID user, LocalDate from, LocalDate to) {
        checkRange(from, to);
        books.account(user);
        JsonNode st = books.ledgerStatement("customer:" + user + ":cash", from, to.plusDays(1));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : st.path("lines")) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", l.path("postedAt").asText());
            line.put("description", l.path("description").asText());
            // cash is money Sprout owes the customer: a credit adds to it, a debit takes from it
            line.put(l.path("side").asText().equals("CREDIT") ? "credit" : "debit", l.path("amount").asText());
            line.put("balance", l.path("balanceAfter").asText());
            lines.add(line);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("openingBalance", st.path("openingBalance").asText());
        m.put("closingBalance", st.path("closingBalance").asText());
        m.put("lines", lines);
        return m;
    }

    // ── profit and loss ──────────────────────────────────────────────────────

    public Map<String, Object> pnl(UUID user, LocalDate from, LocalDate to) {
        checkRange(from, to);
        JsonNode account = books.account(user);
        // every trade since the account opened, so sales in the period are matched to purchases from any time before
        LocalDate opened = account.hasNonNull("openedAt")
                ? java.time.OffsetDateTime.parse(account.path("openedAt").asText()).atZoneSameInstant(IST).toLocalDate() : from.minusYears(5);
        List<Trade> trades = new ArrayList<>();
        long charges = 0;
        for (LocalDate start = opened.isAfter(to) ? to : opened; !start.isAfter(to); start = start.plusYears(1).plusDays(1)) {
            LocalDate end = start.plusYears(1).isAfter(to) ? to : start.plusYears(1);
            for (JsonNode e : books.executions(user, start, end)) {
                LocalDate d = LocalDate.parse(e.path("tradeDate").asText());
                trades.add(new Trade(d, e.path("symbol").asText(), e.path("side").asText().equals("BUY"), e.path("product").asText().equals("MIS"),
                        e.path("quantity").asLong(), Money.paise(e.path("price").asText()),
                        e.hasNonNull("realisedPnl") ? Money.paise(e.path("realisedPnl").asText()) : null));
                if (!d.isBefore(from)) {
                    charges += Money.paise(e.path("charges").path("total").asText());
                }
            }
        }
        List<Gain> gains = TaxLots.realised(trades, from, to);
        Map<Category, Long> byCategory = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            byCategory.put(c, 0L);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Gain g : gains) {
            byCategory.merge(g.category(), g.pnl(), Long::sum);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("symbol", g.symbol());
            line.put("category", g.category().name());
            line.put("quantity", g.quantity());
            line.put("buyDate", g.buyDate().toString());
            line.put("sellDate", g.sellDate().toString());
            line.put("buyValue", Money.rupees(g.buyValue()));
            line.put("sellValue", Money.rupees(g.sellValue()));
            line.put("pnl", Money.rupees(g.pnl()));
            lines.add(line);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("intraday", Money.rupees(byCategory.get(Category.INTRADAY)));
        m.put("shortTerm", Money.rupees(byCategory.get(Category.SHORT_TERM)));
        m.put("longTerm", Money.rupees(byCategory.get(Category.LONG_TERM)));
        m.put("total", Money.rupees(byCategory.values().stream().mapToLong(Long::longValue).sum()));
        m.put("charges", Money.rupees(charges));
        m.put("lines", lines);
        return m;
    }

    // ── holdings statement ───────────────────────────────────────────────────

    public Map<String, Object> holdingsStatement(UUID user) {
        JsonNode account = books.account(user);
        if (!account.hasNonNull("dematAccount")) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Your demat account isn't open yet.");
        }
        String bo = account.path("dematAccount").asText();
        List<Map<String, Object>> holdings = new ArrayList<>();
        for (JsonNode h : books.dematHoldings(bo)) {
            holdings.add(Map.of("symbol", h.path("symbol").asText(), "quantity", h.path("quantity").asLong()));
        }
        return Map.of("dematAccount", bo, "asOf", clock.instant().toString(), "holdings", holdings);
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from must be on or before to.");
        }
        if (from.plusYears(1).isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "At most a year at a time.");
        }
    }
}
