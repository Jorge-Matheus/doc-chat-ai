package com.docchat.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores files in a directory on the local disk. See docs/adr/0005. */
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${docchat.storage.directory}") Path directory) {
        this.root = directory.toAbsolutePath().normalize();
    }

    /** Writes to a temporary file first, so a failed upload never leaves a partial file behind. */
    public void store(String key, InputStream content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, ".upload-", ".tmp");
            try {
                Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new StorageException("Could not store file " + key, ex);
        }
    }

    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException ex) {
            throw new StorageException("Could not open file " + key, ex);
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new StorageException("Could not delete file " + key, ex);
        }
    }

    boolean isAvailable() {
        try {
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (IOException ex) {
            return false;
        }
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!root.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return resolved;
    }
}
