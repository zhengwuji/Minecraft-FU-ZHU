package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.ContainerHelper
import net.minecraft.core.NonNullList
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.ShulkerBoxBlock

class ANDisplayTools : ANBaseModule(
    name = "DisplayTools",
    description = "悬停物品时提供潜影盒容器预览、地图画预览与中键直接打开潜影盒",
    category = ANModuleCategory.RENDER,
    chineseName = "悬浮提示增强",
    defaultState = ANModuleState.ENABLED
) {
    val middleClickOpen = addSetting(ANSetting("MiddleClickOpen", true))
    val storage = addSetting(ANSetting("Storage", true))
    val maps = addSetting(ANSetting("Maps", true))

    companion object {
        @JvmStatic
        fun hasItems(itemStack: ItemStack): Boolean {
            val tag = BlockItem.getBlockEntityData(itemStack) ?: return false
            return tag.contains("Items", 9)
        }

        @JvmStatic
        fun getShulkerItems(itemStack: ItemStack): NonNullList<ItemStack> {
            val items = NonNullList.withSize(27, ItemStack.EMPTY)
            val tag = BlockItem.getBlockEntityData(itemStack) ?: return items
            if (tag.contains("Items", 9)) {
                ContainerHelper.loadAllItems(tag, items)
            }
            return items
        }
    }
}
