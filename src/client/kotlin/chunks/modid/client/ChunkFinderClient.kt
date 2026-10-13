
package chunks.modid.client

import chunks.modid.ChunkFinder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.client.Minecraft

object ChunkFinderClient : ClientModInitializer {
    private const val RADIUS_CHUNKS = 5
    private const val START_DELAY_TICKS = 20 * 15
    private const val RESCAN_DELAY_TICKS = 20 * 60
    private const val MIN_ACTIVITY_BLOCKS = 12

    private data class ChunkKey(val x: Int, val z: Int)

    private data class ChunkResult(
        val key: ChunkKey,
        val activityBlocks: Int = 0,
        val spawnerCount: Int = 0,
        val sampleY: Int = 0
    )

    private var tickCounter = 0
    private var scanStartedAt = 0
    private var nextScanTick = START_DELAY_TICKS
    private var scanning = false
    private var chunkIndex = 0
    private var chunkKeys: List<ChunkKey> = emptyList()
    private val results = mutableMapOf<ChunkKey, ChunkResult>()

    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick(client)
        }
        ChunkFinder.logger.info("ChunkFinder client initialized")
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
            scanStartedAt = tickCounter
            scanning = true

            ChunkFinder.logger.info(
                "[ChunkFinder] Scanning nearby chunks."
            )
        }

        if (!scanning) return

        if (chunkIndex < chunkKeys.size) {
            scanChunk(level, chunkKeys[chunkIndex])
            chunkIndex++
        }

        if (
            chunkIndex >= chunkKeys.size &&
            tickCounter - scanStartedAt >= START_DELAY_TICKS
        ) {
            showResults()
            scanning = false
            nextScanTick = tickCounter + RESCAN_DELAY_TICKS
        }
    }

    private fun scanChunk(
        level: net.minecraft.client.multiplayer.ClientLevel,
        key: ChunkKey
    ) {
        val minY = level.minY
        val maxY = level.maxY

        var activity = 0
        var spawners = 0
        var sampleY = minY

        for (x in (key.x shl 4)..((key.x shl 4) + 15)) {
            for (z in (key.z shl 4)..((key.z shl 4) + 15)) {
                for (y in minY until maxY) {
                    val pos = BlockPos(x, y, z)

                    if (!level.hasChunkAt(pos)) continue

                    val state = level.getBlockState(pos)

                    if (isActivityBlock(state)) {
                        activity++
                        sampleY = y
                    }

                    if (state.`is`(Blocks.SPAWNER)) {
                        spawners++
                    }
                }
            }
        }

        if (activity > 0 || spawners > 0) {
            results[key] = ChunkResult(
                key = key,
                activityBlocks = activity,
                spawnerCount = spawners,
                sampleY = sampleY
            )
        }
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
            "[ChunkFinder] Scan complete. Only client-visible block data was checked."
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
            ChunkFinder.logger.info(
                "[ChunkFinder] No visible spawner blocks found in scanned chunks."
            )
        } else {
            spawnerFlags.forEach { result ->
                ChunkFinder.logger.info(
                    "[ChunkFinder] Spawner chunk ${result.key.x}, ${result.key.z}: " +
                    "${result.spawnerCount} spawner block(s)."
                )
            }
        }

        ChunkFinder.logger.info(
            "[ChunkFinder] Results are in the game log. Purple world markers are not implemented yet."
        )
    }
}
