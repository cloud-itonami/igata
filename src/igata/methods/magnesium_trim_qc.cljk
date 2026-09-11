(ns igata.methods.magnesium-trim-qc
  "magnesium_trim_qc.cljc — 鋳型 magnesium-HPDC cell, second executable slice:
  trim/machining + post-cast QC disposition (:magnesium-hpdc manufacturing cell,
  scripts/hermes-magnesium-systems-bots/system-scope.edn on com-junkawasaki
  origin/main).

  Extends igata.methods.magnesium-hpdc (melt-and-shot, first slice) downstream
  through the manifest cell chain: igata_post_cast_qc -> igata_trim_machining.

  Models activity -> decision -> effect -> audit for trim/machining activity on
  an ejected magnesium part. The bot may design and simulate; it may NOT command
  physical equipment — a machine command is refused unconditionally.

  Hazard boundaries encoded (combustible magnesium chips / rotating machinery):
    - dry chip collection, Class-D extinguisher, no-water-contact (water +
      fine Mg chips = hydrogen generation + exotherm) and machine-guard
      interlocks must be declared before a plan is approved
    - human approval is required for the machining step — absence defers,
      never approves
    - no constant is invented: recovery mass balance is computed ONLY from
      measured masses supplied by the caller against the G10 threshold;
      missing price / lead-time / utility / safety / compliance values are
      recorded as :unmeasured, never filled in

  Constitutional parity with manifest.edn gates:
    - G10: measured scrap recovery ≥ 95 % (sprue + runner + reject + chip +
      die-spray residue) — computed, not asserted
    - G14: full lineage CIDs (alloy + die + shot + QC) required on every plan

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g10-min-recovery-ratio 0.95)
(def ^:private required-interlocks
  #{:dry-chip-collection :class-d-extinguisher :no-water-contact :machine-guard-verified})
(def ^:private required-lineage [:alloy :die :shot :qc])
(def ^:private recognized-qc-dispositions #{:accept :rework :scrap})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
  "The audit tail every decision returns: what was decided, against which gates,
  and the explicit no-physical-command attestation."
  [activity-id decision refusal gates-checked effect]
  {:audit/activity-id activity-id
   :audit/decision decision
   :audit/refusal refusal
   :audit/gates-checked gates-checked
   :audit/effect effect
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

(defn- recovery-ratio
  "G10 recovery from measured masses only: recovered / total-in. Returns nil
  when any mass is missing or non-positive-input — nothing is assumed."
  [{:measured/keys [total-input-g recovered-g]}]
  (when (and (number? total-input-g) (pos? total-input-g)
             (number? recovered-g) (>= recovered-g 0))
    (/ recovered-g total-input-g)))

;; ── activity 1: trim-and-QC plan (hazardous — human approval required) ─────

(defn plan-trim-and-qc
  "One magnesium trim/machining + QC disposition activity for an ejected part.

  `req` keys (all measured values must be supplied by the caller; this function
  invents none):
    :activity/id           string
    :part/id               string (lineage subject)
    :lineage-cids          map {:alloy :die :shot :qc} -> content-addressed CID
                           strings (G14 — every lineage hop must be present)
    :measured-total-input-g   number — measured total alloy mass into the shot
    :measured-recovered-g     number — measured recovered mass (sprue + runner
                              + reject + chip + die-spray residue) (G10)
    :qc-disposition        :accept | :rework | :scrap — decided by the caller's
                           QC evidence (X-ray CT, dimensional, mech); this
                           module records the disposition, it does not judge it
    :interlocks            collection of interlock keywords (dry chip
                           collection, Class-D extinguisher, no-water-contact,
                           machine guard)
    :witness-robot-dids    vector of ≥2 robot DIDs (G4 parity)
    :human-approval        {:approver-did string  :approved-at string
                            :scope #{:trim} — machining is hazardous}
    :requested-effect      :simulate-plan (the only admissible kind) or
                           :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        lineage (get req :lineage-cids)
        missing-lineage (remove #(present? (get lineage %)) required-lineage)
        recovery (recovery-ratio req)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a trim-and-QC activity needs an :activity/id")

          (not (present? (get req :part/id)))
          (do (note :part-id-present)
              "part-id: the lineage subject needs a :part/id")

          (seq missing-lineage)
          (do (note :g14-lineage-complete)
              (str "G14: full lineage CIDs required (alloy + die + shot + qc); missing "
                   (pr-str (sort (vec missing-lineage)))))

          (not recovery)
          (do (note :g10-measured-masses-required)
              "unmeasured: G10 recovery must be computed from measured :measured-total-input-g and :measured-recovered-g; this module never substitutes an assumed recovery")

          (< recovery g10-min-recovery-ratio)
          (do (note :g10-recovery-below-threshold)
              (str "G10: measured scrap recovery " (double recovery)
                   " is below the " g10-min-recovery-ratio
                   " threshold; the plan is refused until the material balance closes"))

          (not (contains? recognized-qc-dispositions (get req :qc-disposition)))
          (do (note :qc-disposition-recorded)
              (str "qc-disposition: must be one of "
                   (pr-str (sort (vec recognized-qc-dispositions)))
                   " decided by the caller's QC evidence; got "
                   (pr-str (get req :qc-disposition))))

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   2))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs ≥ 2 robot signers per record"))

          (= :command-machine (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (contains? (set (map keyword (get-in req [:human-approval :scope])))
                               :trim)))
          (do (note :human-approval-required)
              "human-approval: trim/machining is a hazardous rotating-machinery operation; a named human approver with :trim scope must be recorded — absence defers, never approves")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:part/id (:part/id req)
                                  :lineage-cids (select-keys lineage required-lineage)
                                  :measured-total-input-g (:measured/total-input-g req)
                                  :measured-recovered-g (:measured/recovered-g req)
                                  :computed-recovery-ratio (double recovery)
                                  :g10-min-recovery-ratio g10-min-recovery-ratio
                                  :qc-disposition (:qc-disposition req)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: scrap/disposition stream screening (procurement — deferred) ─

(defn screen-scrap-recovery-offer
  "Screen one offer for the cell's chip/scrap recovery or rework services path.
  Procurement is a financial commitment: the decision is ALWAYS :deferred to a
  human approver; this fn only assembles the auditable evidence record.
  Condition must be distinguished as \"new\", \"used\", \"refurbished\" or
  \"unknown\". Missing price / lead-time / utility / safety / compliance values
  are recorded as :unmeasured — never invented."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [:condition-distinguished :source-recorded])
        condition (get offer :condition)
        source-url (get offer :source-url)]
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: an equipment screening needs an :activity/id" @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions)) "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: an offer needs a first-party :source-url" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:manufacturer (get offer :manufacturer)
                     :model (get offer :model)
                     :equipment-class (get offer :equipment-class)
                     :condition condition
                     :seller (get offer :seller)
                     :source-url source-url
                     :observed-at (get offer :observed-at)
                     :unmeasured-fields [:price :currency :lead-time :utility :safety :compliance]}}]
        {:decision :deferred
         :effect effect
         :audit (audit-record activity-id :deferred
                              "procurement is a human decision; screening evidence assembled only"
                              (conj @gates :human-approval-required :no-financial-commitment)
                              effect)}))))
