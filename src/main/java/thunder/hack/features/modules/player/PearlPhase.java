package thunder.hack.features.modules.player;

import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;

/**
 * PearlPhase: throws an ender pearl at a position inside the block you are
 * standing in (clamped to the 0.241/0.759 edge offsets) so the pearl
 * teleports you slightly into the wall - a pearl phase.
 * Ported from Alien's PearlPhase (simplified: no rotation-manager
 * dependency; angles are baked into the interact packet).
 */
public class PearlPhase extends Module {
    public PearlPhase() {
        super("PearlPhase", Category.PLAYER);
    }

    private final Setting<Boolean> autoYaw = new Setting<>("AutoYaw", true);
    private final Setting<Boolean> bypass = new Setting<>("Bypass", true);
    private final Setting<Boolean> pauseAC = new Setting<>("PauseAC", true);
    private final Setting<Boolean> onlyOnGround = new Setting<>("OnlyOnGround", false);
    // Pitch used for the throw. Bypass on -> Pitch value (default 89, the classic
    // aggressive extreme); Bypass off -> clamps to a safer 80 max.
    private final Setting<Float> pitch = new Setting<>("Pitch", 89f, 75f, 90f, v -> bypass.getValue());
    private final Setting<Float> cooldown = new Setting<>("Cooldown", 2f, 0.5f, 10f);

    private Vec3d targetPos;
    private final Timer cooldownTimer = new Timer();

    @Override
    public void onEnable() {
        if (fullNullCheck()) {
            disable();
            return;
        }

        if (!cooldownTimer.passedS(cooldown.getValue())) {
            disable("cooldown");
            return;
        }

        if (onlyOnGround.getValue() && !mc.player.isOnGround()) {
            disable("not on ground");
            return;
        }

        updatePos();

        int epSlot = findEPSlot();
        if (epSlot == -1) {
            disable("no pearls");
            return;
        }

        if (pauseAC.getValue()) {
            ModuleManager.autoCrystal.pause();
            ModuleManager.aura.pause();
        }

        // Bake the aim into the interact packet: yaw toward the clamp target
        // (AutoYaw) or the player's current yaw; pitch is configurable
        // (Bypass off caps it at the safer 80).
        float yaw = autoYaw.getValue() ? InteractionUtility.calculateAngle(targetPos)[0] : mc.player.getYaw();
        // Bypass on: configurable Pitch (75-90). Bypass off: fixed safer 80 (Alien semantics).
        float pitch = bypass.getValue() ? this.pitch.getValue() : 80f;

        int prevSlot = mc.player.getInventory().selectedSlot;

        if (epSlot != prevSlot) {
            mc.player.getInventory().selectedSlot = epSlot;
            sendPacket(new UpdateSelectedSlotC2SPacket(epSlot));
        }

        sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, yaw, pitch));
        sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        if (epSlot != prevSlot) {
            mc.player.getInventory().selectedSlot = prevSlot;
            sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        }

        cooldownTimer.reset();
        disable();
    }

    @Override
    public void onDisable() {
        targetPos = null;
    }

    /**
     * Computes the pearl target: the player's position snapped toward the
     * nearest 0.241/0.759 edge on each axis (clamped to +-0.2 blocks) and
     * half a block down. Throwing a pearl there wedges the player into the
     * block corner on pearl teleport.
     */
    private void updatePos() {
        double px = mc.player.getX();
        double pz = mc.player.getZ();

        double targetX = px + MathHelper.clamp(roundToClosest(px, Math.floor(px) + 0.241, Math.floor(px) + 0.759) - px, -0.2, 0.2);
        double targetZ = pz + MathHelper.clamp(roundToClosest(pz, Math.floor(pz) + 0.241, Math.floor(pz) + 0.759) - pz, -0.2, 0.2);

        targetPos = new Vec3d(targetX, mc.player.getY() - 0.5, targetZ);
    }

    private static double roundToClosest(double num, double low, double high) {
        double d1 = num - low;
        double d2 = high - num;
        return d2 > d1 ? low : high;
    }

    private int findEPSlot() {
        if (mc.player.getMainHandStack().getItem() == Items.ENDER_PEARL)
            return mc.player.getInventory().selectedSlot;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL)
                return i;
        }
        return -1;
    }
}
