package chunks.modid.client

import chunks.modid.ChunkFinder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.client.Minecraft

/**
 * Client-only, conservative scanner. It only reads block states already available
 * in the client's loaded world; it does not request or reveal hidden server data.
 */
object ChunkFinderClient : ClientModInitializer {
    private const val RADIUS_CHUNKS = 5
    private const val START_DELAY_TICKS = 20 * 15
    private const val RESCAN_DELAY_TICKS = 20 * 60
    private const val MIN_ACTIVITY_BLOCKS = 12

    private data class ChunkKey(val x: Int, val z: Int)
    private data class ChunkResult(
        val key: ChunkKey,
        var activityBlocks: Int = 0,
        var spawnerCount: Int = 0,
        var sampleY: Int = 0
    )

    private var tickCounter = 0
    private var scanStartedAt = 0
    private var nextScanTick = START_DELAY_TICKS
    private var scanning = false
    private var chunkIndex = 0
    private var chunkKeys: List<ChunkKey> = emptyList()
    private val results = mutableMapOf<ChunkKey, ChunkResult>()
    private var spawnerChunks = mutableSetOf<ChunkKey>()

    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
        ChunkFinder.logger.info("ActivityScanner client initialized")
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
                    for (z in centerZ - RADIUS_CHUNKS..centerZ + RADIUS_CHUNKS) add(ChunkKey(x, z))
                }
            }
            results.clear()
            spawnerChunks.clear()
            chunkIndex = 0
            scanStartedAt = tickCounter
            scanning = true
            player.displayClientMessage(Component.literal("[ActivityScanner] Scanning loaded chunks; results will appear after 15 seconds."), false)
        }

        if (!scanning) return

        // Process one chunk per client tick to avoid one large frame hitch.
        if (chunkIndex < chunkKeys.size) {
            scanChunk(level, chunkKeys[chunkIndex])
            chunkIndex++
        }

        // Always enforce the requested 15-second information delay.
        if (chunkIndex >= chunkKeys.size && tickCounter - scanStartedAt >= START_DELAY_TICKS) {
            showResults(client)
            scanning = false
            nextScanTick = tickCounter + RESCAN_DELAY_TICKS
        }
    }

    private fun scanChunk(level: net.minecraft.client.multiplayer.ClientLevel, key: ChunkKey) {
        // hasChunkAt is a loaded-world check; skip a chunk if its data is not present.
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
                    if (state.`is`(Blocks.SPAWNER)) spawners++
                }
            }
        }

        if (activity > 0 || spawners > 0) {
            results[key] = ChunkResult(key, activity, spawners, sampleY)
        }
        if (spawners > 0) spawnerChunks.add(key)
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

    private fun showResults(client: Minecraft) {
        val player = client.player ?: return
        val activityFlags = results.values
            .filter { it.activityBlocks >= MIN_ACTIVITY_BLOCKS }
            .sortedByDescending { it.activityBlocks }
        val spawnerFlags = results.values.filter { it.spawnerCount > 0 }

        player.displayClientMessage(Component.literal("[ActivityScanner] Scan complete. Only loaded client-side block data was checked."), false)
        if (activityFlags.isEmpty()) {
            player.displayClientMessage(Component.literal("[ActivityScanner] No activity-block clusters met the threshold ($MIN_ACTIVITY_BLOCKS blocks per chunk)."), false)
        } else {
            activityFlags.take(12).forEach { r ->
                player.displayClientMessage(
                    Component.literal("[ActivityScanner] PURPLE FLAG candidate chunk ${r.key.x}, ${r.key.z} — ${r.activityBlocks} selected blocks (sample Y=${r.sampleY}). This is only a clue, not proof of player activity."),
                    false
                )
            }
        }
        if (spawnerFlags.isEmpty()) {
            player.displayClientMessage(Component.literal("[ActivityScanner] No visible skeleton-spawner block data found in scanned loaded chunks."), false)
        } else {
            spawnerFlags.forEach { r ->
                player.displayClientMessage(Component.literal("[ActivityScanner] SPAWNER chunk ${r.key.x}, ${r.key.z} — ${r.spawnerCount} spawner block(s) in client-visible data."), false)
            }
        }
        player.displayClientMessage(Component.literal("[ActivityScanner] Purple flags are reported in chat only in this starter build; no through-wall renderer is enabled."), false)
    }
}
