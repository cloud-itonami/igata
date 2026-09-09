(ns igata.methods.magnesium-cartridge-handling
  "magnesium_cartridge_handling.cljc — 鋳型 magnesium-hydrogen powertrain cell:
  inert MgH2 cartridge handling decision contract (:cartridge-handling
  manufacturing cell, scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  First-generation boundary (kept): MgH2 synthesis is OUTSOURCED — this module
  receives and handles finished, sealed MgH2 cartridges only. Cartridge
  integration (charging a sealed cartridge into a reactor), inert-atmosphere
  storage, and intake inspection remain IN scope. No synthesis, no desorption
  control, no chemistry is modeled here.

  Models activity -> decision -> effect -> audit for cartridge handling
  activity. The bot may design and simulate; it may NOT command physical
  equipment — a machine command is refused unconditionally.

  Hazard boundaries encoded (pyrophoric MgH2 dust / hydrogen evolution on
  hydrolysis / pressurized hydrogen containment):
    - cartridges must be SEALED with an intact seal record; a seal breach or
      \"unknown\" integrity is refused outright
    - an inert-gas enclosure must be declared and purged; a water-based or
      air-based atmosphere is refused outright (water + MgH2 = H2 evolution +
      exotherm; air + pyrophoric MgH2 dust = ignition risk)
    - hydrogen leak detection, no-water-contact and static-dissipation
      interlocks must be declared before a plan is approved
    - human approval is required for the hazardous steps (:intake-inspection
      opens packaging; :integration-charge connects a cartridge to hydrogen
      plumbing) — absence defers, never approves
    - no capacity, cycle time, yield, price or certification constant is
      invented: measured cartridge masses / seal pressures are supplied by the
      caller, and missing price / lead-time / utility / safety / compliance
      values are recorded as :unmeasured, never filled in

  Constitutional parity with manifest.edn gates: G4 (witness quorum ≥ 2 robot
  signers), G14 (full lineage CIDs on every plan).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private required-interlocks
  #{:inert-enclosure-purged :hydrogen-leak-detector-armed :no-water-contact
    :static-dissipation-verified})
(def ^:private required-lineage [:cartridge :receipt])
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private hazardous-steps #{:intake-inspection :integration-charge})
(def ^:private recognized-steps #{:intake-inspection :storage-stow :integration-charge})
(def ^:private refused-atmosphere-classes #{:air :water :water-based})
(def ^:private sealed-integrities #{:sealed :verified-sealed})

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

;; ── activity 1: cartridge handling plan (hazardous — human approval req.) ──

(defn plan-cartridge-handling
  "One MgH2 cartridge handling activity (finished, sealed cartridge only).

  `req` keys (all measured values must be supplied by the caller; this function
  invents none):
    :activity/id           string
    :cartridge/id          string
    :cartridge/integrity   :sealed | :verified-sealed (anything else refused —
                           a breached or unknown-integrity cartridge must not be
                           moved, inspected or charged)
    :atmosphere            {:class :inert  :agent \"...\"  :measured-o2-ppm number}
                           :class :air / :water / :water-based is refused
                           outright; :inert without an agent name or a measured
                           O2 reading is not verifiable and is refused
    :measured-cartridge-mass-g  number (optional; recorded as :unmeasured when
                           absent — never assumed)
    :measured-seal-pressure-bar number (optional; recorded as :unmeasured when
                           absent — never assumed)
    :lineage-cids          map {:cartridge :receipt} -> content-addressed CID
                           strings (G14 parity)
    :interlocks            collection of interlock keywords
    :witness-robot-dids    vector of ≥2 robot DIDs (G4)
    :human-approval        {:approver-did string  :approved-at string
                            :scope #{:intake-inspection | :integration-charge}}
                           — required iff :step is a hazardous step
    :step                  :intake-inspection | :storage-stow | :integration-charge
    :requested-effect      :simulate-plan (the only admissible kind) or
                           :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        lineage (get req :lineage-cids)
        missing-lineage (remove #(present? (get lineage %)) required-lineage)
        step (get req :step)
        atmosphere (get req :atmosphere)
        atmos-class (get atmosphere :class)
        hazardous? (contains? hazardous-steps step)
        measured-mass (get req :measured-cartridge-mass-g)
        measured-seal-press (get req :measured-seal-pressure-bar)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a cartridge handling activity needs an :activity/id")

          (not (present? (get req :cartridge/id)))
          (do (note :cartridge-id-present)
              "cartridge-id: the handling subject needs a :cartridge/id")

          (not (contains? recognized-steps step))
          (do (note :step-recognized)
              (str "step: must be one of " (pr-str (sort (vec recognized-steps)))
                   "; got " (pr-str step)))

          (not (contains? sealed-integrities (get req :cartridge/integrity)))
          (do (note :cartridge-sealed)
              "cartridge-integrity: only a :sealed or :verified-sealed cartridge may be handled; a breached or unknown-integrity cartridge is refused outright")

          (or (not (map? atmosphere))
              (not (keyword? atmos-class)))
          (do (note :atmosphere-declared)
              "atmosphere: an atmosphere map with a :class keyword must be declared (molten/pyrophoric MgH2 is handled only under declared inert gas)")

          (contains? refused-atmosphere-classes atmos-class)
          (do (note :atmosphere-not-inert)
              (str "atmosphere: :class " (pr-str atmos-class) " is refused outright — air contact with pyrophoric MgH2 dust or water contact with MgH2 (H2 evolution + exotherm) is a fire/explosion hazard"))

          (not= :inert atmos-class)
          (do (note :atmosphere-not-inert)
              (str "atmosphere: :class " (pr-str atmos-class)
                   " is not admissible; only :inert is"))


          (or (not (present? (get atmosphere :agent)))
              (not (number? (get atmosphere :measured-o2-ppm))))
          (do (note :atmosphere-measured)
              "atmosphere: :inert must carry an agent name and a measured :measured-o2-ppm reading; an unmeasured purge is not verifiable")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs ≥ " g4-min-witness-robots " robot signers per record"))

          (seq missing-lineage)
          (do (note :g14-lineage-complete)
              (str "G14: full lineage CIDs required (cartridge + receipt); missing "
                   (pr-str (sort (vec missing-lineage)))))

          (= :command-machine (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (and hazardous?
               (not (and (map? (get req :human-approval))
                         (present? (get-in req [:human-approval :approver-did]))
                         (present? (get-in req [:human-approval :approved-at]))
                         (contains? (set (map keyword (get-in req [:human-approval :scope])))
                                    step))))
          (do (note :human-approval-required)
              (str "human-approval: " (pr-str step)
                   " is a hazardous step (packaging breach / hydrogen plumbing connection); a named human approver with matching scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:cartridge/id (:cartridge/id req)
                                  :cartridge/integrity (:cartridge/integrity req)
                                  :step step
                                  :atmosphere {:class atmos-class
                                               :agent (:agent atmosphere)
                                               :measured-o2-ppm (:measured-o2-ppm atmosphere)}
                                  :measured-cartridge-mass-g (or measured-mass :unmeasured)
                                  :measured-seal-pressure-bar (or measured-seal-press :unmeasured)
                                  :lineage-cids (select-keys lineage required-lineage)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :human-approval-scope (when hazardous? step)}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: cartridge supply screening (procurement — deferred) ────────

(defn screen-cartridge-supply-offer
  "Screen one offer for finished MgH2 cartridge supply. Procurement is a
  financial commitment: the decision is ALWAYS :deferred to a human approver;
  this fn only assembles the auditable evidence record.

  Direct-first rule: offers from the cartridge manufacturer or the
  owner-operated dealer channel carry :route :direct; a reseller/intermediary
  carries :route :intermediary and must name its verified added value — an
  unnamed intermediary premium is recorded, not accepted.

  Condition must be distinguished as \"new\", \"used\", \"refurbished\" or
  \"unknown\". Missing price / lead-time / hydrogen-capacity / safety /
  compliance values are recorded as :unmeasured — never invented."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [:condition-distinguished :source-recorded])
        condition (get offer :condition)
        source-url (get offer :source-url)
        route (get offer :route)]
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: a supply screening needs an :activity/id" @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be one of " (pr-str (sort (vec recognized-conditions)))
                   " — new / used / refurbished / unknown must be distinguished, not assumed")
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: a supply screening needs a recorded :source-url" @gates)

      (and (= route :intermediary)
           (not (present? (get offer :intermediary-verified-value))))
      (refuse activity-id
              "procurement: direct-first rule — an intermediary offer with no verified added value named is refused; go to the cartridge manufacturer or the owner-operated dealer channel first"
              (conj @gates :intermediary-value-unverified))

      :else
      (let [evidence {:effect/kind :screening-evidence-only
                      :effect/financial-commitment false
                      :effect/evidence
                      {:offer/id (:offer/id offer)
                       :source-url source-url
                       :source-kind (:source-kind offer)
                       :route (or route :unmeasured)
                       :condition condition
                       :unit-price-usd (or (get offer :unit-price-usd) :unmeasured)
                       :lead-time-days (or (get offer :lead-time-days) :unmeasured)
                       :hydrogen-capacity-kg (or (get offer :hydrogen-capacity-kg) :unmeasured)
                       :safety-certifications (or (get offer :safety-certifications) :unmeasured)
                       :decision-recommended :deferred-to-human
                       :measured-at (:measured-at offer)}}]
        {:decision :deferred
         :effect evidence
         :audit (audit-record activity-id :deferred
                              "procurement: financial commitment deferred to a human approver"
                              @gates
                              evidence)}))))
