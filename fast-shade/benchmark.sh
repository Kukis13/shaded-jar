#!/usr/bin/env bash
# Benchmarks the fat-jar archive step of fast-shade vs Shadow vs stock Gradle Jar.
#
# Method: warm the daemon, keep compilation up-to-date, then re-run only the
# archive task with --rerun-tasks. Each task is timed best-of-N. A no-op control
# task measures the fixed per-invocation overhead (daemon roundtrip + graph
# setup), which is subtracted to isolate the actual archive cost.
#
# Usage:  bash benchmark.sh [runs]
set -euo pipefail
cd "$(dirname "$0")"

RUNS="${1:-6}"
GRADLE="${GRADLE:-gradle}"

echo "Warming daemon and compiling..."
$GRADLE -q :sample:classes >/dev/null 2>&1

# best-of-N wall time (ms) for a task run with --rerun-tasks
bench() {
  local task=$1 best=99999999 i start end ms
  for ((i=0; i<RUNS; i++)); do
    start=$(date +%s%N)
    $GRADLE -q --console=plain ":sample:${task}" --rerun-tasks >/dev/null 2>&1
    end=$(date +%s%N)
    ms=$(( (end - start) / 1000000 ))
    (( ms < best )) && best=$ms
  done
  echo "$best"
}

echo "Measuring fixed overhead (benchNoop)..."
OVERHEAD=$(bench benchNoop)

printf "\nfixed overhead (daemon roundtrip + graph): %d ms\n" "$OVERHEAD"
printf "\n%-14s %12s %14s\n" "task" "wall(best)" "archive(net)"
printf -- "-------------- ------------ --------------\n"
for t in fatJar shadowJar stockFatJar; do
  wall=$(bench "$t")
  net=$(( wall - OVERHEAD ))
  (( net < 0 )) && net=0
  printf "%-14s %9d ms %11d ms\n" "$t" "$wall" "$net"
done

echo
echo "Output sizes:"
ls -la sample/build/libs/*.jar | awk '{printf "  %10d  %s\n", $5, $9}'
