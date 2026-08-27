package thunder.hack.features.modules.render;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventAttack;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.render.Render3DEngine;

import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class KillEffect extends Module {
    public KillEffect() {
        super("KillEffect", Category.RENDER);
    }

    private final Setting<Mode> mode = new Setting<>("Mode", Mode.Orthodox);
    private final Setting<Integer> speed = new Setting<>("Y Speed", 0, -10, 10, value -> mode.getValue() == Mode.Orthodox);
    public final Setting<Integer> volume = new Setting<>("Volume", 100, 0, 100);
    private final Setting<Boolean> playSound = new Setting<>("Play Sound", true, value -> mode.getValue() == Mode.Orthodox);
    private final Setting<ColorSetting> color = new Setting<>("Color", new ColorSetting(new Color(255, 255, 0, 150)), value -> mode.getValue() == Mode.Orthodox);
    private final Setting<Boolean> mobs = new Setting<>("Mobs", false, value -> mode.getValue() != Mode.Marker);

    // Marker: camera-facing billboard (circle + arrow) at the death position.
    // Ported from AstraPlus "HereHasALowiqDie", renamed per request.
    private final Setting<Float> duration = new Setting<>("Duration", 3f, 1f, 10f, value -> mode.getValue() == Mode.Marker);
    private final Setting<Float> scale = new Setting<>("Scale", 1f, 0.1f, 3f, value -> mode.getValue() == Mode.Marker);
    private final Setting<Boolean> flash = new Setting<>("Flash", true, value -> mode.getValue() == Mode.Marker);
    private final Setting<Boolean> streakSound = new Setting<>("StreakSound", true, value -> mode.getValue() == Mode.Marker);
    private final Setting<Float> streakReset = new Setting<>("StreakReset", 5f, 1f, 30f, value -> mode.getValue() == Mode.Marker && streakSound.getValue());
    private final Setting<Boolean> onlySelf = new Setting<>("SelfKill", false, value -> mode.getValue() == Mode.Marker);
    private final Setting<SoundRange> soundRange = new Setting<>("SoundRange", SoundRange.All, value -> mode.getValue() == Mode.Marker && streakSound.getValue());
    private final Setting<Float> soundDistance = new Setting<>("SoundDistance", 16f, 10f, 100f, value -> mode.getValue() == Mode.Marker && streakSound.getValue() && soundRange.getValue() == SoundRange.Distance);

    private final Map<Entity, Long> renderEntities = new ConcurrentHashMap<>();
    private final Map<Entity, Long> lightingEntities = new ConcurrentHashMap<>();
    private final List<DeathEffect> deathEffects = new CopyOnWriteArrayList<>();
    private final Map<Entity, Long> recentlyAttacked = new ConcurrentHashMap<>();
    private int killStreak = 0;
    private long lastKillTime = 0L;

    private static final Identifier YUANQUAN = Identifier.of("thunderhack", "textures/killeffect/yuanquan.png");
    private static final Identifier JIANTOU = Identifier.of("thunderhack", "textures/killeffect/jiantou.png");

    private enum Mode {
        Orthodox,
        FallingLava,
        LightningBolt,
        Marker
    }

    public enum SoundRange {
        All,
        Distance
    }

    @Override
    public void onRender3D(MatrixStack stack) {
        if (mc.world == null) return;

        switch (mode.getValue()) {
            case Orthodox -> renderEntities.forEach((entity, time) -> {
                if (System.currentTimeMillis() - time > 3000) {
                    renderEntities.remove(entity);
                } else {
                    Render3DEngine.drawLine(entity.getPos().add(0, calculateSpeed(), 0), entity.getPos().add(0, 3 + calculateSpeed(), 0), color.getValue().getColorObject());
                    Render3DEngine.drawLine(entity.getPos().add(1, 2.3 + calculateSpeed(), 0), entity.getPos().add(-1, 2.3 + calculateSpeed(), 0), color.getValue().getColorObject());
                    Render3DEngine.drawLine(entity.getPos().add(0.5, 1.2 + calculateSpeed(), 0), entity.getPos().add(-0.5, 0.8 + calculateSpeed(), 0), color.getValue().getColorObject());
                }
            });
            case FallingLava -> renderEntities.keySet().forEach(entity -> {
                for (int i = 0; i < entity.getHeight() * 10; i++) {
                    for (int j = 0; j < entity.getWidth() * 10; j++) {
                        for (int k = 0; k < entity.getWidth() * 10; k++) {
                            mc.world.addParticle(ParticleTypes.FALLING_LAVA, entity.getX() + j * 0.1, entity.getY() + i * 0.1, entity.getZ() + k * 0.1, 0, 0, 0);
                        }
                    }
                }

                renderEntities.remove(entity);
            });
            case LightningBolt -> renderEntities.forEach((entity, time) -> {
                LightningEntity lightningEntity = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
                lightningEntity.refreshPositionAfterTeleport(entity.getX(), entity.getY(), entity.getZ());
                mc.world.addEntity(lightningEntity);
                renderEntities.remove(entity);
                lightingEntities.put(entity, System.currentTimeMillis());
            });
            case Marker -> {
                long now = System.currentTimeMillis();
                deathEffects.removeIf(e -> now - e.timestamp > duration.getValue() * 1000f);
                for (DeathEffect effect : deathEffects) {
                    if (!flash.getValue() || now / 200L % 2L == 0L) {
                        drawMarker(stack, effect.x, effect.y, effect.z, 1f);
                    }
                }
            }
        }
    }

    @Override
    public void onUpdate() {
        Managers.ASYNC.getAsyncEntities().forEach(entity -> {
            if (!(entity instanceof PlayerEntity) && !mobs.getValue()) return;
            if (!(entity instanceof LivingEntity liv)) return;

            if (entity == mc.player || renderEntities.containsKey(entity) || lightingEntities.containsKey(entity))
                return;
            if (entity.isAlive() || liv.getHealth() != 0) return;

            if (playSound.getValue() && mode.getValue() == Mode.Orthodox)
                mc.world.playSound(mc.player, entity.getBlockPos(), Managers.SOUND.ORTHODOX_SOUNDEVENT, SoundCategory.BLOCKS, volume.getValue() / 100f, 1f);

            if (mode.getValue() == Mode.Marker) {
                if (!(entity instanceof PlayerEntity)) return; // mobs drift after death and bypass the 0.5-block coord dedup, causing repeated pattern/audio
                if (onlySelf.getValue() && !recentlyAttacked.containsKey(entity)) return; // not our kill
                double ex = entity.getX();
                double ey = entity.getY() + 1.0;
                double ez = entity.getZ();
                boolean exists = false;
                for (DeathEffect e : deathEffects) {
                    if (Math.abs(e.x - ex) < 0.5 && Math.abs(e.y - ey) < 0.5 && Math.abs(e.z - ez) < 0.5) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    if (streakSound.getValue()) {
                        long now = System.currentTimeMillis();
                        if (now - lastKillTime > streakReset.getValue() * 1000f) killStreak = 0;
                        killStreak++;
                        lastKillTime = now;
                        // MC audible distance ~ 16 * volume blocks: All = whole map (AstraPlus original), Distance = custom slider
                        float soundVolume = switch (soundRange.getValue()) {
                            case All -> 100f;
                            case Distance -> soundDistance.getValue() / 16f;
                        };
                        mc.world.playSound(mc.player, entity.getBlockPos(), Managers.SOUND.STALKER_SOUNDEVENT, SoundCategory.BLOCKS, soundVolume, 1f);
                    }
                    deathEffects.add(new DeathEffect(ex, ey, ez, System.currentTimeMillis()));
                }
            } else {
                renderEntities.put(entity, System.currentTimeMillis());
            }
        });

        if (!lightingEntities.isEmpty()) {
            lightingEntities.forEach((entity, time) -> {
                if (System.currentTimeMillis() - time > 5000) {
                    lightingEntities.remove(entity);
                }
            });
        }

        recentlyAttacked.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > 10_000L);
    }

    @EventHandler
    public void onAttack(EventAttack event) {
        if (event.getEntity() != null)
            recentlyAttacked.put(event.getEntity(), System.currentTimeMillis());
    }

    @Override
    public String getDisplayInfo() {
        if (mode.getValue() == Mode.Marker && streakSound.getValue() && killStreak > 0)
            return "Streak: " + killStreak;
        return null;
    }

    private double calculateSpeed() {
        return (double) speed.getValue() / 100;
    }

    // Camera-facing billboard drawn at world (x,y,z). The onRender3D matrix is
    // effectively world-aligned (ThunderHack cancels the camera rotation in
    // MixinGameRenderer so world-axis boxes render correctly), so we must
    // re-apply the camera rotation here to make the quad face the player.
    private void drawMarker(MatrixStack matrices, double x, double y, double z, float alpha) {
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        matrices.push();
        matrices.translate(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z);
        matrices.multiply(mc.gameRenderer.getCamera().getRotation());
        float s = 0.0245f * scale.getValue();
        matrices.scale(s, s, s);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        RenderSystem.setShaderTexture(0, YUANQUAN);
        drawTexturedQuad(matrices, -32f, -32f, 64f, 64f);

        RenderSystem.setShaderTexture(0, JIANTOU);
        drawTexturedQuad(matrices, -5f, -5f, 64f, 64f);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        matrices.pop();
    }

    private void drawTexturedQuad(MatrixStack matrices, float x, float y, float width, float height) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        buffer.vertex(matrix, x, y + height, 0f).texture(0f, 0f);
        buffer.vertex(matrix, x + width, y + height, 0f).texture(1f, 0f);
        buffer.vertex(matrix, x + width, y, 0f).texture(1f, 1f);
        buffer.vertex(matrix, x, y, 0f).texture(0f, 1f);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static class DeathEffect {
        final double x;
        final double y;
        final double z;
        final long timestamp;

        DeathEffect(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
        }
    }
}
