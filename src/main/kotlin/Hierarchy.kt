/**
 * A `Hierarchy` stores an arbitrary _forest_ (an ordered collection of ordered trees)
 * as an array of node IDs in the order of DFS traversal, combined with a parallel array of node depths.
 *
 * Parent-child relationships are identified by the position in the array and the associated depth.
 * Each tree root has depth 0, its children have depth 1 and follow it in the array, their children have depth 2 and follow them, etc.
 *
 * Example:
 * ```
 * nodeIds: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
 * depths:  0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2
 * ```
 *
 * the forest can be visualized as follows:
 * ```
 * 1
 * - 2
 * - - 3
 * - - - 4
 * - 5
 * 6
 * - 7
 * 8
 * - 9
 * - 10
 * - - 11
 *```
 * 1 is a parent of 2 and 5, 2 is a parent of 3, etc. Note that depth is equal to the number of hyphens for each node.
 *
 * Invariants on the depths array:
 *  * Depth of the first element is 0.
 *  * If the depth of a node is `D`, the depth of the next node in the array can be:
 *      * `D + 1` if the next node is a child of this node;
 *      * `D` if the next node is a sibling of this node;
 *      * `d < D` - in this case the next node is not related to this node.
 */
interface Hierarchy {
  /** The number of nodes in the hierarchy. */
  val size: Int

  /**
   * Returns the unique ID of the node identified by the hierarchy index. The depth for this node will be `depth(index)`.
   * @param index must be non-negative and less than [size]
   * */
  fun nodeId(index: Int): Int

  /**
   * Returns the depth of the node identified by the hierarchy index. The unique ID for this node will be `nodeId(index)`.
   * @param index must be non-negative and less than [size]
   * */
  fun depth(index: Int): Int

  fun formatString(): String {
    return (0 until size).joinToString(
      separator = ", ",
      prefix = "[",
      postfix = "]"
    ) { i -> "${nodeId(i)}:${depth(i)}" }
  }
}

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 *
 * Single pass, O(n) time, O(n) extra space for the result.
 *
 * Because the nodes are stored in DFS order, the subtree of a node is the contiguous run of
 * following nodes with a strictly greater depth. When a node is rejected we remember its depth
 * and skip everything deeper than that until we reach a node with depth <= the rejected depth.
 *
 * Since every kept node has all of its ancestors kept, its depth in the filtered forest is
 * unchanged, so depths are copied as is. The predicate is not invoked for nodes inside a
 * rejected subtree, and is called at most once per node.
 */
fun Hierarchy.filter(nodeIdPredicate: (Int) -> Boolean): Hierarchy {
  val n = size
  val ids = IntArray(n)
  val depths = IntArray(n)
  var count = 0

  // Depth of the shallowest rejected node whose subtree we are currently skipping.
  // Int.MAX_VALUE means "not skipping".
  var skipDepth = Int.MAX_VALUE

  for (i in 0 until n) {
    val d = depth(i)
    if (d > skipDepth) continue // inside a rejected subtree
    skipDepth = Int.MAX_VALUE   // left the rejected subtree (or never in one)

    val id = nodeId(i)
    if (nodeIdPredicate(id)) {
      ids[count] = id
      depths[count] = d
      count++
    } else {
      skipDepth = d
    }
  }

  return ArrayBasedHierarchy(ids.copyOf(count), depths.copyOf(count))
}

class ArrayBasedHierarchy(
  private val myNodeIds: IntArray,
  private val myDepths: IntArray,
) : Hierarchy {
  override val size: Int = myDepths.size

  override fun nodeId(index: Int): Int = myNodeIds[index]

  override fun depth(index: Int): Int = myDepths[index]
}

