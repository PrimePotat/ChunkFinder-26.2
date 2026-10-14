
package chunks.modid.client

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.OptionalDouble
import org.joml.Matrix4fc

object ChunkFinderRenderer {
    private data class Marker(
        val x: Int,
        val z: Int,
        val y: Int
    )

    private var markers: List<Marker> = emptyList()
    private var initialized = false

    private val pipeline: RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(
                    ResourceLocation.fromNamespaceAndPath(
                        "chunk-finder",
                        "pipeline/chunk_markers"
                    )
                )
                .withDepthStencilState(Optional.empty())
                .build()
        )

    private val buffer = StagedVertexBuffer(
        { "ChunkFinder Markers" },
        RenderType.SMALL_BUFFER_SIZE
    )

    private val colorModulator =
        org.joml.Vector4f(1f, 1f, 1f, 1f)
    private val modelOffset = org.joml.Vector3f()
    private val textureMatrix = org.joml.Matrix4f()

    fun init() {
        if (initialized) return
        initialized = true

        LevelExtractionEvents.END_EXTRACTION.register {
            markers = ChunkFinderClient.getMarkers().map {
                Marker(
                    x = it.chunkX shl 4,
                    z = it.chunkZ shl 4,
                    y = it.sampleY
                )
            }
        }

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register {
            render(it)
        }
    }

    private fun render(context: LevelRenderContext) {
        if (markers.isEmpty()) return

        val format = pipeline.getVertexFormatBinding(0) ?: return
        val topology = pipeline.primitiveTopology

        val draw = buffer.appendDraw(
            format,
            topology,
            if (topology == PrimitiveTopology.QUADS)
                RenderSystem.getProjectionType().vertexSorting()
            else null
        )

        val poses = context.poseStack()
        val camera: Vec3 = context.levelState().cameraRenderState.pos

        poses.pushPose()
        poses.translate(-camera.x, -camera.y, -camera.z)

        val matrix = poses.last().pose()
        val vertices = buffer.getVertexBuilder(draw)

        for (marker in markers) {
            val bottom = (marker.y - 2).toFloat()
            val top = (marker.y + 6).toFloat()

            box(
                matrix, vertices,
                marker.x.toFloat(), bottom, marker.z.toFloat(),
                marker.x + 16f, top, marker.z + 16f
            )
        }

        poses.popPose()
        buffer.upload()

        val info = buffer.getExecuteInfo(draw)
        if (info != null) {
            drawPass(info)
        }

        buffer.endFrame()
    }

    private fun box(
        matrix: Matrix4fc,
        out: VertexConsumer,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float
    ) {
        face(out, matrix, x1,y1,z2, x2,y1,z2, x2,y2,z2, x1,y2,z2)
        face(out, matrix, x2,y1,z1, x1,y1,z1, x1,y2,z1, x2,y2,z1)
        face(out, matrix, x1,y1,z1, x1,y1,z2, x1,y2,z2, x1,y2,z1)
        face(out, matrix, x2,y1,z2, x2,y1,z1, x2,y2,z1, x2,y2,z2)
        face(out, matrix, x1,y2,z2, x2,y2,z2, x2,y2,z1, x1,y2,z1)
        face(out, matrix, x1,y1,z1, x2,y1,z1, x2,y1,z2, x1,y1,z2)
    }

    private fun face(
        out: VertexConsumer,
        matrix: Matrix4fc,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float
    ) {
        vertex(out, matrix, x1,y1,z1)
        vertex(out, matrix, x2,y2,z2)
        vertex(out, matrix, x3,y3,z3)
        vertex(out, matrix, x4,y4,z4)
    }

    private fun vertex(
        out: VertexConsumer,
        matrix: Matrix4fc,
        x: Float, y: Float, z: Float
    ) {
        out.addVertex(matrix, x, y, z)
            .setColor(0.65f, 0.1f, 1f, 0.22f)
    }

    private fun drawPass(info: StagedVertexBuffer.ExecuteInfo) {
        val client = Minecraft.getInstance()
        val transforms = RenderSystem.getDynamicUniforms()
            .writeTransform(
                RenderSystem.getModelViewMatrixCopy(),
                colorModulator,
                modelOffset,
                textureMatrix
            )

        val target = client.gameRenderer.mainRenderTarget()
        val color = target.getColorTextureView() ?: return

        val depth = target.getDepthTextureView()

        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "ChunkFinder purple markers" },
            color,
            Optional.empty(),
            depth,
            OptionalDouble.empty()
        ).use { pass ->
            pass.setPipeline(pipeline)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setVertexBuffer(0, info.vertexBuffer().slice())
            pass.setIndexBuffer(info.indexBuffer(), info.indexType())
            pass.drawIndexed(
                info.indexCount(), 1,
                info.firstIndex(), info.baseVertex(), 0
            )
        }
    }
}
