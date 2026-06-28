# ADR-0005 — government⟷economy join (keizu money ⟕ kabuto supply ⟕ kanjō financials)

- Status: design (R1) — cross-actor; implementation gated, see below
- Date: 2026-06-28
- Actors: **keizu 系図** (government power-relations / money) · **kanjō 勘定** (corporate
  financials) · **kabuto 兜** (corporate supply chain). Shared `org.corp.*` id space.
- Relates: keizu charter (G1 public-role-only, G2 non-adjudicating, G3 ≥2 sources),
  kanjō ADR-0003 (depgraph), ADR-0004 (multi-currency).

## The gap

The user's vision — "政府の情報を ingest し、世界経済・supplychain の依存関係につなぐ" — needs
government money to reach the corporate dependency graph. It currently **cannot**:
keizu's `:money/payee` is an **opaque disclosed-name node** (`"jp-vendor-x"`), and the
seed has **zero** `org.corp.*` references. So "ministry → vendor" and "company → supplier"
live in two disconnected graphs. kanjō/kabuto already share `org.corp.*`; keizu does not
join it. This ADR specifies the bridge.

## Decision — a `:money/payee-corp` resolution edge (keizu side)

Add an OPTIONAL resolved corporate link on keizu money flows, leaving `:money/payee`
(the disclosed payee name as filed) untouched:

```clojure
{:money/id "m-award-jp-1" :money/payer "jp-meti" :money/payee "jp-vendor-x"
 :money/kind :procurement-award :money/amount 1.2e9 :money/currency "JPY"
 ;; NEW — resolved only when the payee is a LISTED company in the org.corp.* space:
 :money/payee-corp "org.corp.jp.somevendor"
 :money/payee-corp-confidence :exact            ; :exact | :probable | :unresolved
 :money/payee-corp-sourcing :synthesized        ; the RESOLUTION is derived, G5-honest
 :money/sources [...]}                           ; resolution carries its own ≥2 sources (G3)
```

**Resolution discipline (constitutional):**
- **G1 public-role / no-doxxing PRESERVED.** A payee resolves to `org.corp.*` ONLY when it
  is a listed legal entity (the same public-company space kabuto/kanjō hold). Private
  individuals and unlisted payees stay `:unresolved` — the bridge can never de-anonymize a
  person. Resolution is entity↔entity, never entity↔individual.
- **G5 sourcing.** The resolution is `:synthesized` (a derived mapping), never re-asserted
  as a disclosed fact; it carries ≥2 sources (G3) like every keizu edge.
- Built by a new keizu cell `cell:keizu.payee_resolve` (LEI / company-register match;
  Murakumo-only narration). Offline + dry-run in R0; live resolution G8-gated.

## The unified query (read-side, no new store)

With the bridge, one join spans all three actors over `org.corp.*`:

```
keizu  :money  (payer=organ) ──payee-corp──▶  C : org.corp.*
kabuto :supply.edge          C ──depends-on──▶ S (supplier, criticality)
kanjō  :fin.fact :revenue    C, S ──disclosed scale──▶
```

Answerable observations (all **non-adjudicating**, G2 — a *resilience + accountability*
map, never an allegation):
- **public-money → fragile-chain exposure**: government funds flowing into companies that
  sit on a single-source chokepoint (e.g. subsidy → fabless co. whose sole foundry is a
  Σdep-8.7 node) — a supply-resilience signal for the funder, not a verdict on anyone;
- **procurement concentration by ultimate supplier**: many ministries → many vendors that
  all converge on ONE upstream supplier (systemic single point of failure in public spend);
- **money-vs-disclosed-scale**: award size read against the payee's disclosed revenue
  (transparency ratio, never a fairness/efficiency judgement).

## Visualization (可視化) — depgraph overlay

`depgraph.cljc` gains an OPTIONAL government-money edge class: when a keizu money export
is supplied, payer-organ nodes (distinct shape/colour) draw `payer ─funds→ payee-corp`
edges into the existing supply graph, so public money and supply dependency render on one
canvas. The G2 frame already printed on the canvas covers it; organ nodes are seats/
ministries (G1 public-role), never people.

## Constitutional compatibility (all three charters hold)

| Gate | keizu | kanjō | kabuto | join |
|---|---|---|---|---|
| non-adjudicating | G2 | G2 | G2 | ✅ observation only |
| accountability/resilience map, not target-list | G5 | G2 | G2(N) | ✅ |
| public-role / public-company only | G1 | G1 | listed-only | ✅ no private persons |
| ≥2 sources / sourcing-honest | G3 | G5 | G5 | ✅ resolution :synthesized |
| outward live ingest/post gated | G8 | G7 | G7 | ✅ stays gated |

## Implementation order (each step a future loop increment, gated where noted)

1. keizu: add `:money/payee-corp*` attrs to `government-relations-ontology.kotoba.edn` +
   `moneyFlowObservation` lexicon (schema only — no behaviour change). **ungated.**
2. keizu: `cell:keizu.payee_resolve` over the `:representative` seed (offline). ungated.
3. keizu: export resolved money edges as EDN (`com.etzhayyim.keizu.moneyFlowObservation`).
4. kanjō: `depgraph.cljc` reads the optional keizu export → overlay + a
   `government-exposure-report.md`. ungated (offline).
5. Live LEI/register resolution + live posting — **G8/G7 Council + operator gated.**

## Status

Design accepted; **no code shipped in this ADR** (the payee opacity is in keizu's data
model, so step 1 is a keizu-side schema change — recorded here as the cross-actor contract
and cross-referenced from keizu). kanjō's depgraph is already structured to accept the
overlay (it joins on `org.corp.*` today). The honest current state: government and economy
graphs remain **disconnected** until step 1–2 land.
