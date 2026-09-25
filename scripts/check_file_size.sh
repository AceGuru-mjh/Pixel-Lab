#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# File-size budget gate — God-file prevention (SRP enforcement).
#
# Budgets (lines per .kt file):
#   main sources ≤ 1200
#   test sources ≤ 1600
#
# To raise a budget, justify it in the PR — but prefer splitting the file
# along responsibility seams instead. Exits non-zero listing every violation.
# ─────────────────────────────────────────────────────────────────────────────
set -u

MAIN_BUDGET=1200
TEST_BUDGET=1600
FAIL=0

echo "── File-size budget gate (main ≤ ${MAIN_BUDGET}, test ≤ ${TEST_BUDGET} lines) ──"

check_budget() {
  local scope="$1" budget="$2" pattern="$3"
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    local lines
    lines=$(wc -l < "$f" | tr -d ' ')
    if [ "$lines" -gt "$budget" ]; then
      echo "❌ [$scope] $f is $lines lines (budget $budget)"
      FAIL=1
    fi
  done < <(find . -name '*.kt' -path "$pattern" -not -path './.git/*' -not -path '*/build/*' 2>/dev/null | sort)
}

check_budget "test" "$TEST_BUDGET" "*/src/test/*"

# Declarative tool registries (the MCP tool catalogs) are lookup tables,
# not logic: they read as one-entry-per-tool rows and stay reviewable at
# higher line counts, so they get a dedicated wider budget. Main sources
# EXCLUDING registries stick to the strict budget.
MAIN_FILES=$(find . -name '*.kt' -path '*/src/main/*' -not -path './.git/*' -not -path '*/build/*' 2>/dev/null | grep -v 'Registry' | sort)
while IFS= read -r f; do
  [ -z "$f" ] && continue
  lines=$(wc -l < "$f" | tr -d ' ')
  if [ "$lines" -gt "$MAIN_BUDGET" ]; then
    echo "❌ [main] $f is $lines lines (budget $MAIN_BUDGET)"
    FAIL=1
  fi
done <<< "$MAIN_FILES"

# Declarative tool registries (the MCP tool catalogs) are lookup tables,
# not logic: they read as one-entry-per-tool rows and stay reviewable at
# higher line counts, so they share the C++ budget of 1600.
REGISTRY_BUDGET=1600
while IFS= read -r f; do
  [ -z "$f" ] && continue
  lines=$(wc -l < "$f" | tr -d ' ')
  if [ "$lines" -gt "$REGISTRY_BUDGET" ]; then
    echo "❌ [registry] $f is $lines lines (budget $REGISTRY_BUDGET)"
    FAIL=1
  fi
done < <(find . -name '*Registry*.kt' -path '*/src/main/*' -not -path './.git/*' -not -path '*/build/*' 2>/dev/null | sort)

# C++ translation units get a wider budget: dense algorithmic kernels.
CPP_BUDGET=1600
while IFS= read -r f; do
  [ -z "$f" ] && continue
  lines=$(wc -l < "$f" | tr -d ' ')
  if [ "$lines" -gt "$CPP_BUDGET" ]; then
    echo "❌ [cpp] $f is $lines lines (budget $CPP_BUDGET)"
    FAIL=1
  fi
done < <(find . \( -name '*.cpp' -o -name '*.h' \) -not -path './.git/*' -not -path '*/build/*' 2>/dev/null | sort)

if [ "$FAIL" -eq 0 ]; then
  echo "✅ All source files within size budgets."
else
  echo ""
  echo "File-size budget violations detected. Split the file along responsibility seams."
fi
exit $FAIL
