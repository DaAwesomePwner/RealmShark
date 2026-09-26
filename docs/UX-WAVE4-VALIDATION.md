# Wave 4 validation record

Status: implementation complete; final required PR CI and merge pending. PR #15.
Base: Wave 3 main merge `dfceefb`; its main CI `36245882529` passed.
Toolchain: JDK 17.0.20.1+1, Gradle 7.6.4, main Java 8 targeting.

## Bounded packages

- Foundation/annotation/ability store: 8 focused tests passed; malformed/future files,
  detached snapshots, save failure/retry, account isolation, concurrent revision
  rejection, reservation demand and annotation restore/restart.
- Producer/journal compatibility: 10 tests passed, including actual synthetic entity
  update before/after MP mutation and existing journal behavior.
- Integrated loot/search: 9 tests passed, including captured-zero versus absent boost,
  exact variants round-trip, legacy/malformed enrichment and non-mutating search.
- Integrated shell/quest: 16 tests passed at `ce11f56`, including actual search routes,
  Back, account races, failed-save retry and requirement/reservation math.

Outputs are in the isolated local `build/w4-root` tree. These focused invocations
replace that tree's latest reports, so counts are invocation records, not a summed
final suite count. Worker evidence will be reconciled with final integrated runs.

## Diagnostic history

The first annotation fixture bypassed Entity observation revision tracking by
mutating a stat directly; it failed to produce a pending-alive snapshot. Corrected
to send a synthetic update through the real producer and rerun successfully.
This initial failed invocation is not represented as a pass.

## Approved shortest finish

On 2026-09-26 the user approved finishing with the verified fixes and one final
required PR CI, without repeating the complete local full/scaled/package cycle.
Required Windows CI still runs the full suite, shadow JAR, build contract and help.
Independent final-head review, normal merge and main verification remain required.
No live capture, real Bridge deliveries or workstation display-setting changes.

The initial integrated run had 964 tests with 3 failures; each scaled run had 251
tests with 2 failures. These were the outdated journal-schema fixture, obsolete
ability-log assertions and nested roster scrolling. Corrections are integrated.
Later native diagnostics exposed incorrect metadata-font and Ctrl+End assumptions
and incomplete font-default cleanup. These failed runs are not passes.

The final evidence-lane recheck passed 4 tests at each 100/150/200% scale (zero
failures/errors/skips), including roster keyboard reveal and loot text geometry.
Source review approved production through 5ab6c6b; the last narrow delta requires
final review. Independent visuals confirmed ability/search, quest surfaces and the
corrected loot summary/details. Whole-suite final scaled success is not claimed.

Deferred follow-up: the newly added enlarged death-editor Open-to-Save native Tab
fixture remains unfinished and is excluded from this wave, not marked passed or
skipped. Its work is preserved in worker commits b72e65c, 90201f0 and b3e4b1a.
The existing character semantic and visual tests remain, with corrected font roles
and cleanup. Additional visual polish and old accepted Wave 3 cosmetic notes are
separate follow-ups; no new confirmed product blocker is being waived.

## Integrated audit

All three production packages are integrated. Character goals, quest plans and
reservations share one revision-checked account document; stale drafts cannot overwrite
another panel's save. Exact character run links use the installed navigator.

The first independent visual pass found a clipped ability coverage footer and faint
unfocused table selection. The footer now uses short semantic lines with full-text
geometry assertions; inactive table rows retain the selected violet fill. Final
integrated captures must verify these fixes. Loot enrichment captures intentionally
reveal the selected detail; a separate initial-top capture now distinguishes initial
scroll state from deliberate detail navigation.

The Wave 3 cohort audit confirmed exports keep the last applied query while inputs
are invalid. The stale notice now explicitly states this behavior. The My Info EDT
library recomputation finding is being addressed with asynchronous, generation-checked
reloads. Other accepted Wave 3 limitations remain subject to the final review record;
this progress note does not claim they were all revalidated.
