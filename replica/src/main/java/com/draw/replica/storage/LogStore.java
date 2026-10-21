package com.draw.replica.storage;

import com.draw.replica.model.LogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class LogStore {
    private final List<LogEntry> logs = new ArrayList<>();
    private final Path storageFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LogStore() { this(configuredDataDirectory()); }

    public LogStore(Path dataDirectory) {
        this.storageFile = dataDirectory == null ? null : dataDirectory.resolve("raft-log.json");
        load();
    }

    private static Path configuredDataDirectory() {
        String configured = System.getenv("RAFT_DATA_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    public synchronized void append(LogEntry entry) {
        logs.add(entry);
        persist();
    }

    public synchronized void appendAll(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        logs.addAll(entries);
        persist();
    }

    public synchronized void truncateFrom(int index) {
        if (index < 0 || index > logs.size()) throw new IllegalArgumentException("Invalid log truncation index: " + index);
        if (index == logs.size()) return;
        logs.subList(index, logs.size()).clear();
        persist();
    }

    public synchronized List<LogEntry> getFrom(int fromIndex) {
        if (fromIndex >= logs.size()) return List.of();
        if (fromIndex < 0) throw new IllegalArgumentException("fromIndex must be non-negative");
        return new ArrayList<>(logs.subList(fromIndex, logs.size()));
    }

    public synchronized List<LogEntry> getLogs() { return new ArrayList<>(logs); }
    public synchronized int getLastIndex() { return logs.size() - 1; }
    public synchronized int size() { return logs.size(); }
    public synchronized LogEntry get(int index) { return logs.get(index); }

    private void load() {
        if (storageFile == null || !Files.exists(storageFile)) return;
        try {
            List<LogEntry> persisted = objectMapper.readValue(storageFile.toFile(), new TypeReference<List<LogEntry>>() {});
            logs.addAll(persisted);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not load persisted Raft log from " + storageFile, e);
        }
    }

    private void persist() {
        if (storageFile == null) return;
        Path temporaryFile = null;
        try {
            Files.createDirectories(storageFile.getParent());
            temporaryFile = Files.createTempFile(storageFile.getParent(), "raft-log-", ".tmp");
            objectMapper.writeValue(temporaryFile.toFile(), logs);
            moveAtomically(temporaryFile, storageFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Raft log to " + storageFile, e);
        } finally {
            if (temporaryFile != null) try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
