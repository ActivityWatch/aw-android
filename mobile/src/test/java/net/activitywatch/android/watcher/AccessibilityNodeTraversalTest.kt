package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// A stand-in for AccessibilityNodeInfo (final, and not usable in local unit tests) that
// counts recycle() calls so the tests can assert the recycle contract exactly.
private class FakeNode(val id: String, vararg children: FakeNode?) {
    val children: List<FakeNode?> = children.toList()
    var recycleCount = 0
}

private class FakeOps : NodeOps<FakeNode> {
    var getChildCalls = 0
    var recycleCalls = 0

    override fun childCount(node: FakeNode): Int = node.children.size

    override fun getChild(node: FakeNode, index: Int): FakeNode? {
        getChildCalls++
        return node.children[index]
    }

    override fun recycle(node: FakeNode) {
        recycleCalls++
        node.recycleCount++
    }
}

// A straight chain of `length` nodes ending in `leaf`, returning the topmost one. Built
// iteratively so the test helper itself doesn't blow the stack for the pathological cases.
private fun chainTo(leaf: FakeNode, length: Int, prefix: String = "n"): FakeNode {
    var node = leaf
    for (i in length - 2 downTo 0) {
        node = FakeNode("$prefix$i", node)
    }
    return node
}

private fun chain(length: Int, prefix: String = "n"): FakeNode =
    chainTo(FakeNode("$prefix${length - 1}"), length, prefix)

private fun allNodes(root: FakeNode): List<FakeNode> {
    val out = mutableListOf<FakeNode>()
    val pending = mutableListOf(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeAt(pending.size - 1)
        out.add(node)
        node.children.filterNotNull().forEach { pending.add(it) }
    }
    return out
}

class AccessibilityNodeTraversalTest {

    private val ops = FakeOps()
    private val warnings = mutableListOf<String>()
    private val onLimit: (String) -> Unit = { warnings.add(it) }

    //
    // Behaviour preserved from the previous recursive implementation
    //

    @Test
    fun `findNode returns the root without touching children when the root matches`() {
        val root = FakeNode("root", FakeNode("a"), FakeNode("b"))

        val found = findNodeIn(root, ops, onLimit) { it.id == "root" }

        assertSame(root, found)
        assertEquals(0, ops.getChildCalls)
        assertEquals(0, ops.recycleCalls)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `findNode visits depth-first pre-order and returns the first match`() {
        //        root
        //       /    \
        //      a      d
        //     / \      \
        //    b   c      target
        val b = FakeNode("b")
        val c = FakeNode("c")
        val a = FakeNode("a", b, c)
        val target = FakeNode("target")
        val d = FakeNode("d", target)
        val root = FakeNode("root", a, d)

        val order = mutableListOf<String>()
        val found = findNodeIn(root, ops, onLimit) {
            order.add(it.id)
            it.id == "target"
        }

        assertSame(target, found)
        assertEquals(listOf("root", "a", "b", "c", "d", "target"), order)
    }

    @Test
    fun `findNode recycles every node it obtained except the match`() {
        val b = FakeNode("b")
        val c = FakeNode("c")
        val a = FakeNode("a", b, c)
        val target = FakeNode("target")
        val d = FakeNode("d", target)
        val root = FakeNode("root", a, d)

        val found = findNodeIn(root, ops, onLimit) { it.id == "target" }

        assertSame(target, found)
        assertEquals("match must be left for the caller", 0, target.recycleCount)
        assertEquals("root is owned by the caller", 0, root.recycleCount)
        // Everything else, including the ancestors of the match, is recycled exactly once.
        listOf(a, b, c, d).forEach { assertEquals(it.id, 1, it.recycleCount) }
    }

    @Test
    fun `findNode recycles the whole tree when nothing matches`() {
        val root = FakeNode(
            "root",
            FakeNode("a", FakeNode("b"), FakeNode("c")),
            FakeNode("d", FakeNode("e", FakeNode("f"))),
        )
        val all = allNodes(root)

        assertNull(findNodeIn(root, ops, onLimit) { false })

        assertEquals(0, root.recycleCount)
        all.filter { it !== root }.forEach { assertEquals(it.id, 1, it.recycleCount) }
        assertEquals(all.size - 1, ops.recycleCalls)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `traversal skips null children`() {
        val a = FakeNode("a")
        val root = FakeNode("root", null, a, null)

        val visited = mutableListOf<String>()
        forEachNodeIn(root, ops, onLimit) { node, _ -> visited.add(node.id) }

        assertEquals(listOf("root", "a"), visited)
        assertEquals(3, ops.getChildCalls)
        assertEquals(1, a.recycleCount)
        assertEquals(1, ops.recycleCalls)
    }

    @Test
    fun `forEachNode reports the depth of every node and recycles all descendants`() {
        val root = FakeNode(
            "root",
            FakeNode("a", FakeNode("b", FakeNode("c"))),
            FakeNode("d"),
        )
        val all = allNodes(root)

        val seen = mutableListOf<Pair<String, Int>>()
        forEachNodeIn(root, ops, onLimit) { node, depth -> seen.add(node.id to depth) }

        assertEquals(
            listOf("root" to 0, "a" to 1, "b" to 2, "c" to 3, "d" to 1),
            seen,
        )
        assertEquals(0, root.recycleCount)
        all.filter { it !== root }.forEach { assertEquals(it.id, 1, it.recycleCount) }
        assertEquals(all.size - 1, ops.recycleCalls)
        assertTrue(warnings.isEmpty())
    }

    //
    // Bounds (ActivityWatch/aw-android#267)
    //

    @Test
    fun `findNode survives a pathologically deep tree and respects the depth cap`() {
        // The recursive implementation blew the 8MB stack here: StackOverflowError inside
        // AccessibilityNodeInfo.getChild, 16 reports / 8 users in a week on v0.14.0b2.
        val root = chain(10_000)

        var visited = 0
        val found = findNodeIn(root, ops, onLimit) {
            visited++
            false
        }

        assertNull(found)
        assertEquals(MAX_TRAVERSAL_DEPTH, visited)
        // One getChild() per descent (63) plus the one node fetched at the cap and dropped.
        assertEquals(MAX_TRAVERSAL_DEPTH, ops.getChildCalls)
        // Every node we obtained is recycled; the root belongs to the caller.
        assertEquals(MAX_TRAVERSAL_DEPTH, ops.recycleCalls)
        assertEquals(0, root.recycleCount)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("depth limit"))
    }

    @Test
    fun `forEachNode survives a pathologically deep tree and respects the depth cap`() {
        val root = chain(10_000)

        val depths = mutableListOf<Int>()
        forEachNodeIn(root, ops, onLimit) { _, depth -> depths.add(depth) }

        assertEquals((0 until MAX_TRAVERSAL_DEPTH).toList(), depths)
        assertEquals(MAX_TRAVERSAL_DEPTH, ops.recycleCalls)
        assertEquals(0, root.recycleCount)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("depth limit"))
    }

    @Test
    fun `depth cap prunes the deep branch but keeps searching its siblings`() {
        val target = FakeNode("target")
        val root = FakeNode("root", chain(10_000, prefix = "deep"), target)

        val found = findNodeIn(root, ops, onLimit) { it.id == "target" }

        assertSame(target, found)
        assertEquals(0, target.recycleCount)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("depth limit"))
    }

    @Test
    fun `findNode respects the node budget on a very wide tree`() {
        val children = Array(MAX_TRAVERSAL_NODES * 2) { FakeNode("c$it") }
        val root = FakeNode("root", *children)

        var visited = 0
        val found = findNodeIn(root, ops, onLimit) {
            visited++
            false
        }

        assertNull(found)
        assertEquals(MAX_TRAVERSAL_NODES, visited)
        // The budget covers the root plus every getChild() call, so budget - 1 children
        // were fetched, visited and recycled, and the rest were never asked for.
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.getChildCalls)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.recycleCalls)
        assertEquals(0, root.recycleCount)
        children.take(MAX_TRAVERSAL_NODES - 1).forEach { assertEquals(it.id, 1, it.recycleCount) }
        children.drop(MAX_TRAVERSAL_NODES - 1).forEach { assertEquals(it.id, 0, it.recycleCount) }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("node budget"))
    }

    @Test
    fun `forEachNode respects the node budget on a very wide tree`() {
        val children = Array(MAX_TRAVERSAL_NODES * 2) { FakeNode("c$it") }
        val root = FakeNode("root", *children)

        var visited = 0
        forEachNodeIn(root, ops, onLimit) { _, _ -> visited++ }

        assertEquals(MAX_TRAVERSAL_NODES, visited)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.getChildCalls)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.recycleCalls)
        assertEquals(0, root.recycleCount)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("node budget"))
    }

    @Test
    fun `a match beyond the node budget is not returned but everything is still recycled`() {
        val children = Array(MAX_TRAVERSAL_NODES * 2) { FakeNode("c$it") }
        val root = FakeNode("root", *children)

        val found = findNodeIn(root, ops, onLimit) { it.id == "c${MAX_TRAVERSAL_NODES * 2 - 1}" }

        assertNull(found)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.recycleCalls)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `node budget covers getChild calls that return null`() {
        // A stale node can report thousands of children and hand back null for each. Those
        // are still binder round-trips on the accessibility service's main thread, so they
        // have to be paid for out of the budget.
        val root = FakeNode("root", *arrayOfNulls<FakeNode>(MAX_TRAVERSAL_NODES * 10))

        var visited = 0
        forEachNodeIn(root, ops, onLimit) { _, _ -> visited++ }

        assertEquals("only the root is a real node", 1, visited)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.getChildCalls)
        assertEquals(0, ops.recycleCalls)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("node budget"))
    }

    @Test
    fun `node budget covers children fetched at the depth bound`() {
        // A very wide node sitting right at the depth bound: every one of its children is
        // fetched and immediately dropped, so without the budget covering those fetches it
        // could stall the main thread just as badly as an unbounded descent.
        val wide = FakeNode("wide", *Array(MAX_TRAVERSAL_NODES * 10) { FakeNode("w$it") })
        val root = FakeNode("root", chainTo(wide, MAX_TRAVERSAL_DEPTH - 1))

        assertNull(findNodeIn(root, ops, onLimit) { false })

        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.getChildCalls)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.recycleCalls)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("node budget"))
        assertTrue(warnings[0], warnings[0].contains("depth limit"))
    }

    @Test
    fun `a single warning is logged when both bounds are hit`() {
        // A wide tree of deep chains: the depth cap prunes each chain, and the sheer number
        // of nodes then exhausts the node budget.
        val root = FakeNode("root", *Array(100) { chain(200, prefix = "d$it-") })

        assertNull(findNodeIn(root, ops, onLimit) { false })

        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("node budget"))
        assertTrue(warnings[0], warnings[0].contains("depth limit"))
    }
}
