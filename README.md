# igata (鋳型) — Megacasting / HPDC Tier-B Actor

**DID**: `did:web:etzhayyim.com:igata`
**Namespace**: `com.etzhayyim.igata.*`
**ADR**: ADR-2605261200 (R0 master), ADR-2605261215 (R1, reserved), ADR-2605261230 (R2, reserved), ADR-2605261245 (R3, reserved)
**Status**: R0 scaffold (2026-05-26) — all 8 cells import-time RuntimeError
**Methodology source**: YouTube `y0oF2UirEMk` — IDRA Giga Press build-from-scratch documentary (manufacturing methodology adopted, military application rejected per §2(a))

## Overview

religious-corp first-party **high-pressure die-casting (HPDC) / megacasting** actor. wadachi (vehicle body) + tatekata (construction structural) + watatsumi (submersible reinforcement) + silicon Wave 2 (fab equipment frame) の supplier 位置として、religious-corp が外部 vendor (IDRA / Bühler / Idra-Sayer) に依存せず大型アルミ構造部品を製造可能にする。

**R0 scope**: HPDC clamping force ≤6000 ton, Al-Si alloy 単一片 ≤50 kg (R3 上限). Giga press class (≥7500 ton, IDRA OL 9000) は N1 で post-R3 deferral.

## Why "igata" (鋳型)

**鋳型 = die-casting die/mold**。Megacasting の差別化因子は die そのもの — 9000 ton の clamp force に耐えながら 1.5m × 2.5m の単一片を成形する die 設計が IDRA を IDRA たらしめている。actor name として最も直接的。silicon Wave 1 の iwakura (磐座) + fuigo (鞴) が「inference ASIC」「training ASIC」を kami / 工房道具で名付けたのと同じ系譜。

## Robotics Classes

| Class | Role | Inherited from | Notes |
|---|---|---|---|
| Otete (heat-resistant) | molten metal ladle, die spray, ingot transfer | kuni-umi | R0 reuse; R1+ may need thermal armor ext |
| Mimi (dimensional + X-ray CT) | metrology, porosity inspection | kuni-umi | R0 reuse; R2+ adds X-ray CT subsystem |
| Hitogata (class-A clean) | heat-treatment loading | kuni-umi | deferred to R2 |
| Funamori (marine) | aluminum ingot international transport | silicon Wave 2 | reuse for raw logistics |
| **Hibachi (火鉢)** *(R2+)* | high-temperature die-spray + lubricant | new, igata-native | constitutional design pending R2 ADR |
| **Tatara (踏鞴)** *(R2+)* | melt furnace tending + degassing | new, igata-native | echoes silicon fuigo (鞴) — same kami root, distinct role |

## Pregel Cells (8, all R0 import-time RuntimeError)

| Cell | Murakumo node | Phase | Input → Output |
|---|---|---|---|
| `igata_alloy_melt` | naphtali | melt | rawIngotIds + recipeUri → alloyAttestation |
| `igata_die_preparation` | zebulun | die-prep | dieId + lubricantBatch → dieReadyRecord |
| `igata_shot_injection` | joseph | shot | alloyAttestation + dieReady → castShotRecord |
| `igata_solidification_eject` | joseph | solidify | castShotRecord → ejectedPartRecord |
| `igata_post_cast_qc` | levi | QC | ejectedPart → qcAttestation (X-ray CT + dimensional + mech) |
| `igata_trim_machining` | simeon | trim | qcAttestation → trimmedPart (sprue/runner + CNC) |
| `igata_heat_treatment` | dan | HT | trimmedPart → heatTreated (T5/T6/HT-free) |
| `igata_part_attestation` | levi | attest | heatTreated → partAttestation (final lineage CID + IPFS pin) |

Linear chain (no branching in R0): alloy → die → shot → solidify → QC → trim → HT → final attestation. Matches tatekata 5-cell linear pattern.

## Constitutional Gates (G1–G14)

See ADR-2605261200 for full list. **IMMUTABLE** per R0..R3.

Key gates:
- **G1**: HPDC clamping force ≤6000 ton (giga press class ≥7500 ton = N1 deferral)
- **G2**: Al-Si alloy only, composition fully disclosed (no proprietary closed alloys)
- **G3**: Open-source process parameters under Apache 2.0 + Charter Rider
- **G4**: Witness quorum ≥2 robot signers per partAttestation (Mimi + Otete)
- **G6**: Charter Rider §2(a) clearance — no military / aerospace structural
- **G7**: Alloy 5-element baseline (Al + Si + Mg + Mn + Fe + trace Sr/Ti). No OPCW Schedule, no RoHS, no radioactive. **G7=NONE** wave (yakushi Wave 1c parity)
- **G8**: Shot replay determinism @ 1 kHz injection profile, WASM state-machine sealed
- **G9**: Induction/electric melting only (no fossil-fired). ≤4 kWh/kg cast R3
- **G10**: Scrap recovery ≥95% (sprue + runner + reject + chip + die-spray)
- **G11**: Personnel vetting (Adherent SBT + 危険物取扱主任者 equiv >500 ton)
- **G13**: Murakumo placement 30-day prior notice + 1 km community feedback

## Non-Goals (N1–N10)

Explicitly excluded from R0–R3:

- **N1**: Giga press class (≥7500 ton) — post-R3 + Council Lv6+ supermajority
- **N2**: Military vehicle / aerospace fuselage / armor — **NEVER** (§2(a))
- **N3**: Firearms / ammunition structural — **NEVER** (§2(a))
- **N4**: Nuclear containment Class 1/2/3 — **NEVER** (radiological)
- **N5**: Hazmat pressure vessel (LPG/CNG/H₂, chemical reactor) — kuni-umi-S6 carve-out
- **N6**: Proprietary alloy "secret sauce" — **NEVER** (§2(e))
- **N7**: Human-occupied vehicle structural cert — R3 only (Council Lv6+ audit)
- **N8**: Mass-market consumer for external sale — SBT↔SBT internal only
- **N9**: Fossil-fired melting — **NEVER** (G9 invariant)
- **N10**: State defense subsidy / military procurement — **NEVER** (§2(a) + §2(i))

## Roadmap

| Phase | Timeline | Scope | Murakumo | Gate |
|---|---|---|---|---|
| **R0** | 2026-05-26 | Scaffold. 8 cells RuntimeError. | No deploy | ✅ Proposed (ADR-2605261200) |
| **R1** | post-Council | Benchtop ≤500 ton, parts ≤200 g (bracket/hinge). Single-cavity. | naphtali + zebulun + joseph | ADR-2605261215 + Council Lv6+ + SME (HPDC + metallurgist) + Hibachi PoC |
| **R2** | post-R1 | Pilot ≤2500 ton, parts ≤5 kg (knuckle/control-arm/housing). Multi-cavity + vacuum-assist. | 6 nodes | ADR-2605261230 + 30-day public + 3-shot consistency + Hibachi + Tatara onboard |
| **R3** | post-R2 | Community ≤6000 ton, parts ≤50 kg (cradle/subframe/architectural). Structural cert (G11+N7). | Full 10-node fleet | ADR-2605261245 + 60-day public + 法務 audit + cross-actor wadachi/tatekata/watatsumi Council vote |

## Lexicons (5, R0 stub deferred to R1+)

```
com.etzhayyim.igata.{
  alloyAttestation         # Al-Si melt lot (composition, mass, certifications)
  dieAttestation           # die geometry CAD CID + thermal cycle history
  castShotRecord           # per-shot injection profile + sensor stream
  partAttestation          # final part with full lineage chain
  silenIgataReview         # Council Lv6+ baseline review (R2+ ≥2500 ton activation)
}
```

## Cross-Actor Supply

| Consumer | igata supplies | Wire (R2+) |
|---|---|---|
| wadachi | front/rear underbody, battery tray, motor housing | R3 only (G11+N7 gate) |
| tatekata | aluminum 柱頭/梁継手/curtain-wall structural | R2 pilot OK |
| watatsumi | 耐圧殻 ring section pour-cast 補強材 (≤200m class only) | R3 only |
| silicon Wave 2 | fab equipment frame structural casting | R2 bidirectional |

## Integration

- **Parent actor**: none (peer of wadachi / tatekata / watatsumi / yakushi / silicon Wave 1+2)
- **Methodology source**: YouTube `y0oF2UirEMk` (IDRA giga press documentary, methodology adopted, military rejected per §2(a))
- **Witness quorum**: ADR-2605191524 (≥2 robot Ed25519 sigs + human attestation)
- **Vendor independence chain**: closes the gap for `religious-corp 自前 大型アルミ構造部品` (parallel to silicon Wave 1 自前 GPU, yakushi 自前 OTC, watatsumi 自前 潜水艦)

## References

- `/90-docs/adr/2605261200-igata-megacasting-tier-b-actor-r0.md` — Full ADR
- `/20-actors/kuni-umi/README.md` — Sibling Tier-B (robotics class source)
- `/20-actors/silicon/README.md` — Sibling Tier-B (Funamori marine inheritance + naming convention)
- `/20-actors/watatsumi/README.md` — Sibling Tier-B (YouTube methodology + military exclusion precedent)
- `/CHARTER-RIDER.md` — §2(a) weapons + §2(e) anti-gatekeeping + §2(g) sustainability + §2(h) circular economy
- `/CLAUDE.md` — Religious-corp status table
