# ADR-0003 — kanjō×kabuto supply-chain dependency graph + visualization

- Status: accepted (R1 increment)
- Date: 2026-06-27
- Extends: ADR-0002 (atproto layer — `com.etzhayyim.kanjo.dependency` lexicon);
  kabuto 兜 ADR-2606022000 (world public-company supply-chain graph).
- Actor: `did:web:etzhayyim.github.io:com-etzhayyim-kanjo`

## Context

kanjō is, by charter, "the financials face of `kabuto` 兜" and shares the `org.corp.*`
id space. kabuto holds the world's first-class **supply edges** (supplier→customer,
with tier / commodity / criticality) — 361 in its seed graph across 1,719 listed
companies. kanjō holds the **disclosed financials** (722 SEC filings / 9,327 facts).
Neither, alone, answers the task's "supply-chain の依存関係を ingest, 分析, 可視化":
the dependency *structure* lives in kabuto, the *scale* (who actually moves what
revenue) lives in kanjō. This ADR joins them.

## Decision

`methods/depgraph.cljc` — a pure cell that loads kabuto's `:company` + `:supply.edge`
rows and kanjō's `:revenue` facts (shared `org.corp.*` keys) and emits:

1. **Dependency records** (`out/depgraph/dependencies.kotoba.edn`) — one
   `com.etzhayyim.kanjo.dependency`-shaped edge per supply edge, carrying the
   filer-disclosed criticality, commodity, both company names, a
   `:dep/customer-disclosed?` flag (does kanjō have the customer's financials?), and
   `:sourcing`. Publishable through the ADR-0002 guarded atproto path.

2. **Dependency metrics (依存関係 分析)** — per company:
   - `out-crit` = Σ criticality of edges where it is the **supplier** → how much the
     rest of the graph depends ON it (systemic-chokepoint intensity);
   - `in-crit` = Σ criticality where it is the **customer** → how dependent it is.
   The report ranks the most depended-upon suppliers; the seed graph correctly
   surfaces **TSMC (Σ 8.75, 12 customers), CATL, ASML, Arm, NVIDIA** — the real
   semiconductor / battery chokepoints.

3. **Visualization (可視化)** — Graphviz DOT (`world-supply.dot` full,
   `world-supply-core.dot` = edges incident to the top-20 suppliers, readable).
   Nodes coloured by sector, sized/labelled by **disclosed revenue** (kanjō) with a
   gold border, or market-cap fallback (kabuto); edge width = disclosed criticality.
   Render: `dot -Tsvg out/depgraph/world-supply-core.dot -o core.svg`.

## Constitutional frame (inherited, not weakened)

- **G2 non-adjudicating.** `out-crit` is an OBSERVATION of disclosed/representative
  supply intensity so buyers can **diversify a fragile chain** — a resilience +
  transparency map, **never a ranking of "importance", a rating, or a target list**.
  The G2 sentence is printed on the canvas itself and asserted by the test suite.
- **G5 sourcing honesty.** Every edge carries `:sourcing`; kabuto supply edges are
  `:representative` (inferred from public disclosure, NOT a bill of materials).
- **Coverage honesty.** Σ is bounded by what is ingested — NOT a world total. Of 335
  incident companies only 14 currently carry disclosed financials (kanjō is
  EDGAR-heavy; most chokepoint suppliers are non-US) — stated in every output.

## Consequences

- The two observation-layer actors compose into one **world-economy dependency map**
  grounded in primary disclosure, with a publishable social surface.
- Closing the coverage gap (disclosed financials for the non-US chokepoints — TSMC,
  ASML, CATL via EDINET / EU OAM / TWSE) is exactly task #3 (world-universe ingest);
  the depgraph already has the slots and will light up as ingest expands.

Invariants: `tests/test_depgraph.cljc` (chokepoint metric, core subgraph, provenance
join, G2-frame-on-canvas).
