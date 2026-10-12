---
name: aps-androidhosttest-flaky-baseline
description: :plugins:aps testAndroidHostTest is red at HEAD in SIX order-dependent classes, not five; measure the baseline yourself before blaming a move
metadata:
  type: reference
---

`./gradlew :plugins:aps:testAndroidHostTest` does not pass at HEAD and has not for a while. Measured
on 2026-10-12 on branch `kmp-aimi-merge-dev-2026-10-10`, with no local changes:
**2156 tests, 92 failures, 2 skipped**, spread over six classes:

`ShellDecisionTraceTest` (~73), `AimiPhysioLlmNarrativeDeliveryTest` (~7),
`LegacyMealEffectTraceTest` (~5), `AimiPhysioLlmKeyWiringTest` (~3),
`AimiLlmRequestBodyParityTest` (2), `AimiHttpClientMessageTest` (2).

**Why this is worth remembering:** the task briefs circulating for this work name only *five* of
these and omit `AimiPhysioLlmKeyWiringTest`. It looks exactly like a regression you just caused. It
is not - it passes 13/13 when run alone, and it fails at HEAD in the full suite.

**How to apply.** The invariant is "no new failing class", and the per-class counts drift run to run
(the whole cluster is order-dependent, and the LLM/HTTP classes share global state). Before
investigating any failure in this module:

1. Re-run the suspect class alone. The LLM and trace classes pass in isolation.
2. If it still looks new, measure the baseline properly - and do it by moving your own files out by
   explicit path and back in **one** Bash call, never with `git stash`, which would swallow the
   unreviewed work this tree usually carries.

Related: [[aimi-control-center-move]], [[mutation-restore-proof]]
