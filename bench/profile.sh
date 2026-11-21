#!/usr/bin/env bash
# Profiles one case of ShapesBench with JFR: where the CPU time goes, what allocates.
# The recording stays in bench/target (not committed).
#
#   bench/profile.sh <program> <format> [engine]     e.g. bench/profile.sh projectObj Avro
set -euo pipefail
cd "$(dirname "$0")/.."
program=$1; format=$2; engine=${3:-Planned}
mkdir -p bench/target
jfr="$PWD/bench/target/profile-$program-$format-$engine.jfr"
rm -f "$jfr"
sbt -batch "bench/Jmh/run -f 1 -wi 3 -i 5 -p program=$program -p format=$format -p engine=$engine -jvmArgsAppend -XX:StartFlightRecording=filename=$jfr,settings=profile konsume.bench.ShapesBench" >/dev/null
sbt -batch "bench/runMain konsume.bench.Profile $jfr"
