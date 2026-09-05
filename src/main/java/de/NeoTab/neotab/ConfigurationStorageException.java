package de.NeoTab.neotab;

/** A user file must be repaired and reloaded before persistent edits can resume. */
public final class ConfigurationStorageException extends IllegalStateException {
    private final String fileName;

    ConfigurationStorageException(String fileName, Throwable cause) {
        super("Cannot load or save " + fileName + "; repair the file and run /tab reload. Previous settings were retained.", cause);
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
