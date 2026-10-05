package app.sprout.statements.web;

import app.sprout.statements.domain.ApiException;
import app.sprout.statements.domain.ErrorCode;
import app.sprout.statements.domain.Reports;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The statements API (statements-v1.yaml). The gateway signs customers in and passes their id. */
@RestController
public class StatementsController {

    private final Reports reports;

    public StatementsController(Reports reports) {
        this.reports = reports;
    }

    @GetMapping("/v1/contract-notes")
    public Map<String, Object> contractNotes(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("contractNotes", reports.contractNotes(userId(user)));
    }

    @GetMapping("/v1/contract-notes/{tradeDate}")
    public Map<String, Object> contractNote(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable LocalDate tradeDate) {
        return reports.contractNote(userId(user), tradeDate);
    }

    @GetMapping("/v1/funds-statement")
    public Map<String, Object> fundsStatement(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestParam LocalDate from,
                                              @RequestParam LocalDate to) {
        return reports.fundsStatement(userId(user), from, to);
    }

    @GetMapping("/v1/pnl")
    public Map<String, Object> pnl(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestParam LocalDate from,
                                   @RequestParam LocalDate to) {
        return reports.pnl(userId(user), from, to);
    }

    @GetMapping("/v1/holdings-statement")
    public Map<String, Object> holdingsStatement(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return reports.holdingsStatement(userId(user));
    }

    static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
        }
    }
}
