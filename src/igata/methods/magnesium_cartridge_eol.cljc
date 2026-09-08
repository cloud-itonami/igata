(ns igata.methods.magnesium-cartridge-eol
  "magnesium_cartridge_eol.cljc — 鋳型 magnesium-hydrogen powertrain cell:
  spent MgH2 cartridge end-of-life decision contract (:cartridge-eol-and-recovery
  manufacturing cell, scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  Back-end partner to magnesium-cartridge-handling (which covers intake /
  inert storage / integration-charge). This module governs what happens AFTER
  the cartridge is depleted by the reactor: depressurization and venting,
  opening and emptying under inert gas, and routing the spent material toward
  magnesium recovery (G10 scrap recovery ≥ 95%, Charter Rider §2(h) circular
  economy), supplier take-back, or regulated disposal.

  First-generation boundary (kept): MgH2 synthesis is OUTSOURCED and the
  cartridge is supplied finished-and-sealed; decommissioning of a SPENT
  cartridge is IN scope (explicitly retained in-house per the boundary
  :eol-validation). No synthesis, no desorption control, no new chemistry.

  Models activity -> decision -> effect -> audit for cartridge end-of-life
  activity. The bot may design and simulate; it may NOT command physical
  equipment — a machine command is refused unconditionally.

  Hazard boundaries encoded (residual pressurized hydrogen on a spent
  cartridge / pyrophoric MgH2 + Mg residue):
    - residual hydrogen pressure must be MEASURED on the spent cartridge; an
      opening or emptying step is refused while the measured residual pressure
      exceeds the operator-declared depressurize threshold (no threshold is
      invented here — the caller must declare it)
    - opening / emptying / transfer steps require a declared inert atmosphere
      with a measured O2 reading; air and water-based atmospheres are refused
      outright (water + MgH2 = H2 evolution + exotherm; air + pyrophoric MgH2
      dust = ignition risk)
    - hydrogen leak detection, no-water-contact, static-dissipation and
      pressure-gauge interlocks must be declared before a plan is approved
    - human approval is required for every hazardous step (:depressurize-and-vent
      and :open-and-empty) — absence defers, never approves
    - routing: recycle and supplier take-back are recorded routes; DISPOSAL is
      a regulated-hazardous-waste commitment, so it is deferred to a human
      approver, never authorized by the bot; any sale of recovered magnesium
      material is a financial commitment and is likewise deferred
    - no capacity, cycle time, yield, price or certification constant is
      invented: measured residual pressures / recovered-mass balances are
      supplied by the caller; missing mass-balance and recovery values are
      recorded as :unmeasured, never filled in

  Constitutional parity with manifest.edn gates: G4 (witness quorum ≥ 2 robot
  signers), G10 (scrap recovery ≥ 95%), G14 (full lineage CIDs on every plan).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private required-interlocks
  #{:hydrogen-leak-detector-armed :no-water-contact
    :static-dissipation-verified :pressure-gauge-verified})
(def ^:private required-lineage [:cartridge :reactor])
(def ^:private hazardous-steps #{:depressurize-and-vent :open-and-empty})
(def ^:private recognized-steps
  #{:depressurize-and-vent :open-and-empty :recycle-transfer})
(def ^:private refused-atmosphere-classes #{:air :water :water-based})
(def ^:private sealed-integrities #{:sealed :verified-sealed})
(def ^:private recognized-routes #{:recycle :return :dispose})

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

;; ── activity 1: spent-cartridge decommissioning plan (hazardous) ──────────

(defn plan-cartridge-decommissioning
  "One spent MgH2 cartridge decommissioning activity.

  `req` keys (all measured values must be supplied by the caller; this function
  invents none):
    :activity/id                 string
    :cartridge/id                string
    :cartridge/integrity         :sealed | :verified-sealed (anything else refused)
    :residual-h2-pressure-bar    number — MEASURED residual hydrogen pressure
                                 on the spent cartridge before any opening step
    :depressurize-threshold-bar  number — operator-declared safe threshold to
                                 OPEN (not invented by this module); opening or
                                 emptying is refused while residual > threshold
    :step                        :depressurize-and-vent | :open-and-empty |
                                 :recycle-transfer
    :atmosphere                  {:class :inert :agent \"...\" :measured-o2-ppm number}
                                 — required, with a measured O2 reading, for the
                                 open/empty and transfer steps; :air / :water /
                                 :water-based is refused outright
    :interlocks                  collection of interlock keywords
    :witness-robot-dids          vector of ≥2 robot DIDs (G4)
    :lineage-cids                map {:cartridge :reactor} -> CID strings (G14)
    :human-approval              {:approver-did string :approved-at string
                                  :scope #{...}} — required iff :step is hazardous
    :requested-effect            :simulate-plan (the only admissible kind) or
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
        residual (get req :residual-h2-pressure-bar)
        threshold (get req :depressurize-threshold-bar)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a decommissioning activity needs an :activity/id")

          (not (present? (get req :cartridge/id)))
          (do (note :cartridge-id-present)
              "cartridge-id: the decommissioning subject needs a :cartridge/id")

          (not (contains? recognized-steps step))
          (do (note :step-recognized)
              (str "step: must be one of " (pr-str (sort (vec recognized-steps)))
                   "; got " (pr-str step)))

          (not (contains? sealed-integrities (get req :cartridge/integrity)))
          (do (note :cartridge-integrity-recognized)
              "cartridge-integrity: a spent cartridge must be :sealed or :verified-sealed before decommissioning; an unknown-integrity cartridge is refused outright")

          (not (number? residual))
          (do (note :residual-pressure-measured)
              "residual-h2-pressure-bar: the residual hydrogen pressure on the spent cartridge must be MEASURED and supplied; this module never assumes a value")

          ;; opening / emptying while still pressurized: refuse unless residual ≤ threshold
          (and (contains? #{:open-and-empty :recycle-transfer} step)
               (not (number? threshold)))
          (do (note :depressurize-threshold-declared)
              "depressurize-threshold-bar: the operator-declared safe-to-open threshold must be supplied before an opening / emptying step (not invented by the bot)")

          (and (contains? #{:open-and-empty :recycle-transfer} step)
               (> residual threshold))
          (do (note :residual-pressure-unsafe-to-open)
              (str "safety: measured residual H2 pressure " residual
                   " bar exceeds the declared depressurize threshold "
                   threshold " bar — depressurize-and-vent first; an opening or "
                   "empty step while over threshold is refused"))

          (or (not (map? atmosphere))
              (not (keyword? atmos-class)))
          (do (note :atmosphere-declared)
              "atmosphere: an atmosphere map with a :class keyword must be declared — residual pyrophoric MgH2/Mg is handled only under declared inert gas")

          (contains? refused-atmosphere-classes atmos-class)
          (do (note :atmosphere-not-inert)
              (str "atmosphere: :class " (pr-str atmos-class)
                   " is refused outright — air contact with pyrophoric MgH2 dust or water contact with MgH2 (H2 evolution + exotherm) is a fire/explosion hazard"))

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
                   (pr-str (sort required-interlocks)) " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs ≥ " g4-min-witness-robots
                   " robot signers per record"))

          (seq missing-lineage)
          (do (note :g14-lineage-complete)
              (str "G14: full lineage CIDs required (cartridge + reactor); missing "
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
                   " is a hazardous step (pressurized hydrogen / pyrophoric residue exposure); a named human approver with matching scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:cartridge/id (:cartridge/id req)
                                  :cartridge/integrity (:cartridge/integrity req)
                                  :step step
                                  :residual-h2-pressure-bar residual
                                  :depressurize-threshold-bar (when (contains? #{:open-and-empty :recycle-transfer} step)
                                                                threshold)
                                  :atmosphere {:class atmos-class
                                               :agent (:agent atmosphere)
                                               :measured-o2-ppm (:measured-o2-ppm atmosphere)}
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :lineage-cids (select-keys lineage required-lineage)
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :human-approval-scope (when hazardous? step)}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: spent-material routing (disposal always deferred) ──────────

(defn route-eol-material
  "Decide the routing of spent cartridge material after decommissioning.

  Routing is NOT a financial commitment for the in-house recovery and
  supplier-take-back paths, so :recycle and :return are approved and audited.
  :dispose is a regulated-hazardous-waste commitment and is ALWAYS deferred to
  a human approver — never authorized by this fn. Any monetization of the
  recovered material (selling recovered magnesium for value) is a sale, a
  financial commitment, and is likewise deferred regardless of route.

  Missing mass-balance and recovery-ratio values are recorded as :unmeasured —
  never invented (a recovery claim under G10 ≥ 95% must be measured)."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [:route-recorded :mass-balance-recorded])
        route-kind (get req :route-kind)
        sale-involved (true? (get req :material-sale-involved))]
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: an EOL routing decision needs an :activity/id" @gates)

      (not (contains? recognized-routes route-kind))
      (refuse activity-id
              (str "route-kind: must be one of " (pr-str (sort (vec recognized-routes)))
                   "; got " (pr-str route-kind))
              @gates)

      (not (present? (get req :recovery-route)))
      (refuse activity-id
              "recovery-route: a named recovery / take-back / disposal channel is required so the routing is auditable"
              @gates)

      ;; disposal = regulated hazardous-waste commitment -> always deferred
      (= route-kind :dispose)
      (let [evidence {:effect/kind :deferred-human-approval
                      :effect/financial-commitment false
                      :effect/routing {:cartridge/id (:cartridge/id req)
                                       :route-kind :dispose
                                       :recovery-route (:recovery-route req)
                                       :mass-balance-recorded-g
                                       (or (get req :mass-balance-recorded-g) :unmeasured)
                                       :recovery-ratio (or (get req :recovery-ratio) :unmeasured)
                                       :hazardous-waste-manifest :pending-human}}]
        {:decision :deferred
         :effect evidence
         :audit (audit-record activity-id :deferred
                              "disposal is a regulated hazardous-waste commitment; deferred to a human approver (hazardous-waste manifest required)"
                              (conj @gates :regulatory-commitment :human-approval-required)
                              evidence)})

      ;; sale of recovered material = financial commitment -> deferred
      sale-involved
      (let [evidence {:effect/kind :deferred-human-approval
                      :effect/financial-commitment false
                      :effect/routing {:cartridge/id (:cartridge/id req)
                                       :route-kind route-kind
                                       :recovery-route (:recovery-route req)
                                       :material-sale-involved true
                                       :mass-balance-recorded-g
                                       (or (get req :mass-balance-recorded-g) :unmeasured)
                                       :recovery-ratio (or (get req :recovery-ratio) :unmeasured)}}]
        {:decision :deferred
         :effect evidence
         :audit (audit-record activity-id :deferred
                              "sale of recovered magnesium is a financial commitment; deferred to a human approver"
                              (conj @gates :no-financial-commitment :human-approval-required)
                              evidence)})

      :else
      (let [evidence {:effect/kind :routing-approved
                      :effect/financial-commitment false
                      :effect/routing {:cartridge/id (:cartridge/id req)
                                       :route-kind route-kind
                                       :recovery-route (:recovery-route req)
                                       :recovery-ratio (or (get req :recovery-ratio) :unmeasured)
                                       :mass-balance-recorded-g
                                       (or (get req :mass-balance-recorded-g) :unmeasured)}}]
        {:decision :approved
         :effect evidence
         :audit (audit-record activity-id :approved ""
                              (conj @gates :g10-recovery-considered :no-financial-commitment)
                              evidence)}))))