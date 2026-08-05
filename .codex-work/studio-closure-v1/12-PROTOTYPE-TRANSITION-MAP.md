# ExecuteSpec Studio — Prototype Transition Map

Status: **Implemented and verified in canonical Figma**  
Detailed graph and flow-start evidence: `19-FIGMA-PHASE-6-PROTOTYPE-GRAPH.md`

## Primary playable path

D01 Home → D02 New Project → D03 Brief → D04 Working → D05 Waiting for decision → D06 Preview ready → D15 Checks → D22 Publish review → D23 Deploying → D24 Published → D25 Health warning → D26 Rollback complete.

## Secondary paths

- D06 → D07 visual select → D08 contextual change → D19 new Version → D20 compare → D21 restore.
- D06 → D10 Data → D13 integration/secret → D15 checks.
- D05 → D34 limit → D35 upgrade → resume D04 with same Task.
- D41 Professional Mode → D44 client boundary → D48 source → D52 context → D55 scope → D59 plan → D63 implementation → D67 integration checks → D68 generation → D69 suite validation → D70 CI configuration → D71 CI result → D72 environment → D73 deploy review → D74 deployment → D76 client review package → D77 client decision → D79 acceptance → D80 documents → D81 handoff/reuse boundary.
- Every failure state links to a named recovery or to a safe terminal explanation.

Prototype rule: no dead-end modal; close returns to its invoking composition with Task state intact.

Verified totals: 17 named flow starts, 72 reaction nodes, 71 distinct screen transitions, zero broken destinations, and 19/19 required route assertions passed.
