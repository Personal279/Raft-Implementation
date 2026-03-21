package com.draw.replica.model;

public class LogEntry {

    private int term;
    private StrokeEvent event;

    public LogEntry(int term, StrokeEvent event) {
        this.term = term;
        this.event = event;
    }

    public int getTerm() {
        return term;
    }

    public StrokeEvent getEvent() {
        return event;
    }
}