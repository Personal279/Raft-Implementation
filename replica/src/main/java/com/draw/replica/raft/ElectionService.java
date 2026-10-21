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
    private final RestTemplate restTemplate;

    public ElectionService(NodeStateStore nodeStateStore, RaftNode raftNode,
                           LogStore logStore, RestTemplate restTemplate) {
        this.nodeStateStore = nodeStateStore;
        this.raftNode = raftNode;
        this.logStore = logStore;
        this.restTemplate = restTemplate;
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

            // A higher term alone is not evidence of a live leader. Do not
            // reset the election timer unless the vote is actually granted.
        }

        String votedFor = nodeStateStore.getVotedFor();
        int myLastIndex = logStore.getLastIndex();
        int myLastTerm = myLastIndex >= 0 ? logStore.get(myLastIndex).getTerm() : 0;

        boolean logIsUpToDate =
                request.getLastLogTerm() > myLastTerm
                        || (request.getLastLogTerm() == myLastTerm
                        && request.getLastLogIndex() >= myLastIndex);

        if ((votedFor == null || votedFor.equals(request.getCandidateId())) && logIsUpToDate) {
            nodeStateStore.setVotedFor(request.getCandidateId());
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
            System.out.println("[VOTE] Node " + raftNode.getNodeId()
                    + " voted for " + request.getCandidateId()
                    + " in term " + request.getTerm());
            return new VoteResponse(nodeStateStore.getCurrentTerm(), true);
        }

        String reason = !logIsUpToDate
                ? "candidate log is less up-to-date"
                : "already voted for " + votedFor;
        System.out.println("[VOTE] Node " + raftNode.getNodeId()
                + " denied vote for " + request.getCandidateId()
                + " in term " + request.getTerm() + ": " + reason);
        return new VoteResponse(nodeStateStore.getCurrentTerm(), false);
    }

    @Scheduled(fixedDelay = 200)
    public void triggerElection() {
        if ("LEADER".equals(raftNode.getRole())) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - raftNode.getLastHeartbeatTime() <= raftNode.getElectionTimeout()) {
            return;
        }

        // Mark the attempt before making network calls. If an RPC is slow,
        // the old heartbeat timestamp must not trigger elections every tick.
        long electionStartTime = System.currentTimeMillis();
        raftNode.setLastHeartbeatTime(electionStartTime);
        raftNode.resetElectionTimeout();

        startElection();

        // A failed election may have spent longer than the timeout waiting
        // for peers. Start a fresh timeout after the attempt, but preserve a
        // heartbeat that arrived from a real leader while the RPCs were in flight.
        if (!"LEADER".equals(raftNode.getRole())
                && raftNode.getLastHeartbeatTime() <= electionStartTime) {
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
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

        if ("LEADER".equals(raftNode.getRole())) {
            return;
        }

        raftNode.setRole("CANDIDATE");
        raftNode.setKnownLeaderId(null);
        nodeStateStore.incrementTerm();
        int currentTerm = nodeStateStore.getCurrentTerm();
        // A candidate votes for itself once in this term.
        nodeStateStore.setVotedFor(raftNode.getNodeId());

        System.out.println("[ELECTION] Node " + raftNode.getNodeId()
                + " starting election for term " + currentTerm + " peers: " + peers);

        int votes = 1;

        for (String peer : peers) {
            if (!"CANDIDATE".equals(raftNode.getRole())
                    || nodeStateStore.getCurrentTerm() != currentTerm) {
                return;
            }

            try {
                VoteRequest request = new VoteRequest();
                request.setTerm(currentTerm);
                request.setCandidateId(raftNode.getNodeId());
                request.setLastLogIndex(logStore.getLastIndex());
                request.setLastLogTerm(
                        logStore.size() > 0 ? logStore.get(logStore.getLastIndex()).getTerm() : 0
                );

                VoteResponse response = restTemplate.postForObject(
                        "http://" + peer + "/raft/vote",
                        request,
                        VoteResponse.class
                );

                if (response == null) {
                    continue;
                }

                if (response.getTerm() > currentTerm) {
                    nodeStateStore.setCurrentTerm(response.getTerm());
                    raftNode.setRole("FOLLOWER");
                    nodeStateStore.setVotedFor(null);
                    raftNode.setKnownLeaderId(null);
                    return;
                }

                if (response.isVoteGranted()) {
                    votes++;
                    System.out.println("[ELECTION] Got vote from " + peer);
                }
            } catch (Exception e) {
                System.out.println("[ELECTION] Failed to reach peer: " + peer
                        + " - " + e.getMessage());
            }
        }

        int clusterSize = peers.size() + 1;
        int majority = clusterSize / 2 + 1;
        System.out.println("[ELECTION] Node " + raftNode.getNodeId()
                + " got " + votes + "/" + clusterSize
                + " votes, majority needed: " + majority);

        if (votes >= majority
                && "CANDIDATE".equals(raftNode.getRole())
                && nodeStateStore.getCurrentTerm() == currentTerm) {
            raftNode.setRole("LEADER");
            raftNode.setKnownLeaderId(raftNode.getNodeId());
            raftNode.setLastHeartbeatTime(System.currentTimeMillis());
            System.out.println("[ELECTION] NODE BECAME LEADER: "
                    + raftNode.getNodeId() + " term: " + currentTerm);
            sendImmediateHeartbeat();
        } else if ("CANDIDATE".equals(raftNode.getRole())
                && nodeStateStore.getCurrentTerm() == currentTerm) {
            // Keep the self-vote: Raft permits at most one vote per term.
            raftNode.setRole("FOLLOWER");
            raftNode.resetElectionTimeout();
        }
    }

    private void sendImmediateHeartbeat() {
        int leaderTerm = nodeStateStore.getCurrentTerm();
        int lastIndex = logStore.getLastIndex();
        int lastTerm = lastIndex >= 0 ? logStore.get(lastIndex).getTerm() : 0;

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

                if (response != null && response.getTerm() > leaderTerm) {
                    nodeStateStore.setCurrentTerm(response.getTerm());
                    nodeStateStore.setVotedFor(null);
                    raftNode.setRole("FOLLOWER");
                    raftNode.setKnownLeaderId(null);
                    return;
                }
            } catch (Exception e) {
                System.out.println("[HEARTBEAT ERROR] Initial heartbeat to "
                        + peer + " failed: " + e.getMessage());
            }
        }
    }
}
