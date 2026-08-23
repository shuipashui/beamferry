package com.airferrylite.receiver

import kotlin.math.max
import kotlin.math.min

internal data class ScanRegion(val left: Int, val top: Int, val width: Int, val height: Int)

/** Geometric crops for 2×2 sender tiles that rarely line up with the camera midline. */
internal object ScanLayout {
    const val QUAD_OVERLAP = 0.18f
    const val ROI_MISS_LIMIT = 8
    private const val MIN_SIDE = 64

    fun centerSquare(width: Int, height: Int): ScanRegion {
        val side = min(width, height).coerceAtLeast(1)
        return ScanRegion((width - side) / 2, (height - side) / 2, side, side)
    }

    /** Left/right overlapping halves — dual sender codes sit on the top row of a 2×2 canvas. */
    fun dualHalves(region: ScanRegion): List<ScanRegion> {
        val cropW = ((0.5f + QUAD_OVERLAP) * region.width).toInt().coerceIn(1, region.width)
        val right = region.left + region.width - cropW
        return listOf(
            ScanRegion(region.left, region.top, cropW, region.height),
            ScanRegion(right, region.top, cropW, region.height)
        )
    }

    /** Top-left and top-right overlapping tiles — exclusive 2×2 splits codes on the midline. */
    fun dualTopTiles(region: ScanRegion): List<ScanRegion> = overlappingQuadrants(region).take(2)

    /**
     * Overlapping 2×2 covering the whole analysis buffer so first hits do not depend on
     * an invisible center square. 58% crops stay small enough for 2068 B dual codes.
     */
    fun coverageQuadrants(width: Int, height: Int): List<ScanRegion> {
        val cropW = (width * 0.58f).toInt().coerceIn(1, width)
        val cropH = (height * 0.58f).toInt().coerceIn(1, height)
        val right = (width - cropW).coerceAtLeast(0)
        val bottom = (height - cropH).coerceAtLeast(0)
        return listOf(
            ScanRegion(0, 0, cropW, cropH),
            ScanRegion(right, 0, cropW, cropH),
            ScanRegion(0, bottom, cropW, cropH),
            ScanRegion(right, bottom, cropW, cropH)
        )
    }

    fun horizontalSibling(tile: ScanRegion, width: Int, height: Int): ScanRegion {
        val shift = (tile.width * 1.08f).toInt().coerceAtLeast(1)
        val right = clamp(ScanRegion(tile.left + shift, tile.top, tile.width, tile.height), width, height)
        val left = clamp(ScanRegion(tile.left - shift, tile.top, tile.width, tile.height), width, height)
        val tileMid = tile.left + tile.width / 2
        val rightSep = kotlin.math.abs((right.left + right.width / 2) - tileMid)
        val leftSep = kotlin.math.abs((left.left + left.width / 2) - tileMid)
        val roomRight = tile.left + shift + tile.width <= width
        return if (roomRight && rightSep >= tile.width / 2) right
        else if (leftSep >= tile.width / 2) left
        else right
    }

    fun pairFromHit(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        val tile = regionFromPoints(points, imageWidth, imageHeight, 1)?.let {
            inflate(it, 1.18f, imageWidth, imageHeight)
        } ?: return emptyList()
        return listOf(tile, horizontalSibling(tile, imageWidth, imageHeight)).sortedBy { it.left }
    }

    /**
     * Wide rectangle covering one decoded QR plus its horizontal neighbor.
     * Must not go through clamp() — that forces a square and clips the sibling
     * (0.8.102 格 2 每帧 0.65).
     */
    fun pairBandFromHit(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): ScanRegion? {
        if (points.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((x, y) in points) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val qr = max(maxX - minX, maxY - minY).coerceAtLeast(1f)
        val bandW = (qr * 2.22f).toInt().coerceIn(MIN_SIDE, imageWidth)
        val bandH = (qr * 1.22f).toInt().coerceIn(MIN_SIDE, imageHeight)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val preferRight = cx < imageWidth / 2f
        val left = if (preferRight) {
            (cx - qr * 0.58f).toInt()
        } else {
            (cx + qr * 0.58f).toInt() - bandW
        }
        return clampRect(ScanRegion(left, (cy - bandH / 2f).toInt(), bandW, bandH), imageWidth, imageHeight)
    }

    fun dualTilesFromOneHit(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        val band = pairBandFromHit(points, imageWidth, imageHeight) ?: return emptyList()
        return dualHalves(band)
    }

    /** Tight crop around one decoded QR, preserving room for its quiet zone. */
    fun tileFromHit(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): ScanRegion? {
        if (points.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        // Bootstrap crops are deliberately wider than steady-state tracked tiles.
        // They must tolerate quiet-zone size, perspective and small framing changes.
        val side = (max(maxX - minX, maxY - minY) * 1.38f).toInt().coerceAtLeast(MIN_SIDE)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        return clamp(ScanRegion((cx - side / 2f).toInt(), (cy - side / 2f).toInt(), side, side), imageWidth, imageHeight)
    }

    /**
     * Camera Y is decoded without display rotation, so a visually horizontal pair may
     * be vertical in sensor coordinates. Probe every adjacent direction until the
     * second protocol-distinct QR establishes the real pair axis.
     */
    fun siblingCandidatesFromHit(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        val tile = tileFromHit(points, imageWidth, imageHeight) ?: return emptyList()
        val shift = (tile.width * 0.78f).toInt().coerceAtLeast(1)
        val candidates = listOf(
            ScanRegion(tile.left - shift, tile.top, tile.width, tile.height),
            ScanRegion(tile.left + shift, tile.top, tile.width, tile.height),
            ScanRegion(tile.left, tile.top - shift, tile.width, tile.height),
            ScanRegion(tile.left, tile.top + shift, tile.width, tile.height)
        ).map { clamp(it, imageWidth, imageHeight) }
        return candidates.distinct().filter { it != tile }
    }

    /**
     * Move a proven pair as one rigid unit from either visible member. This keeps the
     * missing crop aligned when the phone shifts and only one QR decodes in a frame.
     */
    fun followPairFromHit(
        pair: List<ScanRegion>,
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        if (pair.size != 2 || points.isEmpty()) return pair
        val fresh = regionFromPoints(points, imageWidth, imageHeight, 1)?.let {
            inflate(it, 1.18f, imageWidth, imageHeight)
        } ?: return pair
        val hitCx = points.map { it.first }.average()
        val hitCy = points.map { it.second }.average()
        val owner = pair.indices.minByOrNull { index ->
            val tile = pair[index]
            val dx = hitCx - (tile.left + tile.width / 2.0)
            val dy = hitCy - (tile.top + tile.height / 2.0)
            dx * dx + dy * dy
        } ?: return pair
        val anchor = pair[owner]
        val dx = (fresh.left + fresh.width / 2) - (anchor.left + anchor.width / 2)
        val dy = (fresh.top + fresh.height / 2) - (anchor.top + anchor.height / 2)
        return pair.mapIndexed { index, tile ->
            if (index == owner) fresh
            else clampRect(ScanRegion(tile.left + dx, tile.top + dy, tile.width, tile.height), imageWidth, imageHeight)
        }
    }

    fun exclusiveQuadrants(region: ScanRegion): List<ScanRegion> {
        val halfW = (region.width / 2).coerceAtLeast(1)
        val halfH = (region.height / 2).coerceAtLeast(1)
        val right = region.left + region.width - halfW
        val bottom = region.top + region.height - halfH
        return listOf(
            ScanRegion(region.left, region.top, halfW, halfH),
            ScanRegion(right, region.top, halfW, halfH),
            ScanRegion(region.left, bottom, halfW, halfH),
            ScanRegion(right, bottom, halfW, halfH)
        )
    }

    fun overlappingQuadrants(region: ScanRegion): List<ScanRegion> {
        val cropW = ((0.5f + QUAD_OVERLAP) * region.width).toInt().coerceIn(1, region.width)
        val cropH = ((0.5f + QUAD_OVERLAP) * region.height).toInt().coerceIn(1, region.height)
        val right = region.left + region.width - cropW
        val bottom = region.top + region.height - cropH
        return listOf(
            ScanRegion(region.left, region.top, cropW, cropH),
            ScanRegion(right, region.top, cropW, cropH),
            ScanRegion(region.left, bottom, cropW, cropH),
            ScanRegion(right, bottom, cropW, cropH)
        )
    }

    fun regionFromPoints(
        points: List<Pair<Float, Float>>,
        imageWidth: Int,
        imageHeight: Int,
        codeCount: Int,
        coverGrid: Boolean = false
    ): ScanRegion? {
        if (points.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((x, y) in points) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val boxW = (maxX - minX).coerceAtLeast(1f)
        val boxH = (maxY - minY).coerceAtLeast(1f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val side = when {
            codeCount >= 4 -> max(boxW, boxH) * 1.40f
            coverGrid && codeCount >= 2 -> max(boxW, boxH) * 2.15f
            coverGrid -> max(boxW, boxH) * 3.8f
            codeCount >= 2 -> max(boxW, boxH) * 1.70f
            else -> max(boxW, boxH) * 1.35f
        }
        val half = side / 2f
        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        return clamp(ScanRegion(left, top, side.toInt().coerceAtLeast(MIN_SIDE), side.toInt().coerceAtLeast(MIN_SIDE)), imageWidth, imageHeight)
    }

    fun union(first: ScanRegion?, second: ScanRegion, imageWidth: Int, imageHeight: Int): ScanRegion {
        if (first == null) return clampRect(second, imageWidth, imageHeight)
        val left = min(first.left, second.left)
        val top = min(first.top, second.top)
        val right = max(first.left + first.width, second.left + second.width)
        val bottom = max(first.top + first.height, second.top + second.height)
        return clampRect(ScanRegion(left, top, right - left, bottom - top), imageWidth, imageHeight)
    }

    fun clamp(region: ScanRegion, width: Int, height: Int): ScanRegion {
        if (width <= 0 || height <= 0) return centerSquare(1, 1)
        val left = region.left.coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = region.top.coerceIn(0, (height - 1).coerceAtLeast(0))
        val cropW = region.width.coerceAtLeast(1).coerceAtMost(width - left)
        val cropH = region.height.coerceAtLeast(1).coerceAtMost(height - top)
        val side = min(cropW, cropH)
        if (side < MIN_SIDE) return centerSquare(width, height)
        return ScanRegion(left, top, side, side)
    }

    fun clampRect(region: ScanRegion, width: Int, height: Int): ScanRegion {
        if (width <= 0 || height <= 0) return centerSquare(1, 1)
        val left = region.left.coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = region.top.coerceIn(0, (height - 1).coerceAtLeast(0))
        val cropW = region.width.coerceAtLeast(1).coerceAtMost(width - left)
        val cropH = region.height.coerceAtLeast(1).coerceAtMost(height - top)
        if (cropW < MIN_SIDE || cropH < MIN_SIDE) return centerSquare(width, height)
        return ScanRegion(left, top, cropW, cropH)
    }

    fun inflateRect(region: ScanRegion, factor: Float, width: Int, height: Int): ScanRegion {
        val grow = factor.coerceAtLeast(1f)
        val nw = (region.width * grow).toInt().coerceAtLeast(MIN_SIDE)
        val nh = (region.height * grow).toInt().coerceAtLeast(MIN_SIDE)
        val cx = region.left + region.width / 2
        val cy = region.top + region.height / 2
        return clampRect(ScanRegion(cx - nw / 2, cy - nh / 2, nw, nh), width, height)
    }

    fun tilesFromHits(
        hits: List<List<Pair<Float, Float>>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        val tiles = hits.mapNotNull { points ->
            regionFromPoints(points, imageWidth, imageHeight, 1)?.let {
                inflate(it, 1.18f, imageWidth, imageHeight)
            }
        }
        if (tiles.size <= 1) return tiles
        val midX = tiles.map { it.left + it.width / 2.0 }.average()
        val midY = tiles.map { it.top + it.height / 2.0 }.average()
        return tiles.sortedWith(
            compareBy<ScanRegion> { if (it.top + it.height / 2.0 < midY) 0 else 1 }
                .thenBy { if (it.left + it.width / 2.0 < midX) 0 else 1 }
        )
    }

    fun tileIndexContaining(tiles: List<ScanRegion>, x: Float, y: Float): Int {
        var owner = -1
        for (index in tiles.indices) {
            val tile = tiles[index]
            if (x >= tile.left && x < tile.left + tile.width && y >= tile.top && y < tile.top + tile.height) {
                if (owner >= 0) return -1
                owner = index
            }
        }
        return owner
    }

    /** Nearest containing tile; overlapping crops pick the closer center. */
    fun ownerIndex(tiles: List<ScanRegion>, x: Float, y: Float): Int {
        var best = -1
        var bestDist = Float.POSITIVE_INFINITY
        for (index in tiles.indices) {
            val tile = tiles[index]
            if (x < tile.left || x >= tile.left + tile.width || y < tile.top || y >= tile.top + tile.height) continue
            val dx = x - (tile.left + tile.width / 2f)
            val dy = y - (tile.top + tile.height / 2f)
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

    fun followContainedHits(
        tiles: List<ScanRegion>,
        hits: List<List<Pair<Float, Float>>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<ScanRegion> {
        if (tiles.isEmpty() || hits.isEmpty()) return tiles
        val next = tiles.toMutableList()
        val claimed = BooleanArray(tiles.size)
        for (points in hits) {
            if (points.isEmpty()) continue
            val cx = points.map { it.first }.average().toFloat()
            val cy = points.map { it.second }.average().toFloat()
            val index = tileIndexContaining(tiles, cx, cy)
            if (index < 0 || claimed[index]) continue
            val tile = regionFromPoints(points, imageWidth, imageHeight, 1)?.let {
                inflate(it, 1.18f, imageWidth, imageHeight)
            } ?: continue
            claimed[index] = true
            next[index] = tile
        }
        return next
    }

    fun inflate(region: ScanRegion, factor: Float, width: Int, height: Int): ScanRegion {
        val side = (max(region.width, region.height) * factor.coerceAtLeast(1f)).toInt().coerceAtLeast(MIN_SIDE)
        val cx = region.left + region.width / 2
        val cy = region.top + region.height / 2
        return clamp(ScanRegion(cx - side / 2, cy - side / 2, side, side), width, height)
    }

    fun activeRegion(tracked: ScanRegion?, misses: Int, width: Int, height: Int): ScanRegion {
        if (tracked == null || misses >= ROI_MISS_LIMIT) return centerSquare(width, height)
        val wide = tracked.width > tracked.height * 1.12f
        val clamped = if (wide) clampRect(tracked, width, height) else clamp(tracked, width, height)
        if (misses <= 0) return clamped
        val grow = 1f + misses * 0.22f
        return if (wide) inflateRect(clamped, grow, width, height) else inflate(clamped, grow, width, height)
    }
}
