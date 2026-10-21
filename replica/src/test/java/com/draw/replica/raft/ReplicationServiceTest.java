package com.draw.replica.raft;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.model.StrokeEvent;
import com.draw.replica.storage.LogStore;
import com.draw.replica.storage.NodeStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplicationServiceTest {

    @Test
    void commitsWhenOnePeerIsUnavailableButTheOtherPeerAcknowledges() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("NODE_ID", "node-1")
                .withProperty("PEERS", "peer-down:8081,peer-up:8082");
        NodeStateStore state = new NodeStateStore();
        state.setCurrentTerm(1);
        LogStore log = new LogStore();
        RaftNode node = new RaftNode(env, state, log);
        node.setRole("LEADER");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(
                eq("http://peer-down:8081/raft/append"),
                any(AppendEntriesRequest.class),
                eq(AppendEntriesResponse.class)))
                .thenThrow(new ResourceAccessException("peer unavailable"));

        when(restTemplate.postForObject(
                eq("http://peer-up:8082/raft/append"),
                any(AppendEntriesRequest.class),
                eq(AppendEntriesResponse.class)))
                .thenReturn(new AppendEntriesResponse(1, true));

        ReplicationService service = new ReplicationService(log, state, node, restTemplate);
        StrokeEvent event = new StrokeEvent();
        event.setType("LINE");

        service.replicate(event);

        assertEquals(1, log.size());
        assertEquals(0, node.getCommitIndex());
    }
}
