#!/usr/bin/env bash
# Installs gstack (https://github.com/garrytan/gstack) when a Claude Code
# cloud session starts, so its skills (/review, /ship, /qa, /investigate, ...)
# are available. Runs only in cloud sessions (CLAUDE_CODE_REMOTE=true) and
# does nothing on a local machine or when gstack is already installed.
#
# GSTACK_REF pins the gstack commit that gets installed. To update gstack,
# change it to a newer commit from https://github.com/garrytan/gstack/commits/main
# after reviewing what changed.
set -u

GSTACK_REF="${GSTACK_REF:-5cb5e1cfc115e82388df07896664640a1224fe5d}"
GS="$HOME/.claude/skills/gstack"
LOG="${TMPDIR:-/tmp}/gstack-install.log"

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
[ -x "$GS/bin/gstack-skill-start" ] && exit 0

if ! command -v bun >/dev/null 2>&1; then
  echo "gstack: Bun not found, skipping install" >&2
  exit 0
fi

{
  set -e
  if [ ! -d "$GS/.git" ]; then
    rm -rf "$GS"
    mkdir -p "$GS"
    git -C "$GS" init -q
    git -C "$GS" remote add origin https://github.com/garrytan/gstack.git
  fi
  GIT_LFS_SKIP_SMUDGE=1 git -C "$GS" fetch -q --depth 1 origin "$GSTACK_REF"
  git -C "$GS" checkout -q FETCH_HEAD
  cd "$GS" && ./setup -q --no-browser
} >"$LOG" 2>&1 || {
  echo "gstack: install failed, see $LOG" >&2
  exit 0
}

echo "gstack installed (${GSTACK_REF:0:7})"
exit 0
