# TODO

## `nodeIfCurrentDoesNotMatch`

Implement `nodeIfCurrentDoesNotMatch(String labelExpression, Closure body)` as a Pipeline Steps command so the implementation can be removed from the
Jenkins Unity Shared Library.

Contract:

- Accept the same Jenkins label expressions as the native `node` step.
- When the current `NODE_NAME` exactly equals `labelExpression`, run the body immediately in the current executor and workspace.
- Otherwise, when the current node matches the label expression, run the body immediately in the current executor and workspace.
- When there is no current node or it does not match, allocate `node(labelExpression)` and run the body in that allocation's workspace.
- Log whether the current node is reused or a new allocation is requested without requiring a trusted Shared Library or exposing controller model objects
  to Pipeline code.
- Return the body's result.
- Preserve body failures and `FlowInterruptedException` without converting or swallowing them.
- Remain restart-safe and do not retain Jenkins, Hudson, label, iterator, or other non-serializable objects across suspension points.
- Cover exact-name reuse, label-expression reuse, node switching, calls outside a node, body return values, failures, interruptions, and controller restart
  in Jenkins test-harness tests.
