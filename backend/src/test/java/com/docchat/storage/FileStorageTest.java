package com.docchat.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageTest {

    @TempDir
    private Path directory;

    private FileStorage storage() {
        return new FileStorage(directory.resolve("files"));
    }

    @Test
    void storesAndReadsAFile() throws IOException {
        FileStorage storage = storage();

        storage.store("a.pdf", stream("content"));

        try (InputStream stored = storage.open("a.pdf")) {
            assertThat(new String(stored.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("content");
        }
    }

    @Test
    void leavesNoFileBehindWhenTheUploadFails() throws IOException {
        FileStorage storage = storage();
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection lost");
            }
        };

        assertThatThrownBy(() -> storage.store("a.pdf", failing)).isInstanceOf(StorageException.class);

        try (var files = Files.list(directory.resolve("files"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void deleteRemovesTheFileAndIgnoresAMissingOne() {
        FileStorage storage = storage();
        storage.store("a.pdf", stream("content"));

        storage.delete("a.pdf");
        storage.delete("a.pdf");

        assertThat(directory.resolve("files/a.pdf")).doesNotExist();
    }

    @Test
    void openingAMissingFileFails() {
        assertThatThrownBy(() -> storage().open("missing.pdf")).isInstanceOf(StorageException.class);
    }

    @Test
    void refusesKeysOutsideTheDirectory() {
        FileStorage storage = storage();

        assertThatThrownBy(() -> storage.store("../escape.pdf", stream("content")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("sub/dir.pdf", stream("content")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("../../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("escape.pdf")).doesNotExist();
    }

    @Test
    void reportsWhetherTheDirectoryCanBeUsed() throws IOException {
        assertThat(storage().isAvailable()).isTrue();

        Path file = Files.createFile(directory.resolve("not-a-directory"));
        assertThat(new FileStorage(file).isAvailable()).isFalse();
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
