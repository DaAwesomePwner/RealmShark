# Wave 4 validation record

Status: in progress. No final-head validation or merge approval is claimed.
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

## Pending gates

Final source review, native/scaled visual review, full/scaled suites, shadow JAR,
isolated help, build/runtime/package checks, PR CI, normal merge and main verification.
No live capture, real Bridge deliveries or workstation display-setting changes.
