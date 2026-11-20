"""Summarizes CompilationCurveBench: time of the N-th compilation in a fresh JVM, across forks.

    sbt 'bench/Jmh/run -rf json -rff compilation-curve.json konsume.bench.CompilationCurveBench'
    python3 bench/scripts/compilation_curve.py bench/compilation-curve.json
"""

import json
import statistics
import sys

POINTS = [1, 2, 3, 5, 10, 20, 30, 50, 100, 150, 200]
THRESHOLDS_MS = [100, 50, 10]

with open(sys.argv[1]) as f:
    results = json.load(f)

for result in results:
    forks = result["primaryMetric"]["rawData"]
    iterations = min(len(fork) for fork in forks)
    medians = [statistics.median(fork[n] for fork in forks) for n in range(iterations)]
    print(f"\n{result['benchmark']} {result.get('params', {})}: "
          f"{len(forks)} fresh JVMs, {result['primaryMetric']['scoreUnit']}")
    print(f"{'N':>5} {'median':>10} {'min':>10} {'max':>10}")
    for n in (p for p in POINTS if p <= iterations):
        values = [fork[n - 1] for fork in forks]
        print(f"{n:>5} {statistics.median(values):>10.2f} {min(values):>10.2f} {max(values):>10.2f}")
    for threshold in THRESHOLDS_MS:
        # First N from which the median stays under the threshold for every later compilation.
        stays = next((n + 1 for n in range(iterations) if max(medians[n:]) < threshold), None)
        print(f"median stays under {threshold} ms from N = {stays if stays else 'never'}")
    print(f"total of the first 10: {sum(medians[:10]):.0f} ms, of all {iterations}: {sum(medians):.0f} ms")
