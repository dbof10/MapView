package com.peterlaurence.mapview.viewmodel

import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.Paint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.peterlaurence.mapview.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The view-model which contains all the logic related to [Tile] management.
 * It defers [Tile] loading to the [TileCollector].
 *
 * @author peterLaurence on 04/06/2019
 */
internal class TileCanvasViewModel(private val scope: CoroutineScope, tileSize: Int,
                          private val visibleTilesResolver: VisibleTilesResolver,
                          tileStreamProvider: TileStreamProvider,
                          private val tileOptionsProvider: TileOptionsProvider?,
                          workerCount: Int) : CoroutineScope by scope {
    private val tilesToRenderLiveData = MutableLiveData<List<Tile>>()
    private val renderTask = throttle<Unit>(wait = 34) {
        /* Right before sending tiles to the view, reorder them so that tiles from current level are
         * above others */
        tilesToRender.sortBy {
            it.zoom == lastVisible.level && it.subSample == lastVisible.subSample
        }
        tilesToRenderLiveData.postValue(tilesToRender)
    }

    private val bitmapPool = Pool<Bitmap>()
    private val paintPool = Pool<Paint>()
    private val visibleTileLocationsChannel = Channel<TileSpec>(capacity = Channel.RENDEZVOUS)
    private val tilesOutput = Channel<Tile>(capacity = Channel.RENDEZVOUS)
    private var tileCollectionJob: Job? = null

    /**
     * A [Flow] of [Bitmap] that first collects from the [bitmapPool] on the Main thread. If the
     * pool was empty, a new [Bitmap] is allocated from the calling thread. It's a simple way to
     * share data between coroutines in a thread safe way, using cold flows.
     */
    private val bitmapFlow: Flow<Bitmap> = flow {
        val bitmap = bitmapPool.get()
        emit(bitmap)
    }.flowOn(Dispatchers.Main).map {
        it ?: Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.RGB_565)
    }

    private lateinit var lastViewport: Viewport
    private lateinit var lastVisible: VisibleTiles
    private var lastVisibleCount: Int = 0
    private var idle = false

    /**
     * So long as this debounced channel is offered a message, the lambda isn't called.
     */
    private val idleDebounced = debounce<Unit> {
        idle = true
        evictTiles(lastVisible)
    }

    private var tilesToRender = mutableListOf<Tile>()

    init {
        /* Launch the TileCollector along with a coroutine to consume the produced tiles */
        with(TileCollector(workerCount)) {
            collectTiles(visibleTileLocationsChannel, tilesOutput, tileStreamProvider, bitmapFlow)
            consumeTiles(tilesOutput)
        }
    }

    fun getTilesToRender(): LiveData<List<Tile>> {
        return tilesToRenderLiveData
    }

    fun setViewport(viewport: Viewport) {
        /* It's important to set the idle flag to false before launching computations, so that
         * tile eviction don't happen too quickly (can cause blinks) */
        idle = false

        lastViewport = viewport
        val visibleTiles = visibleTilesResolver.getVisibleTiles(viewport)
        setVisibleTiles(visibleTiles)
    }

    private fun setVisibleTiles(visibleTiles: VisibleTiles) {
        /* Cancel the previous job, to avoid overwhelming the tile collector */
        tileCollectionJob?.cancel()
        tileCollectionJob = launch {
            collectNewTiles(visibleTiles)
        }

        lastVisible = visibleTiles
        lastVisibleCount = visibleTiles.count

        evictTiles(visibleTiles)

        renderThrottled()
    }

    /**
     * Leverage built-in back pressure, as this function will suspend when the tile collector is busy
     * to the point it can't handshake the [visibleTileLocationsChannel] channel.
     * Using [Flow], a new [TileSpec] instance is created right on time and if necessary, to avoid
     * allocating too much of these objects.
     */
    private suspend fun collectNewTiles(visibleTiles: VisibleTiles) {
        val tileSpecsWithoutTile = flow {
            for (e in visibleTiles.tileMatrix) {
                val row = e.key
                val colRange = e.value
                for (col in colRange) {
                    val alreadyProcessed = tilesToRender.any { tile ->
                        tile.sameSpecAs(visibleTiles.level, row, col, visibleTiles.subSample)
                    }
                    /* Only emit specs which haven't already been processed by the collector
                     * Doing this now results in less object allocations than filtering the flow
                     * afterwards */
                    if (!alreadyProcessed) {
                        emit(TileSpec(visibleTiles.level, row, col, visibleTiles.subSample))
                    }
                }
            }
        }

        /* Back pressure */
        tileSpecsWithoutTile.collect {
            visibleTileLocationsChannel.send(it)
        }
    }

    /**
     * For each [Tile] received, add it to the list of tiles to render if it's visible. Otherwise,
     * add the corresponding Bitmap to the [bitmapPool], and assign a [Paint] object to this tile.
     * The TileCanvasView manages the alpha, but the view-model takes care of recycling those objects.
     */
    private fun CoroutineScope.consumeTiles(tileChannel: ReceiveChannel<Tile>) = launch {
        for (tile in tileChannel) {
            if (lastVisible.contains(tile)) {
                if (!tilesToRender.contains(tile)) {
                    tile.setPaint()
                    tilesToRender.add(tile)
                    idleDebounced.offer(Unit)
                } else {
                    tile.recycle()
                }
                renderThrottled()
            } else {
                tile.recycle()
            }
        }
    }

    /**
     * Pick a [Paint] from the [paintPool], or create a new one. The the alpha needs to be set to 0,
     * to produce a fade-in effect. Color filter is also set.
     */
    private fun Tile.setPaint() {
        paint = (paintPool.get() ?: Paint()).also {
            it.alpha = 0
            it.colorFilter = tileOptionsProvider?.getColorFilter(row, col, zoom)
        }
    }

    private fun VisibleTiles.contains(tile: Tile): Boolean {
        val colRange = tileMatrix[tile.row] ?: return false
        return level == tile.zoom && subSample == tile.subSample && tile.col in colRange
    }

    private fun VisibleTiles.overlaps(tile: Tile): Boolean {
        val colRange = tileMatrix[tile.row] ?: return false
        return level == tile.zoom && tile.col in colRange
    }

    /**
     * Each time we get a new [VisibleTiles], remove all [Tile] from [tilesToRender] which aren't
     * visible or that aren't needed anymore and put their bitmap into the pool.
     */
    private fun evictTiles(visibleTiles: VisibleTiles) {
        val currentLevel = visibleTiles.level
        val currentSubSample = visibleTiles.subSample

        /* Always remove tiles that aren't visible at current level */
        val iterator = tilesToRender.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next()
            if (tile.zoom == currentLevel && tile.subSample == visibleTiles.subSample && !visibleTiles.contains(tile)) {
                iterator.remove()
                tile.recycle()
            }
        }

        if (!idle) {
            partialEviction(visibleTiles)
        } else {
            aggressiveEviction(currentLevel, currentSubSample)
        }
    }

    /**
     * Evict tiles for levels different than the current one, that aren't visible.
     */
    private fun partialEviction(visibleTiles: VisibleTiles) {
        val currentLevel = visibleTiles.level

        /* First, deal with tiles of other levels that aren't sub-sampled */
        val otherTilesNotSubSampled = tilesToRender.filter {
            it.zoom != currentLevel && it.subSample == 0
        }
        val evictList = mutableListOf<Tile>()
        if (otherTilesNotSubSampled.isNotEmpty()) {
            val byLevel = otherTilesNotSubSampled.groupBy { it.zoom }
            byLevel.forEach { (level, tiles) ->
                val visibleAtLevel = visibleTilesResolver.getVisibleTiles(lastViewport, level)
                tiles.filter {
                    !visibleAtLevel.overlaps(it)
                }.let {
                    evictList.addAll(it)
                }
            }
        }

        /* Then, evict sub-sampled tiles that aren't visible anymore */
        val subSampledTiles = tilesToRender.filter {
            it.subSample > 0
        }
        if (subSampledTiles.isNotEmpty()) {
            val visibleAtLowestLevel = visibleTilesResolver.getVisibleTiles(lastViewport, 0)
            subSampledTiles.filter {
                !visibleAtLowestLevel.overlaps(it)
            }.let {
                evictList.addAll(it)
            }
        }

        val iterator = tilesToRender.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next()
            evictList.any {
                it.samePositionAs(tile)
            }.let {
                if (it) {
                    iterator.remove()
                    tile.recycle()
                }
            }
        }
    }

    /**
     * Only triggered after the [idleDebounced] fires.
     */
    private fun aggressiveEviction(currentLevel: Int, currentSubSample: Int) {
        /**
         * If not all tiles at current level (or also current sub-sample) are fetched, don't go
         * further.
         */
        val nTilesAtCurrentLevel = tilesToRender.count {
            it.zoom == currentLevel && it.subSample == currentSubSample
        }
        if (nTilesAtCurrentLevel < lastVisibleCount) {
            return
        }

        val otherTilesNotSubSampled = tilesToRender.filter {
            it.zoom != currentLevel
        }

        val subSampledTiles = tilesToRender.filter {
            it.zoom == 0 && it.subSample != currentSubSample
        }

        val iterator = tilesToRender.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next()
            val found = otherTilesNotSubSampled.any {
                it.samePositionAs(tile)
            }
            if (found) {
                iterator.remove()
                tile.recycle()
                continue
            }

            if (subSampledTiles.contains(tile)) {
                iterator.remove()
                tile.recycle()
            }
        }
    }

    /**
     * Post a new value to the observable. The view should update its UI.
     */
    private fun renderThrottled() {
        renderTask.offer(Unit)
    }

    /**
     * After a [Tile] is no longer visible, recycle its Bitmap and Paint if possible, for later use.
     */
    private fun Tile.recycle() {
        if (bitmap.isMutable) {
            bitmapPool.put(bitmap)
        }
        paint?.let {
            paint = null
            it.alpha = 0
            it.colorFilter = null
            paintPool.put(it)
        }
    }
}
