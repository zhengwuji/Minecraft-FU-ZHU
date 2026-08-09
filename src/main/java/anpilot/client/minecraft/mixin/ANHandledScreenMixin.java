package anpilot.client.minecraft.mixin;

import anpilot.client.minecraft.duck.ANHandledScreenExt;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AbstractContainerScreen.class)
public abstract class ANHandledScreenMixin implements ANHandledScreenExt {
    @Shadow
    protected Slot hoveredSlot;

    @Override
    public Slot anpilot_getHoveredSlot() {
        return this.hoveredSlot;
    }
}
