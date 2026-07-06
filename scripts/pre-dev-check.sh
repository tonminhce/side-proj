#!/usr/bin/env bash
# pre-dev-check.sh — run before any dev work on side-project.
# Catches R-01 blocker, version mismatches with skills/* personas, missing toolchain.
# ponytail: minimal — checks the few things that waste dev cycles if missed.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UTIL_POM="$ROOT/util/pom.xml"
ROOT_POM="$ROOT/pom.xml"

fail=0
warn=0

# ---------- 1. Java toolchain ----------
# Honor JAVA_HOME when set (e.g., openjdk@25) — that's the project's expected override.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin="java"
fi
if command -v "$java_bin" >/dev/null 2>&1 || [ -x "$java_bin" ]; then
  java_ver=$("$java_bin" -version 2>&1 | head -1 | awk -F'"' '{print $2}')
  case "$java_ver" in
    25.*) echo "  [ok]    java $java_ver" ;;
    26.*) echo "  [warn]  java $java_ver (project targets Java 25 LTS; Spring Boot 4.0 may not yet support 26 — set JAVA_HOME to a JDK 25 if maven build fails)" ; warn=$((warn+1)) ;;
    *)    echo "  [fail]  java $java_ver (project requires Java 25)" ; fail=$((fail+1)) ;;
  esac
else
  echo "  [fail]  java not on PATH" ; fail=$((fail+1))
fi

# ---------- 2. Maven toolchain ----------
if command -v mvn >/dev/null 2>&1; then
  mvn_ver=$(mvn --version 2>&1 | head -1 | awk '{print $3}')
  mvn_major=$(printf '%s' "$mvn_ver" | cut -d. -f1)
  mvn_minor=$(printf '%s' "$mvn_ver" | cut -d. -f2)
  if [ "$mvn_major" -gt 3 ] || { [ "$mvn_major" -eq 3 ] && [ "$mvn_minor" -ge 9 ]; }; then
    echo "  [ok]    maven $mvn_ver"
  else
    echo "  [fail]  maven $mvn_ver (need 3.9+)" ; fail=$((fail+1))
  fi
else
  echo "  [fail]  mvn not on PATH" ; fail=$((fail+1))
fi

# ---------- 3. R-01 — util parent pom blocker ----------
# Option A (recommended): util/pom.xml has NO <parent>, self-contained via inline <dependencyManagement>.
# Option B (alternative): util/pom.xml has <parent> matching root pom's groupId:artifactId.
if [ -f "$UTIL_POM" ] && [ -f "$ROOT_POM" ]; then
  util_has_parent=$(grep -c '<parent>' "$UTIL_POM" || true)
  if [ "$util_has_parent" -eq 0 ]; then
    echo "  [ok]    util uses Option A (no <parent>, inline dep mgmt)"
  else
    util_parent_g=$(awk '/<parent>/{flag=1; next} flag && /<groupId>/{gsub(/[ \t]+/,""); gsub(/<\/?groupId>/,""); print; exit}' "$UTIL_POM")
    util_parent_a=$(awk '/<parent>/{flag=1; next} flag && /<artifactId>/{gsub(/[ \t]+/,""); gsub(/<\/?artifactId>/,""); print; exit}' "$UTIL_POM")
    root_g=$(awk '/<groupId>/{gsub(/[ \t]+/,""); gsub(/<\/?groupId>/,""); print; exit}' "$ROOT_POM")
    root_a=$(awk '/<artifactId>/{gsub(/[ \t]+/,""); gsub(/<\/?artifactId>/,""); print; exit}' "$ROOT_POM")
    if [ "$util_parent_g" = "$root_g" ] && [ "$util_parent_a" = "$root_a" ]; then
      echo "  [ok]    util parent = $root_g:$root_a (Option B)"
    else
      echo "  [fail]  R-01 BLOCKER: util parent is $util_parent_g:$util_parent_a but root pom is $root_g:$root_a"
      echo "          → fix per SPRINT-0-ONBOARDING.md §Story 0.1 (Option A recommended: remove <parent>, use inline dependencyManagement)"
      fail=$((fail+1))
    fi
  fi
else
  echo "  [fail]  missing $UTIL_POM or $ROOT_POM" ; fail=$((fail+1))
fi

# ---------- 4. Skill/persona version mismatch — informational ----------
sb_skill=$(grep -oE 'Spring Boot 3(\.[x0-9]+)?' "$ROOT/skills/spring-boot-engineer.md" 2>/dev/null | head -1)
if [ -n "$sb_skill" ]; then
  echo "  [warn]  skills/spring-boot-engineer.md targets $sb_skill; project uses Spring Boot 4.0 — apply principles, verify Boot 4 specifics per ADR" ; warn=$((warn+1))
fi
bk_skill=$(grep -oE 'Node\.js 18\+|Python 3\.[0-9]+\+|Go 1\.[0-9]+' "$ROOT/skills/backend-developer.md" 2>/dev/null | head -1)
if [ -n "$bk_skill" ]; then
  echo "  [warn]  skills/backend-developer.md targets $bk_skill; project is Java 25 — apply principles only" ; warn=$((warn+1))
fi

# ---------- Summary ----------
echo
if [ "$fail" -gt 0 ]; then
  echo "✗ $fail fail(s), $warn warning(s) — DO NOT START DEV until fails are fixed."
  exit 1
elif [ "$warn" -gt 0 ]; then
  echo "⚠ $warn warning(s) — proceed with caution, verify before each commit."
  exit 0
else
  echo "✓ All clear. Safe to start Sprint 0 dev work."
  exit 0
fi