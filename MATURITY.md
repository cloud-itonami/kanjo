# kanjō 勘定 — Maturity Scorecard

Honest R0 status. **This is a proof-of-model, not coverage.** Coverage is gated by
`:sourcing` (G5): only `:authoritative` rows (parsed from a filing's XBRL) count as
real coverage. The seed ships **zero** `:authoritative` rows — every fact is
`:representative` (a public *headline* figure, rounded, not the line-item-exact XBRL).
The point of R0 is to prove the vocabulary + the cross-GAAP normalization end-to-end,
not to have ingested the world's 決算. Per ADR-2606032000.

## Seed contents (R0, 2026-06-03)

`data/seed-financial-facts.kotoba.edn`:

| Vocabulary | Count | All `:representative`? |
|---|---|---|
| `:fin.filing/*` | **6** — JP EDINET 有報 ×4 (Toyota ×2, Sony, Nintendo) + US SEC EDGAR 10-K ×2 (Apple, Microsoft) | yes |
| `:fin.fact/*` | **36** disclosed line items (BS + PL + CF) | yes |
| companies | **5** real filers (shared `org.corp.*` id space with kabuto) | — |
| `:fin.concept/*` | **17** canonical concepts (BS / PL / CF / per-share), `concept_map.py` | `:synthesized` (normalization layer) |

## Coverage breadth (what the proof-of-model demonstrates)

| Axis | Coverage in seed |
|---|---|
| Accounting standards | **3** — IFRS (Toyota, Sony) · JGAAP (Nintendo) · US-GAAP (Apple, Microsoft) — all normalized onto the same canonical concepts |
| Currencies | **2** — JPY, USD (cross-currency aggregates deliberately NOT summed — no FX layer at R0) |
| Statements | **3** — BS (貸借対照表) · PL (損益計算書) · CF (キャッシュフロー) |
| Sectors (joined from kabuto `:company`) | automotive · electronics · consumer · software |
| As-of history (非終末論) | Toyota FY2023 + FY2024 → YoY computed; prior facts retained, never deleted |
| JGAAP-only concept | 経常利益 (`:ordinary-income`) recorded where filed (Nintendo), NOT cross-compared (concept_map note) |

## Tests

`tests/test_kanjo.py` — **10/10 passed** (stdlib, runnable as `python3 tests/test_kanjo.py`
or under pytest). Proves: cross-GAAP revenue normalization · 經常利益 = JGAAP-only ·
derived ratios match disclosed arithmetic · YoY only with ≥2 years · aggregates never
cross-currency · metrics are `:synthesized` · EDGAR parser (base→millions, annual-only
filter) · EDINET parser (unmapped dropped, ordinary-income kept) · EDN round-trip.

## Cells

| Cell | Status |
|---|---|
| `concept_map` | **runnable** — 17-concept dictionary + reverse index; unit-tested |
| `ingest` (EDGAR companyfacts + EDINET element parsers) | **runnable offline** (parsers unit-tested); live `--fetch-edgar CIK` is **G7** Council+operator gated |
| `analyze` (ratios + YoY + aggregates) | **runnable** — emits `out/intel-report.md` + `out/financial-metrics.kotoba.edn`; unit-tested |

## What is NOT done (by design at R0)

| Question | Status |
|---|---|
| All public companies' 決算 ingested? | **NO** — 5 filers (proof-of-model). The world has tens of thousands of listed filers. |
| Any `:authoritative` row in the seed? | **NO** — every seed fact is `:representative` (headline, rounded). Authoritative requires the line-item XBRL via `ingest.py`, which is **G7** gated. |
| Full EDINET / EDGAR universe XBRL parse? | **NO** — the EDGAR-companyfacts + EDINET-element parsers run offline on supplied JSON; full-universe fetch + EDINET XBRL-XML (taxonomy versioning, context dimensions) is **R1**, G7 Council+operator gated. |
| FX cross-currency comparison? | **NO** — deliberately omitted; a future FX layer must itself be sourcing-honest (which rate, as-of when). |
| Live atproto / kotoba-server publish? | **NO** — lexicons defined (`com.etzhayyim.kanjo.*`, validator-clean); serving is R1+. |
| Restatement (訂正) chains populated? | **NO** — `:fin.fact/superseded-by` modeled + documented; no 訂正報告書 in the seed yet. |

## Path to R1

1. Implement the EDINET XBRL-XML parser (beyond the pre-extracted-element adapter).
2. First `:authoritative` rows: a bounded **G7-gated** EDGAR/EDINET fetch of the seed
   filers, replacing their `:representative` headline figures with line-item XBRL.
3. FX-normalization layer (sourcing-honest, as-of-dated) to enable cross-currency aggregates.
4. Wire `com.etzhayyim.kanjo.*` publish to the kotoba-server.
