package com.draw.replica.storage;

import com.draw.replica.model.LogEntry;
import com.draw.replica.model.StrokeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PersistentRaftStateTest {
    @TempDir Path tempDir;

    @Test
    void logEntriesSurviveStoreRecreation() {
        LogStore first = new LogStore(tempDir);
        StrokeEvent event = new StrokeEvent();
        event.setType("line");
        event.setX1(10);
        event.setY1(20);
        first.append(new LogEntry(4, event));

        LogStore restarted = new LogStore(tempDir);
        assertEquals(1, restarted.size());
        assertEquals(0, restarted.getLastIndex());
        assertEquals(4, restarted.get(0).getTerm());
        assertEquals("line", restarted.get(0).getEvent().getType());
        assertEquals(10, restarted.get(0).getEvent().getX1());
        assertEquals(20, restarted.get(0).getEvent().getY1());
    }

    @Test
    void termVoteAndCommitIndexSurviveStoreRecreation() {
        NodeStateStore first = new NodeStateStore(tempDir);
        first.setCurrentTerm(7);
        first.setVotedFor("node-2");
        first.setCommitIndex(3);

        NodeStateStore restarted = new NodeStateStore(tempDir);
        assertEquals(7, restarted.getCurrentTerm());
        assertEquals("node-2", restarted.getVotedFor());
        assertEquals(3, restarted.getCommitIndex());
    }

    @Test
    void higherTermClearsPersistedVoteAndTermCannotMoveBackwards() {
        NodeStateStore state = new NodeStateStore(tempDir);
        state.setCurrentTerm(3);
        state.setVotedFor("node-1");
        state.setCurrentTerm(4);

        assertNull(state.getVotedFor());
        assertThrows(IllegalArgumentException.class, () -> state.setCurrentTerm(2));
        NodeStateStore restarted = new NodeStateStore(tempDir);
        assertEquals(4, restarted.getCurrentTerm());
        assertNull(restarted.getVotedFor());
    }
}
