# ADR-0002 — kanjō atproto social layer (profile · actor · disclosure post)

- Status: accepted (R1 increment)
- Date: 2026-06-27
- Supersedes/extends: ADR-2606032000 (kanjō foundation); the kotoba-native lexicons
  in CLAUDE.md ("path-reserved; lexicon JSON lands with R1").
- Actor: `did:web:etzhayyim.github.io:com-etzhayyim-kanjo` · `at://kanjo.etzhayyim.com`

## Context

kanjō already ingests primary financial disclosure (SEC EDGAR live: 722 filings /
9,327 `:authoritative` facts; JP EDINET seed), normalizes cross-GAAP concepts, and
persists a content-addressed kotoba Datom graph to **DataLad + IPFS**
(CID `bafybeiae7xbotq4m2m55mycpsh3qrn4g67xz52dporyf4sfxoj6hcj7quq`). Its `did.json`
already declares an **AT Protocol identity** (PDS `pds.etzhayyim.com`, AozoraAppView),
but there was **no atproto record vocabulary and no publishing path** — the disclosed
facts had no social surface. This ADR adds that surface.

## Decision

A pure, deterministic `methods/atproto.cljc` cell composes three AT Protocol surfaces
from the disclosed-fact graph, plus custom lexicons under
`00-contracts/lexicons/com/etzhayyim/kanjo/`:

| Surface | $type | Role |
|---|---|---|
| **Profile** | `app.bsky.actor.profile` | the actor's public face (fixed mission text) |
| **Disclosure** | `com.etzhayyim.kanjo.disclosure` | one disclosed fact, structured + provenance-anchored |
| **Dependency** | `com.etzhayyim.kanjo.dependency` | one DISCLOSED economic-dependency edge (supply-chain face, ADR-0003) |
| **IntelReport** | `com.etzhayyim.kanjo.intelReport` | IPFS pointer to the coverage-bounded report |
| **Social post** | `app.bsky.feed.post` | human-readable transparency line, embeds the disclosure record |

### Constitutional enforcement (by construction)

- **G1 primary-disclosure only.** A post can only carry a fact already in the graph;
  `:source` is an enum of EDINET / EDGAR / Companies House / EU OAM. No other input is
  representable.
- **G2 non-adjudicating / G4 no advice.** `assert-clean` rejects any *machine-composed*
  outward string containing rating/valuation/forecast/buy-sell language — English on
  **word boundaries** (so `ope-rating income` never trips `rating`), Japanese on
  substring (買い推奨 / 目標株価 / 業績予想 / 割安 …). The per-company `post-text` is the
  one risk surface and always passes the guard; the fixed human-reviewed *profile*
  mission statement (which legitimately negates these terms — "no ratings…") is
  exempt by design. Verified by `tests/test_atproto.cljc`.
- **G5 sourcing honesty.** Every record carries `:sourcing` verbatim from the fact.
- **G8 provenance.** Every record carries the source filing's IPFS `docCid`.
- **G11 history-not-deletion.** A restatement sets `supersededBy`; the prior record is
  retained — never deleted.

### Determinism / resume-safety

The record key is a **content hash** (FNV-1a → hex, `rkey`) of the fact id, so the same
fact always yields the same rkey — re-running the cell is idempotent (same shape as the
`autorun` heartbeat, no clock or PRNG in the pure path; `createdAt` is passed in).

## Publishing path (operator-gated, mirrors G7)

`-main` writes `out/atproto/{profile.json, disclosures.jsonl, posts.jsonl,
publish-manifest.json}`. The actual `com.atproto.repo.applyWrites` push to
`pds.etzhayyim.com` is the **operator/Council follow-up** (no token; self-issued CACAO
identity per the workspace's kotoba-server model) — composition is offline and safe;
only the outward write crosses the gate.

## Consequences

- kanjō's disclosed facts are now publishable to the AT Protocol firehose / AozoraAppView
  as a transparency feed — no advice, no verdicts, fully sourced.
- The `dependency` lexicon reserves the supply-chain / world-economy surface (ADR-0003,
  `methods/depgraph.cljc`) so dependency edges publish through the same guarded path.
- Standard Bluesky clients render the posts; the embedded custom record carries the
  structured fact for `com.etzhayyim.kanjo.*`-aware AppViews.
