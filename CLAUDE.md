# com-etzhayyim-igata — CLAUDE.md

## Identity

- **Name**: igata (鋳型 — die-casting die/mold)
- **DID**: `did:web:etzhayyim.com:igata`
- **ADR**: ADR-2605261200 (R0 master, 2026-05-26)
- **Status**: R0 scaffold — all 8 cells import-time RuntimeError
- **Sibling Tier-B actors**: wadachi, tatekata, watatsumi, yakushi, silicon Wave 1+2 (no parent; peer of these)
- **Methodology source**: YouTube `y0oF2UirEMk` (IDRA Giga Press build-from-scratch documentary). Manufacturing methodology adopted, military / aerospace application explicitly rejected per Charter Rider §2(a).

## Architecture

8 Pregel cells arranged in linear Phase sequence (matches physical megacasting workflow):

```
alloy_melt → die_preparation → shot_injection → solidification_eject
   (naphtali)    (zebulun)         (joseph)            (joseph)
                                                          |
                                                          v
   post_cast_qc → trim_machining → heat_treatment → part_attestation
      (levi)         (simeon)           (dan)            (levi)
```

Each cell = 1 Pregel graph with super-step semantics (4–5 LangGraph nodes per cell). Cells communicate via lexicon records on MST (`com.etzhayyim.igata.*` record types).

## Robotics Fleet

**R0 uses inherited kuni-umi + silicon Wave 2 classes** (no igata-specific hardware in R0):

| Robot | Class | Function | Firmware |
|---|---|---|---|
| Otete (heat-resistant) | chem-resist + thermal arm | molten ladle, die spray | `kuni-umi.otete.firmware` (open-source Rust) |
| Mimi (dimensional + CT) | metrology | post-cast inspection | `kuni-umi.mimi.firmware` (open-source) |
| Hitogata (R2+) | class-A clean humanoid | HT loading | deferred to R2 ADR |
| Funamori (marine, R1+) | bulk cargo marine | Al ingot international transport | `silicon-supply.funamori.firmware` (ADR-2605242745) |
| Hibachi (R2+, new) | high-T die-spray | igata-native | new firmware (R2 ADR designs) |
| Tatara (R2+, new) | melt-furnace tending + degassing | igata-native | new firmware (R2 ADR designs); echoes silicon fuigo 鞴 |

**CRITICAL**: All firmware open-source (Apache 2.0 + Charter Rider) per G3. No proprietary control loops, no closed alloy recipes, no closed injection profiles.

## Constitutional Gates (G1–G14)

**IMMUTABLE in R0..R3.** Stored in canonical `manifest.edn` under `:igata/constitutional-gates`. Changes require Council Lv6+ supermajority + new ADR.

See `ADR-2605261200` for full definitions. Key enforcement:

- **G1**: HPDC clamping force **≤6000 ton** in R0..R3. Giga press class (≥7500 ton, IDRA OL 9000) = N1 deferral.
- **G2 + G3**: Al-Si alloy 5-element baseline (Al + Si + Mg + Mn + Fe + trace Sr/Ti) + open-source process parameters (injection profile @ 1 kHz, die thermal management, gate velocity, vacuum-assist pressure profile).
- **G4**: Witness quorum — `partAttestation` signed by ≥2 distinct robot DIDs (Mimi + Otete) Ed25519.
- **G6**: §2(a) clearance — no military vehicle / aerospace fuselage / armor / firearms structural parts.
- **G7=NONE**: No OPCW Schedule compounds in raw materials or die release agents. yakushi Wave 1c parity.
- **G8**: Shot replay determinism — full injection profile (position + velocity + pressure + temp) logged @ 1 kHz, WASM state-machine sealed.
- **G9**: Induction + electric melting only. Energy ≤4 kWh/kg cast R3.
- **G10**: Aluminum scrap recovery ≥95% (sprue + runner + reject + chip + die-spray residue).
- **G11**: Operator vetting — Adherent SBT + 危険物取扱主任者-equivalent for >500 ton operations.
- **G12**: Production rate ≤1 large part / 90 sec R3 (Wellbecoming + anti-Taylorism).
- **G13**: Murakumo mesh 30-day prior notice + 1 km community feedback.
- **G14**: `partAttestation` includes full lineage CIDs (alloy + die + shot + QC + HT + machining) + IPFS-pinned photo + material balance log.

## Non-Goals (N1–N10)

**EXCLUDED from R0–R3 scope** (explicitly documented so future phases cannot violate):

- N1: Giga press class (≥7500 ton). Post-R3 + Council Lv6+ supermajority required.
- N2: Military vehicle / aerospace fuselage / armor / hull plating. **NEVER** (§2(a) constitutional).
- N3: Firearms / ammunition / shell casing / projectile body. **NEVER** (§2(a)).
- N4: Nuclear containment Class 1/2/3. **NEVER** (radiological).
- N5: Hazmat pressure vessel (LPG/CNG/H₂/chemical reactor). kuni-umi-S6 chemistry carve-out.
- N6: Proprietary alloy "secret sauce". **NEVER** (§2(e) anti-gatekeeping).
- N7: Human-occupied vehicle structural cert (R0..R2 prohibited; R3 = full Council audit).
- N8: Mass-market consumer for external sale. SBT↔SBT internal carve-out only.
- N9: Fossil-fired melting (gas reverberatory, oil burner). **NEVER** (G9 invariant).
- N10: State defense subsidy / military procurement. **NEVER** (§2(a) + §2(i)).

## Lexicon Namespace

**App lexicon root**: `com.etzhayyim.igata`

**Records** (5 types):

1. **`com.etzhayyim.igata.alloyAttestation`** — Al-Si melt lot (composition, mass, source ingot, certifications, OPCW Schedule scan result, RoHS scan result)
2. **`com.etzhayyim.igata.dieAttestation`** — Die geometry CAD CID (vendor-free: FreeCAD/OpenSCAD/Open CASCADE) + machining history + thermal cycle count + life-cycle status
3. **`com.etzhayyim.igata.castShotRecord`** — Per-shot injection profile @ 1 kHz + vacuum-assist pressure + die temperature distribution + final outcome (success/reject/anomaly)
4. **`com.etzhayyim.igata.partAttestation`** — Final part with full lineage chain (alloy CID + die CID + shot CID + QC CID + HT CID + machining CID + final part photo IPFS + material balance log)
5. **`com.etzhayyim.igata.silenIgataReview`** — Council Lv6+ baseline review record (R2+ HPDC ≥2500 ton activation gate, parallel to yakushi `silenPharmaReview` + silicon `silenForceReview`)

**Deferred to R1+**: Full lexicon schema definitions. R0 uses stub placeholders.

## Pregel Cells (Detailed)

### igata_alloy_melt

- **Murakumo node**: naphtali (melt + raw material specialist)
- **Input**: `rawIngotIds` (DIDs of incoming ingots), `recipeUri` (alloy composition target CID)
- **Output**: `alloyAttestation` record
- **LangGraph nodes** (placeholder in R0):
  1. `verify_ingot_provenance` — check rawIngotIds against G7 invariant (OPCW + RoHS + radioactive scan)
  2. `induction_melt` — induction furnace control (G9 energy budget tracked)
  3. `composition_assay` — ICP-MS / OES verification against recipe (5-element + trace)
  4. `degassing_holding` — H₂ purge (rotary degasser) + holding furnace transfer
  5. `emit_record` → write `alloyAttestation` to MST (witness: Tatara R2+ + operator)

### igata_die_preparation

- **Murakumo node**: zebulun (die-prep specialist)
- **Input**: `dieId` (die DID from `dieAttestation` chain), `lubricantBatch` (G7-clear release agent lot)
- **Output**: `dieReadyRecord`
- **LangGraph nodes**:
  1. `load_die_design` — fetch CAD from vendor-free source (FreeCAD `.fcstd` / OpenSCAD / Open CASCADE)
  2. `thermal_cycle_check` — verify die life count + crack detection (dye-penetrant)
  3. `preheat_to_target` — die temperature ramp (typical 180-260°C for Al-Si)
  4. `lubricant_spray` — Otete + Hibachi (R2+) spray + blow-off (Charter Rider §2(g) clear release agent only)
  5. `emit_record` → `dieReadyRecord`

### igata_shot_injection

- **Murakumo node**: joseph (shot + structural specialist)
- **Input**: `alloyAttestation` + `dieReadyRecord`
- **Output**: `castShotRecord` (sensor-stream-witnessed)
- **LangGraph nodes**:
  1. `verify_clamp_force` — clamping force ≤6000 ton (G1 invariant)
  2. `slow_phase` — slow injection plate motion (typical 0.1-0.3 m/s for Al-Si)
  3. `fast_phase` — fast injection (typical 2-6 m/s; gate velocity tracked)
  4. `intensification_phase` — pressure intensification (typical 80-120 MPa for structural Al-Si)
  5. `emit_record` → `castShotRecord` (full @ 1 kHz log per G8)

### igata_solidification_eject

- **Murakumo node**: joseph (same node as injection)
- **Input**: `castShotRecord`
- **Output**: `ejectedPartRecord`
- **LangGraph nodes**:
  1. `dwell` — solidification dwell (typical 3-10 sec for ≤5 kg Al-Si)
  2. `die_open` — die opening (clamp release + ejector advance)
  3. `eject` — ejector pin actuation + Otete part removal
  4. `cooling_path` — controlled cool (water spray or natural; HT-free invariant verified)
  5. `emit_record` → `ejectedPartRecord`

### igata_post_cast_qc

- **Murakumo node**: levi (verification specialist)
- **Input**: `ejectedPartRecord`
- **Output**: `qcAttestation`
- **LangGraph nodes**:
  1. `dimensional_inspection` — Mimi metrology arm + 3D scan (compare against CAD)
  2. `xray_ct_porosity` — Mimi X-ray CT (R2+ subsystem); porosity classification per ASTM E155
  3. `mechanical_sample` — tensile/yield/elongation on companion test bar (per shot or per N-shot batch)
  4. `surface_visual` — defect detection (cold shut, flow line, gas blister, crack)
  5. `emit_record` → `qcAttestation` (G4 witness: Mimi + Otete Ed25519)

### igata_trim_machining

- **Murakumo node**: simeon (finishing specialist)
- **Input**: `qcAttestation`
- **Output**: `trimmedPartRecord`
- **LangGraph nodes**:
  1. `sprue_runner_trim` — Otete shear or saw (recovered scrap → G10 mass balance)
  2. `flash_removal` — vibratory deburring or manual finish
  3. `post_machining_decision` — branch: as-cast vs CNC (datum-feature requirement)
  4. `cnc_post_machine` — if needed: vendor-free CAM (LinuxCNC + FreeCAD path)
  5. `emit_record` → `trimmedPartRecord`

### igata_heat_treatment

- **Murakumo node**: dan (HT specialist)
- **Input**: `trimmedPartRecord`
- **Output**: `heatTreatedRecord` (T5 / T6 / HT-free per part spec)
- **LangGraph nodes**:
  1. `ht_decision` — branch: HT-free (Tesla AS3-equivalent open-recipe) vs T5 (artificial age) vs T6 (solution + quench + age)
  2. `solution_treat` — if T6: solutionize 500-540°C, hold per gauge
  3. `quench` — water or polymer quench (residual stress vs distortion tradeoff logged)
  4. `age_treat` — T5 or T6 age 150-200°C, hold per spec
  5. `emit_record` → `heatTreatedRecord` + companion hardness/yield verification

### igata_part_attestation

- **Murakumo node**: levi (final verification + attestation)
- **Input**: `heatTreatedRecord` (chains back to alloy + die + shot + QC + HT + machining)
- **Output**: `partAttestation` + IPFS-pinned final photo + material balance log
- **LangGraph nodes**:
  1. `lineage_assembly` — collect all upstream CIDs into a single `partAttestation.lineage` field
  2. `final_visual` — Otete + Mimi take photo + dimensional summary
  3. `ipfs_pin` — pin final photo + lineage record bundle to IPFS (per ADR-2605241500 dataset CID substrate)
  4. `material_balance_compute` — sum scrap recovery (sprue + runner + reject + chip + die-spray) → ≥95% G10 invariant
  5. `emit_record` → `partAttestation` + downstream emit to consumer actor (wadachi / tatekata / watatsumi / silicon Wave 2)

## Build & Deploy (R0 → R1)

**R0 status**: Scaffold only. No real HPDC. All 8 cells raise `RuntimeError("igata R0 scaffold: activate via Council ADR-2605261215 post-ratification")` on import.

**R1 activation trigger**:
1. ADR-2605261215 authored + Council Lv6+ vote
2. SME (HPDC engineer + metallurgist) onboarded (Council attestation gate)
3. Hibachi PoC firmware tested in benchtop
4. ≤500 ton bench-scale HPDC machine procured (open-source + Charter Rider compliant — preferring Buhler / Idra second-hand revivable with open-source firmware retrofit, NOT new-build vendor lock-in)
5. Cell source replaces RuntimeError with LangGraph stub bodies

**Deployment**:
```bash
cd orgs/etzhayyim/com-etzhayyim-igata
e7m actor deploy .
```

(Returns error in R0; waits for R1 ADR activation.)

## Testing (R0)

**Smoke test**: Verify that all 8 cells import without exception (Python `__init__.py` imports succeed, but `RuntimeError` raised in `cell.py` body on Council-gate check):

```bash
cd orgs/etzhayyim/com-etzhayyim-igata
python -c "from kotodama.cells.igata_alloy_melt import IgataAlloyMeltCell" 2>&1 | grep -q RuntimeError && echo "✓ R0 gate active"
python -c "from kotodama.cells.igata_die_preparation import IgataDiePreparationCell" 2>&1 | grep -q RuntimeError && echo "✓ R0 gate active"
# ... 6 more
```

All 8 cell modules should fail import with `RuntimeError("igata R0 scaffold-only ...")` after R0 commit (yakushi Wave 1 pattern).

## Cross-Actor Wire (R2+ activation)

| Consumer | Cell wire | R-phase |
|---|---|---|
| wadachi | `vehicle_body_assembly` ← `igata.part_attestation` | R3 only (G11 + N7 gate) |
| tatekata | `structural_assembly` ← `igata.part_attestation` | R2 pilot OK |
| watatsumi | `hull_ring_fabrication` ← `igata.part_attestation` (≤200m class only) | R3 only |
| silicon Wave 2 | `silicon_packaging` ← `igata.part_attestation` (bidirectional) | R2 bidirectional |

R0 = declaration only. Actual lexicon record flow activates at consumer's R-phase gate.

## Related Files

- `manifest.edn` — DID + cell registry + constitutional gates
- `/90-docs/adr/2605261200-igata-megacasting-tier-b-actor-r0.md` — Full R0 master ADR
- `/20-actors/silicon/README.md` — Sibling Tier-B (Funamori marine inheritance + iwakura/fuigo naming root)
- `/20-actors/watatsumi/README.md` — Sibling Tier-B (YouTube methodology adoption + military exclusion precedent)
- `/20-actors/yakushi/README.md` — Sibling Tier-B (14 gates + 10 non-goals canonical pattern)
- `/CLAUDE.md` — Status table row 45 (igata, post-watatsumi)
- `/CHARTER-RIDER.md` — §2(a) weapons, §2(e) anti-gatekeeping, §2(g) sustainability, §2(h) circular economy
