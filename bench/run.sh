#!/usr/bin/env bash
# Runs ShapesBench on the planned engine, Avro and JSON, time and allocations, always with the
# same settings, and saves JMH's results in bench/results/<date-time>-<commit>[-dirty].json
# ("-dirty": the working tree had changes, measured on top of that commit).
#
#   bench/run.sh            the planned engine
#   bench/run.sh all        both engines (the interpreter too)
set -euo pipefail
cd "$(dirname "$0")/.."

engines="-p engine=Planned"
[ "${1:-}" = "all" ] && engines=""

commit=$(git rev-parse --short HEAD)
dirty=""
git diff --quiet HEAD -- . ':(exclude)bench/results' || dirty="-dirty"
out="$PWD/bench/results/$(date +%Y%m%d-%H%M)-$commit$dirty.json"

sbt -batch "bench/Jmh/run -f 2 -wi 3 -i 5 -prof gc $engines -rf json -rff $out konsume.bench.ShapesBench"
echo "results: ${out#$PWD/}"
