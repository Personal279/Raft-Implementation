package com.draw.replica.model;

public class LogEntry {
    private int term;
    private StrokeEvent event;

    public LogEntry() { }

    public LogEntry(int term, StrokeEvent event) {
        this.term = term;
        this.event = event;
    }

    public int getTerm() { return term; }
    public void setTerm(int term) { this.term = term; }
    public StrokeEvent getEvent() { return event; }
    public void setEvent(StrokeEvent event) { this.event = event; }
}
