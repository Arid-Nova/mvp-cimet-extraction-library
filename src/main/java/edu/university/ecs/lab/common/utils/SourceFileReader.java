package edu.university.ecs.lab.common.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared bound for source parsers, matching the JavaScript adapter's per-file 5 MiB limit. */
public final class SourceFileReader {
    public static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    private SourceFileReader() { }

    public static byte[] read(Path source) throws IOException {
        if (Files.size(source) > MAX_SOURCE_BYTES) throw tooLarge();
        try (InputStream stream = Files.newInputStream(source)) {
            // Bound the read itself as well as stat(), so concurrent growth cannot defeat the limit.
            byte[] bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) throw tooLarge();
            return bytes;
        }
    }

    private static IOException tooLarge() {
        return new IOException("SOURCE_FILE_TOO_LARGE: source exceeds the 5 MiB parser limit");
    }
}
