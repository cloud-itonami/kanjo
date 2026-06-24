#!/usr/bin/env bash
# kanjo — clj/bb test suite (ADR-2606160842 py->clj port wave; Python pruned).
set -euo pipefail
cd "$(dirname "$0")/../.."
exec bb test:kanjo
