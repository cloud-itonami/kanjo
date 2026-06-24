# kanjō 勘定

**World public-company financial-disclosure (決算) knowledge graph.** Tier-B actor · R0 design-only ·
ADR-2606032000 · `did:web:etzhayyim.com:actor:kanjo`.

kanjō reads the numbers a listed company **disclosed in its primary filing** — the balance sheet
(貸借対照表), income statement (損益計算書), and cash-flow statement (キャッシュフロー計算書) — and
registers each line item as a content-addressed kotoba Datom, normalized so that JP-GAAP, US-GAAP,
and IFRS filings land on one comparable set of canonical concepts.

It is the **external public-company sibling of `toritate` 執帳** (etzhayyim's own internal accounting)
and the **financials face of `kabuto` 兜** (the supply-chain graph), sharing the `org.corp.*` id space.

## Sources — primary disclosure only

| Source | Jurisdiction | Form | License | Tier |
|---|---|---|---|---|
| **SEC EDGAR** companyfacts / XBRL | US | 10-K / 20-Q / 20-F | public-domain (17 CFR 200) | A |
| **JP EDINET** XBRL | JP | 有価証券報告書 / 半期 / 四半期 | 金融庁 free-redistribution | A |
| **UK Companies House** | UK | annual accounts | OGL v3.0 | A |
| **EU OAM** Transparency Directive | EU | annual / half-year | per-member-state | A |

**Not ingested** (Charter Rider §2(e) + §2(c)): 会社四季報 (a paid, copyrighted editorial compilation
**with forecasts**) and all paid commercial terminals — Bloomberg, S&P Capital IQ, Refinitiv,
FactSet, Moody's Orbis, D&B, Pitchbook, Crunchbase. The disclosed *facts* are public and admissible;
the vendor *compilation* of them is not. **We read the filing, never the terminal.**

## What it is not

Non-adjudicating (G2) and **no investment advice** (G4): no ratings, no valuations, no buy/sell, no
forecasts. kanjō records what the company disclosed and the transparent arithmetic of it — a
transparency map, never a verdict or recommendation. (See `manifest.jsonld` gates G1–G12, non-goals
N1–N8.)

## Run

```bash
python3 methods/concept_map.py     # canonical concept dictionary
python3 methods/ingest.py          # EDGAR/EDINET → EAVT (offline default; live = G7-gated)
python3 methods/analyze.py         # → out/intel-report.md
```

See [`out/intel-report.md`](out/intel-report.md) for the seed-cohort report and `CLAUDE.md` for the
constitutional rules.

## R0 honesty

Bounded `:representative` seed (6 filings / 36 facts / 5 real filers). Figures are public headline
numbers, rounded — not authoritative line-item XBRL. Full EDINET/EDGAR-universe ingest is the R1
goal, G7 Council + operator gated.
