package de.NeoTab.neotab;

public interface ActionBarModule {
    void start();

    void stop();

    default void restart() {
        stop();
        start();
    }
}
