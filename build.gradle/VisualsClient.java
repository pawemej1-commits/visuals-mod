package pl.visuals;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

public class VisualsClient implements ClientModInitializer {

    private static final int ZOOM_FOV = 30;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int KEY_BG = 0x88000000;
    private static final int KEY_BG_ON = 0xCCFFFFFF;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    // Stan
    private boolean fullbright = false;
    private boolean hudEnabled = true;
    private boolean particlesEnabled = true;
    private boolean zooming = false;
    private int previousFov = 70;

    // Prędkość i CPS
    private Vec3 lastPos = null;
    private double speed = 0.0;
    private final Deque<Long> clicks = new ArrayDeque<>();
    private boolean wasAttackDown = false;

    private KeyMapping fullbrightKey, zoomKey, hudKey, particlesKey;

    @Override
    public void onInitializeClient() {
        fullbrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visuals.fullbright", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visuals.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KeyMapping.Category.MISC));
        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visuals.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, KeyMapping.Category.MISC));
        particlesKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visuals.particles", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("visuals", "hud"),
                this::renderHud);
    }

    // ---------------------------------------------------------------- TICK

    private void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            zooming = false;
            lastPos = null;
            return;
        }

        while (fullbrightKey.consumeClick()) {
            fullbright = !fullbright;
            if (!fullbright) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
        while (hudKey.consumeClick()) hudEnabled = !hudEnabled;
        while (particlesKey.consumeClick()) particlesEnabled = !particlesEnabled;

        // Fullbright: lokalny Night Vision
        if (fullbright && !mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        }

        // Zoom
        if (zoomKey.isDown() && !zooming) {
            previousFov = mc.options.fov().get();
            mc.options.fov().set(ZOOM_FOV);
            zooming = true;
        } else if (!zoomKey.isDown() && zooming) {
            mc.options.fov().set(previousFov);
            zooming = false;
        }

        // Prędkość (m/s) - różnica pozycji * 20 ticków
        Vec3 pos = mc.player.position();
        if (lastPos != null) {
            double dx = pos.x - lastPos.x, dz = pos.z - lastPos.z;
            speed = Math.sqrt(dx * dx + dz * dz) * 20.0;
        }
        lastPos = pos;

        // CPS - zliczamy zbocze narastające klawisza ataku (nie zjadamy kliknięć)
        boolean attackDown = mc.options.keyAttack.isDown();
        long now = System.currentTimeMillis();
        if (attackDown && !wasAttackDown) clicks.addLast(now);
        wasAttackDown = attackDown;
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) clicks.pollFirst();

        if (particlesEnabled) spawnParticles(mc);
    }

    private void spawnParticles(Minecraft mc) {
        RandomSource r = mc.level.getRandom();
        Vec3 p = mc.player.position();

        // Opadające płatki/liście wokół gracza
        if (r.nextInt(2) == 0) {
            double x = p.x + (r.nextDouble() - 0.5) * 14.0;
            double y = p.y + 2.0 + r.nextDouble() * 5.0;
            double z = p.z + (r.nextDouble() - 0.5) * 14.0;
            mc.level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0.0, -0.02, 0.0);
        }

        // Smuga kurzu przy sprincie
        if (mc.player.isSprinting() && mc.player.onGround()) {
            for (int i = 0; i < 2; i++) {
                mc.level.addParticle(ParticleTypes.CLOUD,
                        p.x + (r.nextDouble() - 0.5) * 0.4, p.y + 0.1, p.z + (r.nextDouble() - 0.5) * 0.4,
                        0.0, 0.01, 0.0);
            }
        }
    }

    // ----------------------------------------------------------------- HUD

    private void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!hudEnabled || mc.player == null || mc.options.hideGui) return;

        int w = g.guiWidth();
        int h = g.guiHeight();

        // Lewy górny róg: koordynaty, nazwa, prędkość
        String coords = String.format("%.0f, %.0f, %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
        g.drawString(mc.font, coords, 6, 6, WHITE, true);
        g.drawString(mc.font, "Visuals", 6, 18, GRAY, true);
        g.drawString(mc.font, mc.player.getDirection().getName().toUpperCase(), 6, 30, GRAY, true);
        g.drawString(mc.font, String.format("%.1f m/s", speed), 6, 42, GRAY, true);

        // Środek góry: FPS
        String fps = "FPS: " + mc.getFps();
        g.drawString(mc.font, fps, (w - mc.font.width(fps)) / 2, 6, WHITE, true);

        // Prawy górny róg: godzina i ping
        String clock = LocalTime.now().format(CLOCK);
        g.drawString(mc.font, clock, w - 6 - mc.font.width(clock), 6, WHITE, true);
        String ping = getPing(mc) + " ms";
        g.drawString(mc.font, ping, w - 6 - mc.font.width(ping), 18, GRAY, true);

        // Lewy dolny róg: keystrokes + CPS
        int size = 16, gap = 2;
        int baseX = 6;
        int baseY = h - 6 - (size * 3 + gap * 3);
        g.drawString(mc.font, "CPS: " + clicks.size(), baseX, baseY - 12, WHITE, true);

        key(g, mc, "W", baseX + size + gap, baseY, size, size, mc.options.keyUp.isDown());
        key(g, mc, "A", baseX, baseY + size + gap, size, size, mc.options.keyLeft.isDown());
        key(g, mc, "S", baseX + size + gap, baseY + size + gap, size, size, mc.options.keyDown.isDown());
        key(g, mc, "D", baseX + 2 * (size + gap), baseY + size + gap, size, size, mc.options.keyRight.isDown());
        int row3 = baseY + 2 * (size + gap);
        int halfW = (size * 3 + gap * 2 - gap) / 2;
        key(g, mc, "LMB", baseX, row3, halfW, 12, mc.options.keyAttack.isDown());
        key(g, mc, "RMB", baseX + halfW + gap, row3, halfW, 12, mc.options.keyUse.isDown());
        key(g, mc, "SPACE", baseX, row3 + 12 + gap, halfW * 2 + gap, 10, mc.options.keyJump.isDown());
    }

    private void key(GuiGraphics g, Minecraft mc, String label, int x, int y, int w, int h, boolean down) {
        g.fill(x, y, x + w, y + h, down ? KEY_BG_ON : KEY_BG);
        int color = down ? 0xFF000000 : WHITE;
        g.drawString(mc.font, label, x + (w - mc.font.width(label)) / 2, y + (h - 8) / 2 + 1, color, false);
    }

    private int getPing(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }
}
