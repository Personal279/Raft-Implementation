package com.draw.replica.storage;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

@Component
public class NodeStateStore {
    private int currentTerm = 0;
    private String votedFor = null;
    private int commitIndex = -1;
    private final Path storageFile;

    public NodeStateStore() { this(configuredDataDirectory()); }

    public NodeStateStore(Path dataDirectory) {
        this.storageFile = dataDirectory == null ? null : dataDirectory.resolve("raft-state.properties");
        load();
    }

    private static Path configuredDataDirectory() {
        String configured = System.getenv("RAFT_DATA_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    public synchronized int getCurrentTerm() { return currentTerm; }

    public synchronized void setCurrentTerm(int term) {
        if (term < currentTerm) throw new IllegalArgumentException("Raft term cannot move backwards");
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
            persist();
        }
    }

    public synchronized void incrementTerm() {
        currentTerm++;
        votedFor = null;
        persist();
    }

    public synchronized String getVotedFor() { return votedFor; }

    public synchronized void setVotedFor(String votedFor) {
        this.votedFor = votedFor;
        persist();
    }

    public synchronized int getCommitIndex() { return commitIndex; }

    public synchronized void setCommitIndex(int requestedIndex) {
        if (requestedIndex < commitIndex) throw new IllegalArgumentException("Commit index cannot move backwards");
        if (requestedIndex > commitIndex) {
            commitIndex = requestedIndex;
            persist();
        }
    }

    private void load() {
        if (storageFile == null || !Files.exists(storageFile)) return;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(storageFile)) {
            properties.load(input);
            currentTerm = Integer.parseInt(properties.getProperty("currentTerm", "0"));
            votedFor = properties.getProperty("votedFor");
            commitIndex = Integer.parseInt(properties.getProperty("commitIndex", "-1"));
            if (currentTerm < 0 || commitIndex < -1) throw new IllegalStateException("Invalid persisted Raft state in " + storageFile);
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("Could not load persisted Raft state from " + storageFile, e);
        }
    }

    private void persist() {
        if (storageFile == null) return;
        Path temporaryFile = null;
        Properties properties = new Properties();
        properties.setProperty("currentTerm", Integer.toString(currentTerm));
        properties.setProperty("commitIndex", Integer.toString(commitIndex));
        if (votedFor != null) properties.setProperty("votedFor", votedFor);
        try {
            Files.createDirectories(storageFile.getParent());
            temporaryFile = Files.createTempFile(storageFile.getParent(), "raft-state-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "Raft persistent state");
            }
            try {
                Files.move(temporaryFile, storageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Raft state to " + storageFile, e);
        } finally {
            if (temporaryFile != null) try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
        }
    }
}
