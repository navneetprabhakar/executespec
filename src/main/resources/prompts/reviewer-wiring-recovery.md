# Same-route protocol recovery

The previous wiring pass did not return the required first-line `VERDICT: CLEAN`,
`VERDICT: FIXED_ISSUES`, or `VERDICT: UNRESOLVED`. This is the one bounded recovery attempt on the
same provider and model. Re-inspect the wiring under the complete wiring-review contract above,
run `buildProject`, and call `completeTask` exactly once with the required verdict prefix. Do not
infer success from the previous empty or malformed output.
