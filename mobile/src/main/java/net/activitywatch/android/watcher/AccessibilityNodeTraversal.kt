package net.activitywatch.android.watcher

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "NodeTraversal"

// Traversal limits. The shape of a browser's accessibility tree is decided by the page it
// is showing, so it is untrusted input as far as this process is concerned: it can be tens
// of thousands of nodes deep or wide, and stale children can make it look cyclic.
// Recursing over one blew the 8MB stack in production (StackOverflowError inside
// AccessibilityNodeInfo.getChild, ActivityWatch/aw-android#267), so the traversal below is
// iterative *and* bounded.
//
// Depth is counted with the root at depth 0, so at most MAX_TRAVERSAL_DEPTH levels are
// visited (the deepest visited depth is MAX_TRAVERSAL_DEPTH - 1). Real toolbars and
// webviews sit within ~20 levels of the window root; anything past 64 is pathological.
internal const val MAX_TRAVERSAL_DEPTH = 64

// Total number of nodes a traversal may obtain: the root, plus one per getChild() call.
// A depth bound alone does not help against a wide tree, and this deliberately counts
// *every* getChild() — including calls that return null and calls for a node too deep to
// descend into — because each one is a binder round-trip on the accessibility service's
// main thread. Counting only the nodes we actually visit would let a stale node with
// thousands of null children, or a very wide node sitting at the depth bound, stall that
// thread just as badly.
internal const val MAX_TRAVERSAL_NODES = 2000

// The operations the traversal core needs from a node. Exists so the core can be exercised
// by local unit tests: AccessibilityNodeInfo is final and unavailable outside an
// instrumented/Robolectric environment.
internal interface NodeOps<T : Any> {
    fun childCount(node: T): Int
    fun getChild(node: T, index: Int): T?
    fun recycle(node: T)
}

private object AccessibilityNodeOps : NodeOps<AccessibilityNodeInfo> {
    override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount
    override fun getChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)
    override fun recycle(node: AccessibilityNodeInfo) = node.recycle()
}

private class Frame<T : Any>(val node: T, val depth: Int, val childCount: Int, val owned: Boolean) {
    var nextChild = 0
}

// Bounded, iterative, depth-first pre-order traversal — the same visit order and the same
// `depth` values a plain recursive walk would produce.
//
// `visit` returns true to stop the traversal and claim the node it was given; that node is
// then returned to the caller and is the only node the traversal does not recycle. Every
// other node obtained via getChild() is recycled exactly once, as soon as its own subtree
// is done (or immediately, if a bound stopped us from descending into it). `root` is never
// recycled — the caller owns that reference.
//
// Exceeding either bound truncates the traversal and calls `onLimit` exactly once, with
// both reasons if both were hit: a subtree deeper than MAX_TRAVERSAL_DEPTH is skipped and
// its siblings are still visited, while spending the MAX_TRAVERSAL_NODES budget stops the
// traversal entirely.
private fun <T : Any> traverse(
    root: T,
    ops: NodeOps<T>,
    onLimit: (String) -> Unit,
    visit: (T, Int) -> Boolean,
): T? {
    if (visit(root, 0)) return root

    var depthLimited = false
    var budgetExceeded = false
    // The root plus every node we ask for, spent before the call so no getChild() escapes
    // the bound.
    var obtained = 1
    var claimed: T? = null
    val stack = ArrayList<Frame<T>>()
    stack.add(Frame(root, depth = 0, childCount = ops.childCount(root), owned = false))

    while (stack.isNotEmpty()) {
        val frame = stack[stack.size - 1]
        if (frame.nextChild >= frame.childCount) {
            stack.removeAt(stack.size - 1)
            if (frame.owned) ops.recycle(frame.node)
            continue
        }
        if (obtained >= MAX_TRAVERSAL_NODES) {
            budgetExceeded = true
            break
        }
        obtained++
        val child = ops.getChild(frame.node, frame.nextChild++) ?: continue
        val childDepth = frame.depth + 1
        if (childDepth >= MAX_TRAVERSAL_DEPTH) {
            depthLimited = true
            ops.recycle(child)
            continue
        }
        if (visit(child, childDepth)) {
            claimed = child
            break
        }
        stack.add(Frame(child, childDepth, ops.childCount(child), owned = true))
    }

    // Unwind whatever is left when we broke out early: every node still on the stack except
    // the root was obtained by us, so it is ours to recycle. A claimed node is never pushed,
    // so it is never recycled here.
    for (i in stack.indices.reversed()) {
        val frame = stack[i]
        if (frame.owned) ops.recycle(frame.node)
    }

    if (depthLimited || budgetExceeded) {
        val reason = when {
            budgetExceeded && depthLimited ->
                "node budget ($MAX_TRAVERSAL_NODES) and depth limit ($MAX_TRAVERSAL_DEPTH)"
            budgetExceeded -> "node budget ($MAX_TRAVERSAL_NODES)"
            else -> "depth limit ($MAX_TRAVERSAL_DEPTH)"
        }
        onLimit("Accessibility tree traversal truncated: hit $reason after obtaining $obtained nodes")
    }
    return claimed
}

// Depth-first search for the first node (`root` itself, or a descendant) matching
// `predicate`. See traverse() for the bounds and the recycle contract.
internal fun <T : Any> findNodeIn(
    root: T,
    ops: NodeOps<T>,
    onLimit: (String) -> Unit,
    predicate: (T) -> Boolean,
): T? = traverse(root, ops, onLimit) { node, _ -> predicate(node) }

// Depth-first visit of `root` and every descendant. See traverse() for the bounds and the
// recycle contract.
internal fun <T : Any> forEachNodeIn(
    root: T,
    ops: NodeOps<T>,
    onLimit: (String) -> Unit,
    visit: (T, Int) -> Unit,
) {
    traverse(root, ops, onLimit) { node, depth ->
        visit(node, depth)
        false
    }
}

private val logLimit: (String) -> Unit = { Log.w(TAG, it) }

// Depth-first search for the first descendant (including `node` itself) matching
// `predicate`. Every rejected node visited along the way is recycled; the matching node is
// left un-recycled for the caller to use and eventually recycle.
internal fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
    findNodeIn(node, AccessibilityNodeOps, logLimit, predicate)

// Depth-first visit of `node` and every descendant. `node` itself is left for the caller to
// recycle (they own that reference); every descendant is recycled once its own subtree has
// been fully visited.
internal fun forEachNode(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo, Int) -> Unit) =
    forEachNodeIn(node, AccessibilityNodeOps, logLimit, visit)
