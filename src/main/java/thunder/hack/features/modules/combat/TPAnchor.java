package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderEyeItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ExperienceBottleItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PotionItem;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.TridentItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import thunder.hack.core.Managers;
import thunder.hack.core.manager.player.CombatManager;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.events.impl.EventSync;
import thunder.hack.events.impl.EventTick;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.SearchInvResult;

import static thunder.hack.features.modules.client.ClientSettings.isRu;

/**
 * TPAnchor: teleports (position-packet only, client position untouched) next
 * to the target, places a respawn anchor, charges it with glowstone, returns,
 * then teleports back to detonate it and returns again. Repeats until the
 * target dies or Cycles is reached. Actions are spread across ticks with
 * configurable delays ("staged packets") so server-side movement validation
 * does not reject a burst of packets.
 */
public class TPAnchor extends Module {
    public TPAnchor() {
        super("TPAnchor", Category.COMBAT);
    }

    // ---------- settings ----------
    private final Setting<Float> targetRange = new Setting<>("TargetRange", 10f, 1f, 200f);
    private final Setting<CombatManager.TargetBy> targetLogic = new Setting<>("TargetLogic", CombatManager.TargetBy.Distance);
    private final Setting<PlaceMode> placeMode = new Setting<>("PlaceMode", PlaceMode.Smart);
    private final Setting<Integer> cycles = new Setting<>("Cycles", 0, 0, 10);
    private final Setting<Boolean> pauseAC = new Setting<>("PauseAC", true);
    private final Setting<Boolean> autoDisable = new Setting<>("AutoDisable", true);

    // staged packet delays (ticks)
    private final Setting<Integer> tpDelay = new Setting<>("TPDelay", 2, 0, 10);
    private final Setting<Integer> actionDelay = new Setting<>("ActionDelay", 2, 0, 10);
    private final Setting<Integer> returnDelay = new Setting<>("ReturnDelay", 2, 0, 10);

    // ---------- state ----------
    private enum State {
        IDLE,       // find target + attack position, record startPos
        TP_GO,      // send position packet to attack position
        PLACE,      // switch to anchor + place it
        CHARGE,     // switch to glowstone + right-click anchor (charge)
        TP_BACK_1,  // return packet to startPos
        TP_GO_2,    // go back to the anchor to detonate
        EXPLODE,    // hold a non-block item + right-click anchor (boom)
        TP_BACK_2   // return packet to startPos
    }

    private State state = State.IDLE;
    private final Timer stageTimer = new Timer();
    private PlayerEntity target;
    private Vec3d startPos;      // real client position when the cycle started
    private Vec3d tpPos;         // where we teleport to (next to the anchor)
    private BlockPos anchorPos;  // where the anchor was placed
    private BlockHitResult anchorBhr; // interact result for the anchor
    private int cycleCount;
    private boolean suppressMove;     // true while spoofed position must survive (TP -> interact window)
    private boolean warnedNoTarget;   // one-shot chat spam guard
    private boolean warnedNoSpot;

    public enum PlaceMode {
        Feet, Nearby, Smart
    }

    @Override
    public void onEnable() {
        if (fullNullCheck()) {
            disable();
            return;
        }
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    public void onLogin() {
        reset();
    }

    private void reset() {
        state = State.IDLE;
        target = null;
        startPos = null;
        tpPos = null;
        anchorPos = null;
        anchorBhr = null;
        cycleCount = 0;
        suppressMove = false;
        warnedNoTarget = false;
        warnedNoSpot = false;
        stageTimer.reset();
    }

    private void next(State next) {
        state = next;
        stageTimer.reset();
    }

    // Wait helper: has the staged delay elapsed since we entered this state?
    private boolean stageElapsed(int delayTicks) {
        return stageTimer.passedMs(delayTicks * 50L);
    }

    // Suppress the client's own movement packets while our spoofed position
    // must stay alive on the server (between TP and the interact).
    @EventHandler
    public void onSync(EventSync event) {
        if (suppressMove)
            event.cancel();
    }

    // While spoofed: if the server answers with a position-look correction,
    // swallow it (vanilla would setPosition - the real body would fly away)
    // and confirm the teleport ourselves so the server keeps us at the
    // spoofed spot.
    @EventHandler
    public void onPacketReceive(PacketEvent.Receive event) {
        if (!suppressMove) return;
        if (event.getPacket() instanceof PlayerPositionLookS2CPacket pac) {
            event.cancel();
            sendPacket(new TeleportConfirmC2SPacket(pac.getTeleportId()));
        }
    }

    @EventHandler
    public void onTick(EventTick e) {
        if (fullNullCheck()) return;

        switch (state) {
            case IDLE -> idle();
            case TP_GO -> {
                if (!stageElapsed(tpDelay.getValue())) return;
                sendTP(tpPos);
                suppressMove = true; // keep the spoofed position alive until the interact lands
                next(State.PLACE);
            }
            case PLACE -> {
                if (!stageElapsed(actionDelay.getValue())) return;
                if (!placeAnchor()) {
                    fail("cannot place anchor");
                    return;
                }
                next(State.CHARGE);
            }
            case CHARGE -> {
                if (!stageElapsed(actionDelay.getValue())) return;
                if (!chargeAnchor()) {
                    fail("cannot charge anchor");
                    return;
                }
                next(State.TP_BACK_1);
            }
            case TP_BACK_1 -> {
                if (!stageElapsed(returnDelay.getValue())) return;
                sendTP(startPos);
                suppressMove = false; // real position restored, resume normal movement
                next(State.TP_GO_2);
            }
            case TP_GO_2 -> {
                if (!stageElapsed(tpDelay.getValue())) return;
                sendTP(tpPos);
                suppressMove = true;
                next(State.EXPLODE);
            }
            case EXPLODE -> {
                if (!stageElapsed(actionDelay.getValue())) return;
                if (!explodeAnchor()) {
                    fail("cannot detonate anchor");
                    return;
                }
                next(State.TP_BACK_2);
            }
            case TP_BACK_2 -> {
                if (!stageElapsed(returnDelay.getValue())) return;
                sendTP(startPos);
                suppressMove = false;
                cycleCount++;

                // target dead / lost / cycles reached?
                if (cycles.getValue() > 0 && cycleCount >= cycles.getValue()) {
                    fail(isRu() ? "Достигнут лимит циклов" : "Cycle limit reached");
                    return;
                }
                if (target == null || target.isDead() || target.getHealth() <= 0) {
                    // target died - find next one
                    state = State.IDLE;
                    stageTimer.reset();
                    return;
                }
                // repeat: place a new anchor
                state = State.IDLE;
                stageTimer.reset();
            }
        }
    }
    // ---------- IDLE: acquire target + compute the attack plan ----------

    private void idle() {
        // keep the real position fresh while idle
        startPos = mc.player.getPos();

        // inventory checks
        if (!InventoryUtility.findItemInHotBar(Items.RESPAWN_ANCHOR).found()) {
            fail(isRu() ? "Нет якоря возрождения" : "No respawn anchor");
            return;
        }
        if (!InventoryUtility.findItemInHotBar(Items.GLOWSTONE).found()) {
            fail(isRu() ? "Нет светокамня" : "No glowstone");
            return;
        }

        target = Managers.COMBAT.getTarget(targetRange.getValue(), targetLogic.getValue());
        if (target == null) {
            if (autoDisable.getValue() && !warnedNoTarget) {
                warnedNoTarget = true;
                fail(isRu() ? "Цель не найдена" : "No target");
            }
            return;
        }
        warnedNoTarget = false;

        // find anchor placement spot
        BlockPos anchorBlock = findAnchorSpot();
        if (anchorBlock == null) {
            if (!warnedNoSpot) {
                warnedNoSpot = true;
                fail(isRu() ? "Невозможно разместить якорь" : "Cannot place anchor near target");
            }
            return;
        }
        warnedNoSpot = false;
        anchorPos = anchorBlock;

        // stand position: one block BESIDE the anchor (not inside it - the
        // blast would hit us at point-blank otherwise).
        BlockPos stand = findStandPos(anchorBlock);
        if (stand == null) stand = anchorBlock; // last resort: overlap
        tpPos = new Vec3d(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);

        if (pauseAC.getValue()) {
            ModuleManager.autoCrystal.pause();
            ModuleManager.aura.pause();
        }

        next(State.TP_GO);
    }

    /**
     * Finds where to put the anchor:
     * - Feet: block under the target (face-place)
     * - Nearby: free neighbouring air block
     * - Smart: Feet first, fallback Nearby
     */
    private BlockPos findAnchorSpot() {
        BlockPos feet = BlockPos.ofFloored(target.getPos()).down();

        if (placeMode.getValue() != PlaceMode.Nearby && canPlaceAt(feet)) {
            return feet;
        }
        if (placeMode.getValue() == PlaceMode.Feet) {
            return null;
        }

        // nearby: scan around the target for a placeable spot
        BlockPos base = BlockPos.ofFloored(target.getPos());
        for (int y = 0; y <= 1; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && y == 0) continue;
                    BlockPos pos = base.add(dx, y, dz);
                    if (canPlaceAt(pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean canPlaceAt(BlockPos pos) {
        if (mc.world == null) return false;
        if (!mc.world.getBlockState(pos).isAir()) return false;
        // TH's own placement check: a valid support face exists
        return InteractionUtility.getPlaceResult(pos, InteractionUtility.Interact.Vanilla, false) != null;
    }

    /**
     * A free, breathable block next to the anchor to "stand" in while
     * interacting, preferring horizontal neighbours so the explosion is not
     * point-blank.
     */
    private BlockPos findStandPos(BlockPos anchor) {
        if (mc.world == null) return null;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos pos = anchor.offset(dir);
            if (mc.world.getBlockState(pos).isAir() && mc.world.getBlockState(pos.up()).isAir())
                return pos;
        }
        // fallback: on top
        BlockPos up = anchor.up();
        if (mc.world.getBlockState(up).isAir() && mc.world.getBlockState(up.up()).isAir())
            return up;
        return null;
    }

    // ---------- staged actions ----------

    private void sendTP(Vec3d pos) {
        sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, true));
    }

    /**
     * Places the anchor at anchorPos (selected by idle()) while "standing" at tpPos.
     */
    private boolean placeAnchor() {
        if (mc.world == null || mc.player == null) return false;

        // recompute a click face for the anchor position
        // TH's own placement helper: blockPos = support block, side = face into
        // the anchor position. This is the convention the server expects.
        BlockHitResult bhr = InteractionUtility.getPlaceResult(anchorPos, InteractionUtility.Interact.Vanilla, false);
        if (bhr == null) return false;

        SearchInvResult anchor = InventoryUtility.findItemInHotBar(Items.RESPAWN_ANCHOR);
        if (!anchor.found()) return false;

        int prevSlot = mc.player.getInventory().selectedSlot;

        // silent switch
        anchor.switchToSilent();

        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, id));
        sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        // restore
        InventoryUtility.switchTo(prevSlot);

        // For charge/detonate we click the anchor itself: blockPos = anchorPos,
        // side = the face we placed against (facing the support block).
        anchorBhr = new BlockHitResult(Vec3d.ofCenter(anchorPos), bhr.getSide().getOpposite(), anchorPos, false);
        return true;
    }

    /**
     * Right-clicks the anchor with glowstone in hand to charge it.
     */
    private boolean chargeAnchor() {
        if (mc.world == null || mc.player == null || anchorBhr == null) return false;

        // server may need a fresh hit result; reuse the placement one
        BlockHitResult bhr = anchorBhr;

        SearchInvResult glow = InventoryUtility.findItemInHotBar(Items.GLOWSTONE);
        if (!glow.found()) return false;

        int prevSlot = mc.player.getInventory().selectedSlot;

        glow.switchToSilent();

        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, id));
        sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        InventoryUtility.switchTo(prevSlot);
        return true;
    }

    /**
     * Right-clicks the charged anchor to detonate it. Prefers an EMPTY hand:
     * if the block-interact fails server-side, vanilla falls back to "use the
     * held item" - with e.g. a pearl selected that THROWS the pearl. An empty
     * hand has no use action, so the worst case is a no-op.
     */
    private boolean explodeAnchor() {
        if (mc.world == null || mc.player == null || anchorBhr == null) return false;

        // empty slot first, then a non-block item that has no instant "use"
        // action (no throwables / potions / xp bottles / eyes / bows)
        SearchInvResult hand = InventoryUtility.findInHotBar(ItemStack::isEmpty);
        if (!hand.found()) {
            hand = InventoryUtility.findInHotBar(stack -> !(stack.getItem() instanceof BlockItem)
                    && !(stack.getItem() instanceof EnderPearlItem)
                    && !(stack.getItem() instanceof SnowballItem)
                    && !(stack.getItem() instanceof EggItem)
                    && !(stack.getItem() instanceof ExperienceBottleItem)
                    && !(stack.getItem() instanceof EnderEyeItem)
                    && !(stack.getItem() instanceof PotionItem)
                    && !(stack.getItem() instanceof BowItem)
                    && !(stack.getItem() instanceof CrossbowItem)
                    && !(stack.getItem() instanceof TridentItem)
                    && !(stack.getItem() instanceof FishingRodItem)
                    && !(stack.getItem() instanceof BucketItem)
                    && !(stack.getItem() instanceof FireworkRocketItem));
        }
        if (!hand.found()) return false;

        int prevSlot = mc.player.getInventory().selectedSlot;

        hand.switchToSilent();

        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, anchorBhr, id));
        sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        InventoryUtility.switchTo(prevSlot);
        return true;
    }

    private void fail(String reason) {
        if (autoDisable.getValue()) {
            disable(reason);
        } else {
            sendMessage(reason);
            reset();
        }
    }
}
