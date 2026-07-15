# Contributing

**Maturity: `:implemented`** — `src/poultryops/` implements the reference
PoultryOpsAdvisor / PoultryFarmOperationsGovernor actor as a synchronous stub
(langgraph-clj StateGraph wiring deferred, see `operation.cljc`).
Contributions that extend coverage are welcome: langgraph-clj StateGraph
integration (real `interrupt-before`/checkpoint-based human-in-the-loop
resume for escalated operations), a Datomic/kotoba-server `Store` backend,
a real LLM `Advisor` implementation, additional Governor rules, and
breed/biosecurity reference-data expansion in `poultryops.facts`. Open an
issue or PR. License: AGPL-3.0-or-later.
