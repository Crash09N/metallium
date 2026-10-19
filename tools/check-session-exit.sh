#!/bin/bash
# Checks the crash guard's session marker across exit kinds (macOS, opens a Minecraft window per run, ~2 min each).
# normal  : client.stop() -> orderly shutdown        -> marker removed, next start on Metal without recovery screen
# sigterm : SIGTERM to the JVM                       -> marker removed
# crash   : crash report saved + System.exit(-1)     -> marker kept (next start would run recovery)
# Then the seeded -PcrashRecovery run must still land on OpenGL with the recovery screen.
# Wrap in gpu-lock.sh when other agents share the machine.
set -u
cd "$(dirname "$0")/.."
M=build/run/clientGameTest/config/metallium/session.json
R=build/run/clientGameTest/metallium-gametest-report.txt
fail=0
for mode in normal sigterm crash; do
  ./gradlew --no-daemon -q :runClientGameTest -PsessionExit=$mode >/tmp/session-exit-$mode.log 2>&1
  grep -h "session exit:\|result:" $R
  if [ "$mode" = crash ]; then
    [ -f "$M" ] && echo "OK  crash keeps marker" || { echo "FAIL crash removed marker"; fail=1; }
  else
    [ ! -f "$M" ] && echo "OK  $mode removed marker" || { echo "FAIL $mode left marker"; fail=1; }
  fi
done
./gradlew --no-daemon -q :runClientGameTest -PcrashRecovery >/tmp/session-exit-recovery.log 2>&1
grep -h "crash recovery:\|result:" $R
grep -q "result: PASS" $R && echo "OK  simulated crash still triggers recovery" || { echo "FAIL recovery"; fail=1; }
exit $fail
