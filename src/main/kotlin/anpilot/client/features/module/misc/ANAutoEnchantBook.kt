package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.ai.task.elytrapilot.ElytraStorageSupport
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.player.ANPacketMine
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import net.minecraft.client.gui.screens.inventory.MerchantScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ANAutoEnchantBook : ANBaseModule(
    name = "EnchantBook",
    description = "自动放置并破坏村民旁的讲台，直到刷出设定的目标附魔书",
    category = ANModuleCategory.MISC,
    chineseName = "自动刷附魔书"
) {
    enum class EnchantGroup(val label: String) {
        UNIVERSAL("通用附魔"),
        TOOLS("工具"),
        ARMOR("通用防具"),
        HELMET("头盔"),
        BOOTS("鞋子"),
        SWORD("剑"),
        BOW("弓"),
        CROSSBOW("弩"),
        TRIDENT("三叉戟"),
        FISHING("钓鱼竿")
    }

    val groupSelect = addSetting(ANSetting("Pages", EnchantGroup.UNIVERSAL))

    val mending = addSetting(ANSetting("经验修补", false) { groupSelect.value == EnchantGroup.UNIVERSAL })
    val unbreaking = addSetting(ANSetting("耐久", false) { groupSelect.value == EnchantGroup.UNIVERSAL })
    val bindingCurse = addSetting(ANSetting("绑定诅咒", false) { groupSelect.value == EnchantGroup.UNIVERSAL })
    val vanishingCurse = addSetting(ANSetting("消失诅咒", false) { groupSelect.value == EnchantGroup.UNIVERSAL })

    val efficiency = addSetting(ANSetting("效率", false) { groupSelect.value == EnchantGroup.TOOLS })
    val silkTouch = addSetting(ANSetting("精准采集", false) { groupSelect.value == EnchantGroup.TOOLS })
    val fortune = addSetting(ANSetting("时运", false) { groupSelect.value == EnchantGroup.TOOLS })

    val thorns = addSetting(ANSetting("荆棘", false) { groupSelect.value == EnchantGroup.ARMOR })
    val protection = addSetting(ANSetting("保护", false) { groupSelect.value == EnchantGroup.ARMOR })
    val fireProtection = addSetting(ANSetting("火焰保护", false) { groupSelect.value == EnchantGroup.ARMOR })
    val blastProtection = addSetting(ANSetting("爆炸保护", false) { groupSelect.value == EnchantGroup.ARMOR })
    val projectileProtection = addSetting(ANSetting("弹射物保护", false) { groupSelect.value == EnchantGroup.ARMOR })

    val respiration = addSetting(ANSetting("水下呼吸", false) { groupSelect.value == EnchantGroup.HELMET })
    val aquaAffinity = addSetting(ANSetting("水下速掘", false) { groupSelect.value == EnchantGroup.HELMET })

    val featherFalling = addSetting(ANSetting("摔落保护", false) { groupSelect.value == EnchantGroup.BOOTS })
    val depthStrider = addSetting(ANSetting("深海探索者", false) { groupSelect.value == EnchantGroup.BOOTS })
    val frostWalker = addSetting(ANSetting("冰霜行者", false) { groupSelect.value == EnchantGroup.BOOTS })

    val sharpness = addSetting(ANSetting("锋利", false) { groupSelect.value == EnchantGroup.SWORD })
    val smite = addSetting(ANSetting("亡灵杀手", false) { groupSelect.value == EnchantGroup.SWORD })
    val baneOfArthropods = addSetting(ANSetting("节肢杀手", false) { groupSelect.value == EnchantGroup.SWORD })
    val knockback = addSetting(ANSetting("击退", false) { groupSelect.value == EnchantGroup.SWORD })
    val fireAspect = addSetting(ANSetting("火焰附加", false) { groupSelect.value == EnchantGroup.SWORD })
    val looting = addSetting(ANSetting("抢夺", false) { groupSelect.value == EnchantGroup.SWORD })

    val power = addSetting(ANSetting("力量", false) { groupSelect.value == EnchantGroup.BOW })
    val punch = addSetting(ANSetting("冲击", false) { groupSelect.value == EnchantGroup.BOW })
    val flame = addSetting(ANSetting("火矢", false) { groupSelect.value == EnchantGroup.BOW })
    val infinity = addSetting(ANSetting("无限", false) { groupSelect.value == EnchantGroup.BOW })

    val piercing = addSetting(ANSetting("穿透", false) { groupSelect.value == EnchantGroup.CROSSBOW })
    val quickCharge = addSetting(ANSetting("快速装填", false) { groupSelect.value == EnchantGroup.CROSSBOW })
    val multishot = addSetting(ANSetting("多重射击", false) { groupSelect.value == EnchantGroup.CROSSBOW })

    val impaling = addSetting(ANSetting("穿刺", false) { groupSelect.value == EnchantGroup.TRIDENT })
    val loyalty = addSetting(ANSetting("忠诚", false) { groupSelect.value == EnchantGroup.TRIDENT })
    val riptide = addSetting(ANSetting("激流", false) { groupSelect.value == EnchantGroup.TRIDENT })
    val channeling = addSetting(ANSetting("引雷", false) { groupSelect.value == EnchantGroup.TRIDENT })

    val lucky = addSetting(ANSetting("海之眷顾", false) { groupSelect.value == EnchantGroup.FISHING })
    val lure = addSetting(ANSetting("诱饵", false) { groupSelect.value == EnchantGroup.FISHING })
    val resetTime = addSetting(ANSetting("等待时间", 1, 1, 10))

    private var moduleStage = StateStage.FIND_VILLAGER
    private var craftTable: BlockPos? = null
    private var placedOnce = false
    private var count = 0
    private var villager: Villager? = null
    private var timerMs = 0L

    override fun onEnable() {
        count = 0
        moduleStage = StateStage.FIND_VILLAGER
        craftTable = null
        villager = null
        placedOnce = false
        timerMs = 0L
    }

    override fun onDisable() {
        placedOnce = false
        Inventory.endSwap()
        Inventory.swapBack()
        mc.options.keyUse.isDown = false
        ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
    }

    override fun onTick() {
        ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
        if (!hasTargetSelected()) {
            disableWithMessage("你没有选择目标附魔书")
            return
        }
        taskTick()
    }

    private fun taskTick() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        when (moduleStage) {
            StateStage.FIND_VILLAGER -> {
                villager = findVillager()
                placedOnce = false
                val target = villager ?: return disableWithMessage("没有村民")

                val rotations = RotationUtil.getRotationsTo(player.getEyePosition(1.0f), target.getEyePosition(1.0f))
                ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))

                when {
                    !hasProfession(target) -> {
                        craftTable = getCraftingPos(target)
                        moduleStage = StateStage.WORKSTATION
                    }
                    isLibrarian(target) -> {
                        craftTable = getCraftingPos(target)
                        if (mc.screen is MerchantScreen) {
                            moduleStage = StateStage.CHECK_BOOKS
                        } else {
                            interactVillager(target)
                        }
                    }
                    else -> disableWithMessage("村民已有职业")
                }
            }

            StateStage.WORKSTATION -> {
                val target = villager ?: return resetToFind()
                val tablePos = craftTable ?: return resetToFind()

                val rotations = RotationUtil.getRotationsTo(player.getEyePosition(1.0f), Vec3.atCenterOf(tablePos))
                ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))

                val blockState = level.getBlockState(tablePos)
                if (!blockState.isAir) {
                    ElytraStorageSupport.mineBlock(tablePos)
                    return
                }

                if (!hasProfession(target)) {
                    timerMs = System.currentTimeMillis()
                    moduleStage = StateStage.PLACE_WORKSTATION
                } else {
                    moduleStage = StateStage.FIND_VILLAGER
                }
            }

            StateStage.PLACE_WORKSTATION -> {
                val tablePos = craftTable ?: return resetToFind()
                val lecternSlot = findLecternSlot()
                if (lecternSlot == Inventory.INVALID_SLOT) {
                    disableWithMessage("没有找到讲台，已关闭模块")
                    return
                }

                if (!placedOnce) {
                    val hit = supportHitResult(tablePos)
                    if (hit != null) {
                        val rotations = RotationUtil.getRotationsTo(player.getEyePosition(1.0f), hit.location)
                        ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))
                    }
                    placedOnce = placeLectern(tablePos, lecternSlot)
                    if (placedOnce) timerMs = System.currentTimeMillis()
                    return
                }

                if (System.currentTimeMillis() - timerMs >= resetTime.value * 1000L) {
                    count++
                    sendModuleMessage("尝试次数:$count")
                    moduleStage = StateStage.FIND_VILLAGER
                    placedOnce = false
                }
            }

            StateStage.CHECK_BOOKS -> {
                val screen = mc.screen as? MerchantScreen ?: return resetToFind()
                val menu: MerchantMenu = screen.menu
                val match = findMatchingOffer(menu.offers)
                player.closeContainer()

                if (match != null) {
                    sendModuleMessage("成功找到${match.label}附魔书！")
                    disableWithMessage("成功找到目标附魔，模块已关闭")
                } else {
                    moduleStage = StateStage.WORKSTATION
                }
            }
        }
    }

    private fun findMatchingOffer(offers: Iterable<MerchantOffer>): TargetEnchant? {
        for (offer in offers) {
            val result = offer.result
            if (!result.`is`(Items.ENCHANTED_BOOK)) continue
            val enchantments = EnchantmentHelper.getEnchantments(result)
            val match = findMatchingEnchant(enchantments)
            if (match != null) return match
        }
        return null
    }

    private fun findMatchingEnchant(enchantments: Map<Enchantment, Int>): TargetEnchant? {
        for ((enchantment, level) in enchantments) {
            for (target in targetEnchants()) {
                if (!target.setting.value) continue
                if (enchantment == target.key) {
                    if (target.level == null || level == target.level) return target
                }
            }
        }
        return null
    }

    private fun placeLectern(pos: BlockPos, slot: Int): Boolean {
        val player = mc.player ?: return false
        val swapped = if (slot == player.inventory.selected) true else Inventory.swap(slot, swapBack = true)
        if (!swapped) return false

        return try {
            val hit = supportHitResult(pos) ?: return false
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            player.swing(InteractionHand.MAIN_HAND)
            true
        } finally {
            if (slot != player.inventory.selected) Inventory.swapBack()
        }
    }

    private fun supportHitResult(pos: BlockPos): BlockHitResult? {
        val level = mc.level ?: return null
        if (!level.getBlockState(pos).canBeReplaced()) return null

        val below = pos.below()
        if (!level.getBlockState(below).isAir && !level.getBlockState(below).canBeReplaced()) {
            return BlockHitResult(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), Direction.UP, below, false)
        }

        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            val state = level.getBlockState(neighbor)
            if (state.isAir || state.canBeReplaced()) continue
            return BlockHitResult(Vec3.atCenterOf(neighbor), direction, neighbor, false)
        }
        return null
    }

    private fun findLecternSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        if (player.mainHandItem.`is`(Items.LECTERN)) return player.inventory.selected
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.LECTERN)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun breakBlock(pos: BlockPos) {
        val player = mc.player ?: return
        mc.gameMode?.startDestroyBlock(pos, Direction.UP)
        mc.gameMode?.continueDestroyBlock(pos, Direction.UP)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun findPacketMine(): ANPacketMine? {
        return runCatching {
            ANServiceRegistry.runtime.moduleManager.get("PacketMine") as? ANPacketMine
        }.getOrNull()
    }

    private fun interactVillager(villager: Villager) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        gameMode.interact(player, villager, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun findVillager(): Villager? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val range = 3.0
        val box = AABB(
            player.x - range,
            player.y - 1.0,
            player.z - range,
            player.x + range,
            player.y + range,
            player.z + range
        )
        return level.getEntities(EntityTypeTest.forClass(Villager::class.java), box) { true }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun getCraftingPos(entity: Entity): BlockPos {
        val player = mc.player ?: return entity.blockPosition()
        val playerEyes = player.getEyePosition(1.0f)
        val center = entity.boundingBox.center
        var dx = playerEyes.x - center.x
        var dz = playerEyes.z - center.z
        val len = sqrt(dx * dx + dz * dz)
        if (len == 0.0) return entity.blockPosition()
        dx /= len
        dz /= len
        return entity.blockPosition().offset(dx.roundToInt(), 0, dz.roundToInt())
    }

    private fun hasProfession(entity: Entity): Boolean {
        val profession = (entity as? Villager)?.villagerData?.profession ?: return false
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT
    }

    private fun isLibrarian(entity: Entity): Boolean {
        val profession = (entity as? Villager)?.villagerData?.profession ?: return false
        return profession == VillagerProfession.LIBRARIAN
    }

    private fun resetToFind() {
        moduleStage = StateStage.FIND_VILLAGER
        placedOnce = false
    }

    private fun hasTargetSelected(): Boolean = targetEnchants().any { it.setting.value }

    private fun sendModuleMessage(message: String) {
        sendClientMessage(message)
    }

    private fun disableWithMessage(message: String) {
        disable(message)
    }

    private fun targetEnchants(): List<TargetEnchant> = listOf(
        TargetEnchant(Enchantments.MENDING, null, mending, "经验修补"),
        TargetEnchant(Enchantments.UNBREAKING, 3, unbreaking, "耐久3"),
        TargetEnchant(Enchantments.BINDING_CURSE, null, bindingCurse, "绑定诅咒"),
        TargetEnchant(Enchantments.VANISHING_CURSE, null, vanishingCurse, "消失诅咒"),
        TargetEnchant(Enchantments.BLOCK_EFFICIENCY, 5, efficiency, "效率5"),
        TargetEnchant(Enchantments.SILK_TOUCH, null, silkTouch, "精准采集"),
        TargetEnchant(Enchantments.BLOCK_FORTUNE, 3, fortune, "时运3"),
        TargetEnchant(Enchantments.THORNS, 3, thorns, "荆棘3"),
        TargetEnchant(Enchantments.ALL_DAMAGE_PROTECTION, 4, protection, "保护4"),
        TargetEnchant(Enchantments.FIRE_PROTECTION, 4, fireProtection, "火焰保护4"),
        TargetEnchant(Enchantments.BLAST_PROTECTION, 4, blastProtection, "爆炸保护4"),
        TargetEnchant(Enchantments.PROJECTILE_PROTECTION, 4, projectileProtection, "弹射物保护4"),
        TargetEnchant(Enchantments.RESPIRATION, 3, respiration, "水下呼吸3"),
        TargetEnchant(Enchantments.AQUA_AFFINITY, null, aquaAffinity, "水下速掘"),
        TargetEnchant(Enchantments.FALL_PROTECTION, 4, featherFalling, "摔落保护4"),
        TargetEnchant(Enchantments.DEPTH_STRIDER, 3, depthStrider, "深海探索者3"),
        TargetEnchant(Enchantments.FROST_WALKER, 2, frostWalker, "冰霜行者2"),
        TargetEnchant(Enchantments.SHARPNESS, 5, sharpness, "锋利5"),
        TargetEnchant(Enchantments.SMITE, 5, smite, "亡灵杀手5"),
        TargetEnchant(Enchantments.BANE_OF_ARTHROPODS, 5, baneOfArthropods, "节肢杀手5"),
        TargetEnchant(Enchantments.KNOCKBACK, 2, knockback, "击退2"),
        TargetEnchant(Enchantments.FIRE_ASPECT, 2, fireAspect, "火焰附加2"),
        TargetEnchant(Enchantments.MOB_LOOTING, 3, looting, "抢夺3"),
        TargetEnchant(Enchantments.POWER_ARROWS, 5, power, "力量5"),
        TargetEnchant(Enchantments.PUNCH_ARROWS, 2, punch, "冲击2"),
        TargetEnchant(Enchantments.FLAMING_ARROWS, null, flame, "火矢"),
        TargetEnchant(Enchantments.INFINITY_ARROWS, null, infinity, "无限"),
        TargetEnchant(Enchantments.PIERCING, 4, piercing, "穿透4"),
        TargetEnchant(Enchantments.QUICK_CHARGE, 3, quickCharge, "快速装填3"),
        TargetEnchant(Enchantments.MULTISHOT, null, multishot, "多重射击"),
        TargetEnchant(Enchantments.IMPALING, 5, impaling, "穿刺5"),
        TargetEnchant(Enchantments.LOYALTY, 3, loyalty, "忠诚3"),
        TargetEnchant(Enchantments.RIPTIDE, 3, riptide, "激流3"),
        TargetEnchant(Enchantments.CHANNELING, null, channeling, "引雷"),
        TargetEnchant(Enchantments.FISHING_LUCK, 3, lucky, "海之眷顾3"),
        TargetEnchant(Enchantments.FISHING_SPEED, 3, lure, "诱饵3")
    )

    private data class TargetEnchant(
        val key: Enchantment,
        val level: Int?,
        val setting: ANSetting<Boolean>,
        val label: String
    )

    private enum class StateStage {
        FIND_VILLAGER,
        WORKSTATION,
        PLACE_WORKSTATION,
        CHECK_BOOKS
    }
}
