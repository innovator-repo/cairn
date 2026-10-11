## gstack

In Claude Code cloud sessions, `.claude/hooks/install-gstack.sh` installs
[gstack](https://github.com/garrytan/gstack) at session start (pinned commit,
browser download skipped). Its skills then live in `~/.claude/skills/`.

Useful skills: /office-hours, /plan-ceo-review, /plan-eng-review, /review,
/ship, /qa, /investigate, /retro, /cso, /careful, /freeze, /guard, /learn.

If a gstack skill is missing, check `$TMPDIR/gstack-install.log` (or
`/tmp/gstack-install.log`) and rerun
`CLAUDE_CODE_REMOTE=true bash .claude/hooks/install-gstack.sh`.
To use gstack locally, install it once:
`git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack && cd ~/.claude/skills/gstack && ./setup`
