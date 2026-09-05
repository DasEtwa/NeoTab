package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage using real services and deterministic Bukkit boundary doubles. */
final @SuppressWarnings("deprecation")
class HotfixRegressionTest {
    @TempDir Path directory;
    NeoTab plugin;
    ConfigManager config;
    AsyncYamlWriter writer;
    Server server;
    Object previousServer;
    final List<Player> players = new ArrayList<>();
    final List<Runnable> immediateTasks = new ArrayList<>();
    final AtomicInteger locationReads = new AtomicInteger();
    final AtomicInteger visibilityChecks = new AtomicInteger();
    final List<Runnable> delayedTasks = new ArrayList<>();
    boolean visible = true;

    @BeforeEach void setup() throws Exception {
        Field singleton = Bukkit.class.getDeclaredField("server");
        singleton.setAccessible(true);
        previousServer = singleton.get(null);
        PluginManager manager = stub(PluginManager.class, (p, m, a) -> fallback(p, m, a));
        BukkitScheduler scheduler = stub(BukkitScheduler.class, (p, m, a) -> {
            if (m.getName().equals("runTask")) immediateTasks.add((Runnable) a[1]);
            if (m.getName().equals("runTaskLater")) delayedTasks.add((Runnable) a[1]);
            if (BukkitTask.class.isAssignableFrom(m.getReturnType()))
                return stub(BukkitTask.class, HotfixRegressionTest::fallback);
            return fallback(p, m, a);
        });
        server = stub(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getLogger" -> Logger.getAnonymousLogger();
            case "getOnlinePlayers" -> players;
            case "getPluginManager" -> manager;
            case "getScheduler" -> scheduler;
            case "getPlayer" -> players.stream().filter(x -> x.getUniqueId().equals(a[0])).findFirst().orElse(null);
            case "getName", "getVersion", "getBukkitVersion" -> "ReviewStub";
            default -> fallback(p, m, a);
        });
        singleton.set(null, server);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        plugin = (NeoTab) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), NeoTab.class);
        set(JavaPlugin.class, plugin, "server", server);
        set(JavaPlugin.class, plugin, "dataFolder", directory.toFile());
        set(JavaPlugin.class, plugin, "configFile", directory.resolve("config.yml").toFile());
        set(JavaPlugin.class, plugin, "classLoader", getClass().getClassLoader());
        set(JavaPlugin.class, plugin, "description", new PluginDescriptionFile("NeoTab", "1.4.1", NeoTab.class.getName()));
        set(JavaPlugin.class, plugin, "logger", new PluginLogger(plugin));
        set(JavaPlugin.class, plugin, "isEnabled", true);
        Files.writeString(directory.resolve("config.yml"), "server-name: Original custom name\nmetrics:\n  enabled: false\n");
        writer = new AsyncYamlWriter(Logger.getAnonymousLogger());
        config = new ConfigManager(plugin, writer);
        set(NeoTab.class, plugin, "configManager", config);
    }

    @AfterEach void teardown() throws Exception {
        if (writer != null) writer.close();
        set(Bukkit.class, null, "server", previousServer);
    }

    @Test void teleportAndPortalEventsHaveDedicatedCancelledAwareHandlers() throws Exception {
        JavaPluginLoader loader = new JavaPluginLoader(server);
        var registered = loader.createRegisteredListeners(new RegionMoveListener(null), plugin);
        assertTrue(registered.containsKey(PlayerMoveEvent.class));
        assertTrue(registered.containsKey(PlayerTeleportEvent.class));
        assertTrue(registered.containsKey(PlayerPortalEvent.class));
        assertNotSame(PlayerMoveEvent.getHandlerList(), PlayerTeleportEvent.getHandlerList());
        assertNotSame(PlayerMoveEvent.getHandlerList(), PlayerPortalEvent.getHandlerList());
        for (Class<?> event : List.of(PlayerTeleportEvent.class, PlayerPortalEvent.class)) {
            assertTrue(registered.get(event).stream().allMatch(RegisteredListener::isIgnoringCancelled));
        }
        assertTrue(registered.containsKey(PlayerChangedWorldEvent.class));
    }

    @Test void malformedConfigRetainsSettingsAndBlocksWritesUntilSuccessfulReload() throws Exception {
        Path file = directory.resolve("config.yml");
        String broken = "server-name: Original custom name\nbroken: [\n";
        Files.writeString(file, broken);
        assertThrows(ConfigurationStorageException.class, config::reload);
        assertEquals("Original custom name", config.getServerNameRaw());
        assertFalse(plugin.getConfig().getBoolean("metrics.enabled", true));
        assertThrows(ConfigurationStorageException.class, () -> config.setServerName("Later edit"));
        writer.flushAsync().get();
        assertEquals(broken, Files.readString(file));
        assertEquals("Original custom name", plugin.getConfig().getString("server-name"));
        Files.writeString(file, "server-name: Repaired\nmetrics:\n  enabled: false\n");
        config.reload();
        config.setServerName("Allowed edit");
        writer.flushAsync().get();
        assertEquals("Allowed edit", YamlConfiguration.loadConfiguration(file.toFile()).getString("server-name"));
    }

    @Test void malformedRegionsRetainDefinitionsAndBlockWritesUntilSuccessfulReload() throws Exception {
        RegionManager regions = regions();
        var selection = new RegionSelectionManager.RegionSelection("world", 0, 0, 0, 10, 100, 10);
        assertTrue(regions.createRegion("original", selection));
        writer.flushAsync().get();
        Path file = directory.resolve("regions.yml");
        String original = Files.readString(file);
        String broken = original + "broken: [\n";
        Files.writeString(file, broken);
        assertThrows(ConfigurationStorageException.class, regions::reload);
        assertTrue(regions.hasRegion("original"));
        assertThrows(ConfigurationStorageException.class, () -> regions.createRegion("replacement", selection));
        assertThrows(ConfigurationStorageException.class, () -> regions.deleteRegion("original"));
        writer.flushAsync().get();
        assertEquals(broken, Files.readString(file));
        Files.writeString(file, original);
        regions.reload();
        assertTrue(regions.createRegion("replacement", selection));
        writer.flushAsync().get();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(saved.contains("regions.original"));
        assertTrue(saved.contains("regions.replacement"));
    }

    private RegionManager regions() {
        return new RegionManager(plugin, config, new RegionSelectionManager(), new WorldEditSelectionProvider(plugin), writer);
    }

    @Test void denseNearestPlayerSearchSnapshotsPositionsAndStopsAtZeroDistance() throws Exception {
        // Skip transport only; execute the original index, candidate search and formatting.
        plugin.getConfig().set("extras.actionbar.enabled", false);
        plugin.getConfig().set("extras.actionbar.nearest-player.enabled", true);
        invoke(config, "rebuildSnapshot");
        var service = new ActionBarService(plugin, config);
        var module = new NearestPlayerModule(plugin, config, service, new ActionBarTextFormatter(plugin, config));
        for (int count : new int[] {100, 200, 500, 1000}) {
            players.clear();
            World world = stub(World.class, (p, m, a) -> m.getName().equals("getName") ? "world" : fallback(p, m, a));
            for (int i = 0; i < count; i++) players.add(player(world));
            locationReads.set(0);
            visibilityChecks.set(0);
            long begin = System.nanoTime();
            invoke(module, "checkPlayers");
            drainImmediateTasks();
            long elapsed = System.nanoTime() - begin;
            assertEquals(count * 2, visibilityChecks.get());
            assertEquals(count * 3, locationReads.get());
            System.out.printf(Locale.ROOT, "Dense search n=%d: canSee=%d getLocation=%d synthetic-ms=%.3f%n",
                count, visibilityChecks.get(), locationReads.get(), elapsed / 1e6);
        }
    }

    @Test void oldQueuedChatInputCannotCompleteANewPrompt() throws Exception {
        Player player = player(null);
        ChatInputManager input = new ChatInputManager(plugin, config);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        input.request(player, "First prompt", (p, text) -> first.add(text));
        input.onPlayerChat(new AsyncPlayerChatEvent(true, player, "answer to first", Set.of()));
        assertEquals(1, immediateTasks.size());
        input.cancel(player, false);
        input.request(player, "Second prompt", (p, text) -> second.add(text));
        immediateTasks.removeFirst().run();
        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        input.onPlayerChat(new AsyncPlayerChatEvent(true, player, "answer to second", Set.of()));
        immediateTasks.removeFirst().run();
        assertEquals(List.of("answer to second"), second);
    }

    @Test void eachTimerUsesItsOwnStartTimeAndCatchesUpAfterLag() throws Exception {
        Player first = player(null);
        Player second = player(null);
        players.addAll(List.of(first, second));
        var now = new java.util.concurrent.atomic.AtomicLong();
        var service = new ActionBarService(plugin, config);
        var timers = new ActionBarTimerService(plugin, config, service, new ActionBarTextFormatter(plugin, config), now::get);
        assertTrue(timers.start(first, 60));
        now.set(950_000_000L);
        assertTrue(timers.start(second, 1));
        now.set(1_000_000_000L);
        invoke(timers, "tick");
        assertTrue(timers.isRunning(second));
        now.set(1_949_999_999L);
        invoke(timers, "tick");
        assertTrue(timers.isRunning(second));
        now.set(1_950_000_000L);
        invoke(timers, "tick");
        assertFalse(timers.isRunning(second));
        now.set(61_000_000_000L);
        invoke(timers, "tick");
        assertFalse(timers.isRunning(first));
    }

    @Test void pausingTimerPreservesFractionalRemainingTime() throws Exception {
        Player player = player(null);
        players.add(player);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var timers = new ActionBarTimerService(plugin, config, new ActionBarService(plugin, config),
            new ActionBarTextFormatter(plugin, config), now::get);
        assertTrue(timers.start(player, 1));
        now.set(600_000_000L);
        assertTrue(timers.pause(player));
        now.set(10_000_000_000L);
        invoke(timers, "tick");
        assertTrue(timers.isRunning(player));
        assertTrue(timers.resume(player));
        now.set(10_399_999_999L);
        invoke(timers, "tick");
        assertTrue(timers.isRunning(player));
        now.set(10_400_000_000L);
        invoke(timers, "tick");
        assertFalse(timers.isRunning(player));
    }

    @Test void elapsedSessionHandlesPauseResumeAndNanoTimeWraparound() {
        long start = Long.MAX_VALUE - 500_000_000L;
        ElapsedSession clock = ElapsedSession.start(start);
        assertEquals(1_000_000_000L, clock.elapsedNanos(start + 1_000_000_000L));
        clock = clock.pause(start + 1_100_000_000L);
        assertEquals(1_100_000_000L, clock.elapsedNanos(start + 20_000_000_000L));
        clock = clock.resume(start + 20_000_000_000L);
        assertEquals(1_600_000_000L, clock.elapsedNanos(start + 20_500_000_000L));
    }

    @Test void oldTimeoutCannotCancelANewChatPrompt() {
        Player player = player(null);
        ChatInputManager input = new ChatInputManager(plugin, config);
        List<String> received = new ArrayList<>();
        input.request(player, "first", (p, text) -> fail("Old input must not run"));
        Runnable oldTimeout = delayedTasks.getLast();
        input.request(player, "second", (p, text) -> received.add(text));
        oldTimeout.run();
        input.onPlayerChat(new AsyncPlayerChatEvent(true, player, "second answer", Set.of()));
        immediateTasks.removeFirst().run();
        assertEquals(List.of("second answer"), received);
    }

    @Test void allHiddenDenseSearchYieldsWithinTheCandidateBudget() throws Exception {
        plugin.getConfig().set("extras.actionbar.enabled", false);
        plugin.getConfig().set("extras.actionbar.nearest-player.enabled", true);
        invoke(config, "rebuildSnapshot");
        visible = false;
        World world = stub(World.class, (p, m, a) -> m.getName().equals("getName") ? "world" : fallback(p, m, a));
        for (int i = 0; i < 200; i++) players.add(player(world));
        var module = new NearestPlayerModule(plugin, config, new ActionBarService(plugin, config), new ActionBarTextFormatter(plugin, config));
        invoke(module, "checkPlayers");
        assertTrue(visibilityChecks.get() <= NearestPlayerModule.MAX_CANDIDATES_PER_TICK);
        assertFalse(immediateTasks.isEmpty());
        int slices = 1;
        while (!immediateTasks.isEmpty()) {
            int before = visibilityChecks.get();
            immediateTasks.removeFirst().run();
            assertTrue(visibilityChecks.get() - before <= NearestPlayerModule.MAX_CANDIDATES_PER_TICK);
            assertTrue(++slices < 100);
        }
        assertEquals(200 * 199, visibilityChecks.get());
        assertEquals(200, locationReads.get());
    }

    @Test void malformedRegionMappingAndMissingConfigAreProtected() throws Exception {
        RegionManager regions = regions();
        Files.writeString(directory.resolve("regions.yml"), "regions: accidental-text\n");
        assertThrows(ConfigurationStorageException.class, regions::reload);
        Path configFile = directory.resolve("config.yml");
        Files.move(configFile, directory.resolve("config.yml.saved"));
        assertThrows(ConfigurationStorageException.class, config::reload);
        assertEquals("Original custom name", config.getServerNameRaw());
        assertThrows(ConfigurationStorageException.class, () -> config.setLanguage(ConfigManager.Language.GERMAN));
        assertFalse(Files.exists(configFile));
    }

    @Test void cancelledTeleportAndPortalDoNotScheduleRegionUpdates() throws Exception {
        RegionManager regions = regions();
        var registered = new JavaPluginLoader(server).createRegisteredListeners(new RegionMoveListener(regions), plugin);
        World world = stub(World.class, (p, m, a) -> m.getName().equals("getName") ? "world" : fallback(p, m, a));
        Player player = player(world);
        for (PlayerTeleportEvent event : List.of(
            new PlayerTeleportEvent(player, new Location(world, 0, 64, 0), new Location(world, 100, 64, 0)),
            new PlayerPortalEvent(player, new Location(world, 0, 64, 0), new Location(world, 200, 64, 0)))) {
            event.setCancelled(true);
            for (RegisteredListener listener : registered.get(event.getClass())) listener.callEvent(event);
            assertTrue(delayedTasks.isEmpty());
            event.setCancelled(false);
            for (RegisteredListener listener : registered.get(event.getClass())) listener.callEvent(event);
            assertEquals(1, delayedTasks.size());
            regions.handleQuit(player);
            delayedTasks.clear();
        }
    }

    @Test void stopwatchUsesElapsedTimeAndExcludesPausedTime() throws Exception {
        Player player = player(null);
        players.add(player);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var service = new ActionBarService(plugin, config);
        var stopwatch = new StopwatchService(plugin, config, service, new ActionBarTextFormatter(plugin, config), now::get);
        assertTrue(stopwatch.start(player));
        now.set(3_500_000_000L);
        invoke(stopwatch, "tick");
        assertActionBarContains(service, player, "stopwatch", "00:03");
        assertTrue(stopwatch.pause(player));
        now.set(30_000_000_000L);
        invoke(stopwatch, "tick");
        assertActionBarContains(service, player, "stopwatch", "00:03");
        assertTrue(stopwatch.resume(player));
        now.set(30_500_000_000L);
        invoke(stopwatch, "tick");
        assertActionBarContains(service, player, "stopwatch", "00:04");
        assertTrue(stopwatch.reset(player));
        assertActionBarContains(service, player, "stopwatch", "00:00");
    }

    @SuppressWarnings("unchecked")
    private void assertActionBarContains(ActionBarService service, Player player, String source, String text) throws Exception {
        Field field = ActionBarService.class.getDeclaredField("messages");
        field.setAccessible(true);
        var messages = (Map<UUID, Map<String, ActionBarMessage>>) field.get(service);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(messages.get(player.getUniqueId()).get(source).text());
        assertTrue(plain.contains(text), plain);
    }

    @Test void guiActionsRunNextTickAndExpireAfterCloseReloadOrDisable() throws Exception {
        var inventory = stub(org.bukkit.inventory.Inventory.class, HotfixRegressionTest::fallback);
        var other = stub(org.bukkit.inventory.Inventory.class, HotfixRegressionTest::fallback);
        var current = new java.util.concurrent.atomic.AtomicReference<>(inventory);
        Player player = stub(Player.class, (p, m, a) -> switch (m.getName()) {
            case "isOnline" -> true;
            case "getOpenInventory" -> view(current.get());
            default -> fallback(p, m, a);
        });
        AtomicInteger calls = new AtomicInteger();
        GuiActions.nextTick(plugin, player, inventory, calls::incrementAndGet);
        assertEquals(0, calls.get());
        immediateTasks.removeFirst().run();
        assertEquals(1, calls.get());
        GuiActions.nextTick(plugin, player, inventory, calls::incrementAndGet);
        current.set(other);
        immediateTasks.removeFirst().run();
        assertEquals(1, calls.get());
        current.set(inventory);
        GuiActions.nextTick(plugin, player, inventory, calls::incrementAndGet);
        plugin.closePluginInventories();
        immediateTasks.removeFirst().run();
        assertEquals(1, calls.get());
        GuiActions.nextTick(plugin, player, inventory, calls::incrementAndGet);
        set(JavaPlugin.class, plugin, "isEnabled", false);
        immediateTasks.removeFirst().run();
        assertEquals(1, calls.get());
    }

    private org.bukkit.inventory.InventoryView view(org.bukkit.inventory.Inventory top) {
        return new org.bukkit.inventory.InventoryView() {
            public org.bukkit.inventory.Inventory getTopInventory() { return top; }
            public org.bukkit.inventory.Inventory getBottomInventory() { return top; }
            public org.bukkit.entity.HumanEntity getPlayer() { return null; }
            public org.bukkit.event.inventory.InventoryType getType() { return org.bukkit.event.inventory.InventoryType.CHEST; }
            public String getTitle() { return "Test"; }
            public String getOriginalTitle() { return "Test"; }
            public void setTitle(String title) { }
        };
    }

    private void drainImmediateTasks() {
        int iterations = 0;
        while (!immediateTasks.isEmpty()) {
            immediateTasks.removeFirst().run();
            assertTrue(++iterations < 10_000, "Pending work must terminate");
        }
    }

    private Player player(World world) {
        UUID id = UUID.randomUUID();
        return stub(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "Player";
            case "isOnline", "isValid" -> true;
            case "getWorld" -> world;
            case "canSee" -> { visibilityChecks.incrementAndGet(); yield visible; }
            case "getLocation" -> { locationReads.incrementAndGet(); yield new Location(world, 1, 64, 1); }
            default -> fallback(p, m, a);
        });
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
    private static Object fallback(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("equals")) return proxy == args[0];
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("toString")) return "ReviewStub";
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
