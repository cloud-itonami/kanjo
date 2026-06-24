#!/usr/bin/env python3
"""kanjō 勘定 — ingest cell: PRIMARY-disclosure → kotoba EAVT 決算 facts.

Bridges primary public-disclosure artifacts into the `:fin.filing/*` + `:fin.fact/*`
vocabulary, normalizing every source taxonomy element onto a canonical concept via
concept_map. Two Tier-A sources (ADR-2605263800 §2):

  • SEC EDGAR  companyfacts JSON  (data.sec.gov/api/xbrl/companyfacts/CIK##########.json)
                 — us-gaap:* facts, public-domain (17 CFR 200)
  • JP EDINET   XBRL              — jppfs_cor / jpcrp_cor facts, 金融庁 free-redistribution
                 (R0 accepts a pre-extracted element JSON; full XBRL-XML parse = R1)

NETWORK DISCIPLINE (G7 + ADR-2605262400 §7 passive-only):
  - DEFAULT = OFFLINE. Reads pre-downloaded files from data/ingest/*.json (no network).
  - LIVE fetch requires BOTH `KANJO_OPERATOR_GATE=1` AND an explicit `--fetch-edgar CIK`.
    Even then it is a single polite request, never an organism-tick scrape.
  - Output facts from live/offline real filings are `:authoritative`; the seed stays
    `:representative`. Merge keeps the more-authoritative source on id collision.

  python3 methods/ingest.py                          # offline: bridge data/ingest/*.json + seed
  KANJO_OPERATOR_GATE=1 python3 methods/ingest.py --fetch-edgar 0000320193   # live Apple (gated)
ADR-2606032000.
"""
from __future__ import annotations
import json
import os
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, "methods"))
import kanjo_edn  # noqa: E402
from concept_map import canonical, CONCEPTS  # noqa: E402

# CIK → org.corp.* id (extend as the registry grows; shared kabuto/tsumugi space)
EDGAR_CIK_TO_ORG = {
    "0000320193": "org.corp.us.apple",
    "0000789019": "org.corp.us.microsoft",
    "0001045810": "org.corp.us.nvidia",
    "0001018724": "org.corp.us.amazon",
    "0001652044": "org.corp.us.alphabet",
    "0001326801": "org.corp.us.meta",
    "0001067983": "org.corp.us.berkshire",
    "0001730168": "org.corp.us.broadcom",
    "0001318605": "org.corp.us.tesla",
    "0000050863": "org.corp.us.intel",
    "0000002488": "org.corp.us.amd",
    "0000723125": "org.corp.us.micron",
    # ── Council-authorized live-EDGAR expansion (2026-06-14) — major US filers ──
    "0000019617": "org.corp.us.jpm",
    "0001403161": "org.corp.us.v",
    "0001141391": "org.corp.us.ma",
    "0000070858": "org.corp.us.bac",
    "0000072971": "org.corp.us.wfc",
    "0000886982": "org.corp.us.gs",
    "0000200406": "org.corp.us.jnj",
    "0000078003": "org.corp.us.pfe",
    "0000310158": "org.corp.us.mrk",
    "0001551152": "org.corp.us.abbv",
    "0000059478": "org.corp.us.lly",
    "0000731766": "org.corp.us.unh",
    "0000104169": "org.corp.us.wmt",
    "0000909832": "org.corp.us.cost",
    "0000354950": "org.corp.us.hd",
    "0000080424": "org.corp.us.pg",
    "0000021344": "org.corp.us.ko",
    "0000077476": "org.corp.us.pep",
    "0000063908": "org.corp.us.mcd",
    "0000034088": "org.corp.us.xom",
    "0000093410": "org.corp.us.cvx",
    "0001744489": "org.corp.us.dis",
    "0001065280": "org.corp.us.nflx",
    "0001108524": "org.corp.us.crm",
    "0001341439": "org.corp.us.orcl",
    "0000796343": "org.corp.us.adbe",
    "0000858877": "org.corp.us.csco",
    "0000051143": "org.corp.us.ibm",
    "0000804328": "org.corp.us.qcom",
    "0000097476": "org.corp.us.txn",
    "0000012927": "org.corp.us.ba",
    "0000018230": "org.corp.us.cat",
    "0000040545": "org.corp.us.ge",
    "0000732717": "org.corp.us.t",
    "0000732712": "org.corp.us.vz",
}
# which canonical concept lives on which statement (for :fin.fact/statement)
CONCEPT_STMT = {c: CONCEPTS[c][0] for c in CONCEPTS}


def parse_edgar_companyfacts(obj, org_id, want_fy=None):
    """SEC EDGAR companyfacts JSON → list of :fin.fact dicts (:authoritative).

    Shape: obj["facts"]["us-gaap"][Element]["units"][unit][ {end, val, fy, fp, form, ...} ].
    Picks annual (fp == "FY", form 10-K/20-F) data points; one fact per (concept, fy).
    """
    facts, filings = [], {}
    gaap = obj.get("facts", {}).get("us-gaap", {})
    for element, body in gaap.items():
        canon = canonical(element, "usgaap")
        if not canon:
            continue
        for unit, points in body.get("units", {}).items():
            for p in points:
                if p.get("fp") != "FY" or p.get("form") not in ("10-K", "20-F"):
                    continue
                fy = p.get("fy")
                if want_fy and fy != want_fy:
                    continue
                end = p.get("end", "")
                accession = p.get("accn", "")
                fid = f"fil.us.edgar.{org_id.split('.')[-1]}.{fy}"
                filings.setdefault(fid, {
                    ":fin.filing/id": fid, ":fin.filing/company": org_id,
                    ":fin.filing/source": ":edgar", ":fin.filing/form": ":" + p.get("form", "10-K"),
                    ":fin.filing/fiscal-year": fy, ":fin.filing/period-type": ":annual",
                    ":fin.filing/period-end": end, ":fin.filing/filed-date": p.get("filed", ""),
                    ":fin.filing/accession": accession, ":fin.filing/doc-cid": "",
                    ":fin.filing/currency": ":" + unit.lower(), ":fin.filing/accounting": ":usgaap",
                    ":fin.filing/sourcing": ":authoritative",
                })
                stmt = CONCEPT_STMT.get(canon, ":pl")
                facts.append({
                    ":fin.fact/id": f"fact.{org_id}.{fy}.{stmt.lstrip(':')}.{canon}.consolidated",
                    ":fin.fact/filing": fid, ":fin.fact/company": org_id,
                    ":fin.fact/statement": stmt, ":fin.fact/concept": ":" + canon,
                    ":fin.fact/concept-raw": "us-gaap:" + element,
                    ":fin.fact/value": float(p["val"]) / 1_000_000.0,  # base → millions
                    ":fin.fact/unit": ":" + unit.lower(), ":fin.fact/scale": ":millions",
                    ":fin.fact/context": ":consolidated", ":fin.fact/period-end": end,
                    ":fin.fact/sourcing": ":authoritative",
                })
    return list(filings.values()), _dedup_latest(facts)


def parse_edinet_elements(obj, org_id):
    """R0 EDINET adapter: pre-extracted element list → facts (jgaap/ifrs).

    Accepts {"company": org_id, "accounting": "jgaap|ifrs", "fiscalYear": int,
             "currency": "jpy", "periodEnd": "2024-03-31",
             "elements": [ {"element": "jppfs_cor:NetSales", "value": 1671900,
                            "scale": "millions", "context": "consolidated"} ]}.
    Full XBRL-XML (.xbrl + taxonomy) parsing is the R1 deliverable.
    """
    std = obj.get("accounting", "jgaap")
    fy = obj.get("fiscalYear")
    cur = obj.get("currency", "jpy")
    end = obj.get("periodEnd", "")
    fid = f"fil.jp.edinet.{org_id.split('.')[-1]}.{fy}"
    filing = {
        ":fin.filing/id": fid, ":fin.filing/company": org_id, ":fin.filing/source": ":edinet",
        ":fin.filing/form": ":yuho", ":fin.filing/fiscal-year": fy, ":fin.filing/period-type": ":annual",
        ":fin.filing/period-end": end, ":fin.filing/filed-date": obj.get("filedDate", ""),
        ":fin.filing/accession": obj.get("docID", ""), ":fin.filing/doc-cid": "",
        ":fin.filing/currency": ":" + cur, ":fin.filing/accounting": ":" + std,
        ":fin.filing/sourcing": ":authoritative",
    }
    facts = []
    for el in obj.get("elements", []):
        canon = canonical(el["element"], "ifrs" if std == "ifrs" else "jgaap")
        if not canon:
            continue
        stmt = CONCEPT_STMT.get(canon, ":pl")
        ctx = el.get("context", "consolidated")
        facts.append({
            ":fin.fact/id": f"fact.{org_id}.{fy}.{stmt.lstrip(':')}.{canon}.{ctx}",
            ":fin.fact/filing": fid, ":fin.fact/company": org_id, ":fin.fact/statement": stmt,
            ":fin.fact/concept": ":" + canon, ":fin.fact/concept-raw": el["element"],
            ":fin.fact/value": float(el["value"]), ":fin.fact/unit": ":" + cur,
            ":fin.fact/scale": ":" + el.get("scale", "millions"), ":fin.fact/context": ":" + ctx,
            ":fin.fact/period-end": end, ":fin.fact/sourcing": ":authoritative",
        })
    return [filing], facts


def _dedup_latest(facts):
    """Keep one fact per id (EDGAR repeats a concept across filings)."""
    seen = {}
    for f in facts:
        seen[f[":fin.fact/id"]] = f
    return list(seen.values())


def fetch_edgar(cik):
    """LIVE EDGAR companyfacts fetch — G7-gated, single polite request."""
    if os.environ.get("KANJO_OPERATOR_GATE") != "1":
        sys.exit("refused: live fetch requires KANJO_OPERATOR_GATE=1 (G7 Council+operator). "
                 "Offline mode reads data/ingest/*.json.")
    import urllib.request
    cik = cik.zfill(10)
    url = f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json"
    org = EDGAR_CIK_TO_ORG.get(cik, f"org.corp.us.cik{cik}")
    req = urllib.request.Request(url, headers={"User-Agent": "etzhayyim-kanjo research jun@etzhayyim.group"})
    with urllib.request.urlopen(req, timeout=30) as r:  # noqa: S310 (gated, trusted gov host)
        return parse_edgar_companyfacts(json.load(r), org)


def offline_ingest():
    """Bridge any data/ingest/*.json (edgar-companyfacts or edinet-elements shaped)."""
    ingest_dir = os.path.join(HERE, "data", "ingest")
    filings, facts = [], []
    if os.path.isdir(ingest_dir):
        for fn in sorted(os.listdir(ingest_dir)):
            if not fn.endswith(".json"):
                continue
            with open(os.path.join(ingest_dir, fn)) as fh:
                obj = json.load(fh)
            if "facts" in obj and "cik" in obj:  # EDGAR companyfacts
                org = EDGAR_CIK_TO_ORG.get(str(obj["cik"]).zfill(10), f"org.corp.us.cik{obj['cik']}")
                fl, fa = parse_edgar_companyfacts(obj, org)
            else:  # EDINET pre-extracted elements
                fl, fa = parse_edinet_elements(obj, obj["company"])
            filings += fl
            facts += fa
    return filings, facts


def merge_with_seed(filings, facts):
    """Merge ingested (:authoritative) over the :representative seed; authoritative wins."""
    seed = kanjo_edn.read_file(os.path.join(HERE, "data", "seed-financial-facts.kotoba.edn"))
    by_id = {}
    rank = {":representative": 0, ":synthesized": 0, ":authoritative": 1}
    for row in seed:
        rid = row.get(":fin.filing/id") or row.get(":fin.fact/id")
        by_id[rid] = row
    for row in filings + facts:
        rid = row.get(":fin.filing/id") or row.get(":fin.fact/id")
        old = by_id.get(rid)
        if old is None or rank.get(row.get(":fin.fact/sourcing") or row.get(":fin.filing/sourcing"), 0) >= \
                rank.get(old.get(":fin.fact/sourcing") or old.get(":fin.filing/sourcing"), 0):
            by_id[rid] = row
    return list(by_id.values())


def main():
    if "--fetch-edgar" in sys.argv:
        cik = sys.argv[sys.argv.index("--fetch-edgar") + 1]
        fl, fa = fetch_edgar(cik)
        print(f"kanjō ingest: fetched EDGAR CIK {cik} → {len(fl)} filings · {len(fa)} authoritative facts")
    else:
        fl, fa = offline_ingest()
        n = len(fl)
        print(f"kanjō ingest (offline): bridged {n} filings · {len(fa)} facts from data/ingest/"
              + ("" if n else " (none present — seed is the graph; drop EDGAR/EDINET JSON in data/ingest/)"))
    merged = merge_with_seed(fl, fa)
    outdir = os.path.join(HERE, "data")
    with open(os.path.join(outdir, "facts.merged.kotoba.edn"), "w") as f:
        f.write(";; kanjō — merged 決算 graph (seed ⊕ ingested; :authoritative wins). GENERATED by ingest.py.\n[")
        f.write("\n".join(" {" + " ".join(f"{k} {_v(v)}" for k, v in row.items()) + "}" for row in merged))
        f.write("\n]\n")
    print(f"  → data/facts.merged.kotoba.edn ({len(merged)} rows). Run analyze.py on it for metrics.")


def _v(v):
    if isinstance(v, str):
        return v if v.startswith(":") else '"' + v.replace('"', '\\"') + '"'
    if isinstance(v, bool):
        return "true" if v else "false"
    return repr(v)


if __name__ == "__main__":
    main()
