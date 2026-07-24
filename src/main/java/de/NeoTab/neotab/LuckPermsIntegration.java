package de.NeoTab.neotab;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

final class LuckPermsIntegration implements LuckPermsSupport {
    private final PlayerAdapter<Player> playerAdapter;

    private LuckPermsIntegration(PlayerAdapter<Player> playerAdapter) {
        this.playerAdapter = playerAdapter;
    }

    static LuckPermsSupport create(NeoTab plugin) {
        RegisteredServiceProvider<LuckPerms> provider = plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
        LuckPerms luckPerms = provider == null ? null : provider.getProvider();
        if (luckPerms == null) {
            return null;
        }

        return new LuckPermsIntegration(luckPerms.getPlayerAdapter(Player.class));
    }

    @Override
    public String decoratePlayerName(Player player) {
        User user = playerAdapter.getUser(player);
        if (user == null) {
            return null;
        }

        CachedMetaData metaData = user.getCachedData().getMetaData();
        String prefix = metaData.getPrefix();
        String suffix = metaData.getSuffix();
        if ((prefix == null || prefix.isBlank()) && (suffix == null || suffix.isBlank())) {
            return null;
        }

        return (prefix == null ? "" : prefix) + player.getName() + (suffix == null ? "" : suffix);
    }
}
