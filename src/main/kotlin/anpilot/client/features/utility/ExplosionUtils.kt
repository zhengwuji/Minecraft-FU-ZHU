package anpilot.client.features.utility

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.ceil
import kotlin.math.max

object ExplosionUtils {

    fun getExplosionDamage(
        pos: Vec3,
        power: Float,
        target: LivingEntity,
        ignoreTerrain: Boolean = false,
        ignoreBlocks: Set<BlockPos> = emptySet()
    ): Float {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return 0f
        val doublePower = power * 2f
        val distance = pos.distanceTo(target.position())
        if (distance > doublePower) return 0f

        val exposure = getExposure(pos, target, level, ignoreTerrain, ignoreBlocks)
        val impact = (1.0f - distance.toFloat() / doublePower) * exposure
        val rawDamage = ((impact * impact + impact) / 2.0f * 7.0f * doublePower + 1.0f)

        return getAppliedDamageToEntity(target, rawDamage)
    }

    fun damageToEntity(
        level: Level,
        target: LivingEntity,
        pos: Vec3,
        ignoreTerrain: Boolean = false,
        ignoreBlocks: Set<BlockPos> = emptySet()
    ): Float {
        return getExplosionDamage(pos, 5.0f, target, ignoreTerrain, ignoreBlocks)
    }

    fun damageToEntity(
        level: Level,
        target: LivingEntity,
        pos: Vec3,
        power: Float,
        ignoreTerrain: Boolean,
        ignoreBlocks: Set<BlockPos>
    ): Float {
        return getExplosionDamage(pos, power, target, ignoreTerrain, ignoreBlocks)
    }

    fun damageToEntity(
        level: Level,
        target: LivingEntity,
        extrapolatedPos: Vec3,
        extrapolatedBox: AABB,
        explosionPos: Vec3,
        power: Float = 5.0f,
        ignoreTerrain: Boolean = false,
        ignoreBlocks: Set<BlockPos> = emptySet()
    ): Float {
        return getExplosionDamage(explosionPos, power, target, ignoreTerrain, ignoreBlocks)
    }

    fun crystalDamageToEntity(
        level: Level,
        target: LivingEntity,
        pos: Vec3,
        ignoreTerrain: Boolean = false,
        ignoreBlocks: Set<BlockPos> = emptySet()
    ): Float {
        return getExplosionDamage(pos, 6.0f, target, ignoreTerrain, ignoreBlocks)
    }

    fun crystalDamageToEntity(
        level: Level,
        target: LivingEntity,
        pos: Vec3,
        power: Float,
        ignoreTerrain: Boolean,
        ignoreBlocks: Set<BlockPos>
    ): Float {
        return getExplosionDamage(pos, power, target, ignoreTerrain, ignoreBlocks)
    }

    fun crystalDamageToEntity(
        level: Level,
        target: LivingEntity,
        extrapolatedPos: Vec3,
        extrapolatedBox: AABB,
        explosionPos: Vec3,
        power: Float = 6.0f,
        ignoreTerrain: Boolean = false,
        ignoreBlocks: Set<BlockPos> = emptySet()
    ): Float {
        return getExplosionDamage(explosionPos, power, target, ignoreTerrain, ignoreBlocks)
    }

    fun getExposure(
        pos: Vec3,
        target: LivingEntity,
        level: Level,
        ignoreTerrain: Boolean,
        ignoreBlocks: Set<BlockPos>
    ): Float {
        val bounds = target.boundingBox
        val dx = 1.0 / ((bounds.maxX - bounds.minX) * 2.0 + 1.0)
        val dy = 1.0 / ((bounds.maxY - bounds.minY) * 2.0 + 1.0)
        val dz = 1.0 / ((bounds.maxZ - bounds.minZ) * 2.0 + 1.0)

        if (dx < 0.0 || dy < 0.0 || dz < 0.0) return 0f

        var rayCount = 0
        var hitCount = 0

        var x = 0.0
        while (x <= 1.0) {
            var y = 0.0
            while (y <= 1.0) {
                var z = 0.0
                while (z <= 1.0) {
                    val targetX = bounds.minX + (bounds.maxX - bounds.minX) * x
                    val targetY = bounds.minY + (bounds.maxY - bounds.minY) * y
                    val targetZ = bounds.minZ + (bounds.maxZ - bounds.minZ) * z
                    val rayTarget = Vec3(targetX, targetY, targetZ)

                    if (clipRay(level, pos, rayTarget, ignoreTerrain, ignoreBlocks) == null) {
                        hitCount++
                    }
                    rayCount++
                    z += dz
                }
                y += dy
            }
            x += dx
        }

        return if (rayCount == 0) 0f else hitCount.toFloat() / rayCount.toFloat()
    }

    private fun clipRay(
        level: Level,
        from: Vec3,
        to: Vec3,
        ignoreTerrain: Boolean,
        ignoreBlocks: Set<BlockPos>
    ): HitResult? {
        val result = level.clip(
            ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null as net.minecraft.world.entity.Entity?
            )
        )

        if (result.type == HitResult.Type.MISS) return null

        val blockPos = result.blockPos
        if (blockPos in ignoreBlocks) return null

        val blockState = level.getBlockState(blockPos)
        if (ignoreTerrain && blockState.block.explosionResistance < 600f) return null

        return result
    }

    fun getAppliedDamageToEntity(entity: LivingEntity, damage: Float): Float {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return max(0f, damage)
        val damageSource = level.damageSources().explosion(null)
        return max(0f, getReduction(entity, damageSource, damage))
    }

    private fun getReduction(entity: LivingEntity, damageSource: DamageSource, damageIn: Float): Float {
        var damage = damageIn

        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level != null && damageSource.scalesWithDifficulty()) {
            when (level.difficulty) {
                Difficulty.EASY -> damage = (damage / 2 + 1).coerceAtMost(damage)
                Difficulty.HARD -> damage *= 1.5f
                else -> {}
            }
        }

        val armorValue = entity.armorValue.toFloat()
        val armorToughness = getAttributeValue(entity, Attributes.ARMOR_TOUGHNESS).toFloat()
        damage = getDamageAfterArmor(damage, armorValue, armorToughness)

        damage = getResistanceReduction(entity, damage)
        damage = getProtectionReduction(entity, damage)

        return damage
    }

    private fun getDamageAfterArmor(damage: Float, armor: Float, toughness: Float): Float {
        val f = 2.0f + toughness / 4.0f
        val g = (armor - damage / f).coerceAtLeast(armor * 0.2f)
        val h = (g.coerceAtMost(20.0f)) / 25.0f
        return damage * (1.0f - h)
    }

    private fun getAttributeValue(
        entity: LivingEntity,
        attribute: Attribute
    ): Double {
        return try {
            entity.getAttributeValue(attribute)
        } catch (_: NullPointerException) {
            0.0
        }
    }

    private fun getResistanceReduction(entity: LivingEntity, damageIn: Float): Float {
        var damage = damageIn
        val resistance = entity.getEffect(MobEffects.DAMAGE_RESISTANCE)
        if (resistance != null) {
            val lvl = resistance.amplifier + 1
            damage *= (1.0f - lvl * 0.2f)
        }
        return max(damage, 0f)
    }

    private fun getProtectionReduction(entity: LivingEntity, damageIn: Float): Float {
        val protLevel = getProtectionAmount(entity)
        if (protLevel == 0) return damageIn
        val f = (protLevel * 0.04f).coerceAtMost(0.8f)
        return damageIn * (1.0f - f)
    }

    fun getArmorDurabilityDamage(armorStack: ItemStack, rawDamage: Float): Int {
        val armorValue = armorStack.damageValue
        val maxDurability = armorStack.maxDamage
        if (maxDurability <= 0) return 0
        
        return ceil(rawDamage / 4.0).toInt().coerceAtLeast(0)
    }

    private fun getProtectionAmount(entity: LivingEntity): Int {
        var total = 0
        val armorSlots = listOf(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)
        for (slot in armorSlots) {
            val stack = entity.getItemBySlot(slot)
            if (stack.isEmpty) continue
            val prot = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, stack)
            val blastProt = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLAST_PROTECTION, stack)
            total += prot + blastProt * 2
        }
        return total
    }
}
