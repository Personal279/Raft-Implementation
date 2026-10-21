package com.draw.replica.raft;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

@Service
public class HeartbeatService {
    private final ReplicationService replicationService;
    private final NodeStateStore nodeStateStore;
    private final RaftNode raftNode;
    private final LogStore logStore;
    private final RestTemplate restTemplate;

    public HeartbeatService(NodeStateStore nodeStateStore, RaftNode raftNode,
                            LogStore logStore, ReplicationService replicationService,
                            RestTemplate restTemplate) {
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
        this.logStore = logStore;
        this.replicationService = replicationService;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedRate = 75)
    public void sendHeartbeat() {
        if (!"LEADER".equals(raftNode.getRole())) return;

        int lastIndex = logStore.getLastIndex();
        int lastTerm = lastIndex >= 0 ? logStore.get(lastIndex).getTerm() : 0;
        int leaderTerm = nodeStateStore.getCurrentTerm();

        for (String peer : raftNode.getPeers()) {
            if (!"LEADER".equals(raftNode.getRole())
                    || nodeStateStore.getCurrentTerm() != leaderTerm) {
                return;
            }

            try {
                AppendEntriesRequest request = new AppendEntriesRequest();
                request.setTerm(leaderTerm);
                request.setLeaderId(raftNode.getNodeId());
                request.setPrevLogIndex(lastIndex);
                request.setPrevLogTerm(lastTerm);
                request.setEntries(List.of());
                request.setLeaderCommit(raftNode.getCommitIndex());

                AppendEntriesResponse response = restTemplate.postForObject(
                        "http://" + peer + "/raft/append",
                        request,
                        AppendEntriesResponse.class
                );

                if (response == null) continue;

                if (response.getTerm() > nodeStateStore.getCurrentTerm()) {
                    nodeStateStore.setCurrentTerm(response.getTerm());
                    nodeStateStore.setVotedFor(null);
                    raftNode.setRole("FOLLOWER");
                    raftNode.setKnownLeaderId(null);
                    return;
                }

                if (!response.isSuccess()) {
                    System.out.println("[SYNC] Peer " + peer
                            + " rejected heartbeat/log prefix; syncing...");
                    replicationService.syncPeer(peer);
                }
            } catch (Exception e) {
                System.out.println("[HEARTBEAT ERROR] Peer " + peer + ": " + e.getMessage());
            }
        }
    }
}
