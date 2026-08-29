package de.NeoTab.neotab;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Keeps platform-specific player output behind one Bukkit-safe boundary.
 */
public final class PlatformBridge {
    private static final String SYSTEM_CHAT_PACKET = "net.minecraft.network.protocol.game.ClientboundSystemChatPacket";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final AtomicBoolean actionBarWarningLogged;
    private final Object transportLock = new Object();

    private volatile List<ActionBarTransport> actionBarTransports;
    private volatile int actionBarTransportIndex;
    private volatile Throwable lastTransportFailure;

    public PlatformBridge(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        actionBarWarningLogged = new AtomicBoolean();
    }

    public void sendActionBar(Player player, Component component) {
        if (player == null) {
            return;
        }

        Component safeComponent = component == null ? Component.empty() : component;
        if (!Bukkit.isPrimaryThread()) {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> sendActionBar(player, safeComponent));
            } catch (LinkageError | RuntimeException ex) {
                logActionBarUnavailable(ex);
            }
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        String legacy;
        try {
            legacy = configManager.toLegacy(safeComponent);
        } catch (LinkageError | RuntimeException ex) {
            logActionBarUnavailable(ex);
            return;
        }

        List<ActionBarTransport> transports = resolveTransports(player);
        while (true) {
            int index = actionBarTransportIndex;
            if (index >= transports.size()) {
                logActionBarUnavailable(lastTransportFailure);
                return;
            }

            try {
                if (transports.get(index).send(player, safeComponent, legacy)) {
                    return;
                }
                lastTransportFailure = new IllegalStateException("ActionBar transport rejected the message");
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                lastTransportFailure = ex;
            }

            synchronized (transportLock) {
                if (actionBarTransportIndex == index) {
                    actionBarTransportIndex++;
                }
            }
        }
    }

    private List<ActionBarTransport> resolveTransports(Player player) {
        List<ActionBarTransport> cached = actionBarTransports;
        if (cached != null) {
            return cached;
        }

        synchronized (transportLock) {
            if (actionBarTransports != null) {
                return actionBarTransports;
            }

            ArrayList<ActionBarTransport> resolved = new ArrayList<>(3);
            try {
                resolved.add(resolveSpigotTransport(player));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                lastTransportFailure = ex;
            }
            try {
                resolved.add(resolveCraftBukkitTransport(player));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                lastTransportFailure = ex;
            }
            resolved.add(this::sendCommandActionBar);
            actionBarTransports = List.copyOf(resolved);
            return actionBarTransports;
        }
    }

    private ActionBarTransport resolveSpigotTransport(Player player) throws ReflectiveOperationException {
        Method spigotMethod = player.getClass().getMethod("spigot");
        ClassLoader serverClassLoader = player.getClass().getClassLoader();
        Class<?> messageTypeClass = Class.forName("net.md_5.bungee.api.ChatMessageType", true, serverClassLoader);
        Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent", true, serverClassLoader);
        Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent", true, serverClassLoader);
        Object actionBar = enumConstant(messageTypeClass, "ACTION_BAR");
        Method fromLegacyText = textComponentClass.getMethod("fromLegacyText", String.class);
        Class<?> componentArrayClass = java.lang.reflect.Array.newInstance(baseComponentClass, 0).getClass();
        Method sendMessage = spigotMethod.getReturnType().getMethod("sendMessage", messageTypeClass, componentArrayClass);

        return (target, ignoredComponent, legacy) -> {
            Object components = fromLegacyText.invoke(null, legacy);
            Object spigot = spigotMethod.invoke(target);
            sendMessage.invoke(spigot, actionBar, components);
            return true;
        };
    }

    private ActionBarTransport resolveCraftBukkitTransport(Player player) throws ReflectiveOperationException {
        Method getHandle = player.getClass().getMethod("getHandle");
        Object sampleHandle = getHandle.invoke(player);
        Field connectionField = findConnectionField(sampleHandle.getClass());
        Object sampleConnection = connectionField.get(sampleHandle);

        String craftPackage = player.getClass().getPackageName();
        int entityPackage = craftPackage.lastIndexOf(".entity");
        if (entityPackage < 0) {
            throw new ClassNotFoundException("Cannot determine CraftBukkit package from " + craftPackage);
        }
        String craftRoot = craftPackage.substring(0, entityPackage);
        ClassLoader serverClassLoader = player.getClass().getClassLoader();
        Class<?> craftChatMessage = Class.forName(craftRoot + ".util.CraftChatMessage", true, serverClassLoader);
        Method fromStringOrEmpty = craftChatMessage.getMethod("fromStringOrEmpty", String.class);
        Object sampleComponent = fromStringOrEmpty.invoke(null, "");

        Class<?> packetClass = Class.forName(SYSTEM_CHAT_PACKET, true, serverClassLoader);
        Constructor<?> packetConstructor = findSystemChatPacketConstructor(packetClass, sampleComponent);
        Method sendMethod = findPacketSendMethod(sampleConnection.getClass(), packetClass);

        return (target, ignoredComponent, legacy) -> {
            Object handle = getHandle.invoke(target);
            Object connection = connectionField.get(handle);
            Object nativeComponent = fromStringOrEmpty.invoke(null, legacy);
            Object packet = packetConstructor.newInstance(nativeComponent, true);
            sendMethod.invoke(connection, packet);
            return true;
        };
    }

    private boolean sendCommandActionBar(Player player, Component component, String ignoredLegacy) {
        if (!Bukkit.isPrimaryThread()) {
            return false;
        }
        try {
            String json = GsonComponentSerializer.gson().serialize(component);
            return Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "minecraft:title " + player.getName() + " actionbar " + json
            );
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private void logActionBarUnavailable(Throwable cause) {
        if (!actionBarWarningLogged.compareAndSet(false, true)) {
            return;
        }
        String causeText = cause == null
            ? "no compatible transport was found"
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        plugin.getConfigManager().log(java.util.logging.Level.WARNING, "log.platform.actionbar-unavailable", java.util.Map.of(
            "cause", causeText
        ));
    }

    private static Field findConnectionField(Class<?> handleClass) throws NoSuchFieldException {
        for (Field field : handleClass.getFields()) {
            String typeName = field.getType().getName();
            if (typeName.endsWith(".PlayerConnection") || typeName.endsWith(".ServerGamePacketListenerImpl")) {
                return field;
            }
        }
        throw new NoSuchFieldException("Server player connection");
    }

    private static Constructor<?> findSystemChatPacketConstructor(Class<?> packetClass, Object nativeComponent)
        throws NoSuchMethodException {
        for (Constructor<?> constructor : packetClass.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                && parameters[1] == boolean.class
                && parameters[0].isInstance(nativeComponent)) {
                return constructor;
            }
        }
        throw new NoSuchMethodException("Compatible ClientboundSystemChatPacket constructor");
    }

    static Method findPacketSendMethod(Class<?> connectionClass, Class<?> packetClass) throws NoSuchMethodException {
        String[] supportedNames = {"sendPacket", "send", "a", "b"};
        for (String supportedName : supportedNames) {
            for (Method method : connectionClass.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && method.getName().equals(supportedName)
                    && parameters.length == 1
                    && parameters[0].isAssignableFrom(packetClass)) {
                    return method;
                }
            }
        }
        throw new NoSuchMethodException("Compatible packet send method on " + connectionClass.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    @FunctionalInterface
    private interface ActionBarTransport {
        boolean send(Player player, Component component, String legacy) throws ReflectiveOperationException;
    }
}
