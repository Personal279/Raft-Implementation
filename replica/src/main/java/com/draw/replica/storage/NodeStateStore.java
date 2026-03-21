package com.draw.replica.storage;

import org.springframework.stereotype.Component;

@Component
public class NodeStateStore {

    private int currentTerm = 0;
    private String votedFor = null;

    public synchronized int getCurrentTerm() {
        return currentTerm;
    }

    public synchronized void setCurrentTerm(int term) {
        this.currentTerm = term;
    }

    public synchronized void incrementTerm() {
        currentTerm++;
    }

    public synchronized String getVotedFor() {
        return votedFor;
    }

    public synchronized void setVotedFor(String votedFor) {
        this.votedFor = votedFor;
    }
}