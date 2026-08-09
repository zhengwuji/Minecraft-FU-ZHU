package anpilot.client.bootstrap

import net.minecraft.client.Minecraft
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

@Mod("anpilotclient")
class ANPilotMod {
    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        modEventBus.addListener(this::onClientSetup)

        MinecraftForge.EVENT_BUS.addListener(this::onClientTick)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        ANClientBootstrap.initialize()
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) {
            val mc = Minecraft.getInstance()
            ANClientBootstrap.onClientTick(mc)
        }
    }
}
