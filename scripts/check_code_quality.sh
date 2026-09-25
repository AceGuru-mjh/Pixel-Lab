#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Anti-pattern gate for main sources (test sources are exempt where noted).
#
# Blocked patterns (hard fail):
#   1. javaClass.getMethod / javaClass.getDeclaredMethod — reflective
#      dispatch hides call graphs from static analysis and breaks tooling.
#   2. printStackTrace() — use the SLF4J abstraction (SLF4J_LOG) or return
#      typed errors; raw stack dumps pollute host-app logcat.
#   3. Empty catch blocks — silently swallowed errors; either handle,
#      rethrow, or log through SLF4J.
#
# Exits non-zero listing every violation with file:line.
# ─────────────────────────────────────────────────────────────────────────────
set -u

FAIL=0

echo "── Anti-pattern gate (reflection dispatch / printStackTrace / empty catch) ──"

KT_MAIN=$(find . -name '*.kt' -path '*/src/main/*' -not -path './.git/*' -not -path '*/build/*' 2>/dev/null | sort)

while IFS= read -r f; do
  [ -z "$f" ] && continue
  # 1. Reflective dispatch.
  grep -nE 'javaClass\.(getDeclared)?Method' "$f" | while IFS=: read -r ln rest; do
    echo "❌ reflective dispatch: $f:$ln: $rest"
  done
  REFLECT=$(grep -cE 'javaClass\.(getDeclared)?Method' "$f" || true)
  [ "$REFLECT" -gt 0 ] && FAIL=1

  # 2. printStackTrace().
  grep -nE 'printStackTrace\(\)' "$f" | while IFS=: read -r ln rest; do
    echo "❌ printStackTrace: $f:$ln: $rest"
  done
  PSTACK=$(grep -cE 'printStackTrace\(\)' "$f" || true)
  [ "$PSTACK" -gt 0 ] && FAIL=1

  # 3. Empty catch blocks: `catch (...) { }` with nothing between braces.
  EMPTY=$(grep -cE 'catch \([^)]+\) \{[[:space:]]*\}' "$f" || true)
  if [ "$EMPTY" -gt 0 ]; then
    grep -nE 'catch \([^)]+\) \{[[:space:]]*\}' "$f" | while IFS=: read -r ln rest; do
      echo "❌ empty catch: $f:$ln: $rest"
    done
    FAIL=1
  fi
done <<< "$KT_MAIN"

# Warning-only census: TODO/FIXME markers are counted, never blocking.
CENSUS=$(grep -rE 'TODO|FIXME' --include='*.kt' --include='*.cpp' --include='*.h' \
  $(find . -name '*.kt' -path '*/src/main/*' -not -path './.git/*' -not -path '*/build/*' 2>/dev/null) \
  2>/dev/null | wc -l)
echo "ℹ️  TODO/FIXME census in main sources: $CENSUS"

if [ "$FAIL" -eq 0 ]; then
  echo "✅ No anti-patterns detected in main sources."
else
  echo ""
  echo "Anti-pattern violations detected; see listing above."
fi
exit $FAIL
