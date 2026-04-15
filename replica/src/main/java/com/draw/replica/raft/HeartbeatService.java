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

    private final RestTemplate restTemplate = new RestTemplate();

    public HeartbeatService(NodeStateStore nodeStateStore, RaftNode raftNode, LogStore logStore,ReplicationService replicationService) {
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
        this.logStore = logStore;
        this.replicationService = replicationService;
    }

    @Scheduled(fixedRate = 75)
    public void sendHeartbeat() {

        if (!raftNode.getRole().equals("LEADER")) return;

        int lastIndex = logStore.size() - 1;

        int lastTerm = 0;
        if (lastIndex >= 0) {
            lastTerm = logStore.get(lastIndex).getTerm();
        }

        for (String peer : raftNode.getPeers()) {

            try {

                AppendEntriesRequest request = new AppendEntriesRequest();
                request.setTerm(nodeStateStore.getCurrentTerm());
                request.setLeaderId(raftNode.getNodeId());
                request.setPrevLogIndex(lastIndex);
                request.setPrevLogTerm(lastTerm);
                request.setEntries(List.of());
                request.setLeaderCommit(lastIndex);

                AppendEntriesResponse response = restTemplate.postForObject(
                        "http://" + peer + "/raft/append",
                        request,
                        AppendEntriesResponse.class
                );

                 if (response != null && !response.isSuccess()) {
                        System.out.println("[SYNC] Peer " + peer + " behind, syncing...");
                        replicationService.syncPeer(peer);
                    }

            } catch (Exception ignored) {}
        }
    }
}