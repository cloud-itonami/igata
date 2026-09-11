(ns igata.methods.test-magnesium-melt-handling
  "Focused tests for the melt-furnace tending and dosing-transfer decision
  contract. Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-melt-handling :as mh]))

(def ^:private valid-tending
  {:activity/id "act-mg-furnace-0001"
   :charging {:ingot-alloy "AZ91D"
              :charging-atmosphere :dry
              :moisture-inspected? true}
   :crucible {:condition-inspected? true :inspection-at "2026-09-01T00:00:00Z"}
   :measured-melt-temp-c 615
   :measured-overtemp-limit-c 640
   :cover-gas {:agent "Novec-7100-inert-class" :measured-flow-lmin 12}
   :interlocks [:dry-dust-collection :class-d-extinguisher :no-water-contact
                :furnace-overtemp-alarm :cover-gas-loss-alarm]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:furnace}}
   :requested-effect :simulate-plan})

(def ^:private valid-dosing
  {:activity/id "act-mg-dosing-0001"
   :measured-mass-g 1200
   :dosing-method "robot-ladle"
   :measured-melt-temp-c 615
   :cover-gas {:agent "Novec-7100-inert-class" :measured-flow-lmin 12}
   :interlocks [:dry-dust-collection :no-water-contact :machine-guard-verified
                :cover-gas-loss-alarm]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:dosing}}
   :requested-effect :simulate-plan})

;; ── tend-melt-furnace ──────────────────────────────────────────────────────

(deftest test-tending-happy-path-approves-simulate-only
  (let [r (mh/tend-melt-furnace valid-tending)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-tending-machine-command-refused-unconditionally
  (let [r (mh/tend-melt-furnace (assoc valid-tending :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-tending-wet-atmosphere-refused
  (let [r (mh/tend-melt-furnace (assoc-in valid-tending [:charging :charging-atmosphere] :wet))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "dry\n               or dry-inert"))))

(deftest test-tending-unmoisture-inspected-charge-refused
  (let [r (mh/tend-melt-furnace (assoc-in valid-tending [:charging :moisture-inspected?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "moisture-inspected"))))

(deftest test-tending-unknown-crucible-refused-not-assumed
  (let [r (mh/tend-melt-furnace (assoc-in valid-tending [:crucible :condition-inspected?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never assumed acceptable"))))

(deftest test-tending-overtemp-refused
  (let [r (mh/tend-melt-furnace (assoc valid-tending :measured-melt-temp-c 650))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "over-temperature limit"))))

(deftest test-tending-invented-overtemp-limit-refused
  (let [r (mh/tend-melt-furnace (dissoc valid-tending :measured-overtemp-limit-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "does\n               not invent a temperature limit"))))

(deftest test-tending-furnace-specific-interlocks-required
  (let [r (mh/tend-melt-furnace (assoc valid-tending :interlocks [:dry-dust-collection]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "furnace-overtemp-alarm"))))

(deftest test-tending-wrong-approval-scope-defers
  (let [r (mh/tend-melt-furnace (assoc-in valid-tending [:human-approval :scope] #{:shot}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))
    (is (str/includes? (:audit/refusal (:audit r)) "absence defers, never approves"))))

;; ── plan-dosing-transfer ──────────────────────────────────────────────────

(deftest test-dosing-happy-path-approves-simulate-only
  (let [r (mh/plan-dosing-transfer valid-dosing)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (= 1200 (get-in r [:effect :effect/plan :measured-mass-g])))))

(deftest test-dosing-machine-command-refused-unconditionally
  (let [r (mh/plan-dosing-transfer (assoc valid-dosing :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-dosing-invented-mass-refused
  (let [r (mh/plan-dosing-transfer (dissoc valid-dosing :measured-mass-g))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "never invents a dose mass"))))

(deftest test-dosing-undeclared-method-refused
  (let [r (mh/plan-dosing-transfer (dissoc valid-dosing :dosing-method))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "dosing-method"))))

(deftest test-dosing-missing-cover-gas-refused
  (let [r (mh/plan-dosing-transfer (dissoc valid-dosing :cover-gas))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "cover gas"))))

(deftest test-dosing-missing-machine-guard-refused
  (let [r (mh/plan-dosing-transfer (assoc valid-dosing :interlocks [:no-water-contact]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "machine-guard-verified"))))

(deftest test-dosing-wrong-approval-scope-defers
  (let [r (mh/plan-dosing-transfer (assoc-in valid-dosing [:human-approval :scope] #{:melt}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-witness-quorum-required-tending
  (let [r (mh/tend-melt-furnace
           (assoc valid-tending :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "witness"))))

(deftest test-witness-quorum-required-dosing
  (let [r (mh/plan-dosing-transfer (assoc valid-dosing :witness-robot-dids []))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "witness"))))
