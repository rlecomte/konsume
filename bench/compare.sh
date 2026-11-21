#!/usr/bin/env bash
# Compares two results of bench/run.sh: by default the two most recent ones.
#
#   bench/compare.sh                    the last two results
#   bench/compare.sh before.json after.json
set -euo pipefail
cd "$(dirname "$0")/.."
if [ $# -eq 2 ]; then before=$1; after=$2
else
  mapfile -t last < <(ls bench/results/*.json | sort | tail -2)
  [ ${#last[@]} -eq 2 ] || { echo "need two results in bench/results" >&2; exit 2; }
  before=${last[0]}; after=${last[1]}
fi
sbt -batch "bench/runMain konsume.bench.Compare $PWD/$before $PWD/$after"
