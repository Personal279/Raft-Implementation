package com.draw.replica.raft;

import com.draw.replica.model.VoteRequest;
import com.draw.replica.model.VoteResponse;
import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class ElectionService {

    private final NodeStateStore nodeStateStore;
    private final RaftNode raftNode;
    private final LogStore logStore;

    private final RestTemplate restTemplate = new RestTemplate();

    public ElectionService(NodeStateStore nodeStateStore, RaftNode raftNode, LogStore logStore) {
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
        this.logStore = logStore;
    }

    public synchronized VoteResponse handleVoteRequest(VoteRequest request) {

        int currentTerm = nodeStateStore.getCurrentTerm();

        if (request.getTerm() < currentTerm) {
            return new VoteResponse(currentTerm, false);
        }

        if (request.getTerm() > currentTerm) {
            nodeStateStore.setCurrentTerm(request.getTerm());
            nodeStateStore.setVotedFor(null);
            raftNode.setRole("FOLLOWER");
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
        }

        String votedFor = nodeStateStore.getVotedFor();

        int myLastIndex = logStore.size() - 1;
        int myLastTerm = myLastIndex >= 0 ? logStore.get(myLastIndex).getTerm() : 0;

        boolean logIsUpToDate =
                request.getLastLogTerm() > myLastTerm ||
                (request.getLastLogTerm() == myLastTerm && request.getLastLogIndex() >= myLastIndex);

        if ((votedFor == null || votedFor.equals(request.getCandidateId())) && logIsUpToDate) {
            nodeStateStore.setVotedFor(request.getCandidateId());
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
            System.out.println("[VOTE] Node " + raftNode.getNodeId() + " voted for " + request.getCandidateId() + " in term " + request.getTerm());
            return new VoteResponse(nodeStateStore.getCurrentTerm(), true);
        }

        System.out.println("[VOTE] Node " + raftNode.getNodeId() + " denied vote for " + request.getCandidateId() + " in term " + request.getTerm() + " already voted for: " + votedFor);
        return new VoteResponse(nodeStateStore.getCurrentTerm(), false);
    }

    @Scheduled(fixedDelay = 200)
    public void triggerElection() {

        long now = System.currentTimeMillis();

        if (raftNode.getRole().equals("LEADER")) return;

        if (now - raftNode.getLastHeartbeatTime() > raftNode.getElectionTimeout()) {
            startElection();
            raftNode.resetElectionTimeout();
        }
    }

    public void startElection() {

        List<String> peers = raftNode.getPeers();

        if (peers.isEmpty()) {
            System.out.println("[ELECTION] No peers configured for node: " + raftNode.getNodeId());
            raftNode.setRole("FOLLOWER");
            return;
        }

        if (raftNode.getRole().equals("LEADER")) return;

        raftNode.setRole("CANDIDATE");
        nodeStateStore.setVotedFor(raftNode.getNodeId());
        nodeStateStore.incrementTerm();
        int currentTerm = nodeStateStore.getCurrentTerm();

        System.out.println("[ELECTION] Node " + raftNode.getNodeId() + " starting election for term " + currentTerm + " peers: " + peers);

        int votes = 1;

        for (String peer : peers) {

            try {

                VoteRequest request = new VoteRequest();
                request.setTerm(currentTerm);
                request.setCandidateId(raftNode.getNodeId());
                request.setLastLogIndex(logStore.size() - 1);
                request.setLastLogTerm(
                        logStore.size() > 0 ? logStore.get(logStore.size() - 1).getTerm() : 0
                );

                VoteResponse response =
                        restTemplate.postForObject(
                                "http://" + peer + "/raft/vote",
                                request,
                                VoteResponse.class
                        );

                if (response != null) {

                    if (response.getTerm() > currentTerm) {
                        nodeStateStore.setCurrentTerm(response.getTerm());
                        raftNode.setRole("FOLLOWER");
                        nodeStateStore.setVotedFor(null);
                        return;
                    }

                    if (response.isVoteGranted()) {
                        votes++;
                        System.out.println("[ELECTION] Got vote from " + peer);
                    }
                }

            } catch (Exception e) {
                System.out.println("[ELECTION] Failed to reach peer: " + peer + " - " + e.getMessage());
            }
        }

        int clusterSize = peers.size() + 1;
        int majority = clusterSize / 2 + 1;

        System.out.println("[ELECTION] Node " + raftNode.getNodeId() + " got " + votes + "/" + clusterSize + " votes, majority needed: " + majority);

        if (votes >= majority) {
            raftNode.setRole("LEADER");
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
            System.out.println("[ELECTION] NODE BECAME LEADER: " + raftNode.getNodeId() + " term: " + currentTerm);
            sendImmediateHeartbeat();
        } else {
            raftNode.setRole("FOLLOWER");
            nodeStateStore.setVotedFor(null);
            raftNode.resetElectionTimeout();
        }
    }

    private void sendImmediateHeartbeat() {

        for (String peer : raftNode.getPeers()) {

            try {

                AppendEntriesRequest request = new AppendEntriesRequest();
                request.setTerm(nodeStateStore.getCurrentTerm());
                request.setLeaderId(raftNode.getNodeId());
                request.setPrevLogIndex(-1);
                request.setPrevLogTerm(0);
                request.setEntries(List.of());
                request.setLeaderCommit(-1);

                restTemplate.postForObject(
                        "http://" + peer + "/raft/append",
                        request,
                        AppendEntriesResponse.class
                );

            } catch (Exception ignored) {}
        }
    }
}