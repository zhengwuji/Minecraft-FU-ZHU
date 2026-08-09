package anpilot.client.features.utility

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.Vec3
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvents

class DamageableFakePlayer(player: Player, name: String) : FakePlayerEntity(player, name) {

    init {
        absorptionAmount = player.absorptionAmount
    }

    override fun baseTick() {
        super.baseTick()
        
        for (effectInstance in activeEffects) {
            val effect = effectInstance.effect
            if (effect.isDurationEffectTick(effectInstance.duration, effectInstance.amplifier)) {
                try {
                    effect.applyEffectTick(this, effectInstance.amplifier)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun simulateAttackFrom(attacker: Player) {
        var f = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE).toFloat()
        val itemStack = attacker.mainHandItem
        val damageSource: DamageSource = attacker.damageSources().playerAttack(attacker)

        val sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack)
        f += 1.0f + 0.5f * sharpnessLevel

        attacker.getEffect(MobEffects.DAMAGE_BOOST)?.let {
            f += 3.0f * (it.amplifier + 1)
        }

        attacker.getEffect(MobEffects.WEAKNESS)?.let {
            f -= 4.0f * (it.amplifier + 1)
        }

        val h = attacker.getAttackStrengthScale(0.5f)
        f *= (0.2f + h * h * 0.8f)

        hurt(damageSource, f)
    }

    fun simulateExplosionFrom(explosionPos: Vec3) {
        val dmg = ExplosionUtils.getExplosionDamage(explosionPos, 6.0f, this)
        if (dmg > 0f) {
            val level = level() as? ClientLevel ?: return
            hurt(level.damageSources().explosion(null), dmg)
        }
    }

    override fun playHurtSound(source: DamageSource) {
        val soundEvent = when {
            source.`is`(DamageTypeTags.IS_DROWNING) -> SoundEvents.PLAYER_HURT_DROWN
            source.`is`(DamageTypeTags.IS_FIRE) -> SoundEvents.PLAYER_HURT_ON_FIRE
            else -> SoundEvents.PLAYER_HURT
        }
        level().playLocalSound(x, y, z, soundEvent, soundSource, 1.0f, 1.0f, false)
    }

    fun simulateGappleEat() {
        addEffect(MobEffectInstance(MobEffects.REGENERATION, 400, 1))
        addEffect(MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 0))
        addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0))
        addEffect(MobEffectInstance(MobEffects.ABSORPTION, 2400, 3))
        absorptionAmount = 16.0f
    }

    fun simulateTotemPop() {
        health = 1.0f
        removeAllEffects()

        addEffect(MobEffectInstance(MobEffects.REGENERATION, 900, 1))
        addEffect(MobEffectInstance(MobEffects.ABSORPTION, 100, 1))
        absorptionAmount = 8.0f

        val connection = Minecraft.getInstance().connection ?: return
        connection.handleEntityEvent(ClientboundEntityEventPacket(this, 35.toByte()))
    }

    override fun isInvulnerableTo(source: DamageSource): Boolean = false

    override fun setDeltaMovement(x: Double, y: Double, z: Double) {
    }
}
