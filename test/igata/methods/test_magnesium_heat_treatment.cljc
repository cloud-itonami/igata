(ns igata.methods.test-magnesium-heat-treatment
  "Focused tests for the heat-treatment cell decision contract (activity ->
  decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-heat-treatment :as ht]))

(def ^:private valid-req
  {:activity/id "act-ht-0001"
   :alloy {:family "AZ91D" :composition-disclosed? true}
   :treatment-type :t6
   :bath {:kind :inert-gas-furnace}
   :quench {:medium :forced-air}
   :atmosphere :inert-gas
   :measured-setpoint-c 415
   :measured-soak-minutes 60
   :interlocks [:overtemp-alarm :class-d-extinguisher :no-water-contact]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete" "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-04T00:00:00Z"
                    :scope #{:heat-treat}}
   :requested-effect :simulate-plan})

(deftest test-happy-path-approves-simulate-only
  (let [r (ht/plan-heat-treatment valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-machine-command-refused-unconditionally
  (let [r (ht/plan-heat-treatment (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-approval-scope-must-match
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:human-approval :scope] #{:melt :shot}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-missing-approval-defers-never-approves
  (let [r (ht/plan-heat-treatment (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "absence defers"))))

(deftest test-undisclosed-composition-refused-g2
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:alloy :composition-disclosed?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G2"))))

(deftest test-unrecognized-treatment-type-refused
  (let [r (ht/plan-heat-treatment (assoc valid-req :treatment-type :t7-mystery))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "treatment-type"))))

(deftest test-nitrate-salt-bath-refused-outright
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:bath :kind] :nitrate-salt))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "salt-bath"))))

(deftest test-salt-bath-kind-refused-outright
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:bath :kind] :salt-bath))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "salt-bath"))))

(deftest test-undeclared-bath-refused
  (let [r (ht/plan-heat-treatment (dissoc valid-req :bath))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "must be declared"))))

(deftest test-t6-without-quench-refused
  (let [r (ht/plan-heat-treatment (dissoc valid-req :quench))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "quench"))))

(deftest test-water-quench-refused
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:quench :medium] :water))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "water"))))

(deftest test-brine-quench-refused
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:quench :medium] :brine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "water"))))

(deftest test-t5-needs-no-quench
  (let [r (ht/plan-heat-treatment (-> valid-req
                                      (assoc :treatment-type :t5)
                                      (dissoc :quench)))]
    (is (= :approved (:decision r)))
    (is (= :t5 (get-in r [:effect :effect/plan :treatment-type])))))

(deftest test-stress-relief-approves
  (let [r (ht/plan-heat-treatment (-> valid-req
                                      (assoc :treatment-type :stress-relief)
                                      (dissoc :quench)))]
    (is (= :approved (:decision r)))))

(deftest test-unmeasured-setpoint-refused-not-invented
  (let [r (ht/plan-heat-treatment (dissoc valid-req :measured-setpoint-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes"))))

(deftest test-unmeasured-soak-refused-not-invented
  (let [r (ht/plan-heat-treatment (dissoc valid-req :measured-soak-minutes))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never defaults"))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (ht/plan-heat-treatment (assoc valid-req :interlocks [:overtemp-alarm]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "class-d-extinguisher"))))

(deftest test-witness-quorum-g4-enforced
  (let [r (ht/plan-heat-treatment (assoc valid-req :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G4"))))

(deftest test-unrecognized-alloy-family-refused
  (let [r (ht/plan-heat-treatment (assoc-in valid-req [:alloy :family] "6061-T6"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G2-magnesium"))))

;; ── activity 2: furnace-offer screening ─────────────────────────────────────

(def ^:private valid-offer
  {:activity/id "act-ht-proc-0001"
   :manufacturer "Furnace Vendor"
   :model "Example-Model"
   :equipment-class :heat-treatment-furnace
   :condition "new"
   :seller "Furnace Vendor"
   :source-url "https://example-vendor.example/products/example-model"})

(deftest test-screening-defers-to-human-approval
  (let [r (ht/screen-furnace-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (= [:price :currency :lead-time :utility :safety :compliance]
           (:unmeasured-fields (get-in r [:effect :effect/screening]))))))

(deftest test-screening-records-unmeasured-fields-only-when-supplied
  (let [r (ht/screen-furnace-offer (assoc valid-offer :price 12345 :currency "JPY"))
        screening (get-in r [:effect :effect/screening])]
    (is (= 12345 (get-in screening [:unmeasured :price])))
    (is (= "JPY" (get-in screening [:unmeasured :currency])))))

(deftest test-screening-unknown-condition-distinguished
  (let [r (ht/screen-furnace-offer (assoc valid-offer :condition "unknown"))]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-screening-condition-must-be-distinguished
  (let [r (ht/screen-furnace-offer (assoc valid-offer :condition "salvage"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-screening-requires-source-url
  (let [r (ht/screen-furnace-offer (dissoc valid-offer :source-url))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "source-url"))))
