(ns poultryops.facts
  "Reference facts for poultry-farm operations coordination: supply
  category cost policy, breed/hybrid classification, and
  biosecurity/notifiable-disease reference vocabulary. This namespace
  contains pure lookup functions for domain reference data -- the
  Governor and Advisor consult these instead of inventing thresholds.
  Mirrors `swineops.facts` (cloud-itonami-isic-0145) in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (farm operator/veterinarian)."
  {"feed"
   {:id "feed" :name "飼料" :cost-threshold 500}

   "veterinary-supply"
   {:id "veterinary-supply" :name "獣医用品" :cost-threshold 500}

   "biosecurity-equipment"
   {:id "biosecurity-equipment" :name "バイオセキュリティ設備" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def breeds
  "Common commercial poultry breeds/hybrids this actor's facility/flock
  records may cover (ISIC 0146: raising of poultry). Spans both broiler
  (meat) and layer (egg) production types."
  {"cobb500"   {:id "cobb500"   :name "コブ500 (Cobb 500)" :flock-type :broiler}
   "ross308"   {:id "ross308"   :name "ロス308 (Ross 308)" :flock-type :broiler}
   "leghorn"   {:id "leghorn"   :name "白色レグホーン (White Leghorn)" :flock-type :layer}
   "isa-brown" {:id "isa-brown" :name "ISAブラウン (ISA Brown)" :flock-type :layer}})

(defn breed-by-id [id]
  (get breeds id))

(def biosecurity-concerns
  "Reference vocabulary for common poultry biosecurity/notifiable-disease
  concerns this actor's :flag-animal-health-concern op may cite (e.g.
  suspected HPAI following wild-bird contact, or a fresh mortality
  spike). Purely descriptive -- citing a concern (or leaving it free
  text) NEVER changes the Governor's disposition: EVERY flagged concern
  always escalates for veterinary/farm-operator review
  (`poultryops.governor/always-escalate-ops`), regardless of the
  concern's `:notifiable` status or apparent severity. This actor has no
  authority to declare an outbreak, order a cull, or contact
  animal-health authorities -- it only surfaces the observation for
  human/veterinary judgment."
  {"hpai" {:id "hpai" :name "高病原性鳥インフルエンザ (Highly Pathogenic Avian Influenza / HPAI)" :notifiable true}
   "nd"   {:id "nd"   :name "ニューカッスル病 (Newcastle Disease)" :notifiable true}
   "ib"   {:id "ib"   :name "伝染性気管支炎 (Infectious Bronchitis)" :notifiable false}
   "ibd"  {:id "ibd"  :name "伝染性ファブリキウス嚢病 (Infectious Bursal Disease / Gumboro Disease)" :notifiable false}})

(defn biosecurity-concern-by-id [id]
  (get biosecurity-concerns id))
