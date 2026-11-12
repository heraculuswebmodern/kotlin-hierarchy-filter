import kotlin.test.Test
import kotlin.test.assertEquals

class FilterTest {
  private fun h(ids: IntArray, depths: IntArray): Hierarchy = ArrayBasedHierarchy(ids, depths)

  private fun assertFiltered(expected: Hierarchy, actual: Hierarchy) =
    assertEquals(expected.formatString(), actual.formatString())

  private val sample = h(
    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2)
  )

  @Test
  fun testFilter() {
    val filteredActual: Hierarchy = sample.filter { nodeId -> nodeId % 3 != 0 }
    val filteredExpected: Hierarchy = ArrayBasedHierarchy(
      intArrayOf(1, 2, 5, 8, 10, 11),
      intArrayOf(0, 1, 1, 0, 1, 2))
    assertEquals(filteredExpected.formatString(), filteredActual.formatString())
  }

  @Test
  fun emptyHierarchy() {
    val empty = h(IntArray(0), IntArray(0))
    assertEquals(0, empty.filter { true }.size)
    assertEquals(0, empty.filter { false }.size)
  }

  @Test
  fun predicateAcceptsAll() {
    assertFiltered(sample, sample.filter { true })
  }

  @Test
  fun predicateRejectsAll() {
    assertEquals(0, sample.filter { false }.size)
  }

  @Test
  fun rejectingRootRemovesWholeTree() {
    // 1 is the root of the first tree: 1, 2, 3, 4, 5 all go away.
    val actual = sample.filter { it != 1 }
    val expected = h(intArrayOf(6, 7, 8, 9, 10, 11), intArrayOf(0, 1, 0, 1, 1, 2))
    assertFiltered(expected, actual)
  }

  @Test
  fun rejectingLeafOnlyRemovesLeaf() {
    val actual = sample.filter { it != 4 }
    val expected = h(
      intArrayOf(1, 2, 3, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 2, 1, 0, 1, 0, 1, 1, 2))
    assertFiltered(expected, actual)
  }

  @Test
  fun rejectedParentHidesPassingDescendants() {
    // 2 is rejected, so 3 and 4 must disappear even though they pass the predicate.
    val actual = sample.filter { it != 2 }
    val expected = h(
      intArrayOf(1, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 0, 1, 0, 1, 1, 2))
    assertFiltered(expected, actual)
  }

  @Test
  fun rejectedNodeDoesNotAffectFollowingSibling() {
    // 9 rejected; its sibling 10 (same depth) and 10's child 11 must be kept.
    val actual = sample.filter { it != 9 }
    val expected = h(
      intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11),
      intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 2))
    assertFiltered(expected, actual)
  }

  @Test
  fun skipEndsWhenDepthReturnsToShallowerLevel() {
    // 3 is rejected (depth 2); after its subtree, 5 (depth 1) must be kept again.
    val actual = sample.filter { it != 3 }
    val expected = h(
      intArrayOf(1, 2, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 1, 0, 1, 0, 1, 1, 2))
    assertFiltered(expected, actual)
  }

  @Test
  fun forestOfSingleRoots() {
    val flat = h(intArrayOf(1, 2, 3, 4), intArrayOf(0, 0, 0, 0))
    val actual = flat.filter { it % 2 == 0 }
    assertFiltered(h(intArrayOf(2, 4), intArrayOf(0, 0)), actual)
  }

  @Test
  fun singleNode() {
    val single = h(intArrayOf(7), intArrayOf(0))
    assertFiltered(single, single.filter { true })
    assertEquals(0, single.filter { false }.size)
  }

  @Test
  fun deepChain() {
    val n = 5
    val chain = h(IntArray(n) { it + 1 }, IntArray(n) { it })
    // Reject the middle node: everything below it is gone.
    assertFiltered(
      h(intArrayOf(1, 2), intArrayOf(0, 1)),
      chain.filter { it != 3 })
  }

  @Test
  fun predicateNotCalledForNodesInRejectedSubtree() {
    val seen = mutableListOf<Int>()
    sample.filter { id -> seen.add(id); id != 2 }
    // 3 and 4 are inside the rejected subtree of 2 and must not be evaluated.
    assertEquals(listOf(1, 2, 5, 6, 7, 8, 9, 10, 11), seen)
  }

  @Test
  fun originalIsNotModified() {
    val before = sample.formatString()
    sample.filter { it % 2 == 0 }
    assertEquals(before, sample.formatString())
  }

  @Test
  fun filterOfFilterEqualsCombinedPredicate() {
    val a = sample.filter { it != 5 }.filter { it != 10 }
    val b = sample.filter { it != 5 && it != 10 }
    assertFiltered(b, a)
  }

  @Test
  fun largeInputIsLinear() {
    // 1M nodes: roots with a single child each. Should run well under a second.
    val n = 1_000_000
    val big = h(IntArray(n) { it }, IntArray(n) { it % 2 })
    val result = big.filter { it % 4 != 0 }
    // Every even index (root) divisible by 4 is rejected along with its child.
    assertEquals(n / 2, result.size)
  }
}
