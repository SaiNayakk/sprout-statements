package app.sprout.statements.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Realised gains as Indian tax treats them. Delivery sales are matched to purchases first in, first
 * out, per share; a sale of shares held more than a year is long-term, otherwise short-term. Intraday
 * trades are speculative: each closing trade's profit or loss is its own line. Values are in paise.
 */
public final class TaxLots {

    public enum Category { INTRADAY, SHORT_TERM, LONG_TERM }

    /** One executed trade, oldest first. {@code realised} is set on intraday trades that closed a position. */
    public record Trade(LocalDate date, String symbol, boolean buy, boolean intraday, long quantity, long pricePaise, Long realised) {}

    public record Gain(String symbol, Category category, long quantity, LocalDate buyDate, LocalDate sellDate, long buyValue, long sellValue) {
        public long pnl() {
            return sellValue - buyValue;
        }
    }

    private record Lot(LocalDate date, long quantity, long pricePaise) {}

    private TaxLots() {}

    /** Every gain realised by sales from {@code from} to {@code to} (inclusive), given all trades up to {@code to}. */
    public static List<Gain> realised(List<Trade> trades, LocalDate from, LocalDate to) {
        Map<String, Deque<Lot>> lots = new HashMap<>();
        List<Gain> gains = new ArrayList<>();
        for (Trade t : trades) {
            boolean inRange = !t.date().isBefore(from) && !t.date().isAfter(to);
            if (t.intraday()) {
                if (t.realised() != null && inRange) {
                    long value = t.pricePaise() * t.quantity();
                    // a closing sell's buy side cost value - profit; a closing buy (covering a short) sold for value + profit
                    long buyValue = t.buy() ? value : value - t.realised();
                    long sellValue = t.buy() ? value + t.realised() : value;
                    gains.add(new Gain(t.symbol(), Category.INTRADAY, t.quantity(), t.date(), t.date(), buyValue, sellValue));
                }
                continue;
            }
            Deque<Lot> held = lots.computeIfAbsent(t.symbol(), k -> new ArrayDeque<>());
            if (t.buy()) {
                held.addLast(new Lot(t.date(), t.quantity(), t.pricePaise()));
                continue;
            }
            long left = t.quantity();
            while (left > 0 && !held.isEmpty()) {
                Lot lot = held.pollFirst();
                long q = Math.min(left, lot.quantity());
                if (q < lot.quantity()) {
                    held.addFirst(new Lot(lot.date(), lot.quantity() - q, lot.pricePaise()));
                }
                left -= q;
                if (inRange) {
                    Category c = ChronoUnit.DAYS.between(lot.date(), t.date()) > 365 ? Category.LONG_TERM : Category.SHORT_TERM;
                    gains.add(new Gain(t.symbol(), c, q, lot.date(), t.date(), lot.pricePaise() * q, t.pricePaise() * q));
                }
            }
            // a sale with nothing left to match (shouldn't happen: Sprout never lets clients sell what they don't hold) has no cost basis
            if (left > 0 && inRange) {
                gains.add(new Gain(t.symbol(), Category.SHORT_TERM, left, t.date(), t.date(), 0, t.pricePaise() * left));
            }
        }
        return gains;
    }
}
