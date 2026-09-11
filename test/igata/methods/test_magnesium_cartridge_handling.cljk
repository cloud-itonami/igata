(ns igata.methods.test-magnesium-cartridge-handling
  "Focused tests for the inert MgH2 cartridge handling decision contract
  (activity -> decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-cartridge-handling :as ch]))

(def ^:private valid-req
  {:activity/id "act-cartridge-0001"
   :cartridge/id "mgh2-cart-014"
   :cartridge/integrity :verified-sealed
   :step :storage-stow
   :atmosphere {:class :inert :agent "argon-5n" :measured-o2-ppm 12}
   :measured-cartridge-mass-g 4820.5
   :measured-seal-pressure-bar 0.8
   :lineage-cids {:cartridge "bafy-cart-cid" :receipt "bafy-receipt-cid"}
   :interlocks [:inert-enclosure-purged :hydrogen-leak-detector-armed
                :no-water-contact :static-dissipation-verified]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :requested-effect :simulate-plan})

(defn- with-approval [step]
  (assoc valid-req
         :step step
         :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                          :approved-at "2026-09-03T00:00:00Z"
                          :scope #{step}}))

;; ── happy paths ────────────────────────────────────────────────────────────

(deftest test-storage-stow-approves-without-human-approval
  (let [r (ch/plan-cartridge-handling valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-hazardous-steps-approved-with-matching-approval
  (doseq [step [:intake-inspection :integration-charge]]
    (testing (str "hazardous step " step)
      (let [r (ch/plan-cartridge-handling (with-approval step))]
        (is (= :approved (:decision r)))
        (is (= step (get-in r [:effect :effect/plan :human-approval-scope])))))))

(deftest test-missing-measured-values-recorded-unmeasured
  (let [r (ch/plan-cartridge-handling (dissoc valid-req
                                              :measured-cartridge-mass-g
                                              :measured-seal-pressure-bar))
        plan (get-in r [:effect :effect/plan])]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (:measured-cartridge-mass-g plan)))
    (is (= :unmeasured (:measured-seal-pressure-bar plan)))))

;; ── refusals ───────────────────────────────────────────────────────────────

(deftest test-machine-command-refused-unconditionally
  (let [r (ch/plan-cartridge-handling (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (= :none (get-in r [:effect :effect/kind])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-physical-command))))

(deftest test-breached-cartridge-refused
  (let [r (ch/plan-cartridge-handling (assoc valid-req :cartridge/integrity :breached))]
    (is (= :refused (:decision r)))
    (is (re-find #"integrity" (:audit/refusal (:audit r))))))

(deftest test-unknown-integrity-refused
  (let [r (ch/plan-cartridge-handling (assoc valid-req :cartridge/integrity :unknown))]
    (is (= :refused (:decision r)))))

(deftest test-air-atmosphere-refused-outright
  (let [r (ch/plan-cartridge-handling
           (assoc valid-req :atmosphere {:class :air :agent "none" :measured-o2-ppm 209400}))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :atmosphere-not-inert))
    (is (re-find #"outright" (:audit/refusal (:audit r))))))

(deftest test-water-atmosphere-refused-outright
  (let [r (ch/plan-cartridge-handling
           (assoc valid-req :atmosphere {:class :water-based :agent "die-spray" :measured-o2-ppm 0}))]
    (is (= :refused (:decision r)))))

(deftest test-inert-without-measured-o2-refused
  (let [r (ch/plan-cartridge-handling
           (assoc valid-req :atmosphere {:class :inert :agent "argon-5n"}))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :atmosphere-measured))))

(deftest test-missing-interlocks-refused
  (let [r (ch/plan-cartridge-handling
           (update valid-req :interlocks (comp vec (partial remove #(= % :hydrogen-leak-detector-armed)))))]
    (is (= :refused (:decision r)))
    (is (re-find #"hydrogen-leak-detector-armed" (:audit/refusal (:audit r))))))

(deftest test-witness-quorum-required
  (let [r (ch/plan-cartridge-handling (assoc valid-req :witness-robot-dids ["did:one"]))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :g4-witness-quorum))))

(deftest test-missing-lineage-refused
  (let [r (ch/plan-cartridge-handling (assoc valid-req :lineage-cids {:cartridge "bafy-cart-cid"}))]
    (is (= :refused (:decision r)))
    (is (re-find #"receipt" (:audit/refusal (:audit r))))))

(deftest test-hazardous-step-without-approval-defers-to-refusal
  (let [r (ch/plan-cartridge-handling (assoc valid-req :step :integration-charge))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-required))
    (is (re-find #"human-approval" (:audit/refusal (:audit r))))))

(deftest test-hazardous-step-with-wrong-scope-refused
  (let [r (ch/plan-cartridge-handling
           (assoc valid-req
                  :step :intake-inspection
                  :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                                   :approved-at "2026-09-03T00:00:00Z"
                                   :scope #{:integration-charge}}))]
    (is (= :refused (:decision r)))))

(deftest test-unrecognized-step-refused
  (let [r (ch/plan-cartridge-handling (assoc valid-req :step :synthesis))]
    (is (= :refused (:decision r)))
    (is (re-find #"step:" (:audit/refusal (:audit r))))))

;; ── procurement screening ──────────────────────────────────────────────────

(def ^:private direct-offer
  {:activity/id "act-supply-0001"
   :offer/id "offer-direct-mgh2-001"
   :condition "new"
   :source-url "https://example-mgh2-oem.example/products/cart-014"
   :source-kind "manufacturer-direct"
   :route :direct})

(deftest test-screening-defers-always
  (let [r (ch/screen-cartridge-supply-offer direct-offer)]
    (is (= :deferred (:decision r)))
    (is (false? (get-in r [:effect :effect/financial-commitment])))
    (is (= :deferred-to-human (get-in r [:effect :effect/evidence :decision-recommended])))
    (is (= :unmeasured (get-in r [:effect :effect/evidence :unit-price-usd])))))

(deftest test-screening-condition-must-be-distinguished
  (let [r (ch/screen-cartridge-supply-offer (assoc direct-offer :condition "good"))]
    (is (= :refused (:decision r)))
    (is (re-find #"condition" (:audit/refusal (:audit r))))))

(deftest test-screening-condition-unknown-accepted-as-recorded
  (let [r (ch/screen-cartridge-supply-offer (assoc direct-offer :condition "unknown"))]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/evidence :condition])))))

(deftest test-screening-source-required
  (let [r (ch/screen-cartridge-supply-offer (dissoc direct-offer :source-url))]
    (is (= :refused (:decision r)))))

(deftest test-screening-intermediary-without-verified-value-refused
  (let [r (ch/screen-cartridge-supply-offer (assoc direct-offer :route :intermediary))]
    (is (= :refused (:decision r)))
    (is (re-find #"direct-first" (:audit/refusal (:audit r))))))

(deftest test-screening-intermediary-with-verified-value-defers
  (let [r (ch/screen-cartridge-supply-offer
           (assoc direct-offer :route :intermediary
                  :intermediary-verified-value "on-site stock + verified RMA SLA"))]
    (is (= :deferred (:decision r)))))

(deftest test-supplied-prices-recorded-not-overwritten
  (let [r (ch/screen-cartridge-supply-offer
           (assoc direct-offer :unit-price-usd 340 :lead-time-days 45))]
    (is (= 340 (get-in r [:effect :effect/evidence :unit-price-usd])))
    (is (= 45 (get-in r [:effect :effect/evidence :lead-time-days])))
    (is (= :unmeasured (get-in r [:effect :effect/evidence :hydrogen-capacity-kg])))))

(deftest test-deterministic
  (is (= (ch/plan-cartridge-handling (with-approval :integration-charge))
         (ch/plan-cartridge-handling (with-approval :integration-charge)))))

