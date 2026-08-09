package anpilot.client.features.ai.task.basefinder

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.BeaconBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BrewingStandBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ConduitBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.FurnaceBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.state.properties.ChestType

object BaseFinderScanner {
    fun scan(level: Level, chunkPos: ChunkPos): BaseFinderScanResult {
        val chunk = level.getChunk(chunkPos.x, chunkPos.z)
        val result = BaseFinderScanResult(chunkPos)
        var doubleChestHalves = 0

        chunk.blockEntities.forEach { (pos, _) ->
            val state = level.getBlockState(pos)
            when (val block = state.block) {
                is ChestBlock -> {
                    val type = state.getValue(ChestBlock.TYPE)
                    if (type == ChestType.SINGLE) result.containers.increment("chest") else doubleChestHalves++
                }
                is BarrelBlock -> if (!isCopperPresent(level, pos)) result.containers.increment("barrel")
                is ShulkerBoxBlock -> result.containers.increment("shulker_box")
                is EnderChestBlock -> result.containers.increment("ender_chest")
                is HopperBlock -> if (!isCopperPresent(level, pos)) result.containers.increment("hopper")
                is FurnaceBlock -> result.containers.increment("furnace")
                is BrewingStandBlock -> result.containers.increment("brewing_stand")
                is BeaconBlock -> result.blocks.increment("beacon")
                is ConduitBlock -> result.blocks.increment("conduit")
                is BedBlock -> if (!isCopperPresent(level, pos)) result.blocks.increment("bed")
            }
        }
        result.containers.increment("double_chest", doubleChestHalves / 2)

        return result
    }

    private fun isCopperPresent(level: Level, center: BlockPos): Boolean {
        for (dx in -2..2) {
            for (dy in -2..2) {
                for (dz in -2..2) {
                    if (level.getBlockState(center.offset(dx, dy, dz)).block in COPPER_BLOCKS) return true
                }
            }
        }
        return false
    }

    private val COPPER_BLOCKS = setOf(
        Blocks.COPPER_BLOCK,
        Blocks.CUT_COPPER,
        Blocks.EXPOSED_COPPER,
        Blocks.WEATHERED_COPPER,
        Blocks.OXIDIZED_COPPER,
        Blocks.WAXED_COPPER_BLOCK,
        Blocks.WAXED_CUT_COPPER,
        Blocks.WAXED_EXPOSED_COPPER,
        Blocks.WAXED_WEATHERED_COPPER,
        Blocks.WAXED_OXIDIZED_COPPER
    )
}
