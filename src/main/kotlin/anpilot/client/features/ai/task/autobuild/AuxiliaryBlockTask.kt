package anpilot.client.features.ai.task.autobuild

import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.module.misc.ANAutoBuild
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

class AuxiliaryBlockTask(agent: ANAgent, val targetPos: BlockPos) : AITask(agent) {
    var auxPos: BlockPos? = null
        private set
    private var state = 0
    private var isBreaking = false

    override fun start() {
        val level = ANAgent.minecraft.level ?: return
        val module = agent.module as? ANAutoBuild
        val searchOrder = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN)
        for (dir in searchOrder) {
            val candidate = targetPos.relative(dir)
            if (module?.placeBlocks?.any { it.pos == candidate } == true) continue

            val candidateState = level.getBlockState(candidate)
            if (candidateState.isAir || candidateState.canBeReplaced()) {
                var hasSolidNeighbor = false
                for (ndir in Direction.values()) {
                    val npos = candidate.relative(ndir)
                    if (npos == targetPos) continue
                    val nstate = level.getBlockState(npos)
                    if (nstate.canAnchorAuxiliary()) {
                        hasSolidNeighbor = true
                        break
                    }
                }
                if (hasSolidNeighbor) {
                    auxPos = candidate
                    break
                }
            }
        }
        if (auxPos == null) {
            AgentUtils.sendMessage("无法找到辅助方块放置位置！")
            finished = true
        }
    }

    override fun tick() {
        val aux = auxPos ?: return
        val player = player ?: return
        val level = ANAgent.minecraft.level ?: return

        when (state) {
            0 -> {
                val actual = level.getBlockState(aux)
                if (!actual.isAir && !actual.canBeReplaced()) {
                    state = 1
                    return
                }

                val slot = findAuxBlockSlot(player)
                if (slot == -1) {
                    AgentUtils.sendMessage("没有可用作辅助方块的物品（请在快捷栏放一些泥土/圆石）")
                    finished = true
                    return
                }

                if (player.distanceToSqr(aux.x + 0.5, aux.y + 0.5, aux.z + 0.5) > 9.0) {
                    if (!BaritoneHelper.isPathing()) {
                        BaritoneHelper.pathNear(aux, 2)
                    }
                } else {
                    BaritoneHelper.cancel()
                    val oldSlot = player.inventory.selected
                    player.inventory.selected = slot
                    placeBlockManually(aux, player)
                    player.inventory.selected = oldSlot
                }
            }
            1 -> {
                val actualTarget = level.getBlockState(targetPos)
                if (!actualTarget.isAir && !actualTarget.canBeReplaced()) {
                    state = 2
                } else {
                    val module = agent.module as? ANAutoBuild
                    val targetPlaceBlock = module?.placeBlocks?.find { it.pos == targetPos }
                    if (module != null && targetPlaceBlock != null) {
                        val stack = ItemStack(targetPlaceBlock.state.block.asItem())
                        val plan = module.placer.findPlacementPlan(targetPlaceBlock, stack, ignoreFeetCheck = true)
                        if (plan != null) {
                            module.placer.placeBlock(targetPlaceBlock, plan, ignoreFeetCheck = true)
                        }
                    }

                    if (player.distanceToSqr(targetPos.x + 0.5, targetPos.y + 0.5, targetPos.z + 0.5) > 9.0) {
                        if (!BaritoneHelper.isPathing()) {
                            BaritoneHelper.pathNear(targetPos, 2)
                        }
                    } else {
                        BaritoneHelper.cancel()
                    }
                }
            }
            2 -> {
                val module = agent.module as? ANAutoBuild
                if (module?.placeBlocks?.any { it.pos == aux } == true) {
                    finished = true
                    return
                }

                val actual = level.getBlockState(aux)
                if (actual.isAir || actual.canBeReplaced()) {
                    finished = true
                    return
                }

                if (player.distanceToSqr(aux.x + 0.5, aux.y + 0.5, aux.z + 0.5) > 9.0) {
                    if (!BaritoneHelper.isPathing()) {
                        BaritoneHelper.pathNear(aux, 2)
                    }
                } else {
                    BaritoneHelper.cancel()

                    val center = Vec3.atCenterOf(aux)
                    val dx = center.x - player.x
                    val dy = center.y - player.eyeY
                    val dz = center.z - player.z
                    val yaw = (Math.toDegrees(Math.atan2(dz, dx)) - 90.0).toFloat()
                    val pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))).toFloat()
                    
                    val rotation = Rotation(yaw, pitch)
                    ANServiceRegistry.runtime.rotationManager.setSilentRotation(rotation)

                    val gameMode = ANAgent.minecraft.gameMode
                    if (gameMode != null) {
                        if (!isBreaking) {
                            gameMode.startDestroyBlock(aux, Direction.UP)
                            isBreaking = true
                        }
                        gameMode.continueDestroyBlock(aux, Direction.UP)
                        player.swing(InteractionHand.MAIN_HAND)
                    }
                }
            }
        }
    }

    private fun placeBlockManually(pos: BlockPos, player: LocalPlayer) {
        val level = ANAgent.minecraft.level ?: return
        for (dir in Direction.values()) {
            val npos = pos.relative(dir)
            val nstate = level.getBlockState(npos)
            if (!nstate.isAir && !nstate.canBeReplaced()) {
                val hit = BlockHitResult(
                    Vec3.atCenterOf(pos).add(dir.stepX * 0.5, dir.stepY * 0.5, dir.stepZ * 0.5),
                    dir.opposite,
                    npos,
                    false
                )
                
                val dx = hit.location.x - player.x
                val dy = hit.location.y - player.eyeY
                val dz = hit.location.z - player.z
                val yaw = (Math.toDegrees(Math.atan2(dz, dx)) - 90.0).toFloat()
                val pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))).toFloat()

                val rotation = Rotation(yaw, pitch)
                ANServiceRegistry.runtime.rotationManager.setSilentRotation(rotation)

                ANAgent.minecraft.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                player.swing(InteractionHand.MAIN_HAND)
                break
            }
        }
    }

    private fun findAuxBlockSlot(player: LocalPlayer): Int {
        val auxItems = listOf(Items.DIRT, Items.COBBLESTONE, Items.STONE)
        for (i in 0 until 9) {
            val item = player.inventory.getItem(i).item
            if (item in auxItems) return i
        }
        for (i in 0 until 9) {
            val item = player.inventory.getItem(i).item
            if (item is BlockItem) return i
        }
        return -1
    }

    private fun BlockState.canAnchorAuxiliary(): Boolean {
        return !isAir && !canBeReplaced()
    }

    override fun stop() {
        BaritoneHelper.cancel()
    }
}
