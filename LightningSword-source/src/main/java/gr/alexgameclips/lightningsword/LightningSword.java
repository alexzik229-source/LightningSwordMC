package gr.alexgameclips.lightningsword;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LightningSword extends JavaPlugin implements Listener {

    private NamespacedKey swordKey;
    private NamespacedKey usesKey;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordKey = new NamespacedKey(this, "lightning_sword");
        usesKey = new NamespacedKey(this, "uses_left");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------------------------------------------------------------- item

    private ItemStack createSword() {
        int maxUses = getConfig().getInt("max-uses", 10);
        ItemStack item = new ItemStack(Material.GOLDEN_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Σπαθί του Κεραυνού", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, maxUses);
        meta.lore(buildLore(maxUses, maxUses));
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> buildLore(int uses, int maxUses) {
        int cd = getConfig().getInt("cooldown-seconds", 30);
        return List.of(
                line("Καλεί κεραυνό στον παίκτη που χτυπάς", NamedTextColor.GRAY),
                line("Cooldown: " + cd + "δ", NamedTextColor.YELLOW),
                line("Χρήσεις: " + uses + "/" + maxUses, NamedTextColor.YELLOW)
        );
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isSword(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    private Component msg(String path, String... replacements) {
        String s = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            s = s.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(msg("no-player"));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(msg("usage"));
            return true;
        }
        target.getInventory().addItem(createSword()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        target.sendMessage(msg("given"));
        return true;
    }

    // ------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (!isSword(hand)) return;

        // The sword NEVER does its normal attack damage (this also covers sweep attacks).
        e.setCancelled(true);
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity victim) || victim == attacker) return;
        if (getConfig().getBoolean("players-only", true) && !(victim instanceof Player)) return;

        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(attacker.getUniqueId());
        if (until != null && until > now) {
            long secs = (until - now + 999) / 1000;
            attacker.sendActionBar(msg("cooldown", "%seconds%", String.valueOf(secs)));
            return;
        }

        // Optional tiny melee damage, hard-capped at half a heart (1.0).
        double melee = Math.min(1.0, Math.max(0.0, getConfig().getDouble("melee-damage", 0.0)));
        if (melee > 0) {
            victim.damage(melee, attacker);
        }

        // Visual + sound only: no fire, no damage to anyone nearby.
        victim.getWorld().strikeLightningEffect(victim.getLocation());

        // Real lightning damage, only to the hit entity.
        double dmg = Math.max(0.0, getConfig().getDouble("lightning-damage", 5.0));
        victim.setNoDamageTicks(0);
        victim.damage(dmg, DamageSource.builder(DamageType.LIGHTNING_BOLT).build());

        cooldownUntil.put(attacker.getUniqueId(),
                now + getConfig().getLong("cooldown-seconds", 30) * 1000L);
        consumeUse(attacker, hand);
    }

    private void consumeUse(Player player, ItemStack hand) {
        int maxUses = getConfig().getInt("max-uses", 10);
        ItemMeta meta = hand.getItemMeta();
        int left = meta.getPersistentDataContainer()
                .getOrDefault(usesKey, PersistentDataType.INTEGER, maxUses) - 1;

        if (left <= 0) {
            player.getInventory().setItemInMainHand(null);
            player.sendMessage(msg("broken"));
            return;
        }
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, left);
        meta.lore(buildLore(left, maxUses));
        hand.setItemMeta(meta);
        player.getInventory().setItemInMainHand(hand);
        player.sendMessage(msg("uses-left", "%uses%", String.valueOf(left)));
    }

    /** The sword can never lose durability. */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (isSword(e.getItem())) e.setCancelled(true);
    }
}
