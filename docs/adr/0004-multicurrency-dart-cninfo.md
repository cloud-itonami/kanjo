# ADR-0004 — multi-currency support + G1 Tier-A source extension (DART · CNINFO)

- Status: accepted (R1 increment)
- Date: 2026-06-28
- Extends: ADR-2606032000 (kanjō foundation, G1 primary-disclosure), ADR-0003 (depgraph).

## Context

The kanjō×kabuto dependency graph (ADR-0003) ranked the world's systemic supply
chokepoints, but four of the very top — **Samsung Electronics, SK Hynix** (memory),
**CATL** (batteries), **Ericsson** (RAN) — could not be ingested: kanjō's unit set was
`{:jpy :usd :eur :gbp}` and its G1 Tier-A source list was `{:edinet :edgar
:companies-house :eu-oam}`. These filers report in **KRW / CNY / SEK** and disclose
through **DART** (KR) / **CNINFO** (CN) / **EU OAM** (SE). Leaving them out meant the
world-economy map was silent on memory, batteries, and telecom infrastructure.

## Decision

1. **Currency set extended** to `{… :krw :cny :sek :twd}` with display glyphs
   (₩ / 元 / kr / NT$) in all three render paths (`atproto`, `depgraph`, `analyze`
   `ccy-sym`) and in the `com.etzhayyim.kanjo.disclosure` lexicon `unit` enum. The
   **no-cross-currency-Σ** rule (R0, no FX layer) is UNCHANGED — aggregates remain
   per-currency, so adding currencies cannot create a misleading mixed-unit sum.

2. **G1 Tier-A source list extended** with `:dart` and `:cninfo`. Rationale: G1's
   principle is *"read the filing, never the terminal."* **DART** (대한민국 금융감독원
   전자공시시스템) and **CNINFO** (巨潮资讯网, the CSRC-designated disclosure portal) are
   **official statutory primary-disclosure systems** — exactly analogous to EDINET and
   SEC EDGAR, and categorically different from the prohibited vendor terminals
   (Bloomberg / CapIQ / Orbis). Ingesting a 사업보고서 from DART or an 年度报告 from CNINFO
   is reading the primary filing, so it is admissible under G1, not a weakening of it.
   `:eu-oam` (already approved) covers Ericsson via Sweden's Transparency-Directive OAM.

## Data

`data/seed-supply-chokepoints.kotoba.edn` gains 4 filers / 14 `:representative` facts:
Samsung (₩300.9tn rev), SK Hynix (₩66.2tn), CATL (元362bn), Ericsson (kr248bn). All
FY2024 headline figures, rounded, `:representative` (G5) — the authoritative DART/CNINFO
XBRL parse stays G7 operator+Council gated. Disclosed-financial coverage 24 → 28.

## Consequences

- The dependency map now anchors memory (Samsung/SK Hynix), batteries (CATL), and
  telecom (Ericsson) — the chokepoints whose absence was previously a stated gap.
- Honest residual: TWD is wired but TSMC is still recorded in its USD 20-F figures;
  many more non-US filers remain unParsed (full DART/CNINFO/EDINET universe = G7).
- Guarded by `tests/test_depgraph.cljc` (extended source set; KRW unit; CATL joins).
