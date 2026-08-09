package net.minecraft.client.renderer.rendertype;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.util.OptionalDouble;

public final class ANPilotRenderTypes {
    public static final RenderType XRAY_LINES = RenderType.lines();
    public static final RenderType XRAY_LINES_VISIBLE = RenderType.lines();
    public static final RenderType XRAY_LINES_HIDDEN = RenderType.lines();
    public static final RenderType XRAY_FILLED_BOX = RenderType.debugQuads();
    public static final RenderType TRACER_LINES = RenderType.lines();

    public static RenderType chamsShineEntity(ResourceLocation texture, boolean depthTest) {
        return RenderType.entityTranslucentCull(texture);
    }

    public static RenderType chamsEntity(ResourceLocation texture, boolean depthTest) {
        return RenderType.entityTranslucentCull(texture);
    }

    public static RenderType chamsShineArmorEntity(ResourceLocation texture) {
        return RenderType.armorCutoutNoCull(texture);
    }

    public static RenderType chamsArmorEntity(ResourceLocation texture) {
        return RenderType.armorCutoutNoCull(texture);
    }

    public static RenderType dropsEspItem(ResourceLocation texture) {
        return RenderType.entityTranslucentCull(texture);
    }

    private ANPilotRenderTypes() {
    }
}
