package com.draw.replica.controller;

import com.draw.replica.raft.RaftNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/raft")
public class LeaderController {

    private final RaftNode raftNode;

    public LeaderController(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @GetMapping("/leader")
    public Map<String, String> getLeader() {
        return Map.of(
            "nodeId", raftNode.getNodeId(),
            "role", raftNode.getRole(),
            "leader", raftNode.getKnownLeaderId() != null ? raftNode.getKnownLeaderId() : ""
        );
    }
}