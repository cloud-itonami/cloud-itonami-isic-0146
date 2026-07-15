# cloud-itonami-isic-0146

Open Occupation Blueprint for **ISIC Rev. 4 0146**: Raising of poultry.

This repository implements a forkable OSS **poultry-farm operations
coordinator**: a facility-management and record-keeping robot manages flock
logging (including mortality and egg-production data), veterinary
appointment scheduling, and supply procurement under a governor-gated
actor, so a broiler or layer operation keeps its own operational records
and maintains full transparency over decisions.

**Maturity: `:implemented`.** `src/poultryops/` implements the
`PoultryOpsAdvisor` (`poultryops.advisor`) and the independent
`PoultryFarmOperationsGovernor` (`poultryops.governor`), composed by
`poultryops.operation` following the itonami actor pattern
(ADR-2607011000): `advise -> govern -> phase-gate -> commit | escalate |
hold`. See `clojure -M:test` output for the current test/assertion
count.

`poultryops.operation` is a synchronous stub of this flow (see its
docstring) — production wiring into a `langgraph-clj` StateGraph with
`interrupt-before`/checkpoint-based human-in-the-loop resume for escalated
operations is deferred, mirroring `cloud-itonami-isic-0141`'s own
`cattleops.operation` and `cloud-itonami-isic-0145`'s own
`swineops.operation`.

## What this does NOT do

This actor coordinates **back-office logistics only**. It explicitly does **NOT**:

- **Direct animal handling** — remains the farm operator's exclusive authority
- **Veterinary treatment decisions** — remains the veterinarian/farm operator authority
- **Culling/depopulation decisions** — economic and ethical authority remains human
- **Direct treatment administration** — any proposal for direct treatment is a hard block
- **Outbreak declarations / animal-health authority contact** — a flagged
  biosecurity concern (e.g. suspected HPAI, Highly Pathogenic Avian
  Influenza) is surfaced for human/veterinary judgment only; this actor
  never itself declares an outbreak or notifies authorities

## HARD invariants (always hold, never overridable)

1. **facility-not-registered** — the request's `facility-id` must resolve to a
   registered facility (barn/house complex) in the Store before any proposal
   can proceed
2. **no-execution** — every proposal's `:effect` must be `:propose` (the governor
   never directly handles animals, never administers treatment, never orders
   culling)
3. **treatment-or-culling-blocked** — `:administer-treatment` and
   `:order-culling` proposals are unconditionally, permanently blocked
4. **op-not-allowed** — any op outside the closed allowlist below is rejected
5. **flock-count-invalid** — `:log-flock-record` with a non-positive count is rejected

## Always-escalate operations (human sign-off, regardless of confidence)

- `:flag-animal-health-concern` — any welfare or biosecurity concern (e.g.
  suspected Highly Pathogenic Avian Influenza / HPAI) → automatic escalation
- `:order-supplies` over its category cost threshold (default 500 currency
  units; see `poultryops.facts/supply-categories`)
- Any proposal with confidence below the Governor's floor (0.7)

## Operational requests (closed allowlist, all `:effect :propose`)

```text
:log-flock-record
  — record flock count, weight, health status, mortality, and
    egg-production data
  — requires a registered facility; non-positive counts are rejected

:schedule-veterinary-visit
  — propose a veterinary appointment
  — does NOT make treatment decisions

:flag-animal-health-concern
  — surface a disease, injury, or biosecurity concern (e.g. suspected HPAI)
  — ALWAYS escalates for human review

:order-supplies
  — procurement for feed, veterinary supplies, biosecurity equipment
  — escalates if cost exceeds its category threshold
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs the
physical domain work**. Here a facility-management robot handles:

- Flock record logging and entry (including mortality and egg-production data)
- Appointment scheduling and reminders
- Supply inventory and ordering
- Audit ledger maintenance

The **PoultryFarmOperationsGovernor** is the independent safety layer that gates all
proposals before a robot action is executed. The governor never dispatches hardware
directly; `:high`/`:safety-critical` actions (such as escalated health/biosecurity
concerns or high-cost supply orders) require human sign-off.

## Core Contract

```text
operational request (log, schedule, concern, order)
        |
        v
PoultryOpsAdvisor -> PoultryFarmOperationsGovernor -> phase gate -> commit, or escalate for human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated operation can dispatch a robot action the governor refuses, suppress an
operating record, or hide a health/biosecurity concern without governor approval and
audit evidence.

## Module structure

Mirrors `cloud-itonami-isic-0145` (`swineops.*`) module-for-module:

- `poultryops.facts` — reference data: supply-category cost thresholds, breeds,
  biosecurity/notifiable-disease vocabulary
- `poultryops.registry` — pure independent verification functions (cost/count/confidence)
- `poultryops.store` — `Store` protocol + in-memory `MemStore` (facility registration lookup)
- `poultryops.advisor` — `Advisor` protocol + `MockAdvisor` (the sealed LLM/decision node)
- `poultryops.governor` — `PoultryFarmOperationsGovernor`: hard invariants + escalation gates
- `poultryops.phase` — 0→3 rollout phase gate
- `poultryops.operation` — composes advisor → governor → phase into one operation run
- `poultryops.sim` — demo runner (`clojure -M:run`)

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISIC Rev. 4 `0146`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Testing

```bash
clojure -M:test   # see output for current test/assertion count
clojure -M:lint   # clj-kondo, 0 errors / 0 warnings
clojure -M:run    # demo runner
```

## License

AGPL-3.0-or-later.
