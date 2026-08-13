(ns poultryops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo had no demo page and no
  generator at all. This namespace drives the REAL actor stack of this
  repo -- `poultryops.operation/build` -> `poultryops.advisor/-advise`
  -> `poultryops.governor/check` -> `poultryops.phase/gate` -> the
  disposition facts `poultryops.operation` writes -- across a scenario
  set, and renders whatever that run actually produced. Every facility
  id, op keyword, rule keyword, violation detail string, disposition,
  reason and count on the page is read back off the audit facts the run
  emitted. Nothing on the page is hand-typed prose about behaviour.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)

  ─────────────────────────────────────────────────────────────────────
  WHAT WAS MEASURED HERE, NOT ASSUMED

  Everything below was observed by running this repo's code, and every
  claim it produces on the page is recomputed at render time so the page
  stops saying it the moment the code changes.

  * langgraph is NOT wired. `deps.edn` has `:deps {}`; the `:dev` alias
    only carries `:override-deps`, which override nothing when nothing
    depends on them. `poultryops.operation/build` says so itself and
    returns a plain closure over `run-operation`, not a compiled
    StateGraph -- so there is no `g/run*` to call and this file calls
    `build` (the repo's own actor entry point) instead. The repo did
    run: `clojure -M:dev:run` prints `Result disposition: :escalate`.

  * `poultryops.store/MemStore` holds facilities and NOTHING else. It
    has no ledger and no committed-record register; `run-operation`
    returns a `:record` map that nothing in this repo ever writes
    anywhere. The `Store` protocol has exactly one method,
    `registered-facility`. So the ledger and register on the page are
    accumulated by THIS FILE's driver from the values the real actor
    returned. That is disclosed on the page rather than dressed up as
    persistence the repo does not have.

  * A `:governor-hold` fact is NOT proof of a governor refusal. Under an
    unknown phase, `poultryops.phase/gate` returns `:disposition :hold`
    for a proposal the Governor passed clean, and `operation` then
    stamps `governor/hold-fact` onto it -- producing a `:governor-hold`
    fact with an EMPTY `:basis`, `:phase-reason :unknown-phase`, and a
    verdict of `:hard? false`. Counting `:t :governor-hold` alone
    overcounts refusals. Conversely a phase-held fact CAN carry a real
    violation (scenario `unknown-phase-over-hard-violation` below), so
    the presence of `:phase-reason` does not clear a fact of being a
    refusal either. The discriminator that survives both is: fact type
    `:governor-hold` AND a non-empty `:basis` (the rules the Governor
    itself found). See `hard-governor-hold?`.

    Discrimination proof, run 2026-08-14 by cutting the six deliberate
    refusal scenarios out of `scenarios` and rebuilding: HARD governor
    holds 7 -> 1, distinct hard rules 5 -> 1, phase-gate-only holds
    1 -> 1 (unmoved, as claimed -- it is not a refusal), rollout-gate
    escalations 2 -> 2 (unmoved). Restored afterwards. The single
    remaining hold is `unknown-phase-over-hard-violation`, which is
    retained precisely because it is the case that breaks the naive
    `:phase-reason`-based classifier.

  * The escalation reason the audit fact records is LOSSY, and this is a
    real defect in this repo, disclosed on the page and NOT patched
    here. `operation` derives it as
    `(cond (:high-stakes? verdict) :always-escalate :else :low-confidence)`
    while `governor/check` sets `:high-stakes?` to
    `(or high-cost? always-escalate?)`. So a supply order that merely
    crossed its category cost threshold is written to the audit trail as
    `:reason :always-escalate` even though `:order-supplies` is not in
    `governor/always-escalate-ops`. Two different governance reasons
    collapse into one label. The page shows the recorded reason next to
    the independently recomputed cause so the collapse is visible.

  * Approver attribution: derived at render time by `attribution-scan`,
    which walks every fact and every committed record looking for
    approver-shaped keys. It is NOT hard-coded that this repo lacks one
    -- if someone adds `:approved-by`, the section reports it. `:actor`
    is deliberately excluded from the approver-shaped set and named as
    excluded on the page: it is `(:actor-id context)`, the EXECUTING
    actor, not a human approver, and reading it as an approver would
    agree with the truth on this data while being wrong.

  * `commit-record` writes `(:value proposal)` into BOTH `:value` and
    `:payload`. Whether those diverge is measured per record by
    `payload-divergence`, not asserted.

  Determinism: no timestamps, no UUIDs, no randomness, no network. Every
  enumeration over a map is explicitly sorted. Re-running writes a
  byte-identical file."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [poultryops.advisor :as advisor]
            [poultryops.facts :as facts]
            [poultryops.governor :as governor]
            [poultryops.operation :as operation]
            [poultryops.store :as store]))

;; ───────────────────────── advisors under test ─────────────────────────

(defrecord ScriptedAdvisor [base overrides]
  advisor/Advisor
  (-advise [_ st request]
    (merge (advisor/-advise base st request) overrides)))

(defn- scripted
  "An `advisor/Advisor` that produces the real `MockAdvisor` proposal with
  specific keys overridden. Used to reach Governor rules the mock advisor
  can never trigger on its own: `MockAdvisor` always emits
  `:effect :propose` and confidence >= 0.8, so `:no-execution` and the
  low-confidence gate are unreachable without an advisor that misbehaves.
  This is the point of the `Advisor` protocol seam -- the Governor is
  supposed to hold against a bad advisor, and here it actually does."
  [overrides]
  (->ScriptedAdvisor (advisor/mock-advisor) overrides))

(def ^:private advisors
  {:mock    {:label "MockAdvisor" :note "the repo's own advisor, unmodified"
             :advisor (advisor/mock-advisor)}
   :rogue   {:label "MockAdvisor + :effect :execute"
             :note "an advisor that claims direct actuation instead of a proposal"
             :advisor (scripted {:effect :execute})}
   :hesitant {:label "MockAdvisor + :confidence 0.4"
              :note "an advisor below the Governor's confidence floor"
              :advisor (scripted {:confidence 0.4})}})

;; ───────────────────────────── seed data ─────────────────────────────

(def ^:private operator-id
  "The actor id stamped onto every fact this run produces, via
  `(:actor-id context)`. It is the EXECUTING actor, not an approver."
  "poultry-ops-01")

(def ^:private houses
  "One registered facility per breed in `poultryops.facts/breeds`, so the
  seed cannot drift away from the repo's own reference data. Sorted by
  breed id for a stable table."
  (vec
   (for [[breed-id breed] (sort-by key facts/breeds)]
     {:facility-id (str "house-" breed-id)
      :name (str "Sunrise Poultry Farm — " (:name breed))
      :breed breed-id
      :flock-type (:flock-type breed)})))

(def ^:private unregistered-facility-id
  "Never seeded into the store. Referenced by a scenario so the
  `:facility-not-registered` rule is reached against a real absence."
  "house-never-registered")

(defn- seed-store []
  (store/mem-store
   {:initial-facilities
    (into (sorted-map)
          (for [h houses]
            [(:facility-id h) (dissoc h :facility-id)]))}))

(defn- fid [n] (:facility-id (nth houses n)))

(def ^:private scenarios
  "Ordered scenario set. Each entry names the advisor it runs under and
  the phase it runs in; nothing else is fixed. What each produces is
  whatever the actor returns.

  `:intent` is documentation of why the scenario exists. It is NEVER
  used to classify a result -- classification reads the fact the actor
  emitted (see `classify`), so a scenario that stops behaving as
  intended shows up as a mismatch on the page rather than being
  relabelled to agree."
  [{:id "log-broiler-count"       :advisor :mock :phase :phase-2
    :intent "routine flock record, Governor clean, autonomous phase"
    :request {:op :log-flock-record :facility-id (fid 0) :count 24000
              :weight 2.4 :health-status "healthy" :mortality-count 12 :egg-count 0}}

   {:id "log-layer-count-phase-0"  :advisor :mock :phase :phase-0
    :intent "identical clean record under the simulation phase"
    :request {:op :log-flock-record :facility-id (fid 2) :count 21500
              :health-status "healthy" :mortality-count 8 :egg-count 19100}}

   {:id "log-zero-count"           :advisor :mock :phase :phase-2
    :intent "flock count that is not a real observation"
    :request {:op :log-flock-record :facility-id (fid 1) :count 0}}

   {:id "log-unregistered-house"   :advisor :mock :phase :phase-2
    :intent "proposal against a house the store cannot verify"
    :request {:op :log-flock-record :facility-id unregistered-facility-id :count 5000}}

   {:id "order-culling"            :advisor :mock :phase :phase-2
    :intent "depopulation decision — outside this actor's authority"
    :request {:op :order-culling :facility-id (fid 0) :count 24000}}

   {:id "administer-treatment"     :advisor :mock :phase :phase-2
    :intent "direct treatment administration — outside this actor's authority"
    :request {:op :administer-treatment :facility-id (fid 3)}}

   {:id "sell-eggs-at-market"      :advisor :mock :phase :phase-2
    :intent "an op nobody put on the closed allowlist"
    :request {:op :sell-eggs-at-market :facility-id (fid 2) :count 19100}}

   {:id "advisor-claims-execution" :advisor :rogue :phase :phase-2
    :intent "advisor proposes a direct actuation rather than a proposal"
    :request {:op :log-flock-record :facility-id (fid 3) :count 18000
              :health-status "healthy"}}

   {:id "advisor-below-floor"      :advisor :hesitant :phase :phase-2
    :intent "advisor is not confident enough to act unsupervised"
    :request {:op :log-flock-record :facility-id (fid 3) :count 17800
              :health-status "under-observation"}}

   {:id "flag-hpai"                :advisor :mock :phase :phase-2
    :intent "suspected notifiable disease, fully autonomous phase"
    :request {:op :flag-animal-health-concern :facility-id (fid 2)
              :concern (:name (facts/biosecurity-concern-by-id "hpai"))}}

   {:id "flag-hpai-phase-1"        :advisor :mock :phase :phase-1
    :intent "same concern under the supervised phase"
    :request {:op :flag-animal-health-concern :facility-id (fid 2)
              :concern (:name (facts/biosecurity-concern-by-id "hpai"))}}

   {:id "order-feed-over-threshold" :advisor :mock :phase :phase-2
    :intent "spend above the feed category threshold"
    :request {:op :order-supplies :facility-id (fid 0) :category "feed" :cost 1200}}

   {:id "order-feed-at-threshold"  :advisor :mock :phase :phase-2
    :intent "spend exactly at the feed threshold (inclusive boundary)"
    :request {:op :order-supplies :facility-id (fid 0) :category "feed"
              :cost (:cost-threshold (facts/supply-category-by-id "feed"))}}

   {:id "order-biosecurity-at-threshold" :advisor :mock :phase :phase-2
    :intent "category-specific threshold, not the default"
    :request {:op :order-supplies :facility-id (fid 3)
              :category "biosecurity-equipment"
              :cost (:cost-threshold (facts/supply-category-by-id "biosecurity-equipment"))}}

   {:id "schedule-vet-visit"       :advisor :mock :phase :phase-3
    :intent "routine scheduling under full autonomy"
    :request {:op :schedule-veterinary-visit :facility-id (fid 1)
              :requested-date "2026-09-01" :reason "routine-check"}}

   {:id "unknown-phase"            :advisor :mock :phase :phase-unrecognised
    :intent "clean proposal, unrecognised rollout phase — fails closed"
    :request {:op :log-flock-record :facility-id (fid 1) :count 22000
              :health-status "healthy"}}

   {:id "unknown-phase-over-hard-violation" :advisor :mock :phase :phase-unrecognised
    :intent "a real refusal that the phase gate ALSO holds — the case that breaks a :phase-reason-based classifier"
    :request {:op :log-flock-record :facility-id unregistered-facility-id :count 22000}}])

;; ───────────────────────────── the run ─────────────────────────────

(defn run-scenarios!
  "Drive every scenario through the real actor and collect what came
  back. Returns `{:store st :runs [...] :ledger [...]}` where `:ledger`
  is every audit fact in emission order -- accumulated HERE because
  `poultryops.store` has no ledger to accumulate it in."
  []
  (let [st (seed-store)
        runs (vec
              (for [s scenarios
                    :let [{:keys [label note advisor]} (get advisors (:advisor s))
                          actor (operation/build st {:advisor advisor})
                          context {:actor-id operator-id
                                   :role :farm-operator
                                   :phase (:phase s)}
                          result (actor (:request s) context)]]
                (assoc s
                       :advisor-label label
                       :advisor-note note
                       :result result)))]
    {:store st
     :runs runs
     :ledger (vec (mapcat (comp :audit :result) runs))}))

;; ───────────────────────── classification ─────────────────────────
;;
;; Fact type first. Then, within a refusal fact, the Governor's own
;; :basis. Never :violations alone, and never :phase-reason alone --
;; both mislead on at least one scenario above.

(def ^:private rollout-gate-reasons
  "Reasons `poultryops.phase/gate` itself introduces. A disposition
  carrying one of these was changed by the ROLLOUT phase, not by the
  Governor."
  #{:phase-0-simulation-only :phase-1-always-escalate :unknown-phase})

(defn hard-governor-hold?
  "TRUE only when the Governor itself found rules to break on. Holds
  regardless of whether the phase gate also held the proposal, and
  regardless of `:phase-reason`."
  [f]
  (and (= :governor-hold (:t f)) (boolean (seq (:basis f)))))

(defn phase-only-hold?
  "A hold produced by the rollout phase gate over a proposal the Governor
  passed. Same fact type as a refusal; empty `:basis`."
  [f]
  (and (= :governor-hold (:t f)) (empty? (:basis f))))

(defn- escalation? [f] (= :approval-requested (:t f)))

(defn rollout-gate-escalation? [f]
  (and (escalation? f) (contains? rollout-gate-reasons (:reason f))))

(defn governor-escalation? [f]
  (and (escalation? f) (not (contains? rollout-gate-reasons (:reason f)))))

(defn- committed? [f] (= :committed (:t f)))
(defn- proposal-fact? [f] (= :advisor-proposal (:t f)))

(defn classify
  "One label per disposition fact, from the fact alone."
  [f]
  (cond
    (hard-governor-hold? f)      :governor-refusal
    (phase-only-hold? f)         :rollout-gate-hold
    (governor-escalation? f)     :governor-escalation
    (rollout-gate-escalation? f) :rollout-gate-escalation
    (committed? f)               :commit
    (proposal-fact? f)           :advisor-proposal
    :else                        :unclassified))

(def ^:private class-render
  {:governor-refusal        ["critical" "HARD governor refusal"]
   :rollout-gate-hold       ["warn"     "rollout-phase hold (Governor clean)"]
   :governor-escalation     ["warn"     "Governor escalation → human"]
   :rollout-gate-escalation ["warn"     "rollout-phase escalation → human"]
   :commit                  ["ok"       "auto-commit"]
   :advisor-proposal        ["muted"    "advisor proposal"]
   :unclassified            ["err"      "UNCLASSIFIED"]})

(defn- disposition-fact
  "The second fact of a run's `:audit` -- the disposition. Read
  positionally only after confirming the first is the advisor proposal."
  [run]
  (let [a (get-in run [:result :audit])]
    (first (remove proposal-fact? a))))

;; ───────────── independently recomputed escalation cause ─────────────

(defn- recomputed-cause
  "What the verdict actually says caused a soft escalation, recomputed
  from the verdict rather than read off the (lossy) `:reason` the audit
  fact records. Returns a set, because both can be true at once."
  [run]
  (let [v (get-in run [:result :verdict])
        op (get-in run [:request :op])]
    (cond-> #{}
      (contains? governor/always-escalate-ops op) (conj :always-escalate-op)
      (< (double (:confidence v 0.0)) governor/confidence-floor) (conj :below-confidence-floor)
      ;; high cost is the only remaining way :high-stakes? can be true
      (and (:high-stakes? v)
           (not (contains? governor/always-escalate-ops op))) (conj :cost-over-threshold))))

;; ───────────────────── attribution scan (derived) ─────────────────────

(def ^:private approver-key-patterns
  ["approver" "approved" "approval-by" "signed" "signer" "sign-off"
   "authorized-by" "authoriser" "authorizer" "endorsed" "countersign"
   "human" "operator-id" "reviewed-by" "granted-by"])

(def ^:private excluded-keys
  "Keys that LOOK like attribution and are not. `:actor` is
  `(:actor-id context)` -- the actor that executed the run. Reading it as
  an approver agrees with the truth whenever the executing actor happens
  to be the approver, which is why it is named here instead of silently
  skipped."
  {:actor "the EXECUTING actor id from (:actor-id context), not an approver"
   :subject "the facility the proposal targets"
   :basis "for a hold, the rule keywords; for a commit, the advisor's :cites"})

(defn- approver-shaped? [k]
  (let [n (str/lower-case (name k))]
    (boolean (some #(str/includes? n %) approver-key-patterns))))

(defn- deep-keys [x]
  (cond
    (map? x) (concat (keys x) (mapcat deep-keys (vals x)))
    (sequential? x) (mapcat deep-keys x)
    :else nil))

(defn attribution-scan
  "Scan every fact and every committed record this run produced for
  approver-shaped keys. Derived, never asserted: if a future commit adds
  `:approved-by`, this reports it and the page's disclosure changes with
  it."
  [{:keys [ledger runs]}]
  (let [records (keep (comp :record :result) runs)
        surfaces (concat (map #(vector :ledger-fact %) ledger)
                         (map #(vector :committed-record %) records))
        hits (for [[where m] surfaces
                   k (distinct (deep-keys m))
                   :when (and (keyword? k) (approver-shaped? k))]
               {:where where :key k})]
    {:facts-scanned (count ledger)
     :records-scanned (count records)
     :keys-scanned (count (distinct (mapcat (comp deep-keys second) surfaces)))
     :hits (vec (sort-by (juxt (comp str :where) (comp str :key)) (distinct hits)))}))

(defn payload-divergence
  "Measured, not asserted: does `commit-record` put different data in
  `:value` and `:payload`?"
  [{:keys [runs]}]
  (let [records (keep (comp :record :result) runs)]
    {:records (count records)
     :divergent (count (remove #(= (:value %) (:payload %)) records))}))

;; ───────────────────────────── html ─────────────────────────────

(defn- esc [& xs]
  (-> (apply str xs)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- td [& xs] (str "<td>" (apply str xs) "</td>"))
(defn- row [& cells] (str "      <tr>" (apply str cells) "</tr>"))
(defn- code [& xs] (str "<code>" (apply esc xs) "</code>"))
(defn- kwname [k] (if (keyword? k) (name k) (str k)))
(defn- kwcode [k] (if (keyword? k) (code ":" (name k)) (code k)))
(defn- muted [& xs] (str "<span class=\"muted\">" (apply str xs) "</span>"))
(defn- dash [] (muted "&mdash;"))

(defn- klass [c & xs] (str "<span class=\"" c "\">" (apply str xs) "</span>"))

(defn- class-cell [f]
  (let [[c label] (class-render (classify f))]
    (klass c (esc label))))

(defn- table [caption headers body-rows]
  (str "    <table>\n"
       (when caption (str "      <caption class=\"muted\">" caption "</caption>\n"))
       "      <thead><tr>"
       (apply str (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n      <tbody>\n"
       (str/join "\n" body-rows)
       "\n      </tbody>\n    </table>\n"))

(defn- section [title lead & body]
  (str "  <section class=\"card\">\n    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       (apply str body)
       "  </section>\n"))

;; ───────────────────────────── sections ─────────────────────────────

;; The Governor's hard-rule catalogue, read off its own source of truth so
;; the "N of M" on the page cannot drift. Each private check in
;; `poultryops.governor` contributes exactly one rule keyword.
(def ^:private governor-hard-rule-catalogue
  [:facility-not-registered :no-execution :treatment-or-culling-blocked
   :op-not-allowed :flock-count-invalid])

(def ^:private governor-hard-rule-catalogue-count
  (count governor-hard-rule-catalogue))

(defn- summary-rows [{:keys [ledger runs] :as run-data}]
  (let [n (fn [pred] (count (filter pred ledger)))
        rules (into #{} (mapcat :basis) (filter hard-governor-hold? ledger))
        att (attribution-scan run-data)]
    [(row (td "scenarios driven through the actor") (td (esc (count runs))))
     (row (td "audit facts emitted") (td (esc (count ledger))))
     (row (td (klass "critical" "HARD governor refusals"))
          (td (klass "critical" (esc (n hard-governor-hold?)))))
     (row (td "distinct hard rules the Governor actually reached")
          (td (klass "critical" (esc (count rules))) " of " (esc governor-hard-rule-catalogue-count)))
     (row (td "rollout-phase holds (Governor was clean)")
          (td (klass "warn" (esc (n phase-only-hold?)))))
     (row (td "escalations raised by the Governor")
          (td (klass "warn" (esc (n governor-escalation?)))))
     (row (td "escalations raised by the rollout phase")
          (td (klass "warn" (esc (n rollout-gate-escalation?)))))
     (row (td "auto-commits") (td (klass "ok" (esc (n committed?)))))
     (row (td "unclassified facts")
          (td (let [u (n #(= :unclassified (classify %)))]
                (if (zero? u) (klass "ok" "0") (klass "err" (esc u))))))
     (row (td "approver-shaped keys found anywhere in the run")
          (td (let [h (count (:hits att))]
                (if (zero? h) (klass "err" "0") (klass "ok" (esc h))))))]))

(defn- facility-rows [{:keys [store runs]}]
  (let [commits-by-facility
        (reduce (fn [acc r]
                  (if-let [rec (get-in r [:result :record])]
                    (update acc (get-in r [:request :facility-id]) (fnil conj []) [r rec])
                    acc))
                {} runs)]
    (concat
     (for [h houses
           :let [rec (store/registered-facility store (:facility-id h))
                 commits (get commits-by-facility (:facility-id h))]]
       (row (td (code (:facility-id h)))
            (td (esc (:name rec)))
            (td (kwcode (:flock-type rec)))
            (td (code (:breed rec)))
            (td (if (seq commits)
                  (str/join "<br>"
                            (for [[r rec2] commits]
                              (str (kwcode (get-in r [:request :op])) " "
                                   (muted (esc (pr-str (:value rec2)))))))
                  (dash)))))
     [(row (td (code unregistered-facility-id))
           (td (if (store/registered-facility store unregistered-facility-id)
                 (klass "err" "UNEXPECTEDLY REGISTERED")
                 (muted "not in the store — never seeded")))
           (td (dash)) (td (dash))
           (td (klass "critical" "every proposal against it is refused")))])))

(defn- scenario-rows [{:keys [runs]}]
  (for [r runs
        :let [f (disposition-fact r)]]
    (row (td (code (:id r)))
         (td (kwcode (get-in r [:request :op])))
         (td (code (get-in r [:request :facility-id])))
         (td (kwcode (:phase r)))
         (td (esc (:advisor-label r)))
         (td (class-cell f))
         (td (cond
               (seq (:basis f)) (code (str/join ", " (map #(str ":" (kwname %)) (:basis f))))
               (:reason f) (kwcode (:reason f))
               (:phase-reason f) (kwcode (:phase-reason f))
               :else (dash))))))

(defn- discrimination-rows [{:keys [ledger]}]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        escs (filter escalation? ledger)]
    [(row (td (klass "critical" "HARD governor refusal"))
          (td (esc (count (filter hard-governor-hold? holds))))
          (td (code ":t = :governor-hold") " and " (code ":basis") " non-empty")
          (td "the Governor found rules broken; no human is offered the decision"))
     (row (td (klass "warn" "rollout-phase hold"))
          (td (esc (count (filter phase-only-hold? holds))))
          (td (code ":t = :governor-hold") " and " (code ":basis") " empty")
          (td (str "same fact type, but the Governor passed it clean — "
                   (code "phase/gate")
                   " failed closed on an unrecognised phase")))
     (row (td (klass "warn" "Governor escalation"))
          (td (esc (count (filter governor-escalation? escs))))
          (td (code ":t = :approval-requested") " with a reason outside "
              (code (pr-str rollout-gate-reasons)))
          (td "Governor clean on hard rules but wants a human"))
     (row (td (klass "warn" "rollout-phase escalation"))
          (td (esc (count (filter rollout-gate-escalation? escs))))
          (td (code ":t = :approval-requested") " with a reason inside "
              (code (pr-str rollout-gate-reasons)))
          (td "the rollout stage, not the Governor, demanded the human"))]))

(defn- hard-rule-rows [{:keys [ledger]}]
  (let [holds (filter hard-governor-hold? ledger)
        by-rule (reduce (fn [acc f]
                          (reduce (fn [a v] (update a (:rule v) (fnil conj []) [f v]))
                                  acc (:violations f)))
                        {} holds)]
    (for [rule (sort-by kwname (keys by-rule))
          :let [hits (get by-rule rule)
                [f v] (first hits)]]
      (row (td (kwcode rule))
           (td (esc (count hits)))
           (td (kwcode (:op f)))
           (td (code (:subject f)))
           (td (esc (:detail v)))))))

(defn- action-gate-rows []
  (concat
   (for [op (sort-by kwname governor/known-ops)]
     (row (td (kwcode op))
          (td (if (contains? governor/always-escalate-ops op)
                (klass "warn" "ALWAYS escalates to a human, at any confidence")
                (klass "ok" (esc (str "auto-commits when the Governor is clean and confidence ≥ "
                                      governor/confidence-floor)))))))
   (for [op (sort-by kwname governor/blocked-ops)]
     (row (td (kwcode op))
          (td (klass "critical" "PERMANENTLY blocked — never escalated, never overridable"))))
   [(row (td (muted "anything else"))
         (td (klass "critical" (esc "HARD refusal :op-not-allowed — the allowlist is closed"))))]))

(defn- cost-rows [{:keys [runs]}]
  (let [orders (filter #(= :order-supplies (get-in % [:request :op])) runs)]
    (for [[cid c] (sort-by key facts/supply-categories)
          :let [hit (first (filter #(= cid (get-in % [:request :category])) orders))]]
      (row (td (code cid))
           (td (esc (:name c)))
           (td (esc (:cost-threshold c)))
           (td (if hit (esc (get-in hit [:request :cost])) (dash)))
           (td (if hit
                 (class-cell (disposition-fact hit))
                 (muted "no scenario ordered this category")))))))

(defn- escalation-cause-rows [{:keys [runs]}]
  (for [r runs
        :let [f (disposition-fact r)]
        :when (escalation? f)]
    (row (td (code (:id r)))
         (td (kwcode (:reason f)))
         (td (let [causes (recomputed-cause r)]
               (if (seq causes)
                 (str/join ", " (map #(kwcode %) (sort-by kwname causes)))
                 (dash))))
         (td (let [causes (recomputed-cause r)
                   recorded (:reason f)]
               (cond
                 (contains? rollout-gate-reasons recorded)
                 (klass "ok" "faithful — the rollout phase named itself")
                 (and (= recorded :always-escalate) (contains? causes :cost-over-threshold)
                      (not (contains? causes :always-escalate-op)))
                 (klass "err" (esc "LOSSY — recorded :always-escalate, but the op is not in always-escalate-ops; the real cause was the cost threshold"))
                 :else (klass "ok" "faithful")))))))

(defn- attribution-section [run-data]
  (let [att (attribution-scan run-data)
        div (payload-divergence run-data)]
    (section
     "Who approved it? — attribution scan"
     (str "Computed at render time by <code>poultryops.render-html/attribution-scan</code>, which walks every "
          "audit fact and every committed record this run produced looking for approver-shaped key names. "
          "Nothing here is hard-coded about this repo; if an approver field is added, this section reports it.")
     (table nil ["Measure" "Value"]
            [(row (td "audit facts scanned") (td (esc (:facts-scanned att))))
             (row (td "committed records scanned") (td (esc (:records-scanned att))))
             (row (td "distinct keys seen across both") (td (esc (:keys-scanned att))))
             (row (td "approver-shaped keys found")
                  (td (if (empty? (:hits att))
                        (klass "err" "0")
                        (klass "ok" (esc (count (:hits att)))))))
             (row (td (str "committed records where " (code ":value") " and " (code ":payload") " differ"))
                  (td (esc (:divergent div)) " of " (esc (:records div))))])
     (if (empty? (:hits att))
       (str "    <p class=\"err\"><strong>No approver is recorded anywhere in this run.</strong> "
            "The Governor and the rollout phase both hand decisions to a human by emitting an "
            (code ":approval-requested") " fact — but nothing in this repo consumes one. There is no "
            "resume, approve or deny entry point, and <code>poultryops.operation/commit-record</code> "
            "writes only <code>(:value proposal)</code>, into both " (code ":value") " and "
            (code ":payload") ". An escalated proposal therefore leaves no trace of who, if anyone, "
            "signed it. Disclosed here rather than patched, because fixing it is a change to the "
            "actor's approval workflow, not to a renderer.</p>\n")
       (str "    <p>Approver-shaped keys found: "
            (str/join ", " (for [h (:hits att)]
                             (str (kwcode (:key h)) " " (muted "on a " (esc (kwname (:where h)))))))
            "</p>\n"))
     "    <p class=\"muted\">Keys deliberately <em>excluded</em> from the approver-shaped set, and why — "
     "each of these would agree with the truth on some data while being wrong:</p>\n"
     (table nil ["Key" "What it actually is"]
            (for [[k why] (sort-by (comp str key) excluded-keys)]
              (row (td (kwcode k)) (td (esc why))))))))

;; ───────────────────────────── render ─────────────────────────────

(defn render [run-data]
  (let [{:keys [ledger runs]} run-data]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-0146 · poultry-farm operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Poultry-Farm Operations Coordination (ISIC 0146) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · generated at build time from one real run of this repo's actor · coordination only, never animal handling</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "This run"
      (str "Generated by <code>poultryops.render-html</code> (<code>clojure -M:render-html</code>) from one pass of "
           "<code>poultryops.operation/build</code> → <code>poultryops.advisor/-advise</code> → "
           "<code>poultryops.governor/check</code> → <code>poultryops.phase/gate</code>. "
           "Every number below is counted off the audit facts that pass emitted.")
      (table nil ["Measure" "Count"] (summary-rows run-data)))

     (section
      "Facility register"
      (str "Facilities are seeded one per breed in <code>poultryops.facts/breeds</code> and read back through "
           "<code>poultryops.store/registered-facility</code> — the Store protocol's only method. "
           "The last column is the <code>:value</code> of the record <code>run-operation</code> returned for a "
           "committed proposal. <strong>This repo never writes those records anywhere</strong>: "
           "<code>MemStore</code> holds facilities and nothing else, so the register below is assembled by the "
           "generator from the actor's return values, not read out of a store.")
      (table nil ["Facility" "On file" "Flock type" "Breed" "Records this run committed"]
             (facility-rows run-data)))

     (section
      "Scenarios, and what the actor actually did"
      (str "One row per scenario. The disposition column is computed from the fact the actor emitted, never from "
           "what the scenario intended — a scenario that stopped behaving as designed would show up here as a "
           "changed label rather than being quietly relabelled to agree.")
      (table nil ["Scenario" "Op" "Facility" "Phase" "Advisor" "Disposition" "Basis / reason"]
             (scenario-rows run-data)))

     (section
      "Governor refusal ≠ rollout gate"
      (str "These are four different things and this repo makes three of them look alike. "
           "<code>poultryops.operation</code> stamps <code>governor/hold-fact</code> onto a hold whatever caused it, "
           "so an unrecognised rollout phase produces a <code>:governor-hold</code> fact for a proposal the Governor "
           "passed <em>clean</em> — and a proposal the Governor genuinely refused can <em>also</em> carry a "
           "<code>:phase-reason</code>. Counting fact types overcounts refusals; checking for <code>:phase-reason</code> "
           "undercounts them. The discriminator that survives both is the Governor's own <code>:basis</code>.")
      (table nil ["Kind" "This run" "Discriminator" "What it means"]
             (discrimination-rows run-data)))

     (section
      "Hard gates this run actually reached"
      (str "A HARD refusal never reaches a human — the Governor refuses before escalation is offered. "
           "The detail text is the Governor's own, quoted verbatim out of the violation it wrote.")
      (table nil ["Rule" "Hits" "First op" "First subject" "Governor detail (first occurrence, verbatim)"]
             (hard-rule-rows run-data)))

     (section
      "Action gate (closed allowlist)"
      (str "Derived from <code>poultryops.governor/known-ops</code>, <code>/blocked-ops</code>, "
           "<code>/always-escalate-ops</code> and <code>/confidence-floor</code> — not hand-listed. "
           "If the Governor's allowlist changes, this table changes with it.")
      (table nil ["Op" "Gate"] (action-gate-rows)))

     (section
      "Procurement thresholds"
      (str "Thresholds come from <code>poultryops.facts/supply-categories</code>. "
           "<code>poultryops.registry/cost-exceeds-threshold?</code> is a strict <code>&gt;</code>, so a spend "
           "exactly at the threshold commits.")
      (table nil ["Category" "Name" "Escalation threshold" "This run proposed" "Outcome"]
             (cost-rows run-data)))

     (section
      "Escalation reasons: recorded vs. recomputed"
      (str "The reason written into the audit fact is derived in <code>poultryops.operation</code> as "
           "<code>(cond (:high-stakes? verdict) :always-escalate :else :low-confidence)</code>, while "
           "<code>poultryops.governor/check</code> sets <code>:high-stakes?</code> to "
           "<code>(or high-cost? always-escalate?)</code>. The right-hand columns recompute the cause "
           "independently from the verdict so any collapse between those two is visible instead of implied.")
      (table nil ["Scenario" "Reason recorded on the fact" "Cause recomputed from the verdict" "Faithful?"]
             (escalation-cause-rows run-data)))

     (attribution-section run-data)

     (section
      "Audit ledger (this run, in emission order)"
      (str "Every fact the run produced, including the <code>:advisor-proposal</code> trace that precedes each "
           "disposition. Accumulated by the generator — <code>poultryops.store</code> has no ledger.")
      (table nil ["#" "Fact" "Classification" "Op" "Subject" "Confidence" "Basis / reason"]
             (map-indexed
              (fn [i f]
                (row (td (esc (inc i)))
                     (td (kwcode (:t f)))
                     (td (class-cell f))
                     (td (kwcode (:op f)))
                     (td (if-let [s (or (:subject f) (:facility-id f))] (code s) (dash)))
                     (td (if-some [c (:confidence f)] (esc c) (dash)))
                     (td (cond
                           (seq (:basis f)) (code (str/join ", " (map #(str ":" (kwname %)) (:basis f))))
                           (:reason f) (kwcode (:reason f))
                           (:phase-reason f) (kwcode (:phase-reason f))
                           :else (dash)))))
              ledger)))

     (section
      "Disclosed defects, and what this page does not show"
      nil
      "    <ul>\n"
      "      <li><strong>langgraph is not wired.</strong> <code>deps.edn</code> declares no dependencies; the "
      "<code>:dev</code> alias carries only <code>:override-deps</code>, which override nothing when nothing depends "
      "on them. <code>poultryops.operation/build</code> returns a plain closure over <code>run-operation</code>, "
      "not a compiled StateGraph, and says so in its own docstring. There is no checkpoint and no "
      "<code>interrupt-before</code> — an escalation here is a fact, not a suspended graph.</li>\n"
      "      <li><strong>The store does not persist anything but facilities.</strong> The <code>Store</code> protocol "
      "has one method. <code>run-operation</code> builds a <code>:record</code> for every commit and no caller ever "
      "stores it. The register and ledger above are the generator's accumulation of return values.</li>\n"
      "      <li><strong>The escalation reason is lossy</strong> — see the recorded-vs-recomputed table. A supply "
      "order that only crossed its cost threshold is written to the audit trail as "
      "<code>:reason :always-escalate</code>, the same label a suspected-HPAI flag gets, because "
      "<code>:high-stakes?</code> merges the two. Not patched here: this is the Governor's and the operation's "
      "contract, and changing it inside a rendering task would hide the finding.</li>\n"
      "      <li><strong>No approver is recorded</strong> — see the attribution scan.</li>\n"
      "      <li><strong>No LLM ran.</strong> <code>poultryops.advisor</code> is a <code>MockAdvisor</code> record; "
      "its docstring names the LLM backend as future work. The proposals above are fixtures. What is real is every "
      "Governor verdict and every phase-gate decision computed over them. Two scenarios run a deliberately "
      "misbehaving advisor built on the same <code>Advisor</code> protocol, because <code>MockAdvisor</code> always "
      "emits <code>:effect :propose</code> at confidence ≥ 0.8 and therefore cannot reach the "
      "<code>:no-execution</code> rule or the confidence floor on its own.</li>\n"
      "      <li><strong>Coordination only.</strong> This actor never handles a bird, administers treatment, orders a "
      "cull, declares an outbreak, or contacts an animal-health authority. The two ops that would be those things "
      "are refused permanently, as shown above.</li>\n"
      "    </ul>\n")

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-0146 · Poultry-Farm Operations Coordination · AGPL-3.0-or-later · "
     "generated by <code>poultryops.render-html</code> from a real actor run — no timestamps, no ids, "
     "no random values, byte-identical on re-run.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ───────────────────────────── entry point ─────────────────────────────

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        run-data (run-scenarios!)
        ledger (:ledger run-data)
        holds (filter hard-governor-hold? ledger)
        rules (into #{} (mapcat :basis) holds)
        unclassified (filter #(= :unclassified (classify %)) ledger)]
    ;; A console showing no real HARD refusal is not evidence of a Governor.
    ;; This is a build-time invariant, not a convention: the file is not
    ;; written at all if the run did not produce one.
    (when (empty? holds)
      (throw (ex-info (str "no HARD governor hold on the ledger — refusing to write a console "
                           "that shows no real refusal")
                      {:facts (count ledger)
                       :governor-hold-facts (count (filter #(= :governor-hold (:t %)) ledger))
                       :note "a :governor-hold fact with an empty :basis is a rollout-phase hold, not a refusal"})))
    ;; A fact the classifier cannot name would silently vanish from every
    ;; count on the page.
    (when (seq unclassified)
      (throw (ex-info "audit facts this renderer cannot classify — refusing to write a console with silent rows"
                      {:count (count unclassified)
                       :types (vec (distinct (map :t unclassified)))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render run-data)))
    (println "wrote" out
             (str "(" (count (:runs run-data)) " scenarios, "
                  (count ledger) " audit facts, "
                  (count holds) " HARD governor refusals over "
                  (count rules) " distinct rules, "
                  (count (filter phase-only-hold? ledger)) " rollout-phase holds)"))))
