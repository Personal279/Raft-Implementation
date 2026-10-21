package com.draw.replica.raft;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.model.LogEntry;
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
    private volatile int commitIndex = -1;

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

    public synchronized String getRole() { return role; }

    public synchronized void setRole(String role) { this.role = role; }

    public synchronized long getLastHeartbeatTime() { return lastHeartbeatTime; }

    public synchronized void setLastHeartbeatTime(long time) { this.lastHeartbeatTime = time; }

    public synchronized long getElectionTimeout() { return electionTimeout; }

    public synchronized void resetElectionTimeout() { this.electionTimeout = generateTimeout(); }

    public synchronized String getKnownLeaderId() { return knownLeaderId; }

    public synchronized void setKnownLeaderId(String leaderId) { this.knownLeaderId = leaderId; }

    public int getCommitIndex() { return commitIndex; }

    /**
     * Advances commitIndex monotonically and never beyond the local log.
     */
    public synchronized int advanceCommitIndex(int requestedIndex) {
        int lastLogIndex = logStore.getLastIndex();
        if (requestedIndex > commitIndex && lastLogIndex >= 0) {
            commitIndex = Math.min(requestedIndex, lastLogIndex);
        }
        return commitIndex;
    }

    /**
     * RPC timeouts are capped below this randomized election window. The
     * wider window lets a leader contact a healthy follower even if an earlier
     * peer in its configured list is unavailable.
     */
    private long generateTimeout() {
        return ThreadLocalRandom.current().nextInt(1200, 1800);
    }

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        int currentTerm = nodeStateStore.getCurrentTerm();

        // Reject stale leaders before touching the election timer.
        if (request.getTerm() < currentTerm) {
            return new AppendEntriesResponse(currentTerm, false);
        }

        // A vote is cleared only when entering a newer term, never on a
        // same-term heartbeat from a leader.
        if (request.getTerm() > currentTerm) {
            nodeStateStore.setCurrentTerm(request.getTerm());
            nodeStateStore.setVotedFor(null);
        }

        setRole("FOLLOWER");
        setKnownLeaderId(request.getLeaderId());
        setLastHeartbeatTime(System.currentTimeMillis());
        resetElectionTimeout();

        int prevLogIndex = request.getPrevLogIndex();
        int prevLogTerm = request.getPrevLogTerm();

        if (prevLogIndex >= 0) {
            if (logStore.size() <= prevLogIndex) {
                return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), false);
            }

            if (logStore.get(prevLogIndex).getTerm() != prevLogTerm) {
                // A failed prevLog check must not itself delete data. The
                // leader retries with an earlier prefix; truncate only when
                // an actual conflicting entry is received.
                return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), false);
            }
        }

        List<LogEntry> incoming = request.getEntries();
        if (incoming != null && !incoming.isEmpty()) {
            int incomingOffset = 0;
            int localIndex = prevLogIndex + 1;

            // Keep the matching prefix. At the first term conflict, discard
            // only the conflicting suffix and append the leader's suffix.
            while (incomingOffset < incoming.size() && localIndex < logStore.size()) {
                if (logStore.get(localIndex).getTerm() != incoming.get(incomingOffset).getTerm()) {
                    logStore.truncateFrom(localIndex);
                    break;
                }
                incomingOffset++;
                localIndex++;
            }

            if (incomingOffset < incoming.size()) {
                logStore.appendAll(incoming.subList(incomingOffset, incoming.size()));
            }
        }

        // Followers commit only up to the leader's committed index, bounded
        // by the last entry they now hold.
        if (request.getLeaderCommit() > commitIndex) {
            advanceCommitIndex(request.getLeaderCommit());
        }

        return new AppendEntriesResponse(nodeStateStore.getCurrentTerm(), true);
    }
}
