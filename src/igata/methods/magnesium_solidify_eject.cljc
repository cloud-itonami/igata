(ns igata.methods.magnesium-solidify-eject
  "magnesium_solidify_eject.cljc — 鋳型 magnesium-HPDC solidification/eject cell
  decision contract (`:igata_solidification_eject` in README's 8-cell table;
  :magnesium-hpdc manufacturing cell per
  scripts/hermes-magnesium-systems-bots/system-scope.edn on com-junkawasaki
  origin/main).

  Cell input -> output per the README cell table: castShotRecord ->
  ejectedPartRecord. This module adds the executable decision layer between
  the melt-and-shot contract (src/igata/methods/magnesium_hpdc.cljc) and the
  post-cast QC contract: it models activity -> decision -> effect -> audit for
  solidification monitoring and part ejection on a magnesium (AZ91D / AM50 /
  AM60 class) shot, plus the traceability handoff record that closes the
  castShotRecord -> ejectedPartRecord chain.

  The bot may design and simulate; it may NOT command physical equipment —
  an ejector / die-temperature-control command is refused unconditionally.

  Hazard boundaries encoded (hot magnesium part, hot die, residual dust):
    - a cooling medium touching the hot magnesium part must be declared;
      water or any water-based medium is refused outright (molten/hot Mg +
      water contact hazard — same boundary as the melt contract)
    - die and part temperatures are MEASURED inputs; this module never
      substitutes a literature or assumed solidification constant
    - hot-part handling and dry dust collection interlocks must be declared
      before a plan is approved (a freshly ejected Mg part is hot, and
      runners/overspray carry combustible Mg dust)
    - human approval is required for every hazardous step (solidify / eject) —
      absence defers, never approves
    - the ejectedPartRecord inherits traceability from a named castShotRecord;
      a handoff without that upstream link is refused (MES chain must not fork)

  Constitutional parity with manifest.edn gates: G2 (alloy family traceable
  back to the shot record), G4 (witness quorum >= 2 robot signers).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private required-interlocks
  #{:dry-dust-collection :no-water-contact :machine-guard-verified
    :hot-part-handling-verified})
(def ^:private recognized-mg-alloy-families #{"AZ91D" "AM50" "AM60"})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
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

;; ── activity 1: solidify-and-eject plan (hazardous — human approval) ───────

(defn plan-solidify-and-eject
  "One magnesium solidify-and-eject activity.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none):
    :activity/id              string
    :cast-shot-record-id      string — upstream MES link from the melt-and-shot
                              contract; the handoff must not fork the chain
    :alloy                    {:family string :composition-disclosed? boolean}
                              (G2; must match a recognized Mg family)
    :measured-die-temp-c      number — measured at the die, not a literature value
    :measured-part-eject-temp-c number — measured at ejection
    :cooling                  {:medium string :contact-with-part boolean}
                              — a water or water-based medium touching the hot
                              Mg part is refused outright
    :interlocks               collection of interlock keywords (dry dust
                              collection, no-water-contact, machine guard,
                              hot-part handling)
    :ejector-stroke-mm        number — measured/programmed machine setting;
                              recorded, never optimized by the bot
    :ejection-force-kgf       number — measured; recorded, never invented
    :witness-robot-dids       vector of >=2 robot DIDs (G4)
    :human-approval           {:approver-did string :approved-at string
                               :scope #{:solidify :eject} — must cover both
                               hazardous steps}
    :requested-effect         :simulate-plan (the only admissible kind) or
                              :command-ejector (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        cooling-medium (str/lower-case (str (get-in req [:cooling :medium])))
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a solidify-and-eject activity needs an :activity/id")

          (not (present? (get req :cast-shot-record-id)))
          (do (note :cast-shot-record-link-required)
              "traceability: an ejectedPartRecord inherits from a named castShotRecord; a handoff without the upstream MES link is refused (the chain must not fork)")

          (not (and (map? (get req :alloy))
                    (contains? recognized-mg-alloy-families
                               (get-in req [:alloy :family] ""))))
          (do (note :g2-magnesium-alloy-family)
              (str "G2-magnesium: alloy family must be one of "
                   (pr-str (sort recognized-mg-alloy-families))
                   " and traceable back to the shot record; got "
                   (pr-str (get-in req [:alloy :family]))))

          (not (true? (get-in req [:alloy :composition-disclosed?])))
          (do (note :g2-composition-disclosed)
              "G2: alloy composition must be fully disclosed")

          (not (number? (get req :measured-die-temp-c)))
          (do (note :measured-die-temp-required)
              "unmeasured: :measured-die-temp-c is required and must be measured at the die; this module never substitutes a literature solidification constant")

          (not (number? (get req :measured-part-eject-temp-c)))
          (do (note :measured-part-eject-temp-required)
              "unmeasured: :measured-part-eject-temp-c is required and must be measured at ejection")

          (not (and (map? (get req :cooling))
                    (present? (get-in req [:cooling :medium]))
                    (boolean? (get-in req [:cooling :contact-with-part]))))
          (do (note :cooling-medium-declared)
              "safety: the cooling medium and whether it contacts the hot Mg part must be declared")

          (and (get-in req [:cooling :contact-with-part])
               (or (str/includes? cooling-medium "water")
                   (str/includes? cooling-medium "aqueous")))
          (do (note :cooling-medium-not-water-based)
              "safety: a water or aqueous medium touching hot magnesium is refused outright (explosive contact hazard)")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (and (number? (get req :ejector-stroke-mm))
                    (number? (get req :ejection-force-kgf))))
          (do (note :ejector-settings-measured)
              "unmeasured: :ejector-stroke-mm and :ejection-force-kgf are required measured machine settings; this module never invents or optimizes them")

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs >= " g4-min-witness-robots
                   " robot signers per record"))

          (= :command-ejector (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? #{:solidify :eject}
                                 (set (map keyword
                                           (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              "human-approval: solidification control and ejection are hazardous operations; a named human approver with :solidify and :eject scope must be recorded — absence defers, never approves")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:cast-shot-record-id (:cast-shot-record-id req)
                                  :alloy (:alloy req)
                                  :measured-die-temp-c (:measured-die-temp-c req)
                                  :measured-part-eject-temp-c
                                  (:measured-part-eject-temp-c req)
                                  :cooling (:cooling req)
                                  :interlocks (sort (set (map keyword
                                                              (:interlocks req))))
                                  :ejector-stroke-mm (:ejector-stroke-mm req)
                                  :ejection-force-kgf (:ejection-force-kgf req)
                                  :witness-robot-dids (vec (:witness-robot-dids req))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved ""
                              (conj @gates :human-approval-approved
                                    :no-physical-command)
                              effect)}))))

;; ── activity 2: ejected-part handoff record (traceability, non-hazardous) ──

(defn record-ejected-part-handoff
  "Close the castShotRecord -> ejectedPartRecord MES chain for one ejected
  part. This is a pure traceability record, not a machine action: the decision
  is :approved when the chain link and G4 witness quorum hold, :refused
  otherwise. It never approves a physical handoff of a hot part — operators
  handle that; this only records it.

  `rec` keys:
    :activity/id              string
    :cast-shot-record-id      string — upstream link (required, non-forking)
    :ejected-part-record-id   string — the new record id
    :alloy-family             string — must match the shot record's family
    :shot-alloy-family        string — family as attested on the shot record
    :witness-robot-dids       vector of >=2 robot DIDs (G4)
    :measured-part-eject-temp-c number — carried from the ejection plan"
  [rec]
  (let [activity-id (get rec :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a handoff record needs an :activity/id")

          (not (present? (get rec :cast-shot-record-id)))
          (do (note :cast-shot-record-link-required)
              "traceability: :cast-shot-record-id is the upstream MES link; a handoff without it is refused")

          (not (present? (get rec :ejected-part-record-id)))
          (do (note :ejected-part-record-id-required)
              "traceability: :ejected-part-record-id names the new record in the chain")

          (not= (get rec :alloy-family) (get rec :shot-alloy-family))
          (do (note :g2-alloy-family-continuity)
              (str "G2-continuity: ejected part alloy family "
                   (pr-str (get rec :alloy-family))
                   " must match the shot record's "
                   (pr-str (get rec :shot-alloy-family))))

          (not (number? (get rec :measured-part-eject-temp-c)))
          (do (note :measured-part-eject-temp-required)
              "unmeasured: :measured-part-eject-temp-c must be carried from the measured ejection plan; never assumed")

          (not (>= (count (remove str/blank? (map str (get rec :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs >= " g4-min-witness-robots
                   " robot signers per record"))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :traceability-record
                    :effect/machine-command false
                    :effect/record {:cast-shot-record-id (:cast-shot-record-id rec)
                                    :ejected-part-record-id
                                    (:ejected-part-record-id rec)
                                    :alloy-family (:alloy-family rec)
                                    :measured-part-eject-temp-c
                                    (:measured-part-eject-temp-c rec)
                                    :witness-robot-dids
                                    (vec (:witness-robot-dids rec))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved ""
                              (conj @gates :traceability-chain-intact
                                    :g4-witness-quorum)
                              effect)}))))
