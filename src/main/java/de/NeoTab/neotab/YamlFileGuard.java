package de.NeoTab.neotab;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Parses before publication and protects malformed/missing files from subsequent edits. */
final class YamlFileGuard {
    private final File file;
    private boolean writable;

    YamlFileGuard(File file) {
        this.file = file;
    }

    YamlConfiguration read() {
        try {
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.load(file);
            return candidate;
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            writable = false;
            throw new ConfigurationStorageException(file.getName(), failure);
        }
    }

    void accept() {
        writable = true;
    }

    ConfigurationStorageException reject(String reason) {
        writable = false;
        return new ConfigurationStorageException(file.getName(), new IllegalArgumentException(reason));
    }

    void requireWritable() {
        if (!writable) {
            throw new ConfigurationStorageException(file.getName(), null);
        }
    }
}
