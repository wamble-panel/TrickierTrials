package de.t14d3.trickiertrials.chamber;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Vault;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;

/**
 * Lets players reopen trial vaults after a cooldown. Uses the Paper Vault API, so no server internals are needed.
 * Reward timestamps are stored in the chunk's persistent data container.
 */
public final class VaultListener implements Listener {

    private final TrickierTrials plugin;

    public VaultListener(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(Block block, UUID player) {
        return new NamespacedKey(plugin, ("vault/" + block.getX() + "_" + block.getY() + "_" + block.getZ() + "/" + player).toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVaultDispense(BlockDispenseLootEvent event) {
        if (event.getPlayer() == null || event.getBlock().getType() != Material.VAULT) return;
        Block block = event.getBlock();
        block.getChunk().getPersistentDataContainer().set(key(block, event.getPlayer().getUniqueId()), PersistentDataType.LONG, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVaultInteract(PlayerInteractEvent event) {
        long cooldown = plugin.settings().vaultCooldownMillis;
        if (!plugin.settings().vaultResetEnabled || cooldown < 0) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.VAULT) return;
        if (!(block.getState() instanceof Vault vault)) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!vault.hasRewardedPlayer(uuid)) return;

        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = key(block, uuid);
        Long rewardedAt = data.get(key, PersistentDataType.LONG);
        long now = System.currentTimeMillis();
        if (rewardedAt == null) {
            // Rewarded before this plugin tracked it: start the timer now.
            data.set(key, PersistentDataType.LONG, now);
            rewardedAt = now;
        }

        long remaining = rewardedAt + cooldown - now;
        if (remaining > 0) {
            player.sendActionBar(Text.msg("vault-cooldown", Text.ph("time", Text.duration(remaining))));
            return;
        }

        vault.removeRewardedPlayer(uuid);
        vault.update(true, false);
        data.remove(key);
        Text.send(player, "vault-refreshed");
        Fx.sound(player, Sound.BLOCK_VAULT_ACTIVATE, 1f, 1.2f);
        block.getWorld().spawnParticle(Particle.VAULT_CONNECTION, block.getLocation().add(0.5, 1.2, 0.5), 20, 0.4, 0.4, 0.4, 0.05);
    }
}
