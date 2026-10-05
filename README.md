# sprout-statements

A customer's records: what a broker must give them, and what they need at tax time. Read-only, built
each time from the books (the [order service](https://github.com/SaiNayakk/sprout-oms)'s executions,
the [ledger](https://github.com/SaiNayakk/sprout-ledger), [accounts](https://github.com/SaiNayakk/sprout-accounts)
and the [depository](https://github.com/SaiNayakk/sprout-depository)), so nothing is stored twice and
nothing can drift.

- **Contract notes**: for every day with trades, every execution and every charge, and what the day comes to.
- **Funds statement**: every movement of the customer's cash between two dates, with the opening balance and the balance after each line (the ledger computes them from the journal).
- **Profit and loss**: realised gains split as Indian tax treats them: intraday (speculative), short-term (held a year or less) and long-term. Delivery sales are matched to purchases first in, first out, across the customer's whole history; charges are shown beside.
- **Holdings statement**: what the depository holds in the customer's demat account: the official record.

A book that can't be read is a `503`: a statement is never partial. It has no database of its own.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`statements-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/statements-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests (FIFO tax lots on worked examples; the API against stand-ins for every
book, each response checked against the contract).

## License

MIT
