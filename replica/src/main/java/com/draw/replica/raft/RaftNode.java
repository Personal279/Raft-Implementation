package com.draw.replica.raft;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RaftNode {

    private final Environment env;
    private final NodeStateStore nodeStateStore;
    private final LogStore logStore;

    private volatile String role = "FOLLOWER";
    private volatile long lastHeartbeatTime = System.currentTimeMillis() + 5000;
    private volatile long electionTimeout = generateTimeout();
    private volatile String knownLeaderId = null;

    public RaftNode(Environment env, NodeStateStore nodeStateStore, LogStore logStore) {
        this.env = env;
        this.nodeStateStore = nodeStateStore;
        this.logStore = logStore;
    }

    public String getNodeId() {
        return env.getProperty("NODE_ID");
    }

    public String getPort() {
        return env.getProperty("PORT");
    }

    public List<String> getPeers() {
        String peers = env.getProperty("PEERS");
        if (peers == null || peers.isEmpty()) return List.of();
        return Arrays.asList(peers.split(","));
    }

    public synchronized String getRole() {
        return role;
    }

    public synchronized void setRole(String role) {
        this.role = role;
    }

    public synchronized long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public synchronized void setLastHeartbeatTime(long time) {
        this.lastHeartbeatTime = time;
    }

    public synchronized long getElectionTimeout() {
        return electionTimeout;
    }

    public synchronized void resetElectionTimeout() {
        this.electionTimeout = generateTimeout();
    }

    public synchronized String getKnownLeaderId() {
        return knownLeaderId;
    }

    public synchronized void setKnownLeaderId(String leaderId) {
        this.knownLeaderId = leaderId;
    }

    private long generateTimeout() {
        return ThreadLocalRandom.current().nextInt(500, 800);
    }

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        
        int currentTerm = nodeStateStore.getCurrentTerm();
        setLastHeartbeatTime(System.currentTimeMillis());

        if (request.getTerm() < currentTerm) {
            return new AppendEntriesResponse(currentTerm, false);
        }

        if (request.getTerm() >= currentTerm) {
            nodeStateStore.setCurrentTerm(request.getTerm());
            setRole("FOLLOWER");
            nodeStateStore.setVotedFor(null);
            setKnownLeaderId(request.getLeaderId());
        }


        int prevLogIndex = request.getPrevLogIndex();
        int prevLogTerm = request.getPrevLogTerm();

        if (prevLogIndex >= 0) {
            if (logStore.size() <= prevLogIndex) {
                System.out.println("[CONSISTENCY] Node " + getNodeId() + " missing entries. My size: " + logStore.size() + " leader prevLogIndex: " + prevLogIndex);
                return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), false);
            }

            if (logStore.get(prevLogIndex).getTerm() != prevLogTerm) {
                System.out.println("[CONSISTENCY] Node " + getNodeId() + " term mismatch at index " + prevLogIndex + ". Expected: " + prevLogTerm + " got: " + logStore.get(prevLogIndex).getTerm());
                logStore.truncateFrom(prevLogIndex);
                return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), false);
            }
        }

        if (request.getEntries() != null && !request.getEntries().isEmpty()) {
            logStore.appendAll(request.getEntries());
            System.out.println("[REPLICATED] Node " + getNodeId() + " appended " + request.getEntries().size() + " entries from leader: " + request.getLeaderId() + ". Log size now: " + logStore.size());
        }

        return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), true);
    }
}