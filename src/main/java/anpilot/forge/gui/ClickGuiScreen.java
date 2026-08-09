package anpilot.forge.gui;

import anpilot.forge.module.Module;
import anpilot.forge.module.ModuleManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.Collectors;

public class ClickGuiScreen extends Screen {

    public ClickGuiScreen() {
        super(Component.literal("ANPilotClient ClickGUI (F4)"));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        
        guiGraphics.drawCenteredString(this.font, "=== ANPilotClient 1.20.1 Native Forge GUI (F4) ===", this.width / 2, 20, 0x00FF00);

        String[] categories = {"Combat", "Render", "Movement", "Player"};
        int startX = 40;
        int panelWidth = 100;
        
        for (int i = 0; i < categories.length; i++) {
            String cat = categories[i];
            int x = startX + i * (panelWidth + 15);
            int y = 50;

            guiGraphics.fill(x, y, x + panelWidth, y + 20, 0xFF2D2D2D);
            guiGraphics.drawCenteredString(this.font, cat, x + panelWidth / 2, y + 6, 0xFFFFFF);

            List<Module> catMods = ModuleManager.modules.stream()
                    .filter(m -> m.getCategory().equalsIgnoreCase(cat))
                    .collect(Collectors.toList());

            int modY = y + 25;
            for (Module m : catMods) {
                int bgColor = m.isEnabled() ? 0xFF00AA00 : 0xFF444444;
                guiGraphics.fill(x, modY, x + panelWidth, modY + 18, bgColor);
                guiGraphics.drawString(this.font, m.getName(), x + 8, modY + 5, 0xFFFFFF);
                modY += 22;
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        String[] categories = {"Combat", "Render", "Movement", "Player"};
        int startX = 40;
        int panelWidth = 100;

        for (int i = 0; i < categories.length; i++) {
            String cat = categories[i];
            int x = startX + i * (panelWidth + 15);

            List<Module> catMods = ModuleManager.modules.stream()
                    .filter(m -> m.getCategory().equalsIgnoreCase(cat))
                    .collect(Collectors.toList());

            int modY = 75;
            for (Module m : catMods) {
                if (mouseX >= x && mouseX <= x + panelWidth && mouseY >= modY && mouseY <= modY + 18) {
                    m.toggle();
                    return true;
                }
                modY += 22;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
