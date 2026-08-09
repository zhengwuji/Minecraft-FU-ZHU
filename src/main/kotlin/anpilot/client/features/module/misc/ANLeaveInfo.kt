package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.gui.ANLeaveGuiState
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft

class ANLeaveInfo : ANBaseModule(
    name = "LeaveInfo",
    description = "在断开服务器连接前保存玩家最后的坐标、维度与生命状态快照",
    category = ANModuleCategory.MISC,
    chineseName = "离开信息",
    defaultState = ANModuleState.ENABLED
) {
    var lastPing: Int = 0
        private set
    var lastDimension: String = ""
        private set
    var lastPosition: String = ""
        private set

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return

        lastPing = minecraft.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
        lastDimension = level.dimension().location().toString()
        lastPosition = "${player.blockX}, ${player.blockY}, ${player.blockZ}"
        ANLeaveGuiState.captureFromCurrentPlayer(minecraft)
    }
}
