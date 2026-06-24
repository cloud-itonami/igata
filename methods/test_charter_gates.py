#!/usr/bin/env python3
"""igata 鋳型 — structural charter-gate conformance tests over the central lexicons.

ADR-2605261200. igata is the HPDC (high-pressure die-casting) megacasting actor. Its
constitutional gates G1–G14 (manifest igata:constitutionalGates) are operationalized at the
schema layer: G6/N2 no military/aerospace/armor parts; G7 no OPCW-schedule / RoHS / radioactive
raw materials; G4 witness quorum (≥2 robot DIDs); G9 induction/electric melt + PFAS-free
water-based die release; G8 shot-replay determinism; G14 full lineage CID chain. This is the
first executable check that pins those gates (igata had NO dedicated charter-gate test — the
only mention is a passing reference in an e7m-sim USD-scene test).

Standalone-runnable (`python3 test_charter_gates.py`) AND pytest-compatible; pure stdlib.
"""
from __future__ import annotations

import json
import os

# G6/N2 — these must never be representable part types.
MILITARY_TOKENS = ("military", "armor", "armour", "fuselage", "firearm", "weapon",
                   "munition", "warhead", "hull-plating", "missile", "gun")
DIE_MATERIALS = {"H13-hot-work-tool-steel", "anviloy-1150-W-base-R3+"}


def _lex_dir():
    d = os.path.dirname(os.path.abspath(__file__))
    while d != "/":
        cand = os.path.join(d, "00-contracts", "lexicons", "com", "etzhayyim", "igata")
        if os.path.isdir(cand):
            return cand
        d = os.path.dirname(d)
    raise FileNotFoundError("could not locate 00-contracts/lexicons/com/etzhayyim/igata")


LEX = _lex_dir()


def _load(name):
    with open(os.path.join(LEX, name)) as f:
        return json.load(f)


def _required_union(doc):
    s = set()

    def walk(o):
        if isinstance(o, dict):
            r = o.get("required")
            if isinstance(r, list):
                s.update(r)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(doc)
    return s


def _known(doc, field):
    out = set()

    def walk(o, parent=None):
        if isinstance(o, dict):
            if "knownValues" in o and parent == field:
                out.update(o["knownValues"])
            for k, v in o.items():
                walk(v, k)
        elif isinstance(o, list):
            for v in o:
                walk(v, parent)
    walk(doc)
    return out


# ── G6 / N2 — no military / aerospace / armor part type ──
def test_g6_no_military_part_type():
    parts = _known(_load("partAttestation.json"), "partType")
    assert parts, "partAttestation must enumerate partType"
    low = {p.lower() for p in parts}
    for tok in MILITARY_TOKENS:
        assert not any(tok in p for p in low), f"G6/N2: part type '{tok}' must not be representable"


# ── G7 — raw-material clearance scans mandatory ──
def test_g7_raw_material_scans_required():
    req = _required_union(_load("alloyAttestation.json"))
    for field in ("opcwScheduleScanPassed", "rohsScanPassed", "radioactiveScanPassed", "g7Scan"):
        assert field in req, f"G7: alloyAttestation must require {field}"


# ── G4 — witness quorum (≥2 robot DIDs) on every attestation ──
def test_g4_witness_quorum_on_all_records():
    for name in ("alloyAttestation.json", "castShotRecord.json", "dieAttestation.json", "partAttestation.json"):
        assert "witnessRobotDids" in _required_union(_load(name)), f"G4: {name} must require witnessRobotDids"


# ── G9 — PFAS-free water-based die release; bounded die materials ──
def test_g9_die_is_pfas_free_water_based():
    doc = _load("dieAttestation.json")
    req = _required_union(doc)
    for field in ("pfasFree", "waterBased", "lubricantFormulationG7"):
        assert field in req, f"G9: dieAttestation must require {field}"
    mats = _known(doc, "dieMaterial")
    assert mats == DIE_MATERIALS, f"die material must be exactly {DIE_MATERIALS}, got {mats}"


# ── G8 — shot-replay determinism (full sensor profile @ 1 kHz logged) ──
def test_g8_shot_replay_determinism():
    req = _required_union(_load("castShotRecord.json"))
    for field in ("sensorStreamCid", "shotProfile", "slowPhase", "fastPhase",
                  "intensificationPhase", "pressureMpa", "velocityMs", "clampingForceTons"):
        assert field in req, f"G8: castShotRecord must require {field} (deterministic shot replay)"


# ── G14 — full lineage CID chain + material balance on the part ──
def test_g14_part_lineage_chain():
    req = _required_union(_load("partAttestation.json"))
    for field in ("alloyAttestationCid", "castShotRecordCid", "dieAttestationCid",
                  "qcAttestationCid", "lineage", "finalPhotoIpfsCid", "materialBalance", "recoveryRatio"):
        assert field in req, f"G14: partAttestation must require {field}"


# ── G11 — operator vetting (operatorDid present on melt/shot/part) ──
def test_g11_operator_attributed():
    for name in ("alloyAttestation.json", "castShotRecord.json", "partAttestation.json"):
        assert "operatorDid" in _required_union(_load(name)), f"G11: {name} must require operatorDid"


def _run():
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for fn in fns:
        fn()
    print(f"igata/charter_gates: {len(fns)} tests passed (lex dir: {os.path.relpath(LEX)})")


if __name__ == "__main__":
    _run()
