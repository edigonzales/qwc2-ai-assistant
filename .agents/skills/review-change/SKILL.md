---
name: review-change
description: Independently review a committed change for behavior, regressions, security, tests and unnecessary complexity.
---

Review a named commit SHA against its specified base and phase acceptance
criteria, not a vague working tree. Inspect diffs and their callers. Read
AGENTS.md and use `qwc2-integration` or `simplify-change` when relevant.
Do not change product files.

Inspect real QWC2 Redux/action semantics, async theme changes, nested/group
layers and WMS behavior. Test that search candidates and metadata use actual
catalog IDs, result selection cannot inject invented IDs, and the agent cannot
invoke non-allowlisted tools. Check chat protocol, multi-call/tool-result
correlation, loop limits, prompt-injection boundaries, proxy POST routing,
secrets and container exposure. Run targeted tests when possible.

Return PASS or FAIL, reviewed base/SHA, OpenCode version, exact DeepSeek model,
findings ordered by severity with file/line/trigger/consequence/fix, checks
actually executed, unchecked areas and simplifications. No fabricated evidence
or general praise. A fix produces a new commit requiring a fresh review.
