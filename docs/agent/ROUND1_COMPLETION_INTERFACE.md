# Round-one completion interface (repair 2)

Baseline: 292c6d83b453ff3f387b20ac6fa7bc3d27cca2b2. A owns workflow/Python and V21; B owns the evidence interface/report adapter.

## EvidenceAuthority interface owned by B

Extend the nested records as follows (retain the old three-argument ReportGoal constructor, which must produce completionVerified=false, empty criteria and an explicit legacy gap):

```java
record ReportCriterion(String criterionId, String text, String status,
                       List<String> checkIds, List<String> claimIds, List<String> gaps) {}
record ReportGoal(String taskId, String text, String status,
                  boolean completionVerified, List<ReportCriterion> criteria,
                  List<String> gaps) {}
```

Criterion status is resolved, uncovered, blocked, or stale. Goal status is done only when completionVerified=true and all criteria are resolved and current dependencies are satisfied; otherwise the producer returns blocked/pending/cancelled as appropriate. A's production AgentEvidenceAuthority.reportGoals will compute these fields using an independent workflow completion verifier, rather than forwarding Python's stored done.

B must preserve each non-resolved criterion and each goal gap in unfinished_goals and the readable answer. A goal is unfinished when status is not done OR completionVerified is false OR any criterion is not resolved OR any goal gap remains. Old constructor data has no completion proof and must stay unfinished. Keep report selectors unable to exclude these gaps. No new client/body declaration is trusted.

## A-owned structural coverage and current proof

A gives each persisted acceptance criterion a stable run/task/index/text-derived ID and publishes it to the decision context. check_claims has explicit one-to-one criterion bindings by criterion_id and claim_index. Unknown/duplicate/cross-task IDs, duplicate Claim scope across separate criteria, unmapped Claims and scope mutation after initial binding are rejected. An omitted standard never defaults to covered. A criterion's initial scoped Claim is immutable; a later check must reuse that scope. The semantic correspondence between a planning criterion and the chosen scoped Claim remains a documented model-planning limit, not a general truth guarantee.

V21 persists original criteria, immutable bound Claim scope, current investigation attempts and the dependency snapshot captured when a criterion is checked. These organize proof; Python flags do not establish it. The production report verifier checks original stored task criteria, scoped run/project/owner evidence records, the current complete B check/Claim/DecisionRecord, the actual settled tool operation, unresolved/blocked/pending checks, and the current prerequisite proof. Supported and refuted outcomes may resolve a verification criterion. Contested/insufficient or failed/uncompleted latest attempts cannot.

Changing a shared investigation invalidates all affected criteria and task statuses; changed prerequisite proof makes earlier downstream executions stale until explicitly rechecked. Historical checks/packets stay immutable. Missing legacy criterion or dependency mappings remain visible gaps on recovery. Existing global tool/model/deadline budgets and frozen v0/V17–V20 stay unchanged.

## Compatibility and testing boundary

Keep ReportGoal(String taskId,String text,String status) source compatibility, but do not treat a legacy done string as proof. B's authority test fixtures that intentionally represent trusted completed goals must construct explicit verified criterion data. Production coverage is verified by A's actual database adapter; fixture data is not that adapter's proof.

No change to B's check request/response protocol or ClaimSpec DTO is needed: A removes criterion binding metadata before sending ClaimSpec, and binds it to the authoritative returned records and SQL operations. B can proceed with N3/N4 independently. Changes to this agreement must be published in the shared interface note before consumers adapt.
