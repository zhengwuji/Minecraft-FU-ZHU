package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.Render3DEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import net.minecraft.world.phys.AABB
import java.util.Random
import net.minecraft.world.level.Level

class ANSlimeChunks : ANBaseModule(
    name = "SlimeChunks",
    description = "Highlights slime chunks based on the world seed.",
    category = ANModuleCategory.RENDER,
    chineseName = "史莱姆区块透视",
    defaultState = ANModuleState.DISABLED
) {
    val seedInput = addSetting(ANSetting("Seed", ""))
    val radius = addSetting(ANSetting("Render Radius", 6, 1, 16))
    
    private val colorLine = ANColor(40, 255, 40, 255)
    private val colorFill = ANColor(40, 255, 40, 50)
    
    private var worldSeed: Long? = null

    override fun onEnable() {
        val s = seedInput.value
        try {
            worldSeed = s.toLong()
        } catch (e: Exception) {
            worldSeed = s.hashCode().toLong()
        }
    }

    @ANEventHandler
    fun onRender(event: Render3DEvent) {
        val seed = worldSeed ?: return
        val player = mc.player ?: return
        val level = mc.level ?: return
        
        
        if (level.dimension() != Level.OVERWORLD) return
        
        val chunkX = player.chunkPosition().x
        val chunkZ = player.chunkPosition().z
        
        val r = radius.value
        
        val minY = level.minBuildHeight.toDouble()
        val maxY = 40.0 
        
        for (x in chunkX - r..chunkX + r) {
            for (z in chunkZ - r..chunkZ + r) {
                if (isSlimeChunk(seed, x, z)) {
                    val startX = (x shl 4).toDouble()
                    val startZ = (z shl 4).toDouble()
                    
                    val box = AABB(startX, minY, startZ, startX + 16.0, maxY, startZ + 16.0)
                    ANRender3DEngine.box(event.context, box, colorLine, colorFill)
                }
            }
        }
    }

    private fun isSlimeChunk(seed: Long, chunkX: Int, chunkZ: Int): Boolean {
        
        val seedForSlime = seed +
                (chunkX * chunkX * 4987142).toLong() +
                (chunkX * 5947611).toLong() +
                (chunkZ * chunkZ) * 4392871L +
                (chunkZ * 389711) xor 987234911L
        
        val random = Random(seedForSlime)
        return random.nextInt(10) == 0
    }
}
