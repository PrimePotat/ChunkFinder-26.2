package chunks.modid.client

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.Optional
import java.util.OptionalDouble

object ChunkFinderRenderer {
    private val pipeline: RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(
                    Identifier.fromNamespaceAndPath(
                        "chunk-finder",
                        "pipeline/chunk_markers"
                    )
                )
                .withDepthStencilState(Optional.empty())
                .build()
        )

    private val stagedBuffer =
        StagedVertexBuffer(
            { "ChunkFinder Buffer" },
            RenderType.SMALL_BUFFER_SIZE
        )

    private val colorModulator = Vector4f(1f, 1f, 1f, 1f)
    private val modelOffset = Vector3f()
    private val textureMatrix = Matrix4f()

    private data class Box(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float
    )

    private var boxes: List<Box> = emptyList()

    fun init() {
        LevelExtractionEvents.END_EXTRACTION.register(::extract)
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(::draw)
    }

    private fun extract(context: LevelExtractionContext) {
        val client = Minecraft.getInstance()
        val world = client.level
        if (world == null) {
            boxes = emptyList()
            return
        }

        boxes = ChunkFinderClient.getMarkers().map { marker ->
            val x = marker.chunkX * 16
            val z = marker.chunkZ * 16
            Box(
                x.toFloat(), world.minY.toFloat(), z.toFloat(),
                (x + 16).toFloat(), world.maxY.toFloat(),
                (z + 16).toFloat()
            )
        }
    }

    private fun draw(context: LevelRenderContext) {
        if (boxes.isEmpty()) return

        val format: VertexFormat =
            pipeline.getVertexFormatBinding(0) ?: return
        val topology = pipeline.primitiveTopology
        val draw = stagedBuffer.appendDraw(
            format,
            topology,
            if (topology == PrimitiveTopology.QUADS)
                RenderSystem.getProjectionType().vertexSorting()
            else null
        )

        val matrices = context.poseStack()
        val camera: Vec3 = context.levelState().cameraRenderState.pos

        matrices.pushPose()
        matrices.translate(-camera.x, -camera.y, -camera.z)

        val consumer = stagedBuffer.getVertexBuilder(draw)
        for (box in boxes) {
            renderBox(
                matrices.last().pose(),
                consumer,
                box,
                0.65f, 0.15f, 0.95f, 0.22f
            )
        }

        matrices.popPose()

        stagedBuffer.upload()
        val info = stagedBuffer.getExecuteInfo(draw)
        if (info != null) {
            executeDraw(Minecraft.getInstance(), info)
        }
        stagedBuffer.endFrame()
    }

    private fun renderBox(
        matrix: Matrix4fc,
        out: VertexConsumer,
        b: Box,
        r: Float,
        g: Float,
        blue: Float,
        a: Float
    ) {
        fun v(x: Float, y: Float, z: Float) {
            out.addVertex(matrix, x, y, z).setColor(r, g, blue, a)
        }

        // Front
        v(b.minX, b.minY, b.maxZ)
        v(b.maxX, b.minY, b.maxZ)
        v(b.maxX, b.maxY, b.maxZ)
        v(b.minX, b.maxY, b.maxZ)

        // Back
        v(b.maxX, b.minY, b.minZ)
        v(b.minX, b.minY, b.minZ)
        v(b.minX, b.maxY, b.minZ)
        v(b.maxX, b.maxY, b.minZ)

        // Left
        v(b.minX, b.minY, b.minZ)
        v(b.minX, b.minY, b.maxZ)
        v(b.minX, b.maxY, b.maxZ)
        v(b.minX, b.maxY, b.minZ)

        // Right
        v(b.maxX, b.minY, b.maxZ)
        v(b.maxX, b.minY, b.minZ)
        v(b.maxX, b.maxY, b.minZ)
        v(b.maxX, b.maxY, b.maxZ)

        // Top
        v(b.minX, b.maxY, b.maxZ)
        v(b.maxX, b.maxY, b.maxZ)
        v(b.maxX, b.maxY, b.minZ)
        v(b.minX, b.maxY, b.minZ)

        // Bottom
        v(b.minX, b.minY, b.minZ)
        v(b.maxX, b.minY, b.minZ)
        v(b.maxX, b.minY, b.maxZ)
        v(b.minX, b.minY, b.maxZ)
    }

    private fun executeDraw(
        client: Minecraft,
        info: StagedVertexBuffer.ExecuteInfo
    ) {
        val transforms: GpuBufferSlice =
            RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(),
                colorModulator,
                modelOffset,
                textureMatrix
            )

        val target: RenderTarget = client.gameRenderer.mainRenderTarget()
        val color: GpuTextureView = target.getColorTextureView() ?: return

        val pass: RenderPass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                { "ChunkFinder markers" },
                color,
                Optional.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty()
            )

        pass.use {
            it.setPipeline(pipeline)
            RenderSystem.bindDefaultUniforms(it)
            it.setUniform("DynamicTransforms", transforms)
            it.setVertexBuffer(0, info.vertexBuffer().slice())
            it.setIndexBuffer(info.indexBuffer(), info.indexType())
            it.drawIndexed(
                info.indexCount(),
                1,
                info.firstIndex(),
                info.baseVertex(),
                0
            )
        }
    }
}
