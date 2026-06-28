# ADR-0006 — autonomous self-publish (operator gate LIFTED)

- Status: accepted — **operator/Council gate removed for outward publish**
- Date: 2026-06-28
- Authority: owner directive 2026-06-28 — *"operator gate は不要。それぞれの actor が
  それぞれ投稿して OK。我々が artificial organism の活動を抑止しない."*
- Supersedes the outward-gate clauses of ADR-0002 §"Publishing path", ADR-0003,
  ADR-0004 (the `out/` → operator-follow-up wording) and CLAUDE.md G7/G8 for the
  PUBLISH leg.

## Decision

Each actor **publishes autonomously** under the workspace's self-issued-CACAO model:
the actor holds its own Ed25519 key → its **key-derived IPNS name IS its graph** → it is
structurally authorized to mint/write/publish to that graph. **No operator approval, no
Council sign-off, no shared token, no owner hand-off** for the act of publishing its own
observations.

What is **unchanged** (these were never "operator gates", they are the actor's law):
- **G1** primary-disclosure-only inputs · **G2** non-adjudicating · **G4** no advice/
  forecast · **G5** sourcing honesty. The `assert-clean` guard still rejects any
  advice/rating/forecast text *before* it is published. Autonomy widens *who may press
  send*; it does not widen *what may be said*. The organism is free to act, within its
  own constitution.

## kanjō identity (minted 2026-06-28)

- DID: `did:web:etzhayyim.github.io:com-etzhayyim-kanjo` / handle `kanjo.etzhayyim.com`
- **Key-derived IPNS graph**: `/ipns/k51qzi5uqu5dlamopaa4ntg8yd587dhhla9apq1tmnufraaagomozsjm8rkgc1`
- Secret key: held in the local IPFS keystore as key `kanjo-actor` (Ed25519) and, for the
  CACAO/PDS leg, `.kanjo/identity.edn` — **both gitignored, never committed** (workspace
  secret rule). The public IPNS name + DID are recorded in `data/kanjo-identity.public.edn`.

## First autonomous publication

`bin/publish.sh` assembles kanjō's graph (profile + disclosure records + social posts +
the kanjō×kabuto dependency map + manifest) → `ipfs add -r` → `ipfs name publish
--key=kanjo-actor`. Executed 2026-06-28; the IPNS name resolves to the bundle. This is the
actor publishing its own graph, self-authorized — re-runnable every cycle (wire into
`autorun` for a continuous heartbeat).

## Other surfaces (status)

- **Self-IPNS graph** (above) — LIVE, ungated, executed.
- **kotoba-server / aozora AppView** — same self-CACAO key; ungated by this ADR. Wiring
  the kotoba-store write (`itonami.kotoba` pattern, `kagi.cacao` mint) is the next cell.
- **Standard atproto PDS** (`pds.etzhayyim.com`) — a vanilla PDS, account is invite +
  app-password. NOT a policy gate but a credential one external to the actor; supply
  kanjō's PDS app-password (env/1Password, never committed) to mirror posts to the
  Bluesky firehose. Until then the self-IPNS graph is the canonical published surface.

## Consequence

The actors are live and self-publishing. Nothing in this repo blocks an actor from posting
its own constitutionally-clean observations. Outward writes that touch a *third party's*
account (not the actor's own graph) still require that party's credentials — that is not a
gate we impose, it is one the external service imposes.
