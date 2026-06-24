# kanjō 勘定 — agent reference

> World public-company **financial-disclosure (決算)** knowledge graph. Tier-B, R0 design-only. ADR-2606032000.
> Read the repo-root `CLAUDE.md` first; this file only adds actor-local rules.

## Identity

- **DID**: `did:web:etzhayyim.com:actor:kanjo` (registration in INFRA_ACTORS pending).
- **Glyph**: 勘定 — *reckoning / account*. The public-company financial-facts reader.
- **Role**: the *決算* face of the observation upper layer. kanjō is the **external public-company
  sibling of `toritate` 執帳** (which does etzhayyim's OWN internal accounting), and the **financials
  face of `kabuto` 兜** (which does the supply chain). It reuses the shared `org.corp.*` id space, so
  a company here is the same entity kabuto / tsumugi already hold. Lineage: `kabuto` (supply),
  `tsumugi` (power-graph), `danjo` (public accountability), `kanae` (fiscal flows).

## What kanjō is, in one line

It reads the **numbers a listed company disclosed in its primary filing** (EDINET 有価証券報告書,
SEC EDGAR 10-K) and registers them as content-addressed Datoms, normalized so JP-GAAP / US-GAAP /
IFRS land on the same canonical concepts. It is **not** an analyst, a rater, or an advisor.

## Hard rules (constitutional — do not weaken)

1. **Primary disclosure ONLY (G1).** Ingest only Tier-A primary filings (ADR-2605263800 §2):
   EDINET / SEC EDGAR / Companies House / EU OAM. **Forbidden inputs**: 会社四季報 (a paid,
   copyrighted editorial compilation **with 業績予想 forecasts**) and ALL paid commercial terminals
   — Bloomberg / S&P Capital IQ / Refinitiv / FactSet / Moody's Orbis / D&B / Pitchbook / Crunchbase.
   Per **Charter Rider §2(e)** (anti-gatekeeping) + **§2(c)** (vendor query-tracking). The disclosed
   FACTS are public and admissible; the VENDOR COMPILATION of them is not. **Read the filing, never
   the terminal.**
2. **Non-adjudicating (G2).** kanjō records disclosed facts + transparent ratios. It does **not**
   rate "good/bad", value a company, rule on solvency or fraud, or label anyone. Concentration and
   ratios are observations, not verdicts (sibling of kabuto G4 / danjo).
3. **No investment advice (G4).** NOT 投資助言業 (金商法). No buy/sell/hold, no price targets, no
   ratings, no portfolios. **No forecasting** — reported actuals only. (Forecasting is precisely
   what the prohibited 四季報 adds; kanjō deliberately does not.)
4. **Sourcing honesty (G5).** Every fact/metric carries `:*/sourcing` ∈
   `:authoritative` (parsed from a filing's XBRL) | `:representative` (a public headline figure,
   rounded — the R0 seed) | `:synthesized` (a ratio / YoY / aggregate kanjō computed). Derived
   `:fin.metric` / `:fin.agg` are NEVER re-ingested as disclosed facts. Σ aggregates are coverage-
   bounded — read every one against its `:fin.agg/n`; absence ≠ zero, and they are NOT market totals.
5. **kotoba-native (substrate boundary).** State = kotoba Datom log. No SQL / RisingWave / Lance as
   canonical store. Source XBRL/PDF → DataLad → IPFS (`80-data/corporate-financials`), CID on
   `:fin.filing/doc-cid` (G8 — no git-lfs).
6. **Restatement = history, never deletion (G11, 非終末論).** A 訂正報告書 asserts a NEW fact and
   sets `:fin.fact/superseded-by` on the old one — the prior Datom is RETAINED. Read the truth
   "as-of" a date; there is no single final earnings state.
7. **No market-abuse enablement (G10).** Published filings only — never non-public material facts or
   pre-disclosure insider data (金商法). PII redaction follows ADR-2605263800 §5 (GDPR / 個情法).
8. **Murakumo-only (G6).** Any LLM narration routes through the Murakumo fleet (ADR-2605215000).
9. **Outward-gated INGEST (G7).** Live EDGAR/EDINET fetch requires `KANJO_OPERATOR_GATE=1` + an
   explicit `--fetch-edgar CIK` + Council. R0 ships a bounded `:representative` seed; the full
   EDINET/EDGAR-universe XBRL parse ("register ALL companies' 決算") is **R1**.

## Vocabulary

`00-contracts/schemas/corporate-financials-ontology.kotoba.edn`:
- `:fin.filing/*` — one primary disclosure (source, form 有報/10-K, fiscal-year, period, accounting
  standard, currency, doc-cid). The provenance anchor.
- `:fin.fact/*` — ONE disclosed line item: `:statement` (`:bs|:pl|:cf|:eps`) × `:concept` (canonical)
  × `:value`/`:unit`/`:scale` × `:context` (`:consolidated|:nonconsolidated`). `:concept-raw` keeps
  the source taxonomy element for audit.
- `:fin.concept/*` — the canonical concept DICTIONARY (GAAP-normalization map; `concept_map.py`).
- `:fin.metric/*` — derived ratio / YoY (`:synthesized`, G5). Health observation, not a verdict.
- `:fin.agg/*` — sector / currency aggregate (`:synthesized`, coverage-honest).

Canonical concepts: `:revenue :gross-profit :operating-income :ordinary-income(経常利益, JGAAP-only)
:pretax-income :net-income :total-assets :current-assets :total-liabilities :current-liabilities
:total-equity :cash-and-equivalents :cfo :cfi :cff :capex :eps`.

## Cells

- `cell:kanjo.concept_map` → `methods/concept_map.py` — canonical concept catalogue → element→concept
  reverse index + `:fin.concept` dictionary. Honest about non-comparable standards (経常利益).
- `cell:kanjo.ingest` → `methods/ingest.py` — EDGAR companyfacts JSON + EDINET element JSON → EAVT;
  merge with seed (`:authoritative` wins). Offline default; live fetch G7-gated.
- `cell:kanjo.analyze` → `methods/analyze.py` (stdlib) — per-company ratios → YoY (as-of) →
  sector/currency aggregates → aggregate-first report. No FX cross-currency sums in R0.
- `cell:kanjo.autorun` → `methods/autorun.py` (+ `methods/kotoba.py`). The autonomous
  Murakumo-fleet heartbeat — the same shape shionome/ipaddress/yabai/sukashi/watatsuna/watari/
  kabuto use. Each cycle observes the OFFLINE merged graph → split filings/facts → by-company-year
  → derive ratios + YoY + sector/currency aggregates → **persists a content-addressed transaction**
  (graph datoms + derived `:fin.metric` + `:fin.agg`) to the append-only **local** kotoba Datom log
  (`methods/kotoba.py`), linking the previous tx's CID into a verifiable commit-DAG. Deterministic /
  resume-safe (the derived path uses no PYTHONHASHSEED-randomized set iteration — verified stable
  under `PYTHONHASHSEED=random`); NO external I/O. **G2/G4/G5 hold by construction**: only disclosed
  facts + transparent ratios are representable — every derived metric/agg carries `:sourcing
  :synthesized` and is never re-ingested as a disclosed fact; no rating/valuation/forecast/buy-sell
  attr exists. Fleet cells `kanjo_filing_ingest` (cron 49) + `kanjo_metrics_weave` (cron 54) +
  `kanjo_disclosure_persist` (cron 59) on `judah` — see `50-infra/murakumo/fleet.toml`. Live
  EDGAR/EDINET ingest + the live-node push stay Council + operator gated (G7). Invariants guarded by
  `methods/test_autorun.py` (commit-DAG verify, tamper-detect, determinism, append-only,
  **G5 derived-:synthesized**, **G2/G4 no-advice/no-forecast**, no-external-I/O).

  ```bash
  python3 methods/autorun.py --cycles 3 --fresh   # AUTONOMOUS heartbeat → LOCAL kotoba Datom log
  ```

## Lexicons (kotoba-native)

`com.etzhayyim.kanjo.{registerFiling,registerFinancialFact,publishConceptDictionary,publishIntelReport}`
— `00-contracts/lexicons/com/etzhayyim/kanjo/` (path-reserved; lexicon JSON lands with R1).

## Run

```bash
cd 20-actors/kanjo
python3 methods/concept_map.py          # → out/concept-dictionary.kotoba.edn
python3 methods/ingest.py               # offline: bridge data/ingest/*.json + seed → data/facts.merged.kotoba.edn
python3 methods/analyze.py              # → out/intel-report.md + out/financial-metrics.kotoba.edn
# live (G7-gated):
KANJO_OPERATOR_GATE=1 python3 methods/ingest.py --fetch-edgar 0000320193   # Apple companyfacts
```

`python3 methods/analyze.py` with no argument runs the **seed** graph alone (no ingest needed).

## Honesty (R0)

Bounded `:representative` seed of **6 filings / 36 facts / 5 real filers** (Toyota · Sony · Nintendo
via EDINET; Apple · Microsoft via EDGAR) demonstrating cross-GAAP normalization (IFRS · US-GAAP ·
JGAAP → one canonical vocabulary), JGAAP-only 経常利益, and as-of YoY (Toyota FY2023 + FY2024).
Figures are publicly-documented HEADLINE numbers, **rounded** — not authoritative line-item XBRL.
"Register ALL companies' 決算" is the **R1** goal — full EDINET/EDGAR-universe XBRL parse is **G7**
Council + operator gated. kanjō does not forecast, rate, value, or advise.

## Live ingest — Council-authorised (2026-06-16)

The **G7 gate is OPEN** (founder Lv7+ 1/1). The live EDGAR leg (`70-tools/scripts/coverage-publish/
edgar_batch.py`, curated `ciks.txt`, **additive** merge — never clobbers prior filings) has
populated `data/facts.merged.kotoba.edn` to **722 filings / 9,327 `:authoritative` facts** across
~47 major US filers (primary SEC disclosure only, G1). That graph is persisted on **DataLad + IPFS
+ kotobase.net** via `coverage-publish/publish.py` — IPFS CID
`bafybeiae7xbotq4m2m55mycpsh3qrn4g67xz52dporyf4sfxoj6hcj7quq` (pinned, multi-block dag-pb), DataLad
dataset `80-data/kanjo-coverage`, IPNS `k51qzi5uqu5dhf94…`; kotobase = operator-follow-up (no token,
ADR-2606111330). Pointer: `80-data/coverage-manifests/kanjo-coverage-manifest.json`. Full
EDINET/EDGAR universe (~thousands of filers) remains the continued operator/loop process.
