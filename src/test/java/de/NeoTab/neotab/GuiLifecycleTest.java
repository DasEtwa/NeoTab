package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GuiLifecycleTest {
    @Test
    void everyNeoTabGuiHolderUsesTheSharedLifecycleMarker() throws Exception {
        assertTrue(NeoTabInventoryHolder.class.isAssignableFrom(Class.forName("de.NeoTab.neotab.NeoTabGui$GuiHolder")));
        assertTrue(NeoTabInventoryHolder.class.isAssignableFrom(Class.forName("de.NeoTab.neotab.RegionProfileGui$GuiHolder")));
    }
}
