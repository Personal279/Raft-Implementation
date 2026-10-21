package com.draw.replica.raft;

import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import com.draw.replica.model.VoteRequest;
import com.draw.replica.model.VoteResponse;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

class ElectionServiceTest {

    private NodeStateStore state;
    private LogStore log;
    private RaftNode node;
    private ElectionService electionService;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("NODE_ID", "node-1")
                .withProperty("PEERS", "");
        state = new NodeStateStore();
        log = new LogStore();
        node = new RaftNode(env, state, log);
        electionService = new ElectionService(state, node, log, new RestTemplate());
    }

    @Test
    void higherTermRequestFromOutOfDateCandidateDoesNotResetElectionTimer() {
        state.setCurrentTerm(3);
        log.append(entry(3, "local-entry"));
        node.setLastHeartbeatTime(12345L);

        VoteRequest request = new VoteRequest();
        request.setTerm(4);
        request.setCandidateId("stale-candidate");
        request.setLastLogIndex(0);
        request.setLastLogTerm(1);

        VoteResponse response = electionService.handleVoteRequest(request);

        assertFalse(response.isVoteGranted());
        assertEquals(4, state.getCurrentTerm());
        assertNull(state.getVotedFor());
        assertEquals(12345L, node.getLastHeartbeatTime());
    }

    @Test
    void grantedVoteResetsElectionTimer() {
        state.setCurrentTerm(2);
        node.setLastHeartbeatTime(12345L);

        VoteRequest request = new VoteRequest();
        request.setTerm(2);
        request.setCandidateId("candidate");
        request.setLastLogIndex(-1);
        request.setLastLogTerm(0);

        VoteResponse response = electionService.handleVoteRequest(request);

        assertTrue(response.isVoteGranted());
        assertEquals("candidate", state.getVotedFor());
        assertTrue(node.getLastHeartbeatTime() > 12345L);
    }

    @Test
    void failedElectionAttemptRestartsElectionTimer() {
        node.setLastHeartbeatTime(0L);

        electionService.triggerElection();

        assertEquals("FOLLOWER", node.getRole());
        assertTrue(node.getLastHeartbeatTime() > 0L);
    }

    private static LogEntry entry(int term, String type) {
        StrokeEvent event = new StrokeEvent();
        event.setType(type);
        return new LogEntry(term, event);
    }
}
