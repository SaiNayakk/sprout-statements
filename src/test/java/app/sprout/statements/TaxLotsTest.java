package app.sprout.statements;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.statements.domain.TaxLots;
import app.sprout.statements.domain.TaxLots.Category;
import app.sprout.statements.domain.TaxLots.Gain;
import app.sprout.statements.domain.TaxLots.Trade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Matching sales to purchases the way the income tax rules do, on small worked examples. */
class TaxLotsTest {

    static Trade buy(String date, long qty, long price) {
        return new Trade(LocalDate.parse(date), "HARBOR", true, false, qty, price, null);
    }

    static Trade sell(String date, long qty, long price) {
        return new Trade(LocalDate.parse(date), "HARBOR", false, false, qty, price, null);
    }

    static final LocalDate FY_START = LocalDate.parse("2026-04-01");
    static final LocalDate FY_END = LocalDate.parse("2027-03-31");

    @Test
    void salesUseTheOldestSharesFirstAndAYearDecidesLongOrShort() {
        List<Gain> gains = TaxLots.realised(List.of(
                buy("2025-06-01", 10, 1000_00),           // older than a year by the sale
                buy("2026-05-01", 10, 1200_00),
                sell("2026-08-01", 15, 1300_00)), FY_START, FY_END);
        assertThat(gains).hasSize(2);
        Gain first = gains.get(0);
        assertThat(first.category()).isEqualTo(Category.LONG_TERM);
        assertThat(first.quantity()).isEqualTo(10);
        assertThat(first.pnl()).isEqualTo(10 * 300_00);
        Gain second = gains.get(1);
        assertThat(second.category()).isEqualTo(Category.SHORT_TERM);
        assertThat(second.quantity()).isEqualTo(5);
        assertThat(second.buyDate()).isEqualTo(LocalDate.parse("2026-05-01"));
        assertThat(second.pnl()).isEqualTo(5 * 100_00);
    }

    @Test
    void exactlyAYearIsStillShortTerm() {
        List<Gain> gains = TaxLots.realised(List.of(buy("2025-08-01", 1, 100_00), sell("2026-08-01", 1, 90_00)), FY_START, FY_END);
        assertThat(gains.get(0).category()).isEqualTo(Category.SHORT_TERM);   // 365 days: "more than twelve months" it isn't
        assertThat(gains.get(0).pnl()).isEqualTo(-10_00);
    }

    @Test
    void onlySalesInThePeriodCountButEarlierOnesStillUseUpShares() {
        List<Gain> gains = TaxLots.realised(List.of(
                buy("2026-01-10", 10, 100_00),
                sell("2026-02-10", 6, 150_00),             // before the period: uses 6 of the first lot, not reported
                buy("2026-04-10", 10, 200_00),
                sell("2026-06-10", 6, 250_00)), FY_START, FY_END);
        assertThat(gains).hasSize(2);
        assertThat(gains.get(0).quantity()).isEqualTo(4);   // what's left of the first lot
        assertThat(gains.get(0).buyValue()).isEqualTo(4 * 100_00);
        assertThat(gains.get(1).quantity()).isEqualTo(2);
        assertThat(gains.get(1).buyValue()).isEqualTo(2 * 200_00);
    }

    @Test
    void intradayTradesAreTheirOwnLinesLongOrShort() {
        LocalDate d = LocalDate.parse("2026-09-01");
        List<Gain> gains = TaxLots.realised(List.of(
                new Trade(d, "KOSHA", true, true, 20, 300_00, null),         // opens a long: nothing realised
                new Trade(d, "KOSHA", false, true, 20, 310_00, 200_00L),    // closes it: +200
                new Trade(d, "INKWELL", false, true, 10, 200_00, null),     // opens a short
                new Trade(d, "INKWELL", true, true, 10, 205_00, -50_00L)),  // covers it: -50
                FY_START, FY_END);
        assertThat(gains).extracting(Gain::category).containsOnly(Category.INTRADAY);
        assertThat(gains.get(0).buyValue()).isEqualTo(20 * 300_00);
        assertThat(gains.get(0).sellValue()).isEqualTo(20 * 310_00);
        assertThat(gains.get(1).buyValue()).isEqualTo(10 * 205_00);
        assertThat(gains.get(1).sellValue()).isEqualTo(10 * 200_00);
        assertThat(gains.get(1).pnl()).isEqualTo(-50_00);
    }
}
