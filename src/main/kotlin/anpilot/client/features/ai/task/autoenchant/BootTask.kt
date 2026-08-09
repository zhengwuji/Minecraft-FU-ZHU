package anpilot.client.features.ai.task.autoenchant

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.module.player.ANAutoEnchant

class BootTask(agent: ANAgent) : AITask(agent) {
    private var warnedSlots = false

    override fun tick() {
        val module = agent.module as? ANAutoEnchant ?: return finish()
        val player = player ?: return

        val selected = module.selectedEnchants()
        if (selected.isEmpty()) {
            module.disable("没有选择附魔,模块已关闭")
            return
        }

        val requiredSlots = module.requiredEmptySlots()
        val emptySlots = module.emptyInventorySlots()
        if (emptySlots < requiredSlots) {
            if (!warnedSlots) {
                AgentUtils.sendMessage("CHECK背包空位不足:需要 $requiredSlots 格，当前 $emptySlots 格！清理后任务会继续")
                warnedSlots = true
            }
            return
        }

        if (!hasBatchMaterials(module)) {
            agent.scheduler.push(CollectMaterialsTask(agent))
            return finish()
        }

        if (hasEnchantableWork(module)) {
            agent.scheduler.push(AnvilTask(agent, emptyList(), "Boot"))
        } else {
            agent.scheduler.push(StoreTask(agent))
        }
        finish()
    }

    private fun hasBatchMaterials(module: ANAutoEnchant): Boolean {
        val player = player ?: return false
        for (request in gearRequests(module)) {
            var count = 0
            for (slot in 0 until 36) {
                val stack = player.inventory.getItem(slot)
                if (!stack.isEmpty && request.items.contains(stack.item)) count++
            }
            if (count < request.count) return false
        }

        for (spec in module.selectedEnchants()) {
            var count = 0
            for (slot in 0 until 36) {
                val stack = player.inventory.getItem(slot)
                if (module.isMatchingBook(stack, spec)) count++
            }
            if (count < module.requiredBookCount(spec)) return false
        }

        return true
    }

    private fun hasEnchantableWork(module: ANAutoEnchant): Boolean {
        val player = player ?: return false
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (module.isTargetItem(stack)) return true
        }
        return false
    }

    private fun gearRequests(module: ANAutoEnchant): List<GearRequest> = module.gearRequests()

    private fun finish() {
        finished = true
    }

}
