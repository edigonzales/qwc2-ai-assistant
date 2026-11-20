---
name: simplify-change
description: Remove unnecessary complexity from a design or implemented change before it is committed.
---

Identify the required behavior, actual users, and trust boundaries. Compare the
change with the smallest direct implementation using current QWC2 actions,
Quarkus catalog APIs and standard Python/JavaScript facilities.

Look for duplicated catalogs or state, speculative providers, extra abstractions,
trivial wrappers, custom orchestration, redundant validation, complex caching,
large test harnesses and new libraries without a concrete need. Delete or
consolidate where possible while preserving real behavior, meaningful tests and
security boundaries. Explain each retained complexity in one sentence.

For a review, report exact removable elements, callers, replacement and risk;
do not modify files.
