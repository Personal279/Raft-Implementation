package com.draw.replica.storage;

import com.draw.replica.model.LogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LogStore {

    private final List<LogEntry> logs = new ArrayList<>();

    public synchronized void append(LogEntry entry) {
        logs.add(entry);
    }

    public synchronized void appendAll(List<LogEntry> entries) {
        logs.addAll(entries);
    }

    public synchronized void truncateFrom(int index) {
        logs.subList(index, logs.size()).clear();
    }

    public synchronized List<LogEntry> getFrom(int fromIndex) {
        if (fromIndex >= logs.size()) return List.of();
        return new ArrayList<>(logs.subList(fromIndex, logs.size()));
    }

    public synchronized List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public synchronized int getLastIndex() {
        return logs.size() - 1;
    }

    public synchronized int size() {
        return logs.size();
    }

    public synchronized LogEntry get(int index) {
        return logs.get(index);
    }
}