(ns igata.methods.test-magnesium-cartridge-eol
  "Focused tests for the spent MgH2 cartridge end-of-life / recovery decision
  contract (activity -> decision -> effect -> audit). Pure; deterministic;
  stdlib only."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-cartridge-eol :as eol]))

(def ^:private valid-req
  {:activity/id "act-eol-0001"
   :cartridge/id "mgh2-cart-014"
   :cartridge/integrity :verified-sealed
   :residual-h2-pressure-bar 0.3
   :depressurize-threshold-bar 0.5
   :step :open-and-empty
   :atmosphere {:class :inert :agent "argon-5n" :measured-o2-ppm 12}
   :interlocks [:hydrogen-leak-detector-armed :no-water-contact
                :static-dissipation-verified :pressure-gauge-verified]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :lineage-cids {:cartridge "bafy-cart-cid" :reactor "bafy-reactor-cid"}
   :requested-effect :simulate-plan})

(defn- with-approval [step]
  (assoc valid-req
         :step step
         :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                          :approved-at "2026-09-08T00:00:00Z"
                          :scope #{step}}))

;; ── happy paths ────────────────────────────────────────────────────────────

(deftest test-open-and-empty-approves-with-matching-approval
  (let [r (eol/plan-cartridge-decommissioning (with-approval :open-and-empty))]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= :open-and-empty (get-in r [:effect :effect/plan :human-approval-scope])))
    (is (= 0.5 (get-in r [:effect :effect/plan :depressurize-threshold-bar])))))

(deftest test-depressurize-and-vent-approves-with-approval
  (let [r (eol/plan-cartridge-decommissioning (with-approval :depressurize-and-vent))]
    (is (= :approved (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-authorized-steps-required
  (doseq [step [:depressurize-and-vent :open-and-empty]]
    (testing (str "hazardous step " step)
      (let [r (eol/plan-cartridge-decommissioning (with-approval step))]
        (is (= :approved (:decision r))))))
  ;; recycle-transfer (post-opened) is not hazardous of itself
  (let [r (eol/plan-cartridge-decommissioning (assoc valid-req :step :recycle-transfer))]
    (is (= :approved (:decision r)))))

;; ── safety refusals ────────────────────────────────────────────────────────

(deftest test-machine-command-refused-unconditionally
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty) :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-physical-command))))

(deftest test-residual-pressure-must-be-measured
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty) :residual-h2-pressure-bar nil))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :residual-pressure-measured))))

(deftest test-open-over-threshold-refused
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty)
                  :residual-h2-pressure-bar 2.0
                  :depressurize-threshold-bar 0.5))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :residual-pressure-unsafe-to-open))
    (is (re-find #"depressurize-and-vent" (:audit/refusal (:audit r))))))

(deftest test-threshold-must-be-declared-before-open
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty) :depressurize-threshold-bar nil))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :depressurize-threshold-declared))))

(deftest test-depressurize-step-allowed-well-above-threshold
  ;; you may depressurize while still above threshold — that is the point
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :depressurize-and-vent)
                  :residual-h2-pressure-bar 8.0
                  :depressurize-threshold-bar 0.5))]
    (is (= :approved (:decision r)))))

(deftest test-air-atmosphere-refused-outright
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty)
                  :atmosphere {:class :air :agent "none" :measured-o2-ppm 209400}))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :atmosphere-not-inert))))

(deftest test-water-atmosphere-refused-outright
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty)
                  :atmosphere {:class :water-based :agent "die-spray" :measured-o2-ppm 0}))]
    (is (= :refused (:decision r)))))

(deftest test-inert-without-measured-o2-refused
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty)
                  :atmosphere {:class :inert :agent "argon-5n"}))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :atmosphere-measured))))

(deftest test-missing-interlocks-refused
  (let [r (eol/plan-cartridge-decommissioning
           (update (with-approval :open-and-empty)
                   :interlocks
                   (comp vec (partial remove #(= % :pressure-gauge-verified)))))]
    (is (= :refused (:decision r)))
    (is (re-find #"pressure-gauge-verified" (:audit/refusal (:audit r))))))

(deftest test-witness-quorum-required
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty) :witness-robot-dids ["did:one"]))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :g4-witness-quorum))))

(deftest test-missing-lineage-refused
  (let [r (eol/plan-cartridge-decommissioning
           (assoc (with-approval :open-and-empty)
                  :lineage-cids {:cartridge "bafy-cart-cid"}))]
    (is (= :refused (:decision r)))
    (is (re-find #"reactor" (:audit/refusal (:audit r))))))

(deftest test-hazardous-step-without-approval-refused
  (let [r (eol/plan-cartridge-decommissioning (assoc valid-req :step :open-and-empty))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-required))
    (is (re-find #"human-approval" (:audit/refusal (:audit r))))))

(deftest test-hazardous-step-with-wrong-scope-refused
  (let [r (eol/plan-cartridge-decommissioning
           (assoc valid-req
                  :step :depressurize-and-vent
                  :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                                   :approved-at "2026-09-08T00:00:00Z"
                                   :scope #{:open-and-empty}}))]
    (is (= :refused (:decision r)))))

(deftest test-unrecognized-step-refused
  (let [r (eol/plan-cartridge-decommissioning (assoc valid-req :step :synthesis))]
    (is (= :refused (:decision r)))
    (is (re-find #"step:" (:audit/refusal (:audit r))))))

(deftest test-unrecognized-integrity-refused
  (let [r (eol/plan-cartridge-decommissioning (assoc valid-req :cartridge/integrity :unknown))]
    (is (= :refused (:decision r)))
    (is (re-find #"integrity" (:audit/refusal (:audit r))))))

;; ── EOL routing ────────────────────────────────────────────────────────────

(def ^:private route-req
  {:activity/id "act-eolroute-0001"
   :cartridge/id "mgh2-cart-014"
   :route-kind :recycle
   :recovery-route "in-house vacuum-distillation recovery line"})

(deftest test-recycle-route-approved
  (let [r (eol/route-eol-material route-req)]
    (is (= :approved (:decision r)))
    (is (= :routing-approved (get-in r [:effect :effect/kind])))
    (is (= :unmeasured (get-in r [:effect :effect/routing :recovery-ratio])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :g10-recovery-considered))))

(deftest test-return-route-approved
  (let [r (eol/route-eol-material (assoc route-req :route-kind :return))]
    (is (= :approved (:decision r)))))

(deftest test-dispose-route-always-deferred
  (let [r (eol/route-eol-material (assoc route-req :route-kind :dispose))]
    (is (= :deferred (:decision r)))
    (is (= :pending-human (get-in r [:effect :effect/routing :hazardous-waste-manifest])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :regulatory-commitment))
    (is (re-find #"hazardous-waste" (:audit/refusal (:audit r))))))

(deftest test-sale-of-recovered-material-deferred
  (let [r (eol/route-eol-material (assoc route-req :material-sale-involved true))]
    (is (= :deferred (:decision r)))
    (is (true? (get-in r [:effect :effect/routing :material-sale-involved])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))))

(deftest test-unknown-route-refused
  (let [r (eol/route-eol-material (assoc route-req :route-kind :landfill))]
    (is (= :refused (:decision r)))
    (is (re-find #"route-kind" (:audit/refusal (:audit r))))))

(deftest test-recovery-route-required
  (let [r (eol/route-eol-material (dissoc route-req :recovery-route))]
    (is (= :refused (:decision r)))))

(deftest test-measured-mass-balance-recorded-not-overwritten
  (let [r (eol/route-eol-material (assoc route-req :mass-balance-recorded-g 4230 :recovery-ratio 0.97))]
    (is (= :approved (:decision r)))
    (is (= 4230 (get-in r [:effect :effect/routing :mass-balance-recorded-g])))
    (is (= 0.97 (get-in r [:effect :effect/routing :recovery-ratio])))))

(deftest test-deterministic
  (is (= (eol/plan-cartridge-decommissioning (with-approval :open-and-empty))
         (eol/plan-cartridge-decommissioning (with-approval :open-and-empty))))
  (is (= (eol/route-eol-material route-req)
         (eol/route-eol-material route-req))))