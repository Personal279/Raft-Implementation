package com.draw.replica.raft;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReplicationService {

    private final LogStore logStore;
    private final NodeStateStore nodeStateStore;
    private final RaftNode raftNode;
    private final RestTemplate restTemplate;
    private final Map<String, Integer> nextIndex = new ConcurrentHashMap<>();

    public ReplicationService(LogStore logStore, NodeStateStore nodeStateStore,
                              RaftNode raftNode, RestTemplate restTemplate) {
        this.logStore = logStore;
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
        this.restTemplate = restTemplate;
    }

    /**
     * Serializes writes through this leader so concurrent client requests do
     * not race while appending, replicating, and advancing commitIndex.
     */
    public synchronized void replicate(StrokeEvent event) {
        if (!"LEADER".equals(raftNode.getRole())) {
            System.out.println("[REPLICATION REJECTED] Node " + raftNode.getNodeId()
                    + " is not the leader; known leader: " + raftNode.getKnownLeaderId());
            return;
        }

        int term = nodeStateStore.getCurrentTerm();
        LogEntry entry = new LogEntry(term, event);
        logStore.append(entry);
        int lastIndex = logStore.getLastIndex();

        for (String peer : raftNode.getPeers()) {
            nextIndex.putIfAbsent(peer, lastIndex);
        }

        int acknowledgements = 1;
        for (String peer : raftNode.getPeers()) {
            if (replicateToPeer(peer, lastIndex, term)) {
                acknowledgements++;
            }
        }

        int clusterSize = raftNode.getPeers().size() + 1;
        int majority = clusterSize / 2 + 1;

        if (acknowledgements >= majority
                && "LEADER".equals(raftNode.getRole())
                && nodeStateStore.getCurrentTerm() == term) {
            raftNode.advanceCommitIndex(lastIndex);
            System.out.println("[COMMITTED] Leader " + raftNode.getNodeId()
                    + " committed index " + lastIndex + " in term " + term
                    + ". Acknowledgements: " + acknowledgements + "/" + clusterSize);
        } else {
            System.out.println("[UNCOMMITTED] Entry at index " + lastIndex
                    + " did not reach a valid majority. Acknowledgements: "
                    + acknowledgements + "/" + clusterSize);
        }
    }

    private boolean replicateToPeer(String peer, int targetLastIndex, int leaderTerm) {
        int ni = nextIndex.getOrDefault(peer, 0);

        while (ni >= 0) {
            if (!"LEADER".equals(raftNode.getRole())
                    || nodeStateStore.getCurrentTerm() != leaderTerm) {
                return false;
            }

            int prevLogIndex = ni - 1;
            int prevLogTerm = 0;
            if (prevLogIndex >= 0 && prevLogIndex < logStore.size()) {
                prevLogTerm = logStore.get(prevLogIndex).getTerm();
            }

            List<LogEntry> entriesToSend = logStore.getFrom(ni);
            AppendEntriesRequest request = new AppendEntriesRequest();
            request.setTerm(leaderTerm);
            request.setLeaderId(raftNode.getNodeId());
            request.setPrevLogIndex(prevLogIndex);
            request.setPrevLogTerm(prevLogTerm);
            request.setEntries(entriesToSend);

            // Only advertise entries already committed by the leader.
            request.setLeaderCommit(raftNode.getCommitIndex());

            try {
                AppendEntriesResponse response = restTemplate.postForObject(
                        "http://" + peer + "/raft/append",
                        request,
                        AppendEntriesResponse.class
                );

                if (response == null) return false;

                int currentTerm = nodeStateStore.getCurrentTerm();
                if (response.getTerm() > currentTerm) {
                    nodeStateStore.setCurrentTerm(response.getTerm());
                    raftNode.setRole("FOLLOWER");
                    raftNode.setKnownLeaderId(null);
                    nodeStateStore.setVotedFor(null);
                    return false;
                }

                if (response.getTerm() < leaderTerm) {
                    return false;
                }

                if (response.isSuccess()) {
                    nextIndex.put(peer, targetLastIndex + 1);
                    return true;
                }

                ni--;
                nextIndex.put(peer, Math.max(0, ni));
            } catch (Exception e) {
                System.out.println("[REPLICATION ERROR] Failed to replicate to peer "
                        + peer + ": " + e.getMessage());
                return false;
            }
        }

        return false;
    }

    public synchronized void syncPeer(String peer) {
        nextIndex.put(peer, 0);
        replicateToPeer(peer, logStore.getLastIndex(), nodeStateStore.getCurrentTerm());
    }
}
