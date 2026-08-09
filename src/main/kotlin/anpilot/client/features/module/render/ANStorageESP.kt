package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.vehicle.MinecartChest
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.DispenserBlockEntity
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

class ANStorageESP : ANBaseModule(
    name = "StorageESP",
    description = "穿墙透视高亮箱子、潜影盒、末影箱、木桶及储物矿车等各类容器方块",
    category = ANModuleCategory.RENDER,
    chineseName = "箱子透视"
), ANWorldRenderModule {
    val tracers = addSetting(ANSetting("TracerLine", true))
    val fill = addSetting(ANSetting("Fill", true))
    val chestColor = addSetting(ANSetting("ChestColor", ColorGroupSetting(Color(0xDB1642DC.toInt(), true).rgb)))
    val trappedChestColor = addSetting(ANSetting("TrappedChestColor", ColorGroupSetting(Color(0xFF21F1F8.toInt(), true).rgb)))
    val shulkerColor = addSetting(ANSetting("ShulkerColor", ColorGroupSetting(Color(0xFF35FA1F.toInt(), true).rgb)))
    val enderChestColor = addSetting(ANSetting("EnderChestColor", ColorGroupSetting(Color(0xFF1FF1D8.toInt(), true).rgb)))
    val furnaceColor = addSetting(ANSetting("FurnaceColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))
    val hopperColor = addSetting(ANSetting("HopperColor", ColorGroupSetting(Color(0xFF35FA1F.toInt(), true).rgb)))
    val dispenserColor = addSetting(ANSetting("DispenserColor", ColorGroupSetting(Color(0xFF1FF1D8.toInt(), true).rgb)))
    val barrelColor = addSetting(ANSetting("BarrelColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))
    val minecartColor = addSetting(ANSetting("MinecartColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))
    val itemFrameColor = addSetting(ANSetting("ItemFrameColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))

    override fun renderWorld(context: LevelRenderContext) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val from = ANRender3DEngine.crosshairWorldPos(context)

        for (blockEntity in loadedBlockEntities()) {
            val color = colorOf(blockEntity) ?: continue
            drawBlock(context, from, blockEntity.blockPos, color)
        }

        for (entity in level.entitiesForRendering()) {
            when (entity) {
                is ItemFrame -> drawBlock(context, from, entity.blockPosition(), ANColor.fromArgb(itemFrameColor.value.getColorRGB().rgb))
                is MinecartChest -> drawBox(context, from, entity.boundingBox, ANColor.fromArgb(minecartColor.value.getColorRGB().rgb))
            }
        }
    }

    private fun loadedBlockEntities(): Sequence<BlockEntity> = sequence {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return@sequence
        val player = minecraft.player ?: return@sequence
        val center = player.chunkPosition()
        val radius = minecraft.options.renderDistance().get()
        for (chunkX in center.x - radius..center.x + radius) {
            for (chunkZ in center.z - radius..center.z + radius) {
                val chunk = level.chunkSource.getChunk(chunkX, chunkZ, false) ?: continue
                yieldAll(chunk.blockEntities.values)
            }
        }
    }

    private fun drawBlock(context: LevelRenderContext, from: Vec3, pos: BlockPos, color: ANColor) {
        if (color.alpha <= 0) return
        if (tracers.value) {
            ANRender3DEngine.line(context, from, Vec3.atCenterOf(pos), color.withAlpha(255))
        }
        drawBox(context, from, AABB(pos), color)
    }

    private fun drawBox(context: LevelRenderContext, from: Vec3, box: AABB, color: ANColor) {
        if (color.alpha <= 0) return
        if (tracers.value) {
            ANRender3DEngine.line(context, from, box.center, color.withAlpha(255))
        }
        if (fill.value) {
            ANRender3DEngine.box(context, box, color.withAlpha(45), color.withAlpha(220))
        } else {
            ANRender3DEngine.box(context, box, ANColor.TRANSPARENT, color.withAlpha(220))
        }
    }

    private fun colorOf(blockEntity: BlockEntity): ANColor? {
        val setting = when (blockEntity) {
            is ChestBlockEntity -> chestColor.value
            is TrappedChestBlockEntity -> trappedChestColor.value
            is ShulkerBoxBlockEntity -> shulkerColor.value
            is EnderChestBlockEntity -> enderChestColor.value
            is AbstractFurnaceBlockEntity -> furnaceColor.value
            is HopperBlockEntity -> hopperColor.value
            is DispenserBlockEntity -> dispenserColor.value
            is BarrelBlockEntity -> barrelColor.value
            else -> null
        } ?: return null
        return ANColor.fromArgb(setting.getColorRGB().rgb)
    }
}
