package net.activitywatch.android.watcher

import android.view.accessibility.AccessibilityNodeInfo

// Generic depth-first search for the first descendant (including `node` itself) matching
// `predicate`. Every rejected node visited along the way is recycled; the matching node is
// left un-recycled for the caller to use and eventually recycle.
internal fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (predicate(node)) return node
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = findNode(child, predicate)
        if (found != null) {
            if (found !== child) child.recycle()
            return found
        }
        child.recycle()
    }
    return null
}

// Generic depth-first visit of `node` and every descendant. `node` itself is left for the
// caller to recycle (they own that reference); every descendant is recycled once its own
// subtree has been fully visited.
internal fun forEachNode(node: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo, Int) -> Unit) {
    visit(node, depth)
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        forEachNode(child, depth + 1, visit)
        child.recycle()
    }
}
