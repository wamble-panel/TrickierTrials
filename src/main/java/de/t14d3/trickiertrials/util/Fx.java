package de.t14d3.trickiertrials.util;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/** Sound and particle helpers. */
public final class Fx {

    private static boolean sounds = true;

    private Fx() {
    }

    public static void setSounds(boolean enabled) {
        sounds = enabled;
    }

    public static void sound(Player player, Sound sound, float volume, float pitch) {
        if (sounds) player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public static void sound(Collection<? extends Player> players, Sound sound, float volume, float pitch) {
        if (!sounds) return;
        for (Player player : players) player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public static void worldSound(Location location, Sound sound, float volume, float pitch) {
        if (sounds) location.getWorld().playSound(location, sound, volume, pitch);
    }

    public static void ring(Location center, Particle particle, double radius, int points) {
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            world.spawnParticle(particle, center.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius), 1, 0, 0, 0, 0);
        }
    }

    public static void dustRing(Location center, Color color, double radius, int points, float size) {
        World world = center.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            world.spawnParticle(Particle.DUST, center.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius), 1, 0, 0, 0, 0, dust);
        }
    }

    public static void firework(Location location, Color primary, Color fade) {
        location.getWorld().spawn(location, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(ThreadLocalRandom.current().nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                    .withColor(primary).withFade(fade).trail(true).flicker(true).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
            fw.setShotAtAngle(false);
            fw.getPersistentDataContainer().set(Keys.CELEBRATION, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public static Color color(String paletteName) {
        return Color.fromRGB(Text.color(paletteName).value());
    }
}
