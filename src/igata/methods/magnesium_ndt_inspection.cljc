(ns igata.methods.magnesium-ndt-inspection
  "magnesium_ndt_inspection.cljc — 鋳型 magnesium-HPDC cell, next executable
  slice: post-cast internal-defect inspection (:magnesium-hpdc manufacturing
  cell, scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  Extends igata.methods.magnesium-trim-qc (trim/machining + post-cast QC
  disposition) downstream through the manifest cell chain: the X-ray/CT and
  CMM equipment class (:xray-ct-and-cmm in the scope's
  :wiki-equipment-classes) — the internal-defect gate the QC disposition in
  the trim slice records evidence for. Robot attribution: Mimi
  (dimensional + CT metrology) per CLAUDE.md.

  Models activity -> decision -> effect -> audit for an X-ray/CT porosity
  scan + CMM dimensional scan on a trimmed magnesium part. The bot may
  design and simulate; it may NOT command physical equipment — a machine
  command is refused unconditionally.

  Hazard boundaries encoded (ionizing radiation inside the CT bay, moving
  gantry, part handling):
    - CT bay interlock verified, radiation exposure badge worn and the
      part fixtured/secured are required interlocks before a plan is
      admissible
    - human approval is required for the hazardous step
      (:operate-xray-ct-bay) — absence defers, never approves
    - acceptance bounds come from the caller-supplied engineering record;
      this module never invents a porosity limit, a dimensional tolerance,
      a pass threshold or a certification outcome
    - a verdict is only ever :pass, :fail or :unmeasured based on a
      MEASURED reading against the recorded bounds; a missing reading
      stays :unmeasured — never substituted with a datasheet constant
    - the module judges the NDT verdicts from measurement vs the recorded
      bounds but does NOT decide the final disposition (:accept/:rework/
      :scrap stays with the caller's QC evidence, per the trim slice's
      boundary)

  Lineage (G14): every activity carries the part's full lineage CID chain
  (alloy + die + shot + qc) so the NDT record joins the part attestation
  without inventing record ids. G4 witness quorum (≥ 2 robot DIDs) and G11
  operator attribution are enforced.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-lineage [:alloy :die :shot :qc])
(def ^:private required-interlocks
  #{:ct-bay-interlock-verified :radiation-exposure-badge-worn
    :part-fixtured-secured})
(def ^:private required-hazardous-steps #{:operate-xray-ct-bay})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-equipment-classes #{:xray-ct-and-cmm})
(def ^:private verdicts #{:pass :fail :unmeasured})

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
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

(defn- verdict
  "Judged ONLY from a measured reading against the caller's recorded bound:
  reading <= bound -> :pass, > bound -> :fail, missing reading -> :unmeasured.
  Nothing is assumed or substituted."
  [measured bound]
  (cond (nil? measured) :unmeasured
        (and (number? measured) (number? bound))
        (if (<= measured bound) :pass :fail)
        :else :unmeasured))

(defn- measured-numeric-ok? [x]
  (or (nil? x) (and (number? x) (not (neg? x)))))

(defn- bounds-ok? [pore dev]
  (and (number? pore) (pos? pore)
       (number? dev) (pos? dev)))

;; ── activity 1: NDT inspection plan (hazardous — human approval required) ──

(defn plan-ndt-inspection
  "One X-ray/CT + CMM internal-defect inspection activity for a trimmed
  magnesium part.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none — no porosity limit, tolerance, pass threshold or
  certification outcome is assumed here):
    :activity/id                    string
    :part/id                        string (lineage subject)
    :lineage-cids                   map {:alloy :die :shot :qc} ->
                                    content-addressed CID strings
                                    (G14 — every lineage hop must be present)
    :operator-did                   string (G11 — operator attribution)
    :measured/max-pore-area-pct     number or nil — the CT scan's measured
                                    max porosity area %; nil keeps the
                                    X-ray verdict :unmeasured
    :measured/max-dimensional-deviation-mm number or nil — the CMM's
                                    measured max deviation; nil keeps the
                                    CMM verdict :unmeasured
    :acceptance/max-pore-area-pct   number — recorded porosity limit from
                                    the part's engineering record
    :acceptance/max-deviation-mm    number — recorded dimensional tolerance
    :interlocks                     collection of interlock keywords (CT bay
                                    interlock, radiation badge, part
                                    fixtured)
    :witness-robot-dids             vector of ≥2 robot DIDs (G4 parity)
    :human-approval                 {:approver-did string :approved-at string
                                     :scope #{:operate-xray-ct-bay}} — ionizing
                                     radiation is hazardous
    :requested-effect               :simulate-plan (the only admissible kind)
                                    or :command-machine (refused
                                    unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        lineage (get req :lineage-cids)
        missing-lineage (remove #(present? (get lineage %)) required-lineage)
        pore (get req :measured/max-pore-area-pct)
        dev (get req :measured/max-dimensional-deviation-mm)
        pore-bound (get req :acceptance/max-pore-area-pct)
        dev-bound (get req :acceptance/max-deviation-mm)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: an NDT inspection activity needs an :activity/id")

          (not (present? (get req :part/id)))
          (do (note :part-id-present)
              "part-id: the lineage subject needs a :part/id")

          (seq missing-lineage)
          (do (note :g14-lineage-complete)
              (str "G14: full lineage CIDs required (alloy + die + shot + qc); missing "
                   (pr-str (sort (vec missing-lineage)))))

          (not (present? (get req :operator-did)))
          (do (note :g11-operator-attributed)
              "G11: the NDT record needs an :operator-did; inspection is operator-attributed, not anonymous")

          (not (bounds-ok? pore-bound dev-bound))
          (do (note :acceptance-bounds-from-engineering-record)
              (str "unmeasured: :acceptance/max-pore-area-pct and "
                   ":acceptance/max-deviation-mm must be positive bounds from the part's engineering record; "
                   "this module never invents a porosity limit or tolerance"))

          (not (and (measured-numeric-ok? pore) (measured-numeric-ok? dev)))
          (do (note :measured-readings-numeric)
              (str "unmeasured: :measured/max-pore-area-pct and "
                   ":measured/max-dimensional-deviation-mm must be the scanner's measured non-negative readings or nil (:unmeasured); "
                   "this module never substitutes a datasheet constant"))

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
                    (set/subset? required-hazardous-steps
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: operating an X-ray/CT bay (ionizing radiation, moving gantry) is a hazardous operation; a named human approver with "
                   (pr-str (sort required-hazardous-steps))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [xray-verdict (verdict pore pore-bound)
            cmm-verdict (verdict dev dev-bound)
            effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:part/id (:part/id req)
                                  :lineage-cids (select-keys lineage required-lineage)
                                  :operator-did (:operator-did req)
                                  :measured/max-pore-area-pct pore
                                  :measured/max-dimensional-deviation-mm dev
                                  :acceptance/max-pore-area-pct pore-bound
                                  :acceptance/max-deviation-mm dev-bound
                                  :xray-verdict xray-verdict
                                  :cmm-verdict cmm-verdict
                                  :disposition-decided-here false
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :acceptance-bounds-invented false}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: NDT equipment-offer screening (procurement — deferred) ─────

(defn screen-ndt-equipment-offer
  "Screen one offer for the cell's X-ray/CT and CMM inspection equipment
  class (:xray-ct-and-cmm). Procurement is a financial commitment: the
  decision is ALWAYS :deferred to a human approver; this fn only assembles
  the auditable evidence record. Condition must be distinguished as
  \"new\", \"used\", \"refurbished\" or \"unknown\". Missing price /
  lead-time / utility / safety / compliance values are recorded as
  :unmeasured — never invented. Sources: direct manufacturer or
  owner-operated dealer first-party inventory only (a :source-url is
  required)."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [:condition-distinguished :source-recorded])
        condition (get offer :condition)
        source-url (get offer :source-url)
        equipment-class (some-> (get offer :equipment-class) keyword)]
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: an equipment screening needs an :activity/id" @gates)

      (not (contains? recognized-equipment-classes equipment-class))
      (refuse activity-id
              (str "equipment-class: must be one of "
                   (pr-str (sort (map name recognized-equipment-classes)))
                   "; got " (pr-str (get offer :equipment-class)))
              @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions)) "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: an offer needs a first-party :source-url (manufacturer or owner-operated dealer)" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:manufacturer (get offer :manufacturer)
                     :model (get offer :model)
                     :equipment-class equipment-class
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
