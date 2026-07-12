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

- `cell:kanjo.atproto` → `methods/atproto.cljc` (ADR-0002) — composes the actor's **AT Protocol**
  surface from the disclosed-fact graph: an `app.bsky.actor.profile` PROFILE, structured
  `com.etzhayyim.kanjo.disclosure` records (one per disclosed fact, provenance + IPFS doc-cid), and
  `app.bsky.feed.post` SOCIAL POSTS (headline subset, each embedding its disclosure record). **G2/G4
  by construction**: `assert-clean` rejects any machine-composed outward string carrying rating /
  valuation / forecast / buy-sell language (English on word boundaries so "operating income" never
  trips "rating"; Japanese on substring). Deterministic / resume-safe — record key is a content hash
  (FNV-1a) of the fact id, `createdAt` passed in (no clock/PRNG in the pure path). Offline
  composition is safe; the `com.atproto.repo.applyWrites` push to `pds.aozora.app` is the
  operator/Council follow-up (self-issued CACAO, no token). Invariants in `tests/test_atproto.cljc`.

  ```bash
  bb -e '(load-file "methods/kanjo_edn.cljc")(load-file "methods/atproto.cljc")(require (quote [kanjo.methods.atproto :as a]))(a/-main)'
  # → out/atproto/{profile.json, disclosures.jsonl, posts.jsonl, publish-manifest.json}
  ```

- `cell:kanjo.depgraph` → `methods/depgraph.cljc` (ADR-0003) — the **supply-chain / world-economy
  DEPENDENCY** face. Joins kabuto 兜's first-class supply edges (supplier→customer, shared
  `org.corp.*`) with kanjō's disclosed `:revenue` so the global dependency graph is anchored to
  filed numbers. Emits (1) `com.etzhayyim.kanjo.dependency` records, (2) per-company dependency
  metrics — `out-crit` Σ criticality as SUPPLIER (systemic-chokepoint intensity) / `in-crit` as
  customer — ranking the most depended-upon suppliers (seed correctly surfaces TSMC·CATL·ASML·Arm·
  NVIDIA), and (3) a **Graphviz DOT visualization (可視化)** — `world-supply.dot` (full) +
  `world-supply-core.dot` (top-20 readable), nodes coloured by sector, gold-bordered + revenue-sized
  where kanjō-disclosed, edges by criticality. **G2 non-adjudicating**: a resilience/transparency map
  so buyers diversify — NOT a ranking, rating, or target list (the G2 line is printed on the canvas
  and asserted). kabuto edges are `:representative` (G5). Coverage-honest (Σ ≠ world total).
  Invariants in `tests/test_depgraph.cljc`.

  ```bash
  bb -e '(load-file "methods/kanjo_edn.cljc")(load-file "methods/depgraph.cljc")(require (quote [kanjo.methods.depgraph :as d]))(d/-main)'
  dot -Tsvg out/depgraph/world-supply-core.dot -o out/depgraph/world-supply-core.svg   # 可視化
  ```

  Coverage-anchoring seed: `data/seed-supply-chokepoints.kotoba.edn` — a bounded `:representative`
  seed (10 filings / 23 facts) of disclosed FY2024 headline figures for the NON-US supply chokepoints
  the dependency graph surfaced (TSMC·ASML·Arm 20-F via EDGAR; DENSO·Shin-Etsu·Tokyo Electron·
  Advantest·Lasertec·DISCO·Murata 有報 via EDINET). depgraph merges it with `facts.merged` so those
  systemic nodes carry disclosed scale (coverage 14→24 companies). KRW/CNY/SEK filers (Samsung, SK
  Hynix, CATL, Ericsson) are deferred until kanjō's unit set covers them — NOT because they matter
  less (stated in the seed header; honest gap). Full EDINET/EU-OAM/exchange universe stays G7-gated.

  **Multi-currency + G1 source extension (ADR-0004):** unit set now `{… :krw :cny :sek :twd}` and
  Tier-A sources now include `:dart` (KR 전자공시) + `:cninfo` (CN 巨潮资讯) — official primary-disclosure
  systems, analogous to EDINET/EDGAR (NOT terminals). Samsung/SK Hynix/CATL/Ericsson now ingestable
  (coverage 28). No cross-currency Σ (per-currency aggregates only).

  **IPFS-pinned (2026-06-28, local repo + pin):** depgraph `QmUtXqPsx8PgVqQLTCtQf1sX6XwT7vwmEXuCALm5TkivGz` ·
  atproto `QmVfX9P5LxMeBdTRYj1TgrGPqn2xc2sHLmjejijYQak2sW` · chokepoint-seed
  `Qmco8mv9Zaa6YB6RUPypm4zyhnt7baTx8NaXDCNaybUc6j` · lexicons `QmZEtLrZsrtELMdWuvH49SPdZdH2DamNJnozbUT2PzqgW4` ·
  manifest `QmTNbk4Yx7XVsJUBS9s7tnyDBGCMYybrgxR3MhW3RyHVW9` (`data/coverage-manifests/kanjo-depgraph-manifest.json`).

  **Autonomous self-publish — operator gate LIFTED (ADR-0006).** kanjō holds its own Ed25519 key
  (IPFS keystore `kanjo-actor`, gitignored) and self-publishes its graph to its key-derived IPNS name
  `/ipns/k51qzi5uqu5dlamopaa4ntg8yd587dhhla9apq1tmnufraaagomozsjm8rkgc1` — no operator, no token
  (owner directive 2026-06-28). Re-publish: **`bb methods/publish.bb --live`** — a babashka cell built
  on the SHARED **kototama actor lib** (`../../com-junkawasaki/kototama/lib/actor` — `actor.gates` /
  `actor.atproto` / `actor.identity`; ADR kototama-0002): regenerate → re-assert `gates/assert-no-advice`
  on every post → bundle → `ipfs name publish --key=kanjo-actor`. (omit `--live` for dry-run.) The
  G1/G2/G4/G5 content guards are UNCHANGED — autonomy widens who may press send, not what may be said.
  Standard-PDS mirror (`pds.aozora.app`) needs an app-password (credential, not policy). Public
  identity: `data/kanjo-identity.public.edn`.

  **Government⟷economy join (ADR-0005, design):** keizu 系図 government money → `org.corp.*` payee →
  kabuto supply → kanjō financials, once keizu adds a `:money/payee-corp` bridge (its payees are opaque
  today). depgraph already joins on `org.corp.*` and is structured to overlay the keizu money export.

## Lexicons (AT Protocol — `00-contracts/lexicons/com/etzhayyim/kanjo/`)

Shipped (ADR-0002): **`disclosure`** (one disclosed fact), **`dependency`** (one disclosed
economic-dependency edge — supply-chain face, ADR-0003), **`intelReport`** (IPFS pointer to the
coverage-bounded report). The kotoba-native write lexicons
(`registerFiling,registerFinancialFact,publishConceptDictionary,publishIntelReport`) remain
path-reserved alongside.

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
