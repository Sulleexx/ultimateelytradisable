package org.sulyax.ultimateElytraDisable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UltimateElytraDisable extends JavaPlugin implements Listener, TabCompleter {

    private boolean enabled;
    private boolean disableInListed;
    private Set<String> targetWorlds;
    private String bypassPermission;
    private String adminPermission;

    private boolean useActionbar;
    private Component defaultActionbar;
    private Map<String, Component> perWorldActionbars;
    private boolean preventReEnable;
    private long messageCooldown;
    private boolean logAttempts;

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Set<UUID> temporaryBypass = ConcurrentHashMap.newKeySet();

    private static final String BYPASS_META = "ECBypass";
    private static final String WORLD_PLACEHOLDER = "{world}";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ultimateelytradisable")).setExecutor(this);
        Objects.requireNonNull(getCommand("ultimateelytradisable")).setTabCompleter(this);
        getLogger().info("UltimateElytraDisable enabled. Mode: " + (disableInListed ? "BLACKLIST" : "WHITELIST"));
        getLogger().info("Target worlds: " + String.join(", ", targetWorlds));
    }

    private void reloadSettings() {
        reloadConfig();

        enabled = getConfig().getBoolean("enabled", true);
        disableInListed = getConfig().getBoolean("disable-in-listed", true);

        List<String> worlds = getConfig().getStringList("target-worlds");
        targetWorlds = new HashSet<>(worlds.size());
        for (String world : worlds) {
            targetWorlds.add(world.toLowerCase());
        }

        bypassPermission = getConfig().getString("bypass-permission", "ued.bypass");
        adminPermission = getConfig().getString("admin-permission", "ued.admin");

        useActionbar = getConfig().getBoolean("use-actionbar", true);
        preventReEnable = getConfig().getBoolean("prevent-reenable", true);
        messageCooldown = getConfig().getLong("message-cooldown-ms", 3000);
        logAttempts = getConfig().getBoolean("log-attempts", false);

        String defaultMsg = getConfig().getString("actionbar-message", "&c✈ Flight is disabled in this world!");
        defaultActionbar = LegacyComponentSerializer.legacyAmpersand().deserialize(defaultMsg);

        perWorldActionbars = new HashMap<>();
        if (getConfig().isConfigurationSection("per-world-messages")) {
            for (String key : getConfig().getConfigurationSection("per-world-messages").getKeys(false)) {
                String val = getConfig().getString("per-world-messages." + key, defaultMsg);
                perWorldActionbars.put(key.toLowerCase(), LegacyComponentSerializer.legacyAmpersand().deserialize(val));
            }
        }
    }

    @Override
    public void onDisable() {
        getConfig().set("enabled", enabled);
        getConfig().set("disable-in-listed", disableInListed);
        getConfig().set("target-worlds", new ArrayList<>(targetWorlds));
        saveConfig();

        temporaryBypass.clear();
        lastMessageTime.clear();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList(
                    "addworld", "removeworld", "listworlds", "purge",
                    "reload", "enable", "disable", "status", "bypass", "help"
            );

            String input = args[0].toLowerCase();
            for (String subCommand : subCommands) {
                if (subCommand.startsWith(input)) {
                    if (needsAdminPermission(subCommand) && sender instanceof Player) {
                        if (sender.hasPermission(adminPermission)) {
                            completions.add(subCommand);
                        }
                    } else {
                        completions.add(subCommand);
                    }
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            String input = args[1].toLowerCase();

            switch (subCommand) {
                case "addworld":
                case "removeworld":
                    if (sender instanceof Player || sender.hasPermission(adminPermission)) {
                        for (World world : Bukkit.getWorlds()) {
                            String worldName = world.getName();
                            if (worldName.toLowerCase().startsWith(input)) {
                                completions.add(worldName);
                            }
                        }
                    }
                    break;

                case "bypass":
                    if (sender instanceof Player || sender.hasPermission(adminPermission)) {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            String playerName = player.getName();
                            if (playerName.toLowerCase().startsWith(input)) {
                                completions.add(playerName);
                            }
                        }
                    }
                    break;
            }
        } else if (args.length == 3) {
            String subCommand = args[0].toLowerCase();
            String input = args[2].toLowerCase();

            if ("bypass".equals(subCommand)) {
                if (sender instanceof Player || sender.hasPermission(adminPermission)) {
                    List<String> states = Arrays.asList("on", "off");
                    for (String state : states) {
                        if (state.startsWith(input)) {
                            completions.add(state);
                        }
                    }
                }
            }
        }

        return completions;
    }

    private boolean needsAdminPermission(String subCommand) {
        return !subCommand.equals("help") && !subCommand.equals("status");
    }

    @EventHandler
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (!enabled || !event.isGliding() || !(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        String worldName = player.getWorld().getName();

        if (canBypass(player) || !isGlideDisabledInWorld(worldName)) return;

        event.setCancelled(true);

        if (logAttempts) {
            getLogger().info(player.getName() + " Tried to fly in the world: " + worldName);
        }

        if (preventReEnable) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isGliding()) {
                    player.setGliding(false);
                    sendRestrictionMessage(player, worldName);
                }
            }, 1L);
        }

        sendRestrictionMessage(player, worldName);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkAndDisableGlide(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        checkAndDisableGlide(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        checkAndDisableGlide(event.getPlayer());
    }

    private void checkAndDisableGlide(Player player) {
        if (!enabled || canBypass(player) || !player.isGliding()) return;

        String worldName = player.getWorld().getName();
        if (isGlideDisabledInWorld(worldName)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isGliding()) {
                    player.setGliding(false);
                    sendRestrictionMessage(player, worldName);
                }
            }, 1L);
        }
    }

    private boolean canBypass(Player player) {
        return player.hasPermission(bypassPermission) ||
                temporaryBypass.contains(player.getUniqueId()) ||
                player.hasMetadata(BYPASS_META);
    }

    private boolean isGlideDisabledInWorld(String worldName) {
        boolean contains = targetWorlds.contains(worldName.toLowerCase());
        return disableInListed ? contains : !contains;
    }

    private void sendRestrictionMessage(Player player, String worldName) {
        long currentTime = System.currentTimeMillis();
        long lastTime = lastMessageTime.getOrDefault(player.getUniqueId(), 0L);

        if (currentTime - lastTime < messageCooldown) return;

        lastMessageTime.put(player.getUniqueId(), currentTime);
        Component message = getFormattedMessage(worldName);

        if (useActionbar) {
            player.sendActionBar(message);
        } else {
            player.sendMessage(message);
        }
    }

    private Component getFormattedMessage(String worldName) {
        Component message = perWorldActionbars.getOrDefault(
                worldName.toLowerCase(),
                perWorldActionbars.getOrDefault("default", defaultActionbar)
        );

        return message.replaceText(
                TextReplacementConfig.builder()
                        .matchLiteral(WORLD_PLACEHOLDER)
                        .replacement(worldName)
                        .build()
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAdminPermission(sender)) return true;
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "addworld":
                return handleAddWorld(sender, args);
            case "removeworld":
                return handleRemoveWorld(sender, args);
            case "listworlds":
                return handleListWorlds(sender);
            case "purge":
                return handlePurge(sender);
            case "reload":
                return handleReload(sender);
            case "enable":
                return handleToggle(sender, true);
            case "disable":
                return handleToggle(sender, false);
            case "status":
                return handleStatus(sender);
            case "bypass":
                return handleBypass(sender, args);
            case "help":
            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        Component help = Component.text()
                .append(Component.text("\n§6§lUltimateElytraDisable §7v" + getDescription().getVersion()))
                .append(Component.text("\n§e/ued addworld <world> §7- Add a world"))
                .append(Component.text("\n§e/ued removeworld <world> §7- Remove a world"))
                .append(Component.text("\n§e/ued listworlds §7- List worlds"))
                .append(Component.text("\n§e/ued purge §7- Force-stop flight for all players"))
                .append(Component.text("\n§e/ued reload §7- Reload config"))
                .append(Component.text("\n§e/ued enable|disable §7- Enable/disable plugin"))
                .append(Component.text("\n§e/ued status §7- Plugin status"))
                .append(Component.text("\n§e/ued bypass <player> [on|off] §7- Temporary flight access for a player"))
                .append(Component.text("\n§e/ued help §7- List commands"))
                .build();

        sender.sendMessage(help);
    }

    private boolean hasAdminPermission(CommandSender sender) {
        if (sender instanceof Player && !sender.hasPermission(adminPermission)) {
            sender.sendMessage(Component.text("§cInsufficient permissions! Required: " + adminPermission));
            return false;
        }
        return true;
    }

    private boolean handleAddWorld(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage(Component.text("§cSpecify a world: /ued addworld <world>"));
            return true;
        }

        String worldName = args[1].toLowerCase();
        if (targetWorlds.add(worldName)) {
            updateConfigWorlds();
            sender.sendMessage(Component.text("§aWorld added: " + worldName));
        } else {
            sender.sendMessage(Component.text("§eWorld have already added: " + worldName));
        }
        return true;
    }

    private boolean handleRemoveWorld(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage(Component.text("§cSpecify a world: /ued removeworld <world>"));
            return true;
        }

        String worldName = args[1].toLowerCase();
        if (targetWorlds.remove(worldName)) {
            updateConfigWorlds();
            sender.sendMessage(Component.text("§aWorld deleted: " + worldName));
        } else {
            sender.sendMessage(Component.text("§cWorld not find: " + worldName));
        }
        return true;
    }

    private boolean handleListWorlds(CommandSender sender) {
        if (!hasAdminPermission(sender)) return true;

        Component message = Component.text()
                .append(Component.text("\n§6Mode: " + (disableInListed ? "§cBlackList" : "§aWhiteList")))
                .append(Component.text("\n§6Worlds (" + targetWorlds.size() + "):"))
                .build();

        sender.sendMessage(message);

        if (targetWorlds.isEmpty()) {
            sender.sendMessage(Component.text("§7- No worlds in list"));
        } else {
            for (String world : targetWorlds) {
                sender.sendMessage(Component.text("§7- " + world));
            }
        }
        return true;
    }

    private boolean handlePurge(CommandSender sender) {
        if (!hasAdminPermission(sender)) return true;

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding()) continue;
            if (canBypass(player)) continue;

            String worldName = player.getWorld().getName();
            if (isGlideDisabledInWorld(worldName)) {
                player.setGliding(false);
                sendRestrictionMessage(player, worldName);
                count++;
            }
        }
        sender.sendMessage(Component.text("§aPurged flights: " + count));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasAdminPermission(sender)) return true;

        reloadSettings();
        sender.sendMessage(Component.text("§aConfig reloaded!"));
        sender.sendMessage(Component.text("§7- Mode: " + (disableInListed ? "BlackList" : "WhiteList")));
        sender.sendMessage(Component.text("§7- Worlds: " + targetWorlds.size()));
        return true;
    }

    private boolean handleToggle(CommandSender sender, boolean enable) {
        if (!hasAdminPermission(sender)) return true;

        enabled = enable;
        getConfig().set("enabled", enabled);
        saveConfig();

        if (enable) {
            sender.sendMessage(Component.text("§aPlugin enabled!"));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isGliding()) {
                    checkAndDisableGlide(player);
                }
            }
        } else {
            sender.sendMessage(Component.text("§cPlugin disabled!"));
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        Component status = Component.text()
                .append(Component.text("\n§6Status UltimateElytraDisable(ued)"))
                .append(Component.text("\n§7State: " + (enabled ? "§aEnabled" : "§cDisabled")))
                .append(Component.text("\n§7Mode: " + (disableInListed ? "§cBlackList" : "§aWhiteList")))
                .append(Component.text("\n§7Worlds in list: §e" + targetWorlds.size()))
                .append(Component.text("\n§7Number of temporary bypasses: §e" + temporaryBypass.size()))
                .append(Component.text("\n§7Bypass-permission: §e" + bypassPermission))
                .build();

        sender.sendMessage(status);
        return true;
    }

    private boolean handleBypass(CommandSender sender, String[] args) {
        if (!hasAdminPermission(sender)) return true;

        if (args.length < 2) {
            sender.sendMessage(Component.text("§cUsage: /ued bypass <player> [on|off]"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("§cPlayer not found or offline!"));
            return true;
        }

        UUID targetId = target.getUniqueId();
        boolean state;

        if (args.length > 2) {
            state = args[2].equalsIgnoreCase("on");
        } else {
            state = !temporaryBypass.contains(targetId);
        }

        if (state) {
            temporaryBypass.add(targetId);
            target.setMetadata(BYPASS_META, new FixedMetadataValue(this, true));
            sender.sendMessage(Component.text("§aBypass issued to player: " + target.getName()));
            target.sendMessage(Component.text("§aYou have received temporary access to elytra!"));
        } else {
            temporaryBypass.remove(targetId);
            target.removeMetadata(BYPASS_META, this);
            sender.sendMessage(Component.text("§cBypass revoked from player: " + target.getName()));
            target.sendMessage(Component.text("§cYour temporary access to elytra has been revoked!"));
        }

        return true;
    }

    private void updateConfigWorlds() {
        getConfig().set("target-worlds", new ArrayList<>(targetWorlds));
        saveConfig();
        reloadSettings();
    }
}