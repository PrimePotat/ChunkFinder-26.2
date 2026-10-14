
package chunks.modid.client

import chunks.modid.ChunkFinder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

object ChunkFinderClient : ClientModInitializer {
    private const val RADIUS_CHUNKS = 3
    private const val START_DELAY_TICKS = 20 * 15
    private const val RESCAN_DELAY_TICKS = 20 * 60
    private const val MIN_ACTIVITY_BLOCKS = 12

    // Limit block checks per tick to reduce sudden lag.
    private const val BLOCK_CHECKS_PER_TICK = 4096

    private data class ChunkKey(val x: Int, val z: Int)

    private data class ChunkResult(
        val key: ChunkKey,
        val activityBlocks: Int = 0,
        val spawnerCount: Int = 0,
        val sampleY: Int = 0
    )

    private data class ChunkScan(
        val key: ChunkKey,
        val minY: Int,
        val maxY: Int,
        var x: Int,
        var y: Int,
        var z: Int,
        var activity: Int = 0,
        var spawners: Int = 0,
        var sampleY: Int = minY
    )

    private var tickCounter = 0
    private var nextScanTick = START_DELAY_TICKS
    private var scanning = false
    private var chunkKeys: List<ChunkKey> = emptyList()
    private var chunkIndex = 0
    private var currentScan: ChunkScan? = null
    private var blockChecksThisTick = 0

    private val results = mutableMapOf<ChunkKey, ChunkResult>()

    override fun onInitializeClient() {
        ChunkFinderRenderer.init()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick(client)
        }

        ChunkFinder.logger.info("[ChunkFinder] Client initialized.")
    }

    private fun tick(client: Minecraft) {
        tickCounter++

        val player = client.player ?: return
        val level = client.level ?: return

        if (!scanning && tickCounter >= nextScanTick) {
            val centerX = player.blockPosition().x shr 4
            val centerZ = player.blockPosition().z shr 4

            chunkKeys = buildList {
                for (x in centerX - RADIUS_CHUNKS..centerX + RADIUS_CHUNKS) {
                    for (z in centerZ - RADIUS_CHUNKS..centerZ + RADIUS_CHUNKS) {
                        add(ChunkKey(x, z))
                    }
                }
            }

            results.clear()
            chunkIndex = 0
            currentScan = null
            scanning = true

            ChunkFinder.logger.info(
                "[ChunkFinder] Starting a gradual scan of ${chunkKeys.size} chunks."
            )
        }

        if (!scanning) return

        blockChecksThisTick = 0

        while (
            blockChecksThisTick < BLOCK_CHECKS_PER_TICK &&
            chunkIndex < chunkKeys.size
        ) {
            if (currentScan == null) {
                val key = chunkKeys[chunkIndex]
                val minY = level.minY
                val maxY = level.maxY

                currentScan = ChunkScan(
                    key = key,
                    minY = minY,
                    maxY = maxY,
                    x = key.x shl 4,
                    y = minY,
                    z = key.z shl 4
                )
            }

            val scan = currentScan!!
            val finished = scanChunkStep(level, scan)

            if (finished) {
                if (scan.activity > 0 || scan.spawners > 0) {
                    results[scan.key] = ChunkResult(
                        key = scan.key,
                        activityBlocks = scan.activity,
                        spawnerCount = scan.spawners,
                        sampleY = scan.sampleY
                    )
                }

                currentScan = null
                chunkIndex++
            }
        }

        if (chunkIndex >= chunkKeys.size && currentScan == null) {
            showResults()
            scanning = false
            nextScanTick = tickCounter + RESCAN_DELAY_TICKS
        }
    }

    private fun scanChunkStep(
        level: net.minecraft.client.multiplayer.ClientLevel,
        scan: ChunkScan
    ): Boolean {
        while (scan.x < (scan.key.x shl 4) + 16) {
            while (scan.z < (scan.key.z shl 4) + 16) {
                while (scan.y < scan.maxY) {
                    val pos = BlockPos(scan.x, scan.y, scan.z)
                    blockChecksThisTick++
                    scan.y++

                    if (!level.hasChunkAt(pos)) {
                        if (blockChecksThisTick >= BLOCK_CHECKS_PER_TICK) return false
                        continue
                    }

                    val state = level.getBlockState(pos)

                    if (isActivityBlock(state)) {
                        scan.activity++
                        scan.sampleY = pos.y
                    }

                    if (state.`is`(Blocks.SPAWNER)) {
                        scan.spawners++
                    }

                    if (blockChecksThisTick >= BLOCK_CHECKS_PER_TICK) return false
                }

                scan.y = scan.minY
                scan.z++
            }

            scan.z = scan.key.z shl 4
            scan.x++
        }

        return true
    }

    private fun isActivityBlock(state: BlockState): Boolean =
        state.`is`(Blocks.AMETHYST_CLUSTER) ||
        state.`is`(Blocks.BUDDING_AMETHYST) ||
        state.`is`(Blocks.KELP) ||
        state.`is`(Blocks.KELP_PLANT) ||
        state.`is`(Blocks.CAVE_VINES) ||
        state.`is`(Blocks.CAVE_VINES_PLANT) ||
        state.`is`(Blocks.VINE) ||
        state.`is`(Blocks.BAMBOO) ||
        state.`is`(Blocks.COCOA) ||
        state.`is`(Blocks.BEEHIVE) ||
        state.`is`(Blocks.BEE_NEST)

    private fun showResults() {
        val activityFlags = results.values
            .filter { it.activityBlocks >= MIN_ACTIVITY_BLOCKS }
            .sortedByDescending { it.activityBlocks }

        val spawnerFlags = results.values
            .filter { it.spawnerCount > 0 }

        ChunkFinder.logger.info(
            "[ChunkFinder] Scan complete. Checked client-visible block data."
        )

        if (activityFlags.isEmpty()) {
            ChunkFinder.logger.info(
                "[ChunkFinder] No activity-block clusters met the threshold " +
                "($MIN_ACTIVITY_BLOCKS blocks per chunk)."
            )
        } else {
            activityFlags.take(12).forEach { result ->
                ChunkFinder.logger.info(
                    "[ChunkFinder] Candidate chunk ${result.key.x}, ${result.key.z}: " +
                    "${result.activityBlocks} selected blocks " +
                    "(sample Y=${result.sampleY}). This is a clue, not proof of player activity."
                )
            }
        }

        if (spawnerFlags.isEmpty()) {
            ChunkFinder.logger.info("[ChunkFinder] No visible spawner blocks found.")
        } else {
            spawnerFlags.forEach { result ->
                ChunkFinder.logger.info(
                    "[ChunkFinder] Spawner chunk ${result.key.x}, ${result.key.z}: " +
                    "${result.spawnerCount} spawner block(s)."
                )
            }
        }
    }
}
