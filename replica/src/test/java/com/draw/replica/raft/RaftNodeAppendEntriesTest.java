package com.draw.replica.raft;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeAppendEntriesTest {

    private NodeStateStore state;
    private LogStore log;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("NODE_ID", "node-1")
                .withProperty("PEERS", "node-2,node-3");
        state = new NodeStateStore();
        log = new LogStore();
        node = new RaftNode(env, state, log);
    }

    @Test
    void repeatedAppendEntriesDoesNotDuplicateMatchingEntries() {
        state.setCurrentTerm(1);
        List<LogEntry> entries = List.of(entry(1, "a"), entry(1, "b"));
        AppendEntriesRequest request = request(1, -1, 0, entries, -1);

        assertTrue(node.handleAppendEntries(request).isSuccess());
        assertTrue(node.handleAppendEntries(request).isSuccess());

        assertEquals(2, log.size());
        assertEquals("a", log.get(0).getEvent().getType());
        assertEquals("b", log.get(1).getEvent().getType());
    }

    @Test
    void conflictingIncomingEntryTruncatesOnlyConflictingSuffix() {
        state.setCurrentTerm(1);
        log.append(entry(1, "keep"));
        log.append(entry(1, "old-suffix"));

        AppendEntriesRequest request = request(
                2, 0, 1, List.of(entry(2, "replacement")), -1);

        AppendEntriesResponse response = node.handleAppendEntries(request);

        assertTrue(response.isSuccess());
        assertEquals(2, log.size());
        assertEquals("keep", log.get(0).getEvent().getType());
        assertEquals(2, log.get(1).getTerm());
        assertEquals("replacement", log.get(1).getEvent().getType());
    }

    @Test
    void previousLogTermMismatchRejectsWithoutTruncating() {
        state.setCurrentTerm(2);
        log.append(entry(1, "existing"));

        AppendEntriesRequest request = request(
                2, 0, 2, List.of(entry(2, "new")), -1);

        assertFalse(node.handleAppendEntries(request).isSuccess());
        assertEquals(1, log.size());
        assertEquals(1, log.get(0).getTerm());
    }

    @Test
    void staleLeaderDoesNotResetElectionTimer() {
        state.setCurrentTerm(3);
        node.setLastHeartbeatTime(12345L);
        AppendEntriesRequest request = request(2, -1, 0, List.of(), -1);

        assertFalse(node.handleAppendEntries(request).isSuccess());
        assertEquals(12345L, node.getLastHeartbeatTime());
    }

    @Test
    void sameTermHeartbeatDoesNotClearExistingVote() {
        state.setCurrentTerm(3);
        state.setVotedFor("candidate-A");
        AppendEntriesRequest request = request(3, -1, 0, List.of(), -1);

        assertTrue(node.handleAppendEntries(request).isSuccess());
        assertEquals("candidate-A", state.getVotedFor());
    }

    @Test
    void followerCommitIndexIsBoundedByLocalLog() {
        state.setCurrentTerm(1);
        log.append(entry(1, "a"));
        log.append(entry(1, "b"));
        AppendEntriesRequest request = request(1, 1, 1, List.of(), 100);

        assertTrue(node.handleAppendEntries(request).isSuccess());
        assertEquals(1, node.getCommitIndex());
    }

    private static AppendEntriesRequest request(
            int term, int prevIndex, int prevTerm,
            List<LogEntry> entries, int leaderCommit) {
        AppendEntriesRequest request = new AppendEntriesRequest();
        request.setTerm(term);
        request.setLeaderId("leader");
        request.setPrevLogIndex(prevIndex);
        request.setPrevLogTerm(prevTerm);
        request.setEntries(entries);
        request.setLeaderCommit(leaderCommit);
        return request;
    }

    private static LogEntry entry(int term, String type) {
        StrokeEvent event = new StrokeEvent();
        event.setType(type);
        return new LogEntry(term, event);
    }
}
