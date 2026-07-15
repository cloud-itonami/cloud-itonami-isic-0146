# Governance

Maintained by the cloud-itonami org (gftdcojp). Decisions land as ADRs in the
superproject ledger. The actor pattern (advisor-LLM sealed behind an
independent governor, append-only audit ledger) is non-negotiable per
ADR-2607011000: the governor gates every action; direct animal treatment,
culling/depopulation decisions, and any non-`:propose` effect are
permanently blocked; `:high`/`:safety-critical` actions (animal
health/biosecurity concerns such as suspected HPAI, high-cost supply
orders) require human sign-off.
