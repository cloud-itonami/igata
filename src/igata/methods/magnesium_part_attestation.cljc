(ns igata.methods.magnesium-part-attestation
  "magnesium_part_attestation.cljc — 鋳型 part-attestation cell (the
  :igata_part_attestation cell in manifest.edn, phase \"attest\"), next
  executable slice of the magnesium-HPDC cell chain
  (scripts/hermes-magnesium-systems-bots/system-scope.edn on com-junkawasaki
  origin/main).

  Extends the landed slices downstream: die-prep -> melt-handling ->
  shot (hpdc) -> solidify-eject -> trim-qc -> NDT inspection ->
  heat-treatment all produce per-step evidence; THIS module composes that
  evidence into the final partAttestation (the :com.etzhayyim.igata.
  partAttestation lexicon) and judges whether the part may be RELEASED
  with an attestation, HELD pending missing evidence, or held on failure.

  Models activity -> decision -> effect -> audit. The bot may design and
  simulate; it may NOT command physical equipment — a machine command is
  refused unconditionally. It may NOT issue a certification outcome on its
  own authority: a release is only ever proposed and every attestation
  carries an explicit human approver (absence defers, never approves).

  Gates enforced (manifest.edn):
    - G4 witness quorum ≥ 2 robot signers per partAttestation
    - G14 lineage completeness — the part's full lineage CID chain
      (alloy + die + shot + qc; :ht / :ndt optional hops) must be
      present; a missing hop refuses
    - G10 material balance — scrap recovery mass accounting must be
      present with measured masses; a missing reading stays :unmeasured,
      never invented
    - G5 bilingual SOP acknowledgement is recorded on the attestation
    - G6 clearance flag is carried through from the caller's charter
      record (this module does not judge §2(a) itself)
    - G11 operator DID attribution

  Evidence-driven verdicts only: the module judges release-readiness from
  the supplied step-evidence (each step's verdict as recorded by its own
  cell contract). A missing or :unmeasured step verdict yields :held,
  never a fabricated :pass. Nothing here invents capacity, cycle time,
  yield, price, or certification.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private required-lineage-steps [:alloy :die :shot :qc])
(def ^:private optional-lineage-steps [:ht :ndt])
(def ^:private all-lineage-steps (vec (concat required-lineage-steps
                                              optional-lineage-steps)))
(def ^:private required-step-verdicts #{:trim-qc :ndt-xray :ndt-cmm})
(def ^:private recognized-conditions #{:new :used :refurbished :unknown})
(def ^:private recognized-condition-strs #{"new" "used" "refurbished" "unknown"})
(def ^:private step-verdicts #{:pass :fail :unmeasured})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
  "The audit tail every decision returns: what was decided, against which
  gates, and the explicit no-physical-command attestation."
  [activity-id decision refusal gates-checked effect]
  {:audit/activity-id activity-id
   :audit/decision decision
   :audit/refusal refusal
   :audit/gates-checked gates-checked
   :audit/effect effect
   :audit/bot-commanded-equipment false
   :audit/certification-authority :human-only})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

(defn- cid-ok? [x] (present? x))

(defn- step-verdict-ok? [v] (contains? step-verdicts v))

(defn- condition-ok? [c]
  (or (contains? recognized-conditions c)
      (contains? recognized-condition-strs c)))

(defn- witness-quorum-ok? [dids]
  (>= (count (remove str/blank? (map str dids))) g4-min-witness-robots))

(defn- material-balance-status
  "G10: judges scrap-recovery completeness from MEASURED masses only.
  Returns :pass | :unmeasured | :fail.
  - :pass        every required mass present, recovery <= charged mass
  - :fail        measured masses present but recovery > charged (physics)
  - :unmeasured  a required mass reading is missing — stays unmeasured"
  [mb]
  (let [charged (get mb :measured/charged-mass-kg)
        parts   (get mb :measured/part-mass-kg)
        scrap   (get mb :measured/scrap-recovered-mass-kg)
        readings [charged parts scrap]]
    (cond
      (some nil? readings) :unmeasured
      (some #(or (not (number? %)) (neg? %)) readings) :unmeasured
      (> (+ parts scrap) charged) :fail
      :else :pass)))

;; ── activity 1: compose and judge a part attestation ───────────────────────

(defn compose-part-attestation
  "Compose the partAttestation for one finished magnesium part and judge
  release-readiness from supplied step evidence.

  `req` keys (all evidence is caller-supplied from the landed cell
  contracts; this function invents none):
    :activity/id              string
    :part/id                  string
    :lineage-cids             map with at least {:alloy :die :shot :qc}
                              -> CID strings (G14; :ht / :ndt optional hops)
    :step-verdicts            map {:trim-qc :ndt-xray :ndt-cmm} ->
                              :pass | :fail | :unmeasured — the verdicts
                              recorded by the corresponding landed cells
    :material-balance         {:measured/charged-mass-kg number|nil
                               :measured/part-mass-kg number|nil
                               :measured/scrap-recovered-mass-kg number|nil}
                              (G10 — measured masses; nil stays unmeasured)
    :witness-robot-dids       vector of ≥2 robot DIDs (G4)
    :operator-did             string (G11)
    :sop-acknowledgement      {:ja-ack boolean :en-ack boolean} (G5)
    :charter-clearance        {:g6-clear boolean} — carried from the
                              caller's charter record (not judged here)
    :human-approval           {:approver-did string :approved-at string}
                              — required for any :release-proposed decision
    :requested-effect         :simulate-plan (only admissible) or
                              :command-machine (refused unconditionally)

  Decision outcomes:
    :refused           malformed request / missing lineage / machine command
    :release-proposed  all evidence present and passing AND a human
                       approval recorded; proposes release (certification
                       authority stays human-only)
    :held              evidence incomplete, failing, or no human approval —
                       the attestation is composed but held; failure is
                       never hidden
  Returns {:decision ... :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        lineage (get req :lineage-cids)
        verdicts (get req :step-verdicts)
        mb (get req :material-balance)
        sop (get req :sop-acknowledgement)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a part attestation needs an :activity/id")

          (not (and (map? lineage)
                    (every? cid-ok? (map #(get lineage %) required-lineage-steps))))
          (do (note :g14-lineage-complete)
              (str "G14: lineage chain incomplete — every hop of "
                   (pr-str required-lineage-steps)
                   " must carry a CID; got " (pr-str lineage)))

          (not (and (map? verdicts)
                    (every? step-verdict-ok? (map #(get verdicts %)
                                                  required-step-verdicts))))
          (do (note :step-verdicts-wellformed)
              (str "step-verdicts: each of " (pr-str (sort required-step-verdicts))
                   " must be one of " (pr-str (sort step-verdicts))
                   " as recorded by its own cell contract"))

          (not (map? mb))
          (do (note :g10-material-balance-present)
              "G10: a :material-balance map is required (measured masses; missing readings stay :unmeasured)")

          (not (witness-quorum-ok? (get req :witness-robot-dids)))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs ≥ " g4-min-witness-robots
                   " robot signers per partAttestation"))

          (not (present? (get req :operator-did)))
          (do (note :g11-operator-attribution)
              "G11: operator DID required — every attestation is attributed")

          (= :command-machine (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (= :simulate-plan (get req :requested-effect)))
          (do (note :requested-effect-simulate-only)
              "requested-effect: only :simulate-plan is admissible")

          (not (and (map? sop) (true? (get sop :ja-ack)) (true? (get sop :en-ack))))
          (do (note :g5-bilingual-sop-ack)
              "G5: bilingual (JA + EN) SOP acknowledgement must be recorded on the attestation")

          (not (contains? (get req :charter-clearance) :g6-clear))
          (do (note :g6-clearance-carried)
              "G6: the caller's charter clearance flag (:g6-clear) must be carried through — this module does not judge §2(a) itself")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [mb-status (material-balance-status mb)
            failing-steps (vec (sort (for [k required-step-verdicts
                                           :when (not= :pass (get verdicts k))]
                                       k)))
            ;; release is only proposed when every required step passed and
            ;; the G10 material balance is complete and physically consistent
            release-eligible? (and (empty? failing-steps)
                                   (= :pass mb-status)
                                   (true? (get-in req [:charter-clearance :g6-clear])))
            approval-present? (and (map? (get req :human-approval))
                                   (present? (get-in req [:human-approval :approver-did]))
                                   (present? (get-in req [:human-approval :approved-at])))
            attestation {:attestation/part-id (:part/id req)
                         :attestation/lineage-cids lineage
                         :attestation/step-verdicts verdicts
                         :attestation/failing-steps failing-steps
                         :attestation/material-balance-status mb-status
                         :attestation/material-balance mb
                         :attestation/witness-robot-dids (vec (:witness-robot-dids req))
                         :attestation/operator-did (:operator-did req)
                         :attestation/sop-acknowledgement sop
                         :attestation/charter-clearance (:charter-clearance req)
                         :attestation/certification-authority :human-only
                         :attestation/acceptance-bounds-invented false}]
        (cond
          (and release-eligible? (not approval-present?))
          (do (note :human-approval-required)
              {:decision :held
               :effect {:effect/kind :attestation-held
                        :effect/machine-command false
                        :effect/attestation attestation
                        :effect/hold-reason "release-eligible but no human approval recorded — certification authority is human-only; absence defers, never approves"}
               :audit (audit-record activity-id :held
                                    "human-approval: release requires a named human approver"
                                    @gates
                                    {:effect/kind :attestation-held})})

          release-eligible?
          (let [effect {:effect/kind :release-proposed-only
                        :effect/machine-command false
                        :effect/attestation attestation}]
            {:decision :release-proposed
             :effect effect
             :audit (audit-record activity-id :release-proposed
                                  ""
                                  (conj @gates :human-approval-approved :no-physical-command)
                                  effect)})

          :else
          (let [hold-reasons (cond-> []
                               (seq failing-steps)
                               (conj (str "failing step verdicts: " (pr-str failing-steps)))
                               (= :unmeasured mb-status)
                               (conj "G10 material balance has unmeasured mass readings — never invented")
                               (= :fail mb-status)
                               (conj "G10 material balance physically inconsistent (part+scrap > charged)")
                               (and (empty? failing-steps) (= :pass mb-status)
                                    (false? (get-in req [:charter-clearance :g6-clear])))
                               (conj "G6 charter clearance flag is false — carried through, not judged here"))]
            {:decision :held
             :effect {:effect/kind :attestation-held
                      :effect/machine-command false
                      :effect/attestation attestation
                      :effect/hold-reasons hold-reasons}
             :audit (audit-record activity-id :held
                                  "held: evidence incomplete or failing — failure is never hidden"
                                  @gates
                                  {:effect/kind :attestation-held})}))))))

;; ── activity 2: attested-part disposition screening (sale — always deferred) ──

(defn screen-attested-part-offer
  "Screen one offer to sell or transfer an attested part. A sale is a
  financial/contractual commitment: the decision is ALWAYS :deferred to a
  human approver; this fn only assembles the auditable evidence record.
  Condition is distinguished as one of \"new\", \"used\", \"refurbished\"
  or \"unknown\"; missing commercial values are recorded as :unmeasured —
  never estimated. Note: attested magnesium structural parts for
  human-occupied vehicles remain prohibited per manifest N7 — this
  screening records, and defers, it does not clear."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [])
        condition (get offer :condition)
        source-url (get offer :source-url)]
    (swap! gates conj :condition-distinguished :source-recorded)
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: a part-offer screening needs an :activity/id" @gates)

      (not (condition-ok? condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-condition-strs)) "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: an offer needs a first-party :source-url" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:part/id (get offer :part/id)
                     :attestation-cid (get offer :attestation-cid)
                     :condition condition
                     :seller (get offer :seller)
                     :source-url source-url
                     :observed-at (get offer :observed-at)
                     :unmeasured-fields [:price :currency :lead-time :utility :safety :compliance]
                     :note "manifest N7: human-occupied vehicle structural use prohibited R0..R2 — screening records, defers, does not clear"}}]
        {:decision :deferred
         :effect effect
         :audit (audit-record activity-id :deferred
                              "sale is a human decision; screening evidence assembled only"
                              (conj @gates :human-approval-required :no-financial-commitment)
                              effect)}))))
