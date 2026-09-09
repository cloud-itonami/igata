(ns igata.methods.test-magnesium-die-prep
  "Focused tests for the magnesium-HPDC die-preparation decision contract
  (activity -> decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-die-prep :as dp]))

(def ^:private valid-req
  {:activity/id "act-dieprep-0001"
   :die {:die-id "die-mg-cartridge-lid-01"
         :measured-thermal-cycles 4120
         :max-thermal-cycles 8000
         :crack-inspection {:method "dye-penetrant"
                            :result :pass
                            :inspected-at "2026-09-01T00:00:00Z"}}
   :lineage-cids ["bafy-die-design-cad-0001"]
   :release-agent {:lot-id "ra-lot-0007" :g7-scan-cleared? true}
   :measured-preheat-temp-c 210
   :interlocks [:machine-guard-verified :hot-surface-ppe :die-crane-verified]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete" "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:die-handling :agent-spray}}
   :requested-effect :simulate-plan})

(deftest test-happy-path-approves-simulate-only
  (let [r (dp/plan-die-preparation valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))
    (is (= ["bafy-die-design-cad-0001"] (get-in r [:effect :effect/plan :lineage-cids])))))

(deftest test-machine-command-refused-unconditionally
  (let [r (dp/plan-die-preparation (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-die-life-exhausted-refused-outright
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :measured-thermal-cycles] 8000))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "past its recorded life")))
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :measured-thermal-cycles] 8001))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "past its recorded life"))))

(deftest test-missing-die-life-limit-refused-not-invented
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :max-thermal-cycles] nil))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes a default"))))

(deftest test-uncounted-thermal-cycles-refused
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :measured-thermal-cycles] "many"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "counted value"))))

(deftest test-missing-crack-inspection-refused
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :crack-inspection] nil))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "crack inspection"))))

(deftest test-cracked-die-refused-outright
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:die :crack-inspection :result] :fail))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "cracked die refused outright"))))

(deftest test-g7-release-agent-not-cleared-refused
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:release-agent :g7-scan-cleared?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G7"))))

(deftest test-missing-release-agent-lot-refused
  (let [r (dp/plan-die-preparation (dissoc valid-req :release-agent))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G7"))))

(deftest test-unmeasured-preheat-temp-refused-not-invented
  (let [r (dp/plan-die-preparation (dissoc valid-req :measured-preheat-temp-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes"))))

(deftest test-missing-lineage-cids-refused-g14
  (let [r (dp/plan-die-preparation (assoc valid-req :lineage-cids []))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G14"))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (dp/plan-die-preparation (assoc valid-req :interlocks [:machine-guard-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "die-crane-verified"))))

(deftest test-witness-quorum-g4-refused
  (let [r (dp/plan-die-preparation (assoc valid-req :witness-robot-dids ["did:one"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G4"))))

(deftest test-missing-human-approval-defers-never-approves
  (let [r (dp/plan-die-preparation (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval")))
  (let [r (dp/plan-die-preparation (assoc-in valid-req [:human-approval :scope] #{:die-handling}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-missing-activity-id-refused
  (let [r (dp/plan-die-preparation (dissoc valid-req :activity/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "activity-id"))))

(deftest test-deterministic-output
  (is (= (dp/plan-die-preparation valid-req) (dp/plan-die-preparation valid-req))))
