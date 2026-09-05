(ns igata.methods.test-magnesium-part-attestation
  "Focused tests for the part-attestation decision contract slice (activity
  -> decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [igata.methods.magnesium-part-attestation :as pa]))

(def ^:private valid-req
  {:activity/id "act-mg-attest-0001"
   :part/id "part-mg-0001"
   :lineage-cids {:alloy "bafy-alloy-cid" :die "bafy-die-cid"
                  :shot "bafy-shot-cid" :qc "bafy-qc-cid"
                  :ht "bafy-ht-cid" :ndt "bafy-ndt-cid"}
   :step-verdicts {:trim-qc :pass :ndt-xray :pass :ndt-cmm :pass}
   :material-balance {:measured/charged-mass-kg 12.0
                      :measured/part-mass-kg 8.0
                      :measured/scrap-recovered-mass-kg 3.5}
   :witness-robot-dids ["did:web:etzhayyim.com:igata:mimi" "did:web:etzhayyim.com:igata:otete"]
   :operator-did "did:web:etzhayyim.com:person:operator-1"
   :sop-acknowledgement {:ja-ack true :en-ack true}
   :charter-clearance {:g6-clear true}
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-05T00:00:00Z"}
   :requested-effect :simulate-plan})

;; ── activity 1: compose and judge a part attestation ───────────────────────

(deftest test-happy-path-proposes-release-with-human-approval
  (let [r (pa/compose-part-attestation valid-req)]
    (is (= :release-proposed (:decision r)))
    (is (= :release-proposed-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= :human-only (get-in r [:effect :effect/attestation :attestation/certification-authority])))
    (is (= :pass (get-in r [:effect :effect/attestation :attestation/material-balance-status])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-physical-command))))

(deftest test-release-eligible-but-no-human-approval-holds
  "Certification authority is human-only: without a recorded human approver
  the attestation is HELD — absence defers, never approves."
  (let [r (pa/compose-part-attestation (dissoc valid-req :human-approval))]
    (is (= :held (:decision r)))
    (is (= :attestation-held (get-in r [:effect :effect/kind])))
    (is (str/includes? (get-in r [:effect :effect/hold-reason]) "human-only"))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-required))))

(deftest test-failing-step-verdict-holds-and-never-hides
  (let [r (pa/compose-part-attestation (assoc-in valid-req [:step-verdicts :ndt-xray] :fail))]
    (is (= :held (:decision r)))
    (is (contains? (set (get-in r [:effect :effect/attestation :attestation/failing-steps]))
                   :ndt-xray))
    (is (some #(str/includes? % "ndt-xray") (get-in r [:effect :effect/hold-reasons])))))

(deftest test-unmeasured-step-verdict-holds-not-passes
  (let [r (pa/compose-part-attestation (assoc-in valid-req [:step-verdicts :ndt-cmm] :unmeasured))]
    (is (= :held (:decision r)))
    (is (contains? (set (get-in r [:effect :effect/attestation :attestation/failing-steps]))
                   :ndt-cmm))))

(deftest test-missing-material-balance-reading-stays-unmeasured
  (let [r (pa/compose-part-attestation (assoc-in valid-req
                                                 [:material-balance :measured/scrap-recovered-mass-kg]
                                                 nil))]
    (is (= :held (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/attestation :attestation/material-balance-status])))
    (is (some #(str/includes? % "unmeasured") (get-in r [:effect :effect/hold-reasons])))))

(deftest test-physically-inconsistent-material-balance-holds
  "part + scrap > charged mass is a physics violation: held, never normalized."
  (let [r (pa/compose-part-attestation (assoc-in valid-req
                                                 [:material-balance :measured/scrap-recovered-mass-kg]
                                                 9.0))]
    (is (= :held (:decision r)))
    (is (= :fail (get-in r [:effect :effect/attestation :attestation/material-balance-status])))
    (is (some #(str/includes? % "inconsistent") (get-in r [:effect :effect/hold-reasons])))))

(deftest test-missing-lineage-cid-refused-g14
  (let [r (pa/compose-part-attestation (assoc-in valid-req [:lineage-cids :shot] ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G14"))
    (is (contains? (set (:audit/gates-checked (:audit r))) :g14-lineage-complete))))

(deftest test-g6-clearance-flag-required-but-never-judged-here
  "The flag must be present (carried through); a false flag holds the part
  with the carried-through reason rather than this module clearing §2(a)."
  (let [r1 (pa/compose-part-attestation (assoc valid-req :charter-clearance {:g6-clear false}))]
    (is (= :held (:decision r1)))
    (is (some #(str/includes? % "G6") (get-in r1 [:effect :effect/hold-reasons]))))
  (let [r2 (pa/compose-part-attestation (dissoc valid-req :charter-clearance))]
    (is (= :refused (:decision r2)))
    (is (str/includes? (get-in r2 [:audit :audit/refusal]) "G6"))))

(deftest test-machine-command-refused-unconditionally
  (let [r (pa/compose-part-attestation (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "no-physical-command"))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

(deftest test-missing-witness-quorum-refused-g4
  (let [r (pa/compose-part-attestation (assoc valid-req :witness-robot-dids ["did:one"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G4"))))

(deftest test-missing-bilingual-sop-ack-refused-g5
  (let [r (pa/compose-part-attestation (assoc-in valid-req [:sop-acknowledgement :en-ack] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G5"))))

(deftest test-malformed-step-verdict-refused
  (let [r (pa/compose-part-attestation (assoc-in valid-req [:step-verdicts :trim-qc] :maybe))]
    (is (= :refused (:decision r)))
    (is (contains? (set (:audit/gates-checked (:audit r))) :step-verdicts-wellformed))))

;; ── activity 2: attested-part offer screening (sale — always deferred) ─────

(deftest test-part-offer-screening-defers-to-human
  (let [r (pa/screen-attested-part-offer
           {:activity/id "act-mg-offer-0001"
            :part/id "part-mg-0001"
            :attestation-cid "bafy-attest-cid"
            :condition "used"
            :seller "owner-operator"
            :source-url "https://example.first-party/parts/part-mg-0001"
            :observed-at "2026-09-05T00:00:00Z"})]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (contains? (set (get-in r [:effect :effect/screening :unmeasured-fields])) :price))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))))

(deftest test-part-offer-unknown-condition-distinguished
  (let [r (pa/screen-attested-part-offer
           {:activity/id "act-mg-offer-0002"
            :condition "unknown"
            :source-url "https://example.first-party/parts/x"})]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-part-offer-bad-condition-refused
  (let [r (pa/screen-attested-part-offer
           {:activity/id "act-mg-offer-0003"
            :condition "like-new"
            :source-url "https://example.first-party/parts/x"})]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "condition"))))

(deftest test-part-offer-missing-source-refused
  (let [r (pa/screen-attested-part-offer
           {:activity/id "act-mg-offer-0004" :condition "new" :source-url ""})]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "source-url"))))
