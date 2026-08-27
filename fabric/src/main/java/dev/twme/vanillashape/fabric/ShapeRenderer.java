package dev.twme.vanillashape.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.twme.vanillashape.common.SpecialBlock;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

final class ShapeRenderer {
    private final ClientBlockStore store;
    private final ModelMaterialResolver materials = new ModelMaterialResolver();
    private final TemplateModelResolver models = new TemplateModelResolver();

    ShapeRenderer(final ClientBlockStore store) { this.store = store; }

    void render(final LevelRenderContext context) {
        final Minecraft client = Minecraft.getInstance();
        final ClientLevel level = client.level;
        if (level == null) return;
        final Vec3 camera = context.levelState().cameraRenderState.pos;
        final String world = level.dimension().identifier().toString();
        for (final SpecialBlock block : store.blocks(world)) {
            final double dx = block.x() + .5 - camera.x;
            final double dy = block.y() + .5 - camera.y;
            final double dz = block.z() + .5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > 256 * 256) continue;
            final BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            if (!level.isLoaded(pos)) continue;
            final ModelMaterialResolver.Resolved material = materials.resolve(block.material(), level, pos);
            final int light = LightCoordsUtil.getLightCoords(level, pos);
            final RenderType renderType = material.translucent()
                    ? RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS)
                    : RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);

            final PoseStack poseStack = context.poseStack();
            poseStack.pushPose();
            poseStack.translate(block.x() - camera.x, block.y() - camera.y, block.z() - camera.z);
            context.submitNodeCollector().submitCustomGeometry(poseStack, renderType,
                    (pose, buffer) -> draw(block, material, pose, buffer, light, world, level, pos));
            poseStack.popPose();
        }
    }

    void clearMaterials() { materials.clear(); models.clear(); }

    private void draw(final SpecialBlock block, final ModelMaterialResolver.Resolved material,
                      final PoseStack.Pose pose, final VertexConsumer out, final int light, final String world,
                      final ClientLevel level, final BlockPos pos) {
        if (block.shape() == dev.twme.vanillashape.common.ShapeType.MODEL) {
            drawModel(block, material, pose, out, light, level, pos);
            return;
        }
        for (final ShapeGeometry.Surface surface : ShapeGeometry.surfaces(block, (dx, dy, dz) ->
                store.get(world, new BlockPos(block.x() + dx, block.y() + dy, block.z() + dz)))) {
            if (boundary(surface) && (level.getBlockState(pos.relative(surface.direction())).isSolidRender()
                    || virtualModelOccludes(block, surface.direction(), level))) continue;
            face(out, pose, material.face(surface.direction()), surface.direction(), light, surface);
        }
    }

    private void drawModel(final SpecialBlock block, final ModelMaterialResolver.Resolved material,
                           final PoseStack.Pose pose, final VertexConsumer out, final int light,
                           final ClientLevel level, final BlockPos pos) {
        for (final TemplateModelResolver.Quad quad : models.resolve(block.model())) {
            if (quad.cullDirection() != null
                    && (level.getBlockState(pos.relative(quad.cullDirection())).isSolidRender()
                    || virtualOccludes(block, quad.cullDirection(), level))) continue;
            final float shade = quad.shade() ? shade(quad.direction()) : 1;
            for (final ModelMaterialResolver.Face layer : material.face(quad.direction())) {
                final int color = shade(layer.color(), shade);
                for (final TemplateModelResolver.Vertex vertex : quad.vertices()) {
                    out.addVertex(pose, vertex.x(), vertex.y(), vertex.z())
                            .setColor(color)
                            .setUv(atlasCoordinate(layer.sprite().getU0(), layer.sprite().getU1(), vertex.u()),
                                    atlasCoordinate(layer.sprite().getV0(), layer.sprite().getV1(), vertex.v()))
                            .setOverlay(noOverlay()).setLight(light)
                            .setNormal(pose, quad.direction().getStepX(), quad.direction().getStepY(),
                                    quad.direction().getStepZ());
                }
            }
        }
    }

    private boolean virtualOccludes(final SpecialBlock block, final Direction direction,
                                    final ClientLevel level) {
        final String world = level.dimension().identifier().toString();
        final SpecialBlock neighbor = store.get(world, new BlockPos(block.x(), block.y(), block.z())
                .relative(direction));
        if (neighbor == null) return false;
        if (neighbor.shape() == dev.twme.vanillashape.common.ShapeType.MODEL) {
            return models.occludesFullCube(neighbor.model());
        }
        return ShapeGeometry.coversFace(neighbor, direction.getOpposite());
    }

    private boolean virtualModelOccludes(final SpecialBlock block, final Direction direction,
                                         final ClientLevel level) {
        final String world = level.dimension().identifier().toString();
        final SpecialBlock neighbor = store.get(world, new BlockPos(block.x(), block.y(), block.z())
                .relative(direction));
        return neighbor != null && neighbor.shape() == dev.twme.vanillashape.common.ShapeType.MODEL
                && models.occludesFullCube(neighbor.model());
    }

    private static boolean boundary(final ShapeGeometry.Surface surface) {
        final float epsilon = 1.0e-6f;
        return switch (surface.direction()) {
            case WEST -> surface.minX() <= epsilon;
            case EAST -> surface.maxX() >= 1 - epsilon;
            case DOWN -> surface.minY() <= epsilon;
            case UP -> surface.maxY() >= 1 - epsilon;
            case NORTH -> surface.minZ() <= epsilon;
            case SOUTH -> surface.maxZ() >= 1 - epsilon;
        };
    }

    /** The first two points describe the lower/first edge; the opposite edge is derived per face. */
    private static void face(final VertexConsumer out, final PoseStack.Pose pose,
            final java.util.List<ModelMaterialResolver.Face> layers, final Direction direction, final int light,
            final ShapeGeometry.Surface box) {
        for (final ModelMaterialResolver.Face material : layers) {
        final float[][] vertices = switch (direction) {
            case DOWN -> new float[][] {{box.minX(),box.minY(),box.maxZ()},{box.minX(),box.minY(),box.minZ()},
                    {box.maxX(),box.minY(),box.minZ()},{box.maxX(),box.minY(),box.maxZ()}};
            case UP -> new float[][] {{box.minX(),box.maxY(),box.minZ()},{box.minX(),box.maxY(),box.maxZ()},
                    {box.maxX(),box.maxY(),box.maxZ()},{box.maxX(),box.maxY(),box.minZ()}};
            case NORTH -> new float[][] {{box.maxX(),box.minY(),box.minZ()},{box.maxX(),box.maxY(),box.minZ()},
                    {box.minX(),box.maxY(),box.minZ()},{box.minX(),box.minY(),box.minZ()}};
            case SOUTH -> new float[][] {{box.minX(),box.minY(),box.maxZ()},{box.minX(),box.maxY(),box.maxZ()},
                    {box.maxX(),box.maxY(),box.maxZ()},{box.maxX(),box.minY(),box.maxZ()}};
            case WEST -> new float[][] {{box.minX(),box.minY(),box.minZ()},{box.minX(),box.maxY(),box.minZ()},
                    {box.minX(),box.maxY(),box.maxZ()},{box.minX(),box.minY(),box.maxZ()}};
            case EAST -> new float[][] {{box.maxX(),box.minY(),box.maxZ()},{box.maxX(),box.maxY(),box.maxZ()},
                    {box.maxX(),box.maxY(),box.minZ()},{box.maxX(),box.minY(),box.minZ()}};
        };
        final int color = shade(material.color(), shade(direction));
        for (final float[] vertex : vertices) {
            final float uCoord = switch (direction) {
                case UP, DOWN, NORTH, SOUTH -> vertex[0];
                case EAST, WEST -> vertex[2];
            };
            final float vCoord = switch (direction) {
                case UP, DOWN -> vertex[2];
                default -> 1 - vertex[1];
            };
            out.addVertex(pose, vertex[0], vertex[1], vertex[2])
                    .setColor(color)
                    .setUv(atlasCoordinate(material.sprite().getU0(), material.sprite().getU1(), uCoord),
                            atlasCoordinate(material.sprite().getV0(), material.sprite().getV1(), vCoord))
                    .setOverlay(noOverlay())
                    .setLight(light)
                    .setNormal(pose, direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }
        }
    }

    private static float shade(final Direction direction) {
        return switch (direction) {
            case DOWN -> .5f; case NORTH, SOUTH -> .8f; case WEST, EAST -> .6f; case UP -> 1f;
        };
    }

    private static int shade(final int argb, final float shade) {
        final int a = argb >>> 24;
        final int r = Math.round(((argb >>> 16) & 255) * shade);
        final int g = Math.round(((argb >>> 8) & 255) * shade);
        final int b = Math.round((argb & 255) * shade);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Minecraft 26.2 sprite interpolation uses normalized 0..1 local coordinates. */
    static float atlasCoordinate(final float minimum, final float maximum, final float local) {
        return minimum + (maximum - minimum) * local;
    }

    /** Zero selects the red damage row; this packed coordinate selects the neutral overlay row. */
    static int noOverlay() {
        return OverlayTexture.NO_OVERLAY;
    }
}
