package de.NeoTab.neotab;

public final class StructurePopupModule implements ActionBarModule {
    private final NeoTab plugin;
    private final ConfigManager configManager;
    private boolean warned;

    public StructurePopupModule(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void start() {
        ConfigManager.StructurePopupActionBarConfig config = configManager.getActionBarConfig().structurePopup();
        if (config.enabled() && !warned) {
            warned = true;
            plugin.getLogger().warning("NeoTab structure popup is experimental and currently a GUI/config placeholder; no structure lookup task was started.");
        }
    }

    @Override
    public void stop() {
        warned = false;
    }
}
