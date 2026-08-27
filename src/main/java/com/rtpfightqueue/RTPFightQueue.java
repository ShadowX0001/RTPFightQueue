package com.rtpfightqueue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class RTPFightQueue extends JavaPlugin implements Listener, TabExecutor {

    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> inside = new HashSet<>();
    private final Map<UUID, Location> selections1 = new HashMap<>();
    private final Map<UUID, Location> selections2 = new HashMap<>();
    private BukkitTask countdown;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("rtpqueue")).setExecutor(this);
        Objects.requireNonNull(getCommand("rtpqueue")).setTabCompleter(this);

        Objects.requireNonNull(getCommand("rtpzone")).setExecutor(this);
        Objects.requireNonNull(getCommand("rtpzone")).setTabCompleter(this);

        getLogger().info("RTPFightQueue enabled.");
    }

    @Override
    public void onDisable() {
        if (countdown != null) {
            countdown.cancel();
        }

        queue.clear();
        inside.clear();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void send(Player player, String key) {
        player.sendMessage(color(getConfig().getString("messages." + key, "")));
    }

    private void sound(Player player, String key, float pitch) {
        try {
            player.playSound(
                    player.getLocation(),
                    Sound.valueOf(getConfig().getString("effects." + key)),
                    1f,
                    pitch
            );
        } catch (Exception ignored) {
        }
    }

    private void openGui(Player player) {

        String title = color(getConfig().getString("gui.title"));

        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                Component.text(ChatColor.stripColor(title))
        );

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);

        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        boolean joined = queue.contains(player.getUniqueId());

        ItemStack item = new ItemStack(
                joined ? Material.LIME_CONCRETE : Material.GRASS_BLOCK
        );

        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text(
                        color(getConfig().getString("gui.survival-name"))
                )
        );

        String lore = joined
                ? getConfig().getString("gui.click-leave")
                : getConfig().getString("gui.click-join");

        meta.lore(List.of(
                Component.text(
                        color(getConfig().getString("gui.online"))
                                .replace("%players%", String.valueOf(queue.size()))
                ),
                Component.text(""),
                Component.text(color(lore))
        ));

        item.setItemMeta(meta);

        inventory.setItem(13, item);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String guiTitle = ChatColor.stripColor(
                color(getConfig().getString("gui.title"))
        );

        if (!ChatColor.stripColor(event.getView().getTitle())
                .equalsIgnoreCase(guiTitle)) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() != 13) {
            return;
        }

        toggleQueue(player);
        openGui(player);
    }

    private void toggleQueue(Player player) {

        UUID uuid = player.getUniqueId();

        if (queue.contains(uuid)) {

            queue.remove(uuid);
            send(player, "left");

        } else {

            queue.add(uuid);
            send(player, "joined");
        }

        updateActionbar();
        checkStart();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        if (event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();

        boolean nowInside = insideZone(event.getTo());
        boolean wasInside = insideZone(event.getFrom());

        if (nowInside && !wasInside) {

            inside.add(player.getUniqueId());

            if (queue.add(player.getUniqueId())) {

                send(player, "zone-join");

                player.showTitle(
                        Title.title(
                                Component.text(
                                        ChatColor.stripColor(
                                                color(
                                                        getConfig().getString(
                                                                "messages.zone-title"
                                                        )
                                                )
                                        ),
                                        NamedTextColor.GOLD
                                ),
                                Component.text(
                                        ChatColor.stripColor(
                                                color(
                                                        getConfig().getString(
                                                                "messages.zone-subtitle"
                                                        )
                                                )
                                        ),
                                        NamedTextColor.WHITE
                                ),
                                Title.Times.times(
                                        Duration.ofMillis(200),
                                        Duration.ofSeconds(2),
                                        Duration.ofMillis(300)
                                )
                        )
                );

                sound(player, "zone-sound", 1.5f);

                particle(player, "zone-particles", 30);

                updateActionbar();
                checkStart();
            }

        } else if (!nowInside && wasInside) {

            inside.remove(player.getUniqueId());

            if (queue.remove(player.getUniqueId())) {
                send(player, "zone-leave");
            }

            updateActionbar();
        }
    }

    private boolean insideZone(Location location) {

        if (!getConfig().getBoolean("zone.enabled", false)) {
            return false;
        }

        String worldName = getConfig().getString("zone.world", "lobby");

        if (location.getWorld() == null ||
                !location.getWorld().getName().equals(worldName)) {
            return false;
        }

        Location pos1 = cfgLoc("zone.pos1");
        Location pos2 = cfgLoc("zone.pos2");

        if (pos1 == null || pos2 == null) {
            return false;
        }

        return between(
                location.getX(),
                pos1.getX(),
                pos2.getX()
        )
                &&
                between(
                        location.getY(),
                        pos1.getY(),
                        pos2.getY()
                )
                &&
                between(
                        location.getZ(),
                        pos1.getZ(),
                        pos2.getZ()
                );
    }

    private boolean between(double value, double first, double second) {
        return value >= Math.min(first, second)
                && value <= Math.max(first, second);
    }

    private void updateActionbar() {

        String message = color(
                getConfig().getString("gui.online")
        ).replace(
                "%players%",
                String.valueOf(queue.size())
        );

        for (UUID uuid : queue) {

            Player player = Bukkit.getPlayer(uuid);

            if (player != null) {
                player.sendActionBar(Component.text(message));
            }
        }
    }

    private void checkStart() {

        int minimum = getConfig().getInt(
                "queue.minimum-players",
                2
        );

        if (queue.size() < minimum || countdown != null) {
            return;
        }

        countdown = Bukkit.getScheduler().runTaskTimer(
                this,
                new Runnable() {

                    int seconds = getConfig().getInt(
                            "queue.countdown-seconds",
                            5
                    );

                    @Override
                    public void run() {

                        queue.removeIf(
                                uuid -> Bukkit.getPlayer(uuid) == null
                        );

                        if (queue.size() < minimum) {

                            countdown.cancel();
                            countdown = null;

                            updateActionbar();
                            return;
                        }

                        if (seconds <= 0) {

                            countdown.cancel();
                            countdown = null;

                            startFight();
                            return;
                        }

                        String message = color(
                                getConfig().getString(
                                        "messages.countdown"
                                )
                        ).replace(
                                "%seconds%",
                                String.valueOf(seconds)
                        );

                        for (UUID uuid : queue) {

                            Player player = Bukkit.getPlayer(uuid);

                            if (player != null) {

                                player.sendActionBar(
                                        Component.text(message)
                                );

                                sound(
                                        player,
                                        "countdown-sound",
                                        1.0f
                                                + 0.1f
                                                * (5 - seconds)
                                );
                            }
                        }

                        seconds--;
                    }

                },
                0L,
                20L
        );
    }

    private void startFight() {

        List<Player> players = new ArrayList<>();

        for (UUID uuid : new ArrayList<>(queue)) {

            Player player = Bukkit.getPlayer(uuid);

            if (player != null) {
                players.add(player);
            }
        }

        queue.clear();
        inside.clear();

        World world = Bukkit.getWorld(
                getConfig().getString(
                        "queue.rtp-world",
                        "survival"
                )
        );

        if (world == null) {

            for (Player player : players) {

                player.sendMessage(
                        color(
                                getConfig().getString(
                                        "messages.world-missing"
                                )
                        ).replace(
                                "%world%",
                                getConfig().getString(
                                        "queue.rtp-world"
                                )
                        )
                );
            }

            return;
        }

        Location center = findSafe(world, null);

        if (center == null) {

            for (Player player : players) {
                send(player, "no-location");
            }

            return;
        }

        double spread = Math.max(
                5,
                getConfig().getDouble(
                        "queue.group-spread",
                        35
                )
        );

        for (int i = 0; i < players.size(); i++) {

            double angle =
                    (2 * Math.PI * i)
                            / Math.max(1, players.size());

            Location target =
                    findSafe(
                            world,
                            center,
                            spread,
                            angle
                    );

            if (target == null) {
                target = center.clone();
            }

            Player player = players.get(i);

            player.teleport(target);

            player.showTitle(
                    Title.title(
                            Component.text(
                                    ChatColor.stripColor(
                                            color(
                                                    getConfig().getString(
                                                            "messages.fight"
                                                    )
                                            )
                                    ),
                                    NamedTextColor.RED
                            ),
                            Component.text(
                                    ChatColor.stripColor(
                                            color(
                                                    getConfig().getString(
                                                            "messages.fight-subtitle"
                                                    )
                                            )
                                    ),
                                    NamedTextColor.WHITE
                            )
                    )
            );

            sound(player, "fight-sound", 1.2f);

            particle(player, "fight-particles", 70);
        }
    }

    private Location findSafe(World world, Location center) {

        int maxRadius = getConfig().getInt(
                "queue.max-radius",
                5000
        );

        int attempts = getConfig().getInt(
                "queue.max-search-attempts",
                100
        );

        for (int i = 0; i < attempts; i++) {

            int x;
            int z;

            if (center == null) {

                x = ThreadLocalRandom.current()
                        .nextInt(-maxRadius, maxRadius + 1);

                z = ThreadLocalRandom.current()
                        .nextInt(-maxRadius, maxRadius + 1);

            } else {

                double angle =
                        ThreadLocalRandom.current()
                                .nextDouble(
                                        0,
                                        Math.PI * 2
                                );

                double radius =
                        ThreadLocalRandom.current()
                                .nextDouble(
                                        8,
                                        Math.max(
                                                9,
                                                maxRadius * 0.25
                                        )
                                );

                x = (int) Math.round(
                        center.getX()
                                + Math.cos(angle) * radius
                );

                z = (int) Math.round(
                        center.getZ()
                                + Math.sin(angle) * radius
                );
            }

            Location borderCheck =
                    new Location(world, x, 64, z);

            if (!world.getWorldBorder()
                    .isInside(borderCheck)) {
                continue;
            }

            int y = world.getHighestBlockYAt(x, z) + 1;

            Location location =
                    new Location(world, x, y, z);

            if (safe(location)) {
                return location;
            }
        }

        return null;
    }

    private Location findSafe(
            World world,
            Location center,
            double spread,
            double angle
    ) {

        for (int i = 0; i < 40; i++) {

            double radius =
                    spread
                            * (
                            0.65
                                    + ThreadLocalRandom.current()
                                    .nextDouble()
                                    * 0.35
                    );

            double randomAngle =
                    angle
                            + (
                            ThreadLocalRandom.current()
                                    .nextDouble()
                                    - 0.5
                    ) * 0.35;

            int x = (int) Math.round(
                    center.getX()
                            + Math.cos(randomAngle) * radius
            );

            int z = (int) Math.round(
                    center.getZ()
                            + Math.sin(randomAngle) * radius
            );

            int y = world.getHighestBlockYAt(x, z) + 1;

            Location location =
                    new Location(world, x, y, z);

            if (world.getWorldBorder().isInside(location)
                    && safe(location)) {
                return location;
            }
        }

        return null;
    }

    private boolean safe(Location location) {

        Block ground =
                location.clone()
                        .subtract(0, 1, 0)
                        .getBlock();

        Block feet = location.getBlock();

        Block head =
                location.clone()
                        .add(0, 1, 0)
                        .getBlock();

        Material groundType = ground.getType();

        return groundType.isSolid()
                && feet.isPassable()
                && head.isPassable()
                && groundType != Material.LAVA
                && groundType != Material.MAGMA_BLOCK
                && groundType != Material.CAMPFIRE
                && groundType != Material.SOUL_CAMPFIRE;
    }

    private void particle(
            Player player,
            String key,
            int count
    ) {

        try {

            player.spawnParticle(
                    Particle.valueOf(
                            getConfig().getString(
                                    "effects." + key
                            )
                    ),
                    player.getLocation()
                            .add(0, 1, 0),
                    count,
                    .6,
                    .8,
                    .6,
                    .03
            );

        } catch (Exception ignored) {
        }
    }

    private ItemStack zoneTool() {

        ItemStack item =
                new ItemStack(Material.BLAZE_ROD);

        ItemMeta meta =
                item.getItemMeta();

        meta.displayName(
                Component.text(
                        "⚔ RTP Zone Tool",
                        NamedTextColor.GOLD
                )
        );

        meta.lore(
                List.of(
                        Component.text(
                                "Left click = Position 1"
                        ),
                        Component.text(
                                "Right click = Position 2"
                        )
                )
        );

        item.setItemMeta(meta);

        return item;
    }

    @EventHandler
    public void onTool(PlayerInteractEvent event) {

        Player player = event.getPlayer();

        if (!player.hasPermission("rtpqueue.admin")
                || event.getItem() == null
                || event.getItem().getType() != Material.BLAZE_ROD) {
            return;
        }

        ItemMeta meta =
                event.getItem().getItemMeta();

        if (meta == null
                || meta.displayName() == null
                || !meta.displayName()
                .toString()
                .contains("RTP Zone Tool")) {
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_AIR) {

            selections1.put(
                    player.getUniqueId(),
                    player.getLocation().clone()
            );

            saveCfgLoc(
                    "zone.pos1",
                    player.getLocation()
            );

            send(player, "pos1");

            event.setCancelled(true);

        } else if (
                event.getAction() == Action.RIGHT_CLICK_BLOCK
                        || event.getAction() == Action.RIGHT_CLICK_AIR
        ) {

            selections2.put(
                    player.getUniqueId(),
                    player.getLocation().clone()
            );

            saveCfgLoc(
                    "zone.pos2",
                    player.getLocation()
            );

            send(player, "pos2");

            event.setCancelled(true);
        }
    }

    private Location cfgLoc(String path) {

        if (!getConfig().contains(path + ".x")) {
            return null;
        }

        World world =
                Bukkit.getWorld(
                        getConfig().getString(
                                "zone.world",
                                "lobby"
                        )
                );

        if (world == null) {
            return null;
        }

        return new Location(
                world,
                getConfig().getDouble(path + ".x"),
                getConfig().getDouble(path + ".y"),
                getConfig().getDouble(path + ".z")
        );
    }

    private void saveCfgLoc(
            String path,
            Location location
    ) {

        getConfig().set(
                path + ".x",
                location.getX()
        );

        getConfig().set(
                path + ".y",
                location.getY()
        );

        getConfig().set(
                path + ".z",
                location.getZ()
        );

        getConfig().set(
                "zone.world",
                location.getWorld().getName()
        );

        saveConfig();
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {

        UUID uuid =
                event.getPlayer().getUniqueId();

        queue.remove(uuid);
        inside.remove(uuid);
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!(sender instanceof Player player)) {

            sender.sendMessage("Player only.");
            return true;
        }

        if (command.getName()
                .equalsIgnoreCase("rtpqueue")) {

            if (!player.hasPermission(
                    "rtpqueue.use"
            )) {
                send(player, "no-permission");
                return true;
            }

            if (args.length == 0) {
                openGui(player);
                return true;
            }

            switch (args[0].toLowerCase()) {

                case "join" -> {

                    if (queue.add(
                            player.getUniqueId()
                    )) {
                        send(player, "joined");
                    }

                    updateActionbar();
                    checkStart();
                }

                case "leave" -> {

                    if (queue.remove(
                            player.getUniqueId()
                    )) {
                        send(player, "left");
                    }

                    updateActionbar();
                }

                case "status" ->
                        player.sendMessage(
                                "Queue: " + queue.size()
                        );

                default ->
                        openGui(player);
            }

            return true;
        }

        if (!player.hasPermission(
                "rtpqueue.admin"
        )) {
            send(player, "no-permission");
            return true;
        }

        if (args.length == 0) {

            player.sendMessage(
                    "/rtpzone wand | pos1 | pos2 | create | enable | disable | info | reload"
            );

            return true;
        }

        switch (args[0].toLowerCase()) {

            case "wand" -> {

                player.getInventory()
                        .addItem(zoneTool());

                send(player, "tool-given");
            }

            case "pos1" -> {

                saveCfgLoc(
                        "zone.pos1",
                        player.getLocation()
                );

                send(player, "pos1");
            }

            case "pos2" -> {

                saveCfgLoc(
                        "zone.pos2",
                        player.getLocation()
                );

                send(player, "pos2");
            }

            case "create" -> {

                getConfig().set(
                        "zone.enabled",
                        true
                );

                saveConfig();

                send(player, "zone-created");
            }

            case "enable" -> {

                getConfig().set(
                        "zone.enabled",
                        true
                );

                saveConfig();

                send(player, "zone-enabled");
            }

            case "disable" -> {

                getConfig().set(
                        "zone.enabled",
                        false
                );

                saveConfig();

                send(player, "zone-disabled");
            }

            case "info" ->
                    player.sendMessage(
                            "Zone: "
                                    + getConfig().getString(
                                    "zone.world"
                            )
                                    + " | enabled="
                                    + getConfig().getBoolean(
                                    "zone.enabled"
                            )
                    );

            case "reload" -> {

                reloadConfig();

                send(player, "reloaded");
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        if (args.length != 1) {
            return List.of();
        }

        if (command.getName()
                .equalsIgnoreCase("rtpqueue")) {

            return List.of(
                    "join",
                    "leave",
                    "status"
            );
        }

        return List.of(
                "wand",
                "pos1",
                "pos2",
                "create",
                "enable",
                "disable",
                "info",
                "reload"
        );
    }
          }
