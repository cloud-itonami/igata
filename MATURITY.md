# igata 鋳型 — Maturity

**Stage: R0/R1** (scaffold + benchtop) — ADR-2605261200. HPDC megacasting actor (clamping
≤6000 ton in R0..R3; giga-press class N1-deferred). Induction/electric melt only, ≥95% scrap
recovery, witness-quorum attestation, full lineage CID chain. Civilian only (no military/
aerospace/armor parts).

| Dimension | State |
|---|---|
| Lexicons | ✅ 5 under `com.etzhayyim.igata.*` (alloyAttestation / castShotRecord / dieAttestation / partAttestation / silenIgataReview) |
| Cells | 🟡 8 path-reserved in `40-engine/.../cells/igata_*` (verify_ingot_provenance / induction_melt / …) |
| Manifest | ✅ present (carries `igata:constitutionalGates` G1–G14) |
| Tests | ✅ `test/igata/methods/test_charter_gates.cljc` and repository contracts — **9 tests / 50 assertions, green** — pins G6/G7/G4/G9/G8/G14/G11 schema gates; `bb run_tests.clj` |
| Methods | ⛔ no offline engine yet (R1 benchtop 500-ton loop) |

## Charter gates pinned by the test (manifest igata:constitutionalGates G1–G14)

- **G6/N2 no military** — `partAttestation.partType` enum carries no military / aerospace
  fuselage / armor / firearm / hull-plating / missile token.
- **G7 raw-material clearance** — `alloyAttestation` requires `opcwScheduleScanPassed` +
  `rohsScanPassed` + `radioactiveScanPassed` + `g7Scan`.
- **G4 witness quorum** — every attestation (alloy / shot / die / part) requires `witnessRobotDids`
  (≥2 robot DIDs, Mimi + Otete).
- **G9 PFAS-free water-based die** — `dieAttestation` requires `pfasFree` + `waterBased` +
  `lubricantFormulationG7`; `dieMaterial` enum is exactly {H13-hot-work-tool-steel, anviloy-1150-W-base-R3+}.
- **G8 shot-replay determinism** — `castShotRecord` requires `sensorStreamCid` + `shotProfile`
  + slow/fast/intensification phases + `pressureMpa` + `velocityMs` + `clampingForceTons` (logged @ 1 kHz).
- **G14 full lineage** — `partAttestation` requires the alloy/cast/die/qc attestation CIDs +
  `lineage` + `finalPhotoIpfsCid` + `materialBalance` + `recoveryRatio`.
- **G11 operator vetting** — melt / shot / part records require `operatorDid`.

## R0 → R1 gate

silenIgataReview `r1-benchtop-500ton-baseline` + Council Lv6+ supermajority; cell `.solve()`
stays R0-gated (no live actuation) until then. G1 clamping ≤6000 ton + G12 rate ≤1 part/90 s
are value invariants enforced in the R1 cell logic (not schema-expressible).
