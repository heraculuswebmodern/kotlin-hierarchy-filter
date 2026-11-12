# Take-Home Assessment

Two parts:

1. **Hierarchy `filter()`**: implementation in [`src/main/kotlin/Hierarchy.kt`](src/main/kotlin/Hierarchy.kt), tests in [`src/test/kotlin/FilterTest.kt`](src/test/kotlin/FilterTest.kt)
2. **Code review of `SimpleCache`**: see [`CODE_REVIEW.md`](CODE_REVIEW.md)

## Running locally

Requirements: JDK 17+ and Gradle (or generate the wrapper once with `gradle wrapper`).

```
gradle test      # or ./gradlew test after generating the wrapper
```

## Hierarchy.filter(): approach

The forest is stored in DFS order with a parallel depth array, so a node's subtree is the contiguous run of following nodes with strictly greater depth.

Single pass, **O(n) time**, O(n) space for the output:

1. Walk the nodes in order, keeping `skipDepth`: the depth of the rejected node whose subtree is being skipped (`Int.MAX_VALUE` if none).
2. If `depth > skipDepth`, the node is inside a rejected subtree: skip it **without calling the predicate**.
3. Otherwise, call the predicate. If it passes, append the node. If not, set `skipDepth = depth`.
4. Kept nodes have all their ancestors kept, so their depth is unchanged and is copied as is.

Properties: the predicate is called at most once per node and never for nodes under a rejected ancestor; the input is not modified; no recursion, so very deep trees are safe.

### Assumptions
- Input satisfies the documented invariants (first depth is 0, depth increases by at most 1 per step). No validation is done.
- The predicate is side-effect free.
- The result is a new `ArrayBasedHierarchy` snapshot, not a lazy view.

### Tests
`FilterTest` covers: the provided example, empty and single-node input, accept-all / reject-all, rejecting a root / inner node / leaf, passing descendants of a rejected parent, a sibling after a rejected node, skip ending when depth returns to a shallower level, flat forest, deep chain, predicate-call behaviour, input immutability, composing two filters, and a 1M-node input.
