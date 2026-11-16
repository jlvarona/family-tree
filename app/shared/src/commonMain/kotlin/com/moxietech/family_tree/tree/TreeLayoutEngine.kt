package com.moxietech.family_tree.tree

/** Card and spacing sizes in dp; the canvas draws at these coordinates. */
data class LayoutConfig(
    val cardWidth: Float = 208f,
    val cardHeight: Float = 72f,
    /** Between two partners, wide enough to show the drop to their children. */
    val partnerGap: Float = 32f,
    /** Between unrelated neighbours on the same generation. */
    val siblingGap: Float = 48f,
    val levelGap: Float = 96f,
    val margin: Float = 32f,
)

/** Top-left corner of a person's card. */
data class PlacedPerson(val personId: String, val x: Float, val y: Float, val generation: Int)

data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

data class TreeLayout(
    val width: Float,
    val height: Float,
    val people: List<PlacedPerson>,
    val segments: List<Segment>,
    /**
     * Child links not drawn because they would make someone their own ancestor
     * (a loop in the data) or list a partner as their own child.
     */
    val skippedLinks: Int,
) {
    companion object {
        val EMPTY = TreeLayout(0f, 0f, emptyList(), emptyList(), 0)
    }
}

/**
 * Positions people and union connectors for any [GraphSlice], top generation
 * first, in the style of the tree mockup: partners side by side joined by a
 * line, children hanging from the middle of that line on a shared bar.
 *
 * Steps (a small layered-graph layout):
 * 1. **Loops.** A depth-first walk over parent → child links marks every link
 *    that closes a cycle; those links are skipped, so bad data still renders.
 * 2. **Generations.** Children sit below all their parents (a child in two
 *    unions, e.g. birth and adoptive, goes below the lower one). Partners share
 *    a generation, and people without parents sit just above their highest
 *    child. When those rules can't all hold (a partner who is also an
 *    ancestor), plain parent/child generations are used.
 * 3. **Order.** Partners on the same generation form blocks. Blocks are
 *    ordered and placed by the average position of their parents, then again
 *    bottom-up by their children, so parents end up centred over children.
 * 4. **Connectors** are straight segments in the same coordinates.
 *
 * Pure and deterministic: the same slice gives the same layout.
 */
object TreeLayoutEngine {

    private const val MAX_LEVEL_ROUNDS = 100

    fun layout(slice: GraphSlice, config: LayoutConfig = LayoutConfig()): TreeLayout {
        val ids = slice.personIds.distinct()
        if (ids.isEmpty()) return TreeLayout.EMPTY
        val n = ids.size
        val index = HashMap<String, Int>(n * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }

        var skipped = 0
        val unions = slice.unions.mapNotNull { union ->
            val partners = union.partners().mapNotNull { index[it] }.distinct()
            if (partners.isEmpty()) return@mapNotNull null
            val allChildren = union.children.mapNotNull { index[it.childId] }.distinct()
            val children = allChildren.filter { it !in partners }
            skipped += allChildren.size - children.size
            LayoutUnion(partners.toIntArray(), children.toIntArray())
        }

        val broken = findLoopLinks(n, unions)
        skipped += broken.size

        val parentsOf = Array(n) { LinkedHashSet<Int>() }
        val childrenOf = Array(n) { LinkedHashSet<Int>() }
        unions.forEachIndexed { u, union ->
            for (c in union.children) {
                if (linkKey(u, c) in broken) continue
                for (p in union.partners) {
                    parentsOf[c] += p
                    childrenOf[p] += c
                }
            }
        }

        val level = generations(n, unions, parentsOf, childrenOf)
        val x = order(n, unions, level, parentsOf, childrenOf, config)

        val minX = x.min() - config.cardWidth / 2
        val shift = config.margin - minX
        val levelHeight = config.cardHeight + config.levelGap
        val placed = List(n) { i ->
            PlacedPerson(ids[i], x[i] + shift - config.cardWidth / 2, config.margin + level[i] * levelHeight, level[i])
        }
        val segments = connectors(unions, broken, placed, config)
        val width = placed.maxOf { it.x } + config.cardWidth + config.margin
        val height = placed.maxOf { it.y } + config.cardHeight + config.margin
        return TreeLayout(width, height, placed, segments, skipped)
    }

    private class LayoutUnion(val partners: IntArray, val children: IntArray)

    private fun linkKey(union: Int, child: Int): Long = (union.toLong() shl 32) or child.toLong()

    /** Depth-first walk from people without parents first; links reaching an open node close a loop. */
    private fun findLoopLinks(n: Int, unions: List<LayoutUnion>): Set<Long> {
        val unionsOf = Array(n) { ArrayList<Int>() }
        val hasParent = BooleanArray(n)
        unions.forEachIndexed { u, union ->
            union.partners.forEach { unionsOf[it] += u }
            union.children.forEach { hasParent[it] = true }
        }
        val broken = HashSet<Long>()
        val color = IntArray(n) // 0 unvisited, 1 on the current path, 2 done
        val starts = (0 until n).filter { !hasParent[it] } + (0 until n).filter { hasParent[it] }
        for (start in starts) {
            if (color[start] != 0) continue
            val stack = ArrayDeque<IntArray>() // [node, union position, child position]
            stack.addLast(intArrayOf(start, 0, 0))
            color[start] = 1
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val own = unionsOf[frame[0]]
                if (frame[1] >= own.size) {
                    color[frame[0]] = 2
                    stack.removeLast()
                    continue
                }
                val u = own[frame[1]]
                val children = unions[u].children
                if (frame[2] >= children.size) {
                    frame[1]++
                    frame[2] = 0
                    continue
                }
                val child = children[frame[2]++]
                val key = linkKey(u, child)
                if (key in broken) continue
                when (color[child]) {
                    0 -> {
                        color[child] = 1
                        stack.addLast(intArrayOf(child, 0, 0))
                    }
                    1 -> broken += key
                }
            }
        }
        return broken
    }

    private fun generations(
        n: Int,
        unions: List<LayoutUnion>,
        parentsOf: Array<LinkedHashSet<Int>>,
        childrenOf: Array<LinkedHashSet<Int>>,
    ): IntArray {
        // Topological order of the loop-free parent → child links.
        val indegree = IntArray(n) { parentsOf[it].size }
        val queue = ArrayDeque((0 until n).filter { indegree[it] == 0 })
        val topo = ArrayList<Int>(n)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            topo += v
            for (c in childrenOf[v]) if (--indegree[c] == 0) queue.addLast(c)
        }
        if (topo.size < n) (0 until n).filterTo(topo) { it !in topo }

        fun pushChildrenDown(level: IntArray): Boolean {
            var changed = false
            for (v in topo) for (c in childrenOf[v]) {
                if (level[c] < level[v] + 1) {
                    level[c] = level[v] + 1
                    changed = true
                }
            }
            return changed
        }

        val base = IntArray(n)
        pushChildrenDown(base)
        val level = base.copyOf()
        var settled = false
        for (attempt in 1..MAX_LEVEL_ROUNDS) {
            var changed = false
            for (union in unions) {
                if (union.partners.size < 2) continue
                val top = union.partners.maxOf { level[it] }
                for (p in union.partners) if (level[p] < top) {
                    level[p] = top
                    changed = true
                }
            }
            for (v in 0 until n) {
                if (parentsOf[v].isNotEmpty() || childrenOf[v].isEmpty()) continue
                val target = childrenOf[v].minOf { level[it] } - 1
                if (level[v] < target) {
                    level[v] = target
                    changed = true
                }
            }
            if (pushChildrenDown(level)) changed = true
            if (!changed) {
                settled = true
                break
            }
        }
        val result = if (settled) level else base
        val lowest = result.min()
        return IntArray(n) { result[it] - lowest }
    }

    /** Returns each person's card centre x (before shifting into the margin). */
    private fun order(
        n: Int,
        unions: List<LayoutUnion>,
        level: IntArray,
        parentsOf: Array<LinkedHashSet<Int>>,
        childrenOf: Array<LinkedHashSet<Int>>,
        config: LayoutConfig,
    ): FloatArray {
        val maxLevel = level.max()
        val partnersOf = Array(n) { LinkedHashSet<Int>() }
        for (union in unions) {
            for (a in union.partners) for (b in union.partners) {
                if (a != b && level[a] == level[b]) partnersOf[a] += b
            }
        }

        // Blocks: people joined by partnerships on the same generation, as a chain.
        val blocksByLevel = Array(maxLevel + 1) { ArrayList<IntArray>() }
        val seen = BooleanArray(n)
        for (v in 0 until n) {
            if (seen[v]) continue
            val component = ArrayList<Int>()
            val pending = ArrayDeque(listOf(v))
            seen[v] = true
            while (pending.isNotEmpty()) {
                val w = pending.removeFirst()
                component += w
                for (p in partnersOf[w]) if (!seen[p]) {
                    seen[p] = true
                    pending.addLast(p)
                }
            }
            blocksByLevel[level[v]] += chain(component, partnersOf)
        }

        val center = FloatArray(n)
        val card = config.cardWidth
        fun blockWidth(block: IntArray) = block.size * card + (block.size - 1) * config.partnerGap

        fun place(blocks: List<IntArray>, desiredLeft: (IntArray) -> Float?) {
            val keyed = blocks.map { it to desiredLeft(it) }
            val sorted = keyed.withIndex().sortedWith(
                compareBy<IndexedValue<Pair<IntArray, Float?>>> { it.value.second == null }
                    .thenBy { it.value.second ?: 0f }
                    .thenBy { it.index },
            )
            var right = Float.NEGATIVE_INFINITY
            for ((_, entry) in sorted) {
                val (block, desired) = entry
                val left = if (right == Float.NEGATIVE_INFINITY) {
                    desired ?: 0f
                } else {
                    maxOf(desired ?: Float.NEGATIVE_INFINITY, right + config.siblingGap)
                }
                block.forEachIndexed { i, person -> center[person] = left + i * (card + config.partnerGap) + card / 2 }
                right = left + blockWidth(block)
            }
        }

        fun parentsAnchor(block: IntArray): Float? {
            val anchors = block.flatMap { person -> parentsOf[person].map { center[it] } }
            return if (anchors.isEmpty()) null else anchors.average().toFloat() - blockWidth(block) / 2
        }

        fun childrenAnchor(block: IntArray): Float? {
            val anchors = block.flatMap { person -> childrenOf[person].map { center[it] } }
            return if (anchors.isEmpty()) null else anchors.average().toFloat() - blockWidth(block) / 2
        }

        // Top-down: the first generation in input order, then under the parents.
        place(blocksByLevel[0]) { null }
        for (l in 1..maxLevel) place(blocksByLevel[l], ::parentsAnchor)
        // Bottom-up: centre parents over their children; others keep their place.
        for (l in maxLevel - 1 downTo 0) {
            place(blocksByLevel[l]) { block -> childrenAnchor(block) ?: (center[block[0]] - card / 2) }
        }
        return center
    }

    /** Orders a partner component so each partnership is between neighbours where possible. */
    private fun chain(component: List<Int>, partnersOf: Array<LinkedHashSet<Int>>): IntArray {
        if (component.size == 1) return intArrayOf(component[0])
        val members = component.toSet()
        val start = component.firstOrNull { p -> partnersOf[p].count { it in members } <= 1 } ?: component[0]
        val result = ArrayList<Int>(component.size)
        val visited = HashSet<Int>()
        fun visit(p: Int) {
            if (!visited.add(p)) return
            result += p
            partnersOf[p].filter { it in members }.forEach(::visit)
        }
        visit(start)
        component.filterNot { it in visited }.forEach { result += it }
        return result.toIntArray()
    }

    private fun connectors(
        unions: List<LayoutUnion>,
        broken: Set<Long>,
        placed: List<PlacedPerson>,
        config: LayoutConfig,
    ): List<Segment> {
        val segments = ArrayList<Segment>()
        val w = config.cardWidth
        val h = config.cardHeight
        unions.forEachIndexed { u, union ->
            val partners = union.partners.map { placed[it] }.sortedBy { it.x }
            val anchorX: Float
            val anchorY: Float
            if (partners.size >= 2) {
                val left = partners[0]
                val right = partners[1]
                val x1 = left.x + w
                val y1 = left.y + h / 2
                val x2 = right.x
                val y2 = right.y + h / 2
                segments += Segment(x1, y1, x2, y2)
                anchorX = (x1 + x2) / 2
                anchorY = (y1 + y2) / 2
            } else {
                anchorX = partners[0].x + w / 2
                anchorY = partners[0].y + h
            }
            val children = union.children.filter { linkKey(u, it) !in broken }.map { placed[it] }
            if (children.isEmpty()) return@forEachIndexed
            // Small per-union offset so two families' bars on one generation don't merge.
            val partnersBottom = partners.maxOf { it.y } + h
            val barY = partnersBottom + config.levelGap / 2 + ((u % 3) - 1) * 6f
            segments += Segment(anchorX, anchorY, anchorX, barY)
            val centres = children.map { it.x + w / 2 }
            val minX = minOf(anchorX, centres.min())
            val maxX = maxOf(anchorX, centres.max())
            if (maxX > minX) segments += Segment(minX, barY, maxX, barY)
            children.forEach { child -> segments += Segment(child.x + w / 2, barY, child.x + w / 2, child.y) }
        }
        return segments
    }
}
