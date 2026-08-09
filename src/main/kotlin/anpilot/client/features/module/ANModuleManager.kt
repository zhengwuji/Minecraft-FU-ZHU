package anpilot.client.features.module

import anpilot.client.api.module.ANModule
import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleRegistry
import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.module.hud.ANDraggableHudModule
import anpilot.client.features.module.anpilot.ANPilotClient
import anpilot.client.features.module.anpilot.ANPilotConfig
import anpilot.client.features.module.anpilot.ANPilotFriend
import anpilot.client.features.module.anpilot.ANPilotGuiEditor
import anpilot.client.features.module.anpilot.ANPilotHud
import anpilot.client.features.module.anpilot.ANPilotTheme
import anpilot.client.features.module.combat.ANAutoCrystal
import anpilot.client.features.module.combat.ANAutoMine
import anpilot.client.features.module.combat.ANAnchorAura
import anpilot.client.features.module.combat.ANAutoBed
import anpilot.client.features.module.combat.ANBowAim
import anpilot.client.features.module.combat.ANAutoTotem
import anpilot.client.features.module.combat.ANFeetPlace
import anpilot.client.features.module.combat.ANKillAura
import anpilot.client.features.module.combat.ANKnockback
import anpilot.client.features.module.combat.ANPopCount
import anpilot.client.features.module.hud.ANCoordinate
import anpilot.client.features.module.hud.ANDropsList
import anpilot.client.features.module.hud.ANFPS
import anpilot.client.features.module.hud.ANHotBar
import anpilot.client.features.module.hud.ANHudGuiEditor
import anpilot.client.features.module.hud.ANInventory
import anpilot.client.features.module.hud.ANModuleList
import anpilot.client.features.module.hud.ANPing
import anpilot.client.features.module.hud.ANPlayerArmor
import anpilot.client.features.module.hud.ANPotions
import anpilot.client.features.module.hud.ANTargetInfo
import anpilot.client.features.module.hud.ANWaterMark
import anpilot.client.features.module.misc.ANAntiAFK
import anpilot.client.features.module.misc.ANAntiCrash
import anpilot.client.features.module.misc.ANBotTask
import anpilot.client.features.module.misc.ANAutoLog
import anpilot.client.features.module.misc.ANAutoEnchantBook
import anpilot.client.features.module.misc.ANAutoFarm
import anpilot.client.features.module.misc.ANAutoFish
import anpilot.client.features.module.misc.ANAutoFeed
import anpilot.client.features.module.misc.ANBaseFinder
import anpilot.client.features.module.misc.ANChatUtils
import anpilot.client.features.module.misc.ANElytraPilotPlus
import anpilot.client.features.module.misc.ANFakePlayer
import anpilot.client.features.module.misc.ANFlyTo
import anpilot.client.features.module.misc.ANFriendAdd
import anpilot.client.features.module.misc.ANLeaveInfo
import anpilot.client.features.module.misc.ANMapArt
import anpilot.client.features.module.misc.ANMiddleClick
import anpilot.client.features.module.misc.ANNotifier
import anpilot.client.features.module.misc.ANPlanMove
import anpilot.client.features.module.movement.ANAntiKnockBack
import anpilot.client.features.module.movement.ANAntiWeb
import anpilot.client.features.module.movement.ANGuiMove
import anpilot.client.features.module.movement.ANNoSlow
import anpilot.client.features.module.movement.ANPacketFly
import anpilot.client.features.module.movement.ANAutoFollow
import anpilot.client.features.module.movement.ANPhase
import anpilot.client.features.module.movement.ANParkour
import anpilot.client.features.module.movement.ANBoost
import anpilot.client.features.module.movement.ANElytraBoost
import anpilot.client.features.module.player.ANDeathGhost
import anpilot.client.features.module.player.ANAirPlace
import anpilot.client.features.module.player.ANAutoArmour
import anpilot.client.features.module.player.ANAutoEat
import anpilot.client.features.module.player.ANAutoEnchant
import anpilot.client.features.module.player.ANAutoElytra
import anpilot.client.features.module.player.ANElytraReplace
import anpilot.client.features.module.player.ANAutoPlaceSlabs
import anpilot.client.features.module.player.ANAutoTool
import anpilot.client.features.module.combat.ANAutoXP
import anpilot.client.features.module.player.ANFastUse
import anpilot.client.features.module.player.ANHotbarFill
import anpilot.client.features.module.player.ANPacketMine
import anpilot.client.features.module.player.ANScaffoldPlus
import anpilot.client.features.module.player.ANAutoSign
import anpilot.client.features.module.player.ANAutoDrop
import anpilot.client.features.module.player.ANLootStealer
import anpilot.client.features.module.player.ANTunnelMiner
import anpilot.client.features.module.player.ANNuker
import anpilot.client.features.module.misc.ANFastBuild
import anpilot.client.features.module.misc.ANAutoBuild
import anpilot.client.features.module.misc.oreminer.ANOreMiner
import anpilot.client.features.module.render.ANBlockESP
import anpilot.client.features.module.render.ANChams
import anpilot.client.features.module.render.ANDropsESP
import anpilot.client.features.module.render.ANEsp
import anpilot.client.features.module.render.ANFreecam
import anpilot.client.features.module.render.ANFreeLook
import anpilot.client.features.module.render.ANFullbright
import anpilot.client.features.module.render.ANModels
import anpilot.client.features.module.render.ANNameTags
import anpilot.client.features.module.render.ANNoBobView
import anpilot.client.features.module.render.ANNoRender
import anpilot.client.features.module.render.ANWeather
import anpilot.client.features.module.render.ANStorageESP
import anpilot.client.features.module.render.ANTunnelESP
import anpilot.client.features.module.render.ANViewModel
import anpilot.client.features.module.render.ANXRay
import anpilot.client.features.module.render.ANLogOutPoints
import anpilot.client.features.module.render.ANDisplayTools
import anpilot.client.features.module.render.ANPopChams
import anpilot.client.features.module.render.ANSlimeChunks
import anpilot.client.features.module.movement.ANSafeWalk
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.Bind
import net.minecraft.client.Minecraft
import anpilot.client.compat.LevelRenderContext

class ANModuleManager : ANModuleRegistry {
    fun register() {
    }


    private val modules = listOf(

        ANAutoCrystal(),
        ANAutoMine(),
        ANAnchorAura(),
        ANAutoBed(),
        ANBowAim(),
        ANAutoTotem(),
        ANFeetPlace(),
        ANPopCount(),
        ANKillAura(),
        ANKnockback(),

        ANFullbright(),
        ANChams(),
        ANDropsESP(),
        ANEsp(),
        ANBlockESP(),
        ANTunnelESP(),
        ANNameTags(),
        ANStorageESP(),
        ANNoRender(),
        ANWeather(),
        ANNoBobView(),
        ANViewModel(),
        ANModels(),
        ANFreecam(),
        ANFreeLook(),
        ANXRay(),
        ANLogOutPoints(),
        ANDisplayTools(),
        ANPopChams(),
        ANSlimeChunks(),

        ANAntiWeb(),
        ANGuiMove(),
        ANAntiKnockBack(),
        ANNoSlow(),
        ANPacketFly(),
        ANBoost(),
        ANElytraBoost(),
        ANAutoFollow(),
        ANPhase(),
        ANParkour(),
        ANSafeWalk(),
        

        ANDeathGhost(),

        ANAutoEat(),
        ANAutoEnchant(),
        ANHotbarFill(),
        ANAutoTool(),
        ANAutoElytra(),
        ANElytraReplace(),
        ANAutoArmour(),
        ANFastUse(),
        ANAirPlace(),
        ANAutoPlaceSlabs(),
        ANAutoXP(),
        ANPacketMine(),
        ANScaffoldPlus(),
        ANAutoSign(),
        ANAutoDrop(),
        ANLootStealer(),
        ANFastBuild(),
        ANAutoBuild(),
        ANTunnelMiner(),
        ANNuker(),

        ANAntiAFK(),
        ANAntiCrash(),
        ANBotTask(),
        ANPlanMove(),
        ANAutoEnchantBook(),
        ANAutoFarm(),
        ANAutoFish(),
        ANNotifier(),
        ANChatUtils(),
        ANBaseFinder(),
        ANFriendAdd(),
        ANMiddleClick(),
        ANFakePlayer(),
        ANAutoLog(),
        ANLeaveInfo(),
        ANMapArt(),
        ANAutoFeed(),
        ANFlyTo(),
        ANElytraPilotPlus(),
        ANOreMiner(),

        ANWaterMark(),
        ANCoordinate(),
        ANDropsList(),
        ANFPS(),
        ANTargetInfo(),
        ANPing(),
        ANPotions(),
        ANPlayerArmor(),
        ANInventory(),
        ANHudGuiEditor(),
        ANModuleList(),
        ANHotBar(),

        ANPilotClient(),
        ANPilotFriend(),
        ANPilotConfig(),
        ANPilotTheme(),
        ANPilotGuiEditor(),
        ANPilotHud()
    )

    override fun categories(): List<ANModuleCategory> = listOf(
        ANModuleCategory.COMBAT,
        ANModuleCategory.RENDER,
        ANModuleCategory.MOVEMENT,
        ANModuleCategory.PLAYER,
        ANModuleCategory.MISC,
        ANModuleCategory.HUD,
        ANModuleCategory.CLIENT
    )

    override fun modules(category: ANModuleCategory): List<ANModule> = modules.filter { it.category == category }

    override fun allModules(): List<ANModule> = modules

    fun tick() {
        modules.filter { it.enabled }.forEach { it.onTick() }
    }

    fun unload() {
        modules.forEach { it.onUnload() }
    }

    fun renderWorld(context: LevelRenderContext) {
        modules.filter { it.enabled }
            .filterIsInstance<ANWorldRenderModule>()
            .forEach { it.renderWorld(context) }
    }

    fun renderHud(context: ANGuiRenderContext, editor: Boolean = false) {
        hudModules(editor).forEach { it.renderHud(context, editor) }
    }

    fun hudModules(editor: Boolean = false): List<ANDraggableHudModule> {
        return modules.filterIsInstance<ANDraggableHudModule>()
            .filter { editor || it.enabled }
    }

    fun topHudModuleAt(mouseX: Double, mouseY: Double): ANDraggableHudModule? {
        return hudModules(editor = true).asReversed().firstOrNull { it.contains(mouseX, mouseY) }
    }

    fun onKeyPressed(key: Int) {
        if (key == -1 || key == 0 || Minecraft.getInstance().screen != null) return
        notifyBindSettings(key, mouse = false)
        modules.filterIsInstance<ANBaseModule>()
            .filter { module -> module.getBind().key == key && module.getBind().mouse.not() }
            .forEach { it.toggle() }
    }

    fun onKeyReleased(key: Int) {
        if (key == -1 || key == 0 || Minecraft.getInstance().screen != null) return
        modules.filterIsInstance<ANBaseModule>()
            .filter { module -> module.getBind().key == key && module.getBind().hold && module.getBind().mouse.not() }
            .forEach { it.disable() }
    }

    fun onMousePressed(button: Int) {
        if (button == -1 || Minecraft.getInstance().screen != null) return
        notifyBindSettings(button, mouse = true)
        modules.filterIsInstance<ANBaseModule>()
            .filter { it.enabled }
            .forEach { it.onMousePressed(button) }
        modules.filterIsInstance<ANBaseModule>()
            .filter { module -> module.getBind().key == button && module.getBind().mouse }
            .forEach { it.toggle() }
    }

    fun onMouseReleased(button: Int) {
        if (button == -1 || Minecraft.getInstance().screen != null) return
        modules.filterIsInstance<ANBaseModule>()
            .filter { module -> module.getBind().key == button && module.getBind().hold && module.getBind().mouse }
            .forEach { it.disable() }
    }

    fun get(name: String): ANModule? = modules.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun notifyBindSettings(key: Int, mouse: Boolean) {
        modules.filterIsInstance<ANBaseModule>()
            .filter { it.enabled }
            .forEach { module ->
                module.getSettings()
                    .filter { it.isBindSetting() }
                    .mapNotNull { it.asBindSetting() }
                    .filter { setting -> setting.value.key == key && setting.value.mouse == mouse }
                    .forEach { setting -> module.onBindPressed(setting, key, mouse) }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ANSetting<*>.asBindSetting(): ANSetting<Bind>? = this as? ANSetting<Bind>

    fun chams(): ANChams? = get("Chams") as? ANChams

    fun dropsESP(): ANDropsESP? = get("DropsESP") as? ANDropsESP

    fun noRender(): ANNoRender? = get("NoRender") as? ANNoRender

    fun noBobView(): ANNoBobView? = get("NoBobView") as? ANNoBobView

    fun viewModel(): ANViewModel? = get("ViewModel") as? ANViewModel

    fun models(): ANModels? = get("Models") as? ANModels

    fun fastUse(): ANFastUse? = get("FastUse") as? ANFastUse

    fun airPlace(): ANAirPlace? = get("AirPlace") as? ANAirPlace
}
