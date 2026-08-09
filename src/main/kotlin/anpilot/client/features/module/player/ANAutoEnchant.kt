package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.autoenchant.BootTask
import anpilot.client.features.ai.task.autoenchant.EnchantSpec
import anpilot.client.features.ai.task.autoenchant.GearRequest
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.awt.Color

class ANAutoEnchant : ANBaseModule(
    name = "AutoEnchant",
    description = "自动从绑定箱子取装备和附魔书，在铁砧批量完成附魔并处理经验补给",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动附魔"
), ANWorldRenderModule {
    enum class TargetType {
        Helmet,
        Chestplate,
        Leggings,
        Boots,
        Pickaxe,
        Axe,
        Shovel,
        Sword,
        Elytra
    }

    enum class ChestRole {
        Book,
        Gear,
        Output
    }

    val targetType = addSetting(ANSetting("Target", TargetType.Helmet))
    val batchSets = addSetting(ANSetting("Sets", 1, 1, 9))
    val bindingFillAlpha = addSetting(ANSetting("FillAlpha", 55, 0, 255))

    val mending = addSetting(ANSetting("经验修补", true))
    val unbreaking = addSetting(ANSetting("耐久", true))

    val protection = addSetting(ANSetting("保护", true) { isArmorTarget() })
    val fireProtection = addSetting(ANSetting("火焰保护", false) { isArmorTarget() })
    val blastProtection = addSetting(ANSetting("爆炸保护", false) { isArmorTarget() })
    val projectileProtection = addSetting(ANSetting("弹射物保护", false) { isArmorTarget() })
    val thorns = addSetting(ANSetting("荆棘", false) { isArmorTarget() })
    val respiration = addSetting(ANSetting("水下呼吸", true) { targetType.value == TargetType.Helmet })
    val aquaAffinity = addSetting(ANSetting("水下速掘", true) { targetType.value == TargetType.Helmet })
    val featherFalling = addSetting(ANSetting("摔落保护", true) { targetType.value == TargetType.Boots })
    val depthStrider = addSetting(ANSetting("深海探索者", true) { targetType.value == TargetType.Boots })
    val frostWalker = addSetting(ANSetting("冰霜行者", false) { targetType.value == TargetType.Boots })
    val swiftSneak = addSetting(ANSetting("迅捷潜行", true) { targetType.value == TargetType.Leggings })
    val soulSpeed = addSetting(ANSetting("灵魂疾行", true) { targetType.value == TargetType.Boots })

    val efficiency = addSetting(ANSetting("效率", true) { isToolTarget() })
    val silkTouch = addSetting(ANSetting("精准采集", false) { isToolTarget() })
    val fortune = addSetting(ANSetting("时运", true) { isToolTarget() })
    val sharpness = addSetting(ANSetting("锋利", true) { usesDamageEnchants() })
    val smite = addSetting(ANSetting("亡灵杀手", false) { usesDamageEnchants() })
    val baneOfArthropods = addSetting(ANSetting("节肢杀手", false) { usesDamageEnchants() })
    val looting = addSetting(ANSetting("抢夺", true) { usesWeaponEnchants() })
    val sweepingEdge = addSetting(ANSetting("横扫之刃", true) { targetType.value == TargetType.Sword })
    val fireAspect = addSetting(ANSetting("火焰附加", true) { usesWeaponEnchants() })
    val knockback = addSetting(ANSetting("击退", true) { usesWeaponEnchants() })

    var xpStandPos: BlockPos? = null
        private set
    var xpButtonPos: BlockPos? = null
        private set
    val anvilPositions = ArrayList<BlockPos>()
    val anvilPos: BlockPos?
        get() = anvilPositions.firstOrNull()
    val chestBindings = ArrayList<ChestBinding>()

    private var agent: ANAgent? = null
    private var running = false
    private var lookedChestPos: BlockPos? = null
    private var pendingChestPos: BlockPos? = null
    private var openStoragePos: BlockPos? = null
    private var openStorageContainerId = -1
    private var openStorageOpenedAt = 0L
    private var conflictMessageCooldown = 0
    private var readyMessageSent = false

    override fun onEnable() {
        running = false
        agent = null
        readyMessageSent = false
        pendingChestPos = null
        lookedChestPos = null
        resetOpenStorageDelay()
        BaritoneHelper.configure()
    }

    override fun onDisable() {
        agent?.stop()
        agent = null
        running = false
        readyMessageSent = false
        clearBindings()
        resetOpenStorageDelay()
        Inventory.endSwap()
        Inventory.swapBack()
        BaritoneHelper.cancel()
        BaritoneHelper.restore()
    }

    override fun onUnload() {
        onDisable()
    }

    override fun onTick() {
        enforceExclusiveSettings()

        if (running) {
            agent?.tick()
            if (agent?.scheduler?.current() == null) {
                running = false
            }
            return
        }

        notifyIfReadyToStart()
        updateLookedChest()

        val menu = mc.player?.containerMenu
        if (menu is ChestMenu || menu is ShulkerBoxMenu) {
            val chestPos = pendingChestPos ?: lookedChestPos
            if (!storageReady(chestPos, menu)) return
            if (chestPos != null && !isBoundChest(chestPos)) {
                bindOpenStorage(chestPos, menu)
                pendingChestPos = null
                resetOpenStorageDelay()
                mc.player?.closeContainer()
            } else if (chestPos != null && isBoundChest(chestPos)) {
                resetOpenStorageDelay()
                startTaskFromChest(chestPos)
            }
        } else {
            resetOpenStorageDelay()
        }
    }

    override fun onMousePressed(button: Int) {
        if (!enabled || button != MIDDLE_MOUSE_BUTTON) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        bindTarget(hit.blockPos)
    }

    override fun renderWorld(context: LevelRenderContext) {
        renderPos(context, xpStandPos, ANColor(80, 170, 255, 70), ANColor(80, 170, 255, 255))
        renderPos(context, xpButtonPos, ANColor(255, 210, 80, 70), ANColor(255, 210, 80, 255))
        for (anvilPos in anvilPositions) {
            renderPos(context, anvilPos, ANColor(180, 80, 255, 70), ANColor(180, 80, 255, 255))
        }
        for (binding in chestBindings) {
            val color = binding.color
            val fill = ANColor(color.red, color.green, color.blue, bindingFillAlpha.value)
            val line = ANColor(color.red, color.green, color.blue, 255)
            renderPos(
                context,
                binding.pos,
                fill,
                line
            )
            renderPos(context, binding.secondaryPos, fill, line)
        }
    }

    fun finishCurrentBatch() {
        running = false
        agent?.stop()
        agent = null
    }

    fun selectedEnchants(): List<EnchantSpec> {
        val specs = ArrayList<EnchantSpec>()
        val targetItems = targetItems()
        specs.add(EnchantSpec(Enchantments.MENDING, null, "经验修补", targetItems, mending.value))
        specs.add(EnchantSpec(Enchantments.UNBREAKING, 3, "耐久3", targetItems, unbreaking.value))

        if (isArmorTarget()) {
            specs.add(EnchantSpec(Enchantments.ALL_DAMAGE_PROTECTION, 4, "保护4", targetItems, protection.value))
            specs.add(EnchantSpec(Enchantments.FIRE_PROTECTION, 4, "火焰保护4", targetItems, fireProtection.value))
            specs.add(EnchantSpec(Enchantments.BLAST_PROTECTION, 4, "爆炸保护4", targetItems, blastProtection.value))
            specs.add(EnchantSpec(Enchantments.PROJECTILE_PROTECTION, 4, "弹射物保护4", targetItems, projectileProtection.value))
            specs.add(EnchantSpec(Enchantments.THORNS, 3, "荆棘3", targetItems, thorns.value))
        }

        if (targetType.value == TargetType.Helmet) {
            specs.add(EnchantSpec(Enchantments.RESPIRATION, 3, "水下呼吸3", targetItems, respiration.value))
            specs.add(EnchantSpec(Enchantments.AQUA_AFFINITY, null, "水下速掘", targetItems, aquaAffinity.value))
        }

        if (targetType.value == TargetType.Boots) {
            specs.add(EnchantSpec(Enchantments.FALL_PROTECTION, 4, "摔落保护4", targetItems, featherFalling.value))
            specs.add(EnchantSpec(Enchantments.DEPTH_STRIDER, 3, "深海探索者3", targetItems, depthStrider.value))
            specs.add(EnchantSpec(Enchantments.FROST_WALKER, 2, "冰霜行者2", targetItems, frostWalker.value))
            specs.add(EnchantSpec(Enchantments.SOUL_SPEED, 3, "灵魂疾行3", targetItems, soulSpeed.value))
        }

        if (targetType.value == TargetType.Leggings) {
            specs.add(EnchantSpec(Enchantments.SWIFT_SNEAK, 3, "迅捷潜行3", targetItems, swiftSneak.value))
        }

        if (isToolTarget()) {
            specs.add(EnchantSpec(Enchantments.BLOCK_EFFICIENCY, 5, "效率5", targetItems, efficiency.value))
            specs.add(EnchantSpec(Enchantments.SILK_TOUCH, null, "精准采集", targetItems, silkTouch.value))
            specs.add(EnchantSpec(Enchantments.BLOCK_FORTUNE, 3, "时运3", targetItems, fortune.value))
        }

        if (usesDamageEnchants()) {
            specs.add(EnchantSpec(Enchantments.SHARPNESS, 5, "锋利5", targetItems, sharpness.value))
            specs.add(EnchantSpec(Enchantments.SMITE, 5, "亡灵杀手5", targetItems, smite.value))
            specs.add(EnchantSpec(Enchantments.BANE_OF_ARTHROPODS, 5, "节肢杀手5", targetItems, baneOfArthropods.value))
        }

        if (usesWeaponEnchants()) {
            specs.add(EnchantSpec(Enchantments.MOB_LOOTING, 3, "抢夺3", targetItems, looting.value))
            specs.add(EnchantSpec(Enchantments.FIRE_ASPECT, 2, "火焰附加2", targetItems, fireAspect.value))
            specs.add(EnchantSpec(Enchantments.KNOCKBACK, 2, "击退2", targetItems, knockback.value))
        }

        if (targetType.value == TargetType.Sword) {
            specs.add(EnchantSpec(Enchantments.SWEEPING_EDGE, 3, "横扫之刃3", targetItems, sweepingEdge.value))
        }

        return specs.filter { it.enabled }
    }

    fun targetItems(): List<Item> = when (targetType.value) {
        TargetType.Helmet -> listOf(Items.DIAMOND_HELMET, Items.NETHERITE_HELMET)
        TargetType.Chestplate -> listOf(Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE)
        TargetType.Leggings -> listOf(Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS)
        TargetType.Boots -> listOf(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS)
        TargetType.Pickaxe -> listOf(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)
        TargetType.Axe -> listOf(Items.DIAMOND_AXE, Items.NETHERITE_AXE)
        TargetType.Shovel -> listOf(Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL)
        TargetType.Sword -> listOf(Items.DIAMOND_SWORD, Items.NETHERITE_SWORD)
        TargetType.Elytra -> listOf(Items.ELYTRA)
    }

    fun targetBaseItems(): List<Item> = targetItems()

    fun gearRequests(): List<GearRequest> =
        listOf(GearRequest(targetItems(), targetTypeLabel(), batchSets.value))

    fun isTargetItem(stack: ItemStack): Boolean = !stack.isEmpty && targetItems().contains(stack.item)

    fun isMatchingBook(stack: ItemStack, enchant: Enchantment): Boolean {
        if (!stack.`is`(Items.ENCHANTED_BOOK)) return false
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        return enchantments.containsKey(enchant)
    }

    fun isMatchingBook(stack: ItemStack, spec: EnchantSpec): Boolean {
        if (!stack.`is`(Items.ENCHANTED_BOOK)) return false
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        val lvl = enchantments[spec.enchantment] ?: return false
        return spec.level == null || lvl >= spec.level
    }

    fun firstBookEnchant(stack: ItemStack): EnchantSpec? {
        if (!stack.`is`(Items.ENCHANTED_BOOK)) return null
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        for ((enchant, _) in enchantments) {
            return selectedEnchants().firstOrNull { it.enchantment == enchant }
                ?: knownEnchantSpecs().firstOrNull { it.enchantment == enchant }
        }
        return null
    }

    fun requiredBookCount(spec: EnchantSpec): Int = batchSets.value

    fun requiredGearCount(): Int = batchSets.value

    fun requiredEmptySlots(): Int {
        val missingGearSlots = gearRequests().sumOf { request ->
            (request.count - inventoryItemCount(request.items)).coerceAtLeast(0)
        }
        val missingBookSlots = selectedEnchants().sumOf { spec ->
            (requiredBookCount(spec) - inventoryBookCount(spec)).coerceAtLeast(0)
        }
        return missingGearSlots + missingBookSlots
    }

    fun emptyInventorySlots(): Int {
        val player = mc.player ?: return 0
        var empty = 0
        for (slot in 0 until 36) {
            if (player.inventory.getItem(slot).isEmpty) empty++
        }
        return empty
    }

    private fun inventoryItemCount(items: List<Item>): Int {
        val player = mc.player ?: return 0
        var count = 0
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && items.contains(stack.item)) count++
        }
        return count
    }

    private fun inventoryBookCount(spec: EnchantSpec): Int {
        val player = mc.player ?: return 0
        var count = 0
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (isMatchingBook(stack, spec)) count++
        }
        return count
    }

    fun bookChestsFor(spec: EnchantSpec): List<ChestBinding> =
        chestBindings.filter { it.role == ChestRole.Book && it.enchantment == spec.enchantment }

    fun gearChests(): List<ChestBinding> = chestBindings.filter { it.role == ChestRole.Gear }

    fun outputChests(): List<ChestBinding> = chestBindings.filter { it.role == ChestRole.Output }

    fun availableAnvils(): List<BlockPos> {
        pruneMissingAnvils()
        return anvilPositions.toList()
    }

    fun isMissingAnvil(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) return false
        return level.getBlockState(pos).block !is AnvilBlock
    }

    fun removeAnvil(pos: BlockPos): Boolean {
        val removed = anvilPositions.remove(pos)
        if (removed) readyMessageSent = false
        return removed
    }

    private fun startTaskFromChest(chestPos: BlockPos) {
        if (!validateBeforeStart()) return
        val nextAgent = ANAgent(this)
        agent = nextAgent
        running = true
        mc.player?.closeContainer()
        nextAgent.scheduler.push(BootTask(nextAgent))
        sendClientMessage("自动附魔任务开始!")
    }

    private fun validateBeforeStart(): Boolean {
        if (availableAnvils().isEmpty()) {
            sendClientMessage("尚未绑定铁砧")
            return false
        }
        if (xpStandPos == null) {
            sendClientMessage("尚未绑定刷经验站立位置")
            return false
        }
        if (xpButtonPos == null) {
            sendClientMessage("尚未绑定经验机按钮")
            return false
        }
        if (selectedEnchants().isEmpty()) {
            sendClientMessage("尚未选择需要附魔的项目")
            return false
        }
        if (gearChests().isEmpty()) {
            sendClientMessage("尚未绑定装备/工具箱")
            return false
        }
        if (outputChests().isEmpty()) {
            sendClientMessage("尚未绑定成品输出箱，第一个格子为空的箱子会被识别为输出箱")
            return false
        }
        val missingBook = selectedEnchants().firstOrNull { bookChestsFor(it).isEmpty() }
        if (missingBook != null) {
            sendClientMessage("缺少附魔书箱绑定: ${missingBook.label}")
            return false
        }
        return true
    }

    private fun bindTarget(pos: BlockPos) {
        val level = mc.level ?: return
        val block = level.getBlockState(pos).block
        when {
            block is ChestBlock || block is ShulkerBoxBlock -> {
                pendingChestPos = pos.immutable()
                sendClientMessage("已标记箱子,请打开它以识别附魔书/装备/输出类型")
            }
            block is AnvilBlock -> {
                bindAnvil(pos)
            }
            block is ButtonBlock -> {
                xpButtonPos = pos.immutable()
                sendClientMessage("已绑定经验机按钮")
            }
            else -> {
                xpStandPos = pos.immutable()
                sendClientMessage("已绑定刷经验站立位置")
            }
        }
        notifyIfReadyToStart()
    }

    private fun bindOpenStorage(pos: BlockPos, menu: AbstractContainerMenu) {
        val storageSize = storageSlotCount(menu)
        if (storageSize <= 0) return
        val secondaryPos = secondaryChestPos(pos)

        val first = menu.slots[0].item
        val binding = classifyStorageByContents(pos, secondaryPos, menu, storageSize) ?: when {
            first.isEmpty -> createBinding(pos, secondaryPos, ChestRole.Output, null, "输出箱")
            else -> null
        }

        if (binding == null) {
            sendClientMessage("未识别：第一个格子为空=输出箱，附魔书=书箱，钻石/合金装备工具=材料箱")
            return
        }

        chestBindings.removeIf { it.matches(binding.pos) || binding.secondaryPos?.let { secondary -> it.matches(secondary) } == true }
        chestBindings.add(binding)
        sendClientMessage("绑定箱子为 ${binding.label}")
    }

    private fun notifyIfReadyToStart() {
        val ready = isReadyToStart()
        if (!ready) {
            readyMessageSent = false
            return
        }
        if (readyMessageSent) return
        readyMessageSent = true
        sendClientMessage("已完成目标物品、附魔书箱、装备箱、输出箱、铁砧和经验机绑定，可以开始任务! 打开任意已绑定箱启动!")
    }

    private fun isReadyToStart(): Boolean {
        if (availableAnvils().isEmpty() || xpStandPos == null || xpButtonPos == null) return false
        val enchants = selectedEnchants()
        if (enchants.isEmpty()) return false
        if (gearChests().isEmpty() || outputChests().isEmpty()) return false
        return enchants.all { bookChestsFor(it).isNotEmpty() }
    }

    private fun classifyStorageByContents(pos: BlockPos, secondaryPos: BlockPos?, menu: AbstractContainerMenu, storageSize: Int): ChestBinding? {
        for (slot in 0 until storageSize) {
            val stack = menu.slots[slot].item
            val spec = firstBookEnchant(stack)
            if (spec != null) return createBinding(pos, secondaryPos, ChestRole.Book, spec.enchantment, spec.label)
            if (isTargetItem(stack)) return createBinding(pos, secondaryPos, ChestRole.Gear, null, "装备/工具箱")
        }
        return null
    }

    private fun createBinding(
        pos: BlockPos,
        secondaryPos: BlockPos?,
        role: ChestRole,
        enchantment: Enchantment?,
        label: String
    ): ChestBinding = ChestBinding(
        pos.immutable(),
        secondaryPos,
        role,
        enchantment,
        label,
        colorForBinding(role, enchantment)
    )

    private fun updateLookedChest() {
        if (mc.screen != null) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val state = mc.level?.getBlockState(hit.blockPos) ?: return
        if (state.block is ChestBlock || state.block is ShulkerBoxBlock) {
            lookedChestPos = hit.blockPos.immutable()
        }
    }

    private fun isBoundChest(pos: BlockPos): Boolean = chestBindings.any { it.matches(pos) }

    private fun storageReady(chestPos: BlockPos?, menu: AbstractContainerMenu): Boolean {
        if (chestPos == null) return false
        if (openStoragePos != chestPos || openStorageContainerId != menu.containerId) {
            openStoragePos = chestPos.immutable()
            openStorageContainerId = menu.containerId
            openStorageOpenedAt = System.currentTimeMillis()
            return false
        }
        return true
    }

    private fun resetOpenStorageDelay() {
        openStoragePos = null
        openStorageContainerId = -1
        openStorageOpenedAt = 0L
    }

    private fun clearBindings() {
        readyMessageSent = false
        xpStandPos = null
        xpButtonPos = null
        anvilPositions.clear()
        chestBindings.clear()
        pendingChestPos = null
        lookedChestPos = null
    }

    private fun bindAnvil(pos: BlockPos) {
        val immutable = pos.immutable()
        if (anvilPositions.contains(immutable)) {
            sendClientMessage("铁砧已绑定")
            return
        }
        if (anvilPositions.size >= MAX_BOUND_ANVILS) {
            sendClientMessage("铁砧最多绑定 $MAX_BOUND_ANVILS 个，重启模块清空后重新绑定")
            return
        }
        anvilPositions.add(immutable)
    }

    private fun pruneMissingAnvils() {
        val missing = anvilPositions.filter { isMissingAnvil(it) }
        if (missing.isEmpty()) return
        anvilPositions.removeAll(missing.toSet())
        readyMessageSent = false
    }

    private fun secondaryChestPos(pos: BlockPos): BlockPos? {
        val state = mc.level?.getBlockState(pos) ?: return null
        if (state.block !is ChestBlock || !state.hasProperty(ChestBlock.TYPE)) return null
        val chestType = state.getValue(ChestBlock.TYPE)
        if (chestType == ChestType.SINGLE) return null
        val facing = state.getValue(ChestBlock.FACING)
        val offsetDir = if (chestType == ChestType.RIGHT) facing.counterClockWise else facing.clockWise
        return pos.relative(offsetDir).immutable()
    }

    private fun enforceExclusiveSettings() {
        if (conflictMessageCooldown > 0) conflictMessageCooldown--
        enforceExclusive("保护类附魔", listOf(protection, fireProtection, blastProtection, projectileProtection))
        enforceExclusive("靴子移动附魔", listOf(depthStrider, frostWalker))
        enforceExclusive("工具采集附魔", listOf(silkTouch, fortune))
        enforceExclusive("伤害类附魔", listOf(sharpness, smite, baneOfArthropods))
    }

    private fun enforceExclusive(name: String, settings: List<ANSetting<Boolean>>) {
        val enabled = settings.filter { it.value }
        if (enabled.size <= 1) return
        enabled.drop(1).forEach { it.setValue(false) }
        if (conflictMessageCooldown <= 0) {
            sendClientMessage("$name 互斥，已保留 ${enabled.first().name} 并取消其它选择")
            conflictMessageCooldown = 40
        }
    }

    private fun usesDamageEnchants(): Boolean =
        targetType.value == TargetType.Axe || targetType.value == TargetType.Sword

    private fun usesWeaponEnchants(): Boolean =
        targetType.value == TargetType.Sword

    private fun isArmorTarget(): Boolean = when (targetType.value) {
        TargetType.Helmet,
        TargetType.Chestplate,
        TargetType.Leggings,
        TargetType.Boots -> true
        else -> false
    }

    private fun isToolTarget(): Boolean = when (targetType.value) {
        TargetType.Pickaxe,
        TargetType.Axe,
        TargetType.Shovel -> true
        else -> false
    }

    private fun targetTypeLabel(): String = when (targetType.value) {
        TargetType.Helmet -> "头盔"
        TargetType.Chestplate -> "胸甲"
        TargetType.Leggings -> "护腿"
        TargetType.Boots -> "靴子"
        TargetType.Pickaxe -> "镐"
        TargetType.Axe -> "斧"
        TargetType.Shovel -> "锹"
        TargetType.Sword -> "剑"
        TargetType.Elytra -> "鞘翅"
    }

    private fun knownEnchantSpecs(): List<EnchantSpec> = listOf(
        EnchantSpec(Enchantments.MENDING, null, "经验修补", targetItems(), true),
        EnchantSpec(Enchantments.UNBREAKING, 3, "耐久3", targetItems(), true),
        EnchantSpec(Enchantments.ALL_DAMAGE_PROTECTION, 4, "保护4", targetItems(), true),
        EnchantSpec(Enchantments.FIRE_PROTECTION, 4, "火焰保护4", targetItems(), true),
        EnchantSpec(Enchantments.BLAST_PROTECTION, 4, "爆炸保护4", targetItems(), true),
        EnchantSpec(Enchantments.PROJECTILE_PROTECTION, 4, "弹射物保护4", targetItems(), true),
        EnchantSpec(Enchantments.THORNS, 3, "荆棘3", targetItems(), true),
        EnchantSpec(Enchantments.RESPIRATION, 3, "水下呼吸3", listOf(Items.DIAMOND_HELMET, Items.NETHERITE_HELMET), true),
        EnchantSpec(Enchantments.AQUA_AFFINITY, null, "水下速掘", listOf(Items.DIAMOND_HELMET, Items.NETHERITE_HELMET), true),
        EnchantSpec(Enchantments.FALL_PROTECTION, 4, "摔落保护4", listOf(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS), true),
        EnchantSpec(Enchantments.DEPTH_STRIDER, 3, "深海探索者3", listOf(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS), true),
        EnchantSpec(Enchantments.FROST_WALKER, 2, "冰霜行者2", listOf(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS), true),
        EnchantSpec(Enchantments.BLOCK_EFFICIENCY, 5, "效率5", targetItems(), true),
        EnchantSpec(Enchantments.SILK_TOUCH, null, "精准采集", targetItems(), true),
        EnchantSpec(Enchantments.BLOCK_FORTUNE, 3, "时运3", targetItems(), true),
        EnchantSpec(Enchantments.SWIFT_SNEAK, 3, "迅捷潜行3", listOf(Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS), true),
        EnchantSpec(Enchantments.SOUL_SPEED, 3, "灵魂疾行3", listOf(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS), true),
        EnchantSpec(Enchantments.SHARPNESS, 5, "锋利5", targetItems(), true),
        EnchantSpec(Enchantments.SMITE, 5, "亡灵杀手5", targetItems(), true),
        EnchantSpec(Enchantments.BANE_OF_ARTHROPODS, 5, "节肢杀手5", targetItems(), true),
        EnchantSpec(Enchantments.MOB_LOOTING, 3, "抢夺3", targetItems(), true),
        EnchantSpec(Enchantments.SWEEPING_EDGE, 3, "横扫之刃3", targetItems(), true),
        EnchantSpec(Enchantments.FIRE_ASPECT, 2, "火焰附加2", targetItems(), true),
        EnchantSpec(Enchantments.KNOCKBACK, 2, "击退2", targetItems(), true)
    )

    private fun storageSlotCount(menu: AbstractContainerMenu): Int =
        (menu.slots.size - PLAYER_INVENTORY_MENU_SLOTS).coerceAtLeast(0)

    private fun renderPos(context: LevelRenderContext, pos: BlockPos?, fill: ANColor, line: ANColor) {
        if (pos == null) return
        ANRender3DEngine.box(context, AABB(pos), line, fill)
    }

    private fun colorForBinding(role: ChestRole, enchantment: Enchantment?): Color {
        val key = when (role) {
            ChestRole.Book -> "Book:${enchantment.toString()}"
            ChestRole.Gear -> "Gear"
            ChestRole.Output -> "Output"
        }
        val hash = key.hashCode()
        return Color(
            70 + ((hash ushr 16) and 0x7F),
            70 + ((hash ushr 8) and 0x7F),
            70 + (hash and 0x7F)
        )
    }

    data class ChestBinding(
        val pos: BlockPos,
        val secondaryPos: BlockPos?,
        val role: ChestRole,
        val enchantment: Enchantment?,
        val label: String,
        val color: Color
    ) {
        fun matches(target: BlockPos): Boolean = pos == target || secondaryPos == target
    }

    private companion object {
        const val MIDDLE_MOUSE_BUTTON = 2
        const val PLAYER_INVENTORY_MENU_SLOTS = 36
        const val MAX_BOUND_ANVILS = 4
    }
}
