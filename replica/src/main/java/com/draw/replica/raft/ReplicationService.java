package com.draw.replica.raft;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReplicationService {

    private final LogStore logStore;
    private final NodeStateStore nodeStateStore;
    private final RaftNode raftNode;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, Integer> nextIndex = new HashMap<>();

    public ReplicationService(LogStore logStore, NodeStateStore nodeStateStore, RaftNode raftNode) {
        this.logStore = logStore;
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
    }

    public void replicate(StrokeEvent event) {

        LogEntry entry = new LogEntry(nodeStateStore.getCurrentTerm(), event);
        logStore.append(entry);

        int lastIndex = logStore.size() - 1;

        for (String peer : raftNode.getPeers()) {
            nextIndex.putIfAbsent(peer, lastIndex);
        }

        int success = 1;

        for (String peer : raftNode.getPeers()) {
            if (replicateToPeer(peer, lastIndex)) {
                success++;
            }
        }

        int majority = (raftNode.getPeers().size() + 1) / 2 + 1;

        if (success >= majority) {
            System.out.println("[COMMITTED] Leader " + raftNode.getNodeId() + " committed entry at index " + lastIndex + ". Replicated to " + success + "/" + (raftNode.getPeers().size() + 1) + " nodes.");
        } else {
            System.out.println("[FAILED] Leader " + raftNode.getNodeId() + " could not reach majority. Only " + success + "/" + (raftNode.getPeers().size() + 1) + " nodes responded.");
        }
    }

    private boolean replicateToPeer(String peer, int lastIndex) {

        int ni = nextIndex.getOrDefault(peer, 0);

        while (ni >= 0) {

            int prevLogIndex = ni - 1;
            int prevLogTerm = 0;
            if (prevLogIndex >= 0 && prevLogIndex < logStore.size()) {
                prevLogTerm = logStore.get(prevLogIndex).getTerm();
            }

            List<LogEntry> entriesToSend = logStore.getFrom(ni);

            AppendEntriesRequest request = new AppendEntriesRequest();
            request.setTerm(nodeStateStore.getCurrentTerm());
            request.setLeaderId(raftNode.getNodeId());
            request.setPrevLogIndex(prevLogIndex);
            request.setPrevLogTerm(prevLogTerm);
            request.setEntries(entriesToSend);
            request.setLeaderCommit(lastIndex);

            try {

                AppendEntriesResponse response = restTemplate.postForObject(
                        "http://" + peer + "/raft/append",
                        request,
                        AppendEntriesResponse.class
                );

                if (response == null) return false;

                if (response.getTerm() > nodeStateStore.getCurrentTerm()) {
                    nodeStateStore.setCurrentTerm(response.getTerm());
                    raftNode.setRole("FOLLOWER");
                    nodeStateStore.setVotedFor(null);
                    return false;
                }

                if (response.isSuccess()) {
                    nextIndex.put(peer, lastIndex + 1);
                    System.out.println("[CATCHUP] Peer " + peer + " is now caught up to index " + lastIndex);
                    return true;
                } else {
                    ni--;
                    nextIndex.put(peer, Math.max(0, ni));
                    System.out.println("[CATCHUP] Peer " + peer + " rejected, stepping back to index " + ni);
                }

            } catch (Exception e) {
                System.out.println("[REPLICATION ERROR] Failed to replicate to peer " + peer + ": " + e.getMessage());
                return false;
            }
        }
        
        return false;
    }
    public void syncPeer(String peer) {
        nextIndex.put(peer, 0);
        replicateToPeer(peer, logStore.size() - 1);
    }
}