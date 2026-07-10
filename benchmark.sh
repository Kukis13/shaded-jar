#!/usr/bin/env bash
# Benchmarks the fat/shaded-jar archive step of shaded-jar vs Shadow vs stock Jar.
#
# Method: warm the daemon, keep compilation up-to-date, then re-run only the
# archive task with --rerun-tasks. Each scenario is timed best-of-N. A no-op
# control task measures the fixed per-invocation overhead (daemon roundtrip +
# graph setup), which is subtracted to isolate the actual archive cost.
#
# The shaded-jar row is measured twice: with all cores, and pinned to a single
# worker (--max-workers=1), to separate the parallelism win from the core
# algorithm. Shadow and stock Jar are single-threaded for the archive step.
#
# Usage:  bash benchmark.sh [runs]
set -euo pipefail
cd "$(dirname "$0")"

RUNS="${1:-6}"
GRADLE="${GRADLE:-gradle}"

echo "Warming daemon and compiling..."
$GRADLE -q :sample:classes >/dev/null 2>&1

# best-of-N wall time (ms) for "task [extra gradle args...]" run with --rerun-tasks
bench() {
  local task=$1; shift
  local best=99999999 i start end ms
  for ((i=0; i<RUNS; i++)); do
    start=$(date +%s%N)
    $GRADLE -q --console=plain ":sample:${task}" --rerun-tasks "$@" >/dev/null 2>&1
    end=$(date +%s%N)
    ms=$(( (end - start) / 1000000 ))
    (( ms < best )) && best=$ms
  done
  echo "$best"
}

echo "Measuring fixed overhead (benchNoop)..."
OVERHEAD=$(bench benchNoop)

# scenarios: "label|task|extra-args"
# Two comparisons: shaded (relocation on) vs Shadow, and plain-fat vs stock Jar.
# Single-thread is forced with Gradle's own --max-workers=1.
scenarios=(
  "shaded, all cores  (shaded-jar)|fatJar|"
  "shaded, 1 worker   (shaded-jar)|fatJar|--max-workers=1"
  "shaded             (Shadow)|shadowJar|"
  "fat, 1 worker      (shaded-jar)|fatJar|--max-workers=1 -PnoRelocate"
  "fat                (Gradle Jar)|stockFatJar|"
)

printf "\nfixed overhead (daemon roundtrip + graph): %d ms\n" "$OVERHEAD"
printf "\n%-32s %12s %14s\n" "scenario" "wall(best)" "archive(net)"
printf -- "-------------------------------- ------------ --------------\n"
for s in "${scenarios[@]}"; do
  IFS='|' read -r label task args <<< "$s"
  # shellcheck disable=SC2086 -- intentional word-splitting of the extra args
  if [[ -n "$args" ]]; then wall=$(bench "$task" $args); else wall=$(bench "$task"); fi
  net=$(( wall - OVERHEAD ))
  (( net < 0 )) && net=0
  printf "%-32s %9d ms %11d ms\n" "$label" "$wall" "$net"
done

echo
echo "Output sizes:"
ls -la sample/build/libs/*.jar | awk '{printf "  %10d  %s\n", $5, $9}'
