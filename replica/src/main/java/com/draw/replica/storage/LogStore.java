package com.draw.replica.storage;

import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Component
public class LogStore {
    private final List<LogEntry> logs = new ArrayList<>();
    private final Path storageFile;

    public LogStore() {
        this(configuredDataDirectory());
    }

    public LogStore(Path dataDirectory) {
        this.storageFile = dataDirectory == null ? null : dataDirectory.resolve("raft-log.properties");
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
        if (index < 0 || index > logs.size()) {
            throw new IllegalArgumentException("Invalid log truncation index: " + index);
        }
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
        Properties properties = new Properties();
        try (var input = Files.newInputStream(storageFile)) {
            properties.load(input);
            int count = Integer.parseInt(properties.getProperty("count", "0"));
            if (count < 0) throw new IllegalStateException("Negative persisted log count");
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                int term = Integer.parseInt(properties.getProperty(prefix + "term"));
                StrokeEvent event = null;
                if (!Boolean.parseBoolean(properties.getProperty(prefix + "event.null", "false"))) {
                    event = new StrokeEvent();
                    event.setType(readNullable(properties, prefix + "event.type"));
                    event.setX1(Integer.parseInt(properties.getProperty(prefix + "event.x1", "0")));
                    event.setY1(Integer.parseInt(properties.getProperty(prefix + "event.y1", "0")));
                    event.setX2(Integer.parseInt(properties.getProperty(prefix + "event.x2", "0")));
                    event.setY2(Integer.parseInt(properties.getProperty(prefix + "event.y2", "0")));
                    event.setColor(readNullable(properties, prefix + "event.color"));
                }
                logs.add(new LogEntry(term, event));
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("Could not load persisted Raft log from " + storageFile, e);
        }
    }

    private void persist() {
        if (storageFile == null) return;
        Path temporaryFile = null;
        Properties properties = new Properties();
        properties.setProperty("count", Integer.toString(logs.size()));
        for (int i = 0; i < logs.size(); i++) {
            LogEntry entry = logs.get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "term", Integer.toString(entry.getTerm()));
            StrokeEvent event = entry.getEvent();
            properties.setProperty(prefix + "event.null", Boolean.toString(event == null));
            if (event != null) {
                writeNullable(properties, prefix + "event.type", event.getType());
                properties.setProperty(prefix + "event.x1", Integer.toString(event.getX1()));
                properties.setProperty(prefix + "event.y1", Integer.toString(event.getY1()));
                properties.setProperty(prefix + "event.x2", Integer.toString(event.getX2()));
                properties.setProperty(prefix + "event.y2", Integer.toString(event.getY2()));
                writeNullable(properties, prefix + "event.color", event.getColor());
            }
        }
        try {
            Files.createDirectories(storageFile.getParent());
            temporaryFile = Files.createTempFile(storageFile.getParent(), "raft-log-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "Raft log");
            }
            try {
                Files.move(temporaryFile, storageFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Raft log to " + storageFile, e);
        } finally {
            if (temporaryFile != null) try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
        }
    }

    private static void writeNullable(Properties properties, String key, String value) {
        properties.setProperty(key + ".null", Boolean.toString(value == null));
        if (value != null) properties.setProperty(key, value);
    }

    private static String readNullable(Properties properties, String key) {
        if (Boolean.parseBoolean(properties.getProperty(key + ".null", "false"))) return null;
        return properties.getProperty(key);
    }
}
