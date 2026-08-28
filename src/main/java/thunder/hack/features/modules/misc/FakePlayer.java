package thunder.hack.features.modules.misc;

import com.mojang.authlib.GameProfile;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import thunder.hack.ThunderHack;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.events.impl.EventSync;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.events.impl.TotemPopEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.world.ExplosionUtility;
import thunder.hack.utility.player.InventoryUtility;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FakePlayer extends Module {
    private final Setting<Boolean> copyInventory = new Setting<>("CopyInventory", false);

    public static OtherClientPlayerEntity fakePlayer;

    public FakePlayer() {
        super("FakePlayer", Category.MISC);
    }

    private final Setting<Boolean> record = new Setting<>("Record", false);
    private final Setting<Boolean> play = new Setting<>("Play", false);
    private final Setting<Boolean> autoTotem = new Setting<>("AutoTotem", false);
    private final Setting<String> name = new Setting<>("Name", "Hell_Raider");
    private final Setting<Boolean> regen = new Setting<>("Regeneration", false);
    private final Setting<Boolean> absorption = new Setting<>("Absorption", false);
    private final Setting<Boolean> resistance = new Setting<>("Resistance", false);
    private final Setting<Boolean> respawn = new Setting<>("Respawn", false);

    private final List<PlayerState> positions = new ArrayList<>();
    private final Timer corpseTimer = new Timer();
    private final Timer respawnTimer = new Timer();
    private boolean pendingRespawn;

    int movementTick;

    @Override
    public void onEnable() {
        spawn();
    }

    @Override
    public void onLogin() {
        // world/client reloaded - the old entity is gone. Spawn later from
        // onSync: onLogin can fire before the new world is actually live
        // (fresh join skips PacketEvent.Receive entirely when mc.player==null).
        fakePlayer = null;
        positions.clear();
        movementTick = 0;
        if (isEnabled()) pendingRespawn = true;
    }

    private void spawn() {
        if (fullNullCheck()) {
            disable();
            return;
        }
        fakePlayer = new OtherClientPlayerEntity(mc.world, new GameProfile(UUID.fromString("66123666-6666-6666-6666-666666666600"), name.getValue()));
        fakePlayer.copyPositionAndRotation(mc.player);

        if (copyInventory.getValue()) {
            fakePlayer.setStackInHand(Hand.MAIN_HAND, mc.player.getMainHandStack().copy());
            fakePlayer.setStackInHand(Hand.OFF_HAND, mc.player.getOffHandStack().copy());

            fakePlayer.getInventory().setStack(36, mc.player.getInventory().getStack(36).copy());
            fakePlayer.getInventory().setStack(37, mc.player.getInventory().getStack(37).copy());
            fakePlayer.getInventory().setStack(38, mc.player.getInventory().getStack(38).copy());
            fakePlayer.getInventory().setStack(39, mc.player.getInventory().getStack(39).copy());
        }

        mc.world.addEntity(fakePlayer);

        // optional buffs, all OFF by default - the dummy is a bare training target
        if (regen.getValue())
            fakePlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 9999, 2));
        if (absorption.getValue())
            fakePlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 9999, 4));
        if (resistance.getValue())
            fakePlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 9999, 1));
    }

    /**
     * Standard damage resolution: absorption shield absorbs damage and
     * depletes first, then health drops.
     */
    private void applyDamage(float amount) {
        if (fakePlayer == null || mc.world == null || amount <= 0f) return;

        float absorptionHearts = fakePlayer.getAbsorptionAmount();
        if (absorptionHearts > 0f) {
            float absorbed = Math.min(absorptionHearts, amount);
            fakePlayer.setAbsorptionAmount(absorptionHearts - absorbed);
            amount -= absorbed;
        }
        if (amount > 0f)
            fakePlayer.setHealth(fakePlayer.getHealth() - amount);

        fakePlayer.onDamaged(mc.world.getDamageSources().generic());
    }

    @EventHandler
    public void onPacketReceive(PacketEvent.Receive e) {
        if (e.getPacket() instanceof ExplosionS2CPacket explosion && fakePlayer != null && fakePlayer.hurtTime == 0) {
            applyDamage(ExplosionUtility.getAutoCrystalDamage(new Vec3d(explosion.getX(), explosion.getY(), explosion.getZ()), fakePlayer, 0, false));
            if (fakePlayer.isDead() && fakePlayer.tryUseTotem(mc.world.getDamageSources().generic())) {
                fakePlayer.setHealth(10f);
                ThunderHack.EVENT_BUS.post(new TotemPopEvent(fakePlayer, 1));
            }
            // credit the kill to the user even for explosion kills so
            // KillEffect's SelfKill filter accepts it (no EventAttack fires
            // for crystal damage)
            ThunderHack.EVENT_BUS.post(new EventAttack(fakePlayer, false));
        }
    }

    @EventHandler
    public void onSync(EventSync e) {
        if (record.getValue()) {
            positions.add(new PlayerState(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYaw(), mc.player.getPitch()));
            return;
        }
        if (fakePlayer == null) return;

        if (play.getValue() && !positions.isEmpty()) {
            movementTick++;

            if (movementTick >= positions.size()) {
                movementTick = 0;
                return;
            }
            PlayerState p = positions.get(movementTick);
            fakePlayer.setYaw(p.yaw);
            fakePlayer.setPitch(p.pitch);
            fakePlayer.setHeadYaw(p.yaw);

            fakePlayer.updateTrackedPosition(p.x, p.y, p.z);
            fakePlayer.updateTrackedPositionAndAngles(p.x, p.y, p.z, p.yaw, p.pitch, 3);
        } else movementTick = 0;

        if (autoTotem.getValue() && fakePlayer.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING)
            fakePlayer.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));

        if (fakePlayer.isDead()) {
            if (respawn.getValue()) {
                // respawn: hold the corpse ~2s so kill effects/sounds can fire, then revive
                if (respawnTimer.passedMs(2000L)) {
                    fakePlayer.setHealth(fakePlayer.getMaxHealth());
                    fakePlayer.setAbsorptionAmount(0f);
                    if (absorption.getValue())
                        fakePlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 9999, 4));
                    fakePlayer.hurtTime = 0;
                    fakePlayer.deathTime = 0;
                }
            } else {
                // keep the corpse ~5s (KillEffect polls for isAlive()==false), then remove
                if (corpseTimer.passedMs(5000L)) disable();
            }
        } else {
            corpseTimer.reset();
            respawnTimer.reset();
            if (pendingRespawn) {
                pendingRespawn = false;
                spawn();
            }
        }
    }

    @EventHandler
    public void onAttack(EventAttack e) {
        if (fakePlayer == null || e.getEntity() != fakePlayer || e.isPre()) return;
        if (fakePlayer.hurtTime != 0) return;

        mc.world.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(), SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, 1f);

        if (mc.player.fallDistance > 0 || ModuleManager.criticals.isEnabled())
            mc.world.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 1f);

        float damage = ModuleManager.aura.getAttackCooldown() >= 0.85f
                ? InventoryUtility.getHitDamage(mc.player.getMainHandStack(), fakePlayer)
                : 1f;

        // optional resistance buff reduces damage (vanilla: -20% per level)
        if (resistance.getValue() && fakePlayer.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int level = fakePlayer.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            damage *= Math.max(0f, 1f - 0.2f * level);
        }

        applyDamage(damage);

        if (fakePlayer.isDead() && fakePlayer.tryUseTotem(mc.world.getDamageSources().generic())) {
            fakePlayer.setHealth(10f);
            new EntityStatusS2CPacket(fakePlayer, EntityStatuses.USE_TOTEM_OF_UNDYING).apply(mc.player.networkHandler);
        }
    }

    @Override
    public void onDisable() {
        if (fakePlayer == null) return;
        fakePlayer.kill();
        fakePlayer.setRemoved(Entity.RemovalReason.KILLED);
        fakePlayer.onRemoved();
        fakePlayer = null;
        positions.clear();
        movementTick = 0;
    }

    private record PlayerState(double x, double y, double z, float yaw, float pitch) {
    }
}
