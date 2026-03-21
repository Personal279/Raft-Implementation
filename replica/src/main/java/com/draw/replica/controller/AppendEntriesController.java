package com.draw.replica.controller;

import com.draw.replica.model.AppendEntriesRequest;
import com.draw.replica.model.AppendEntriesResponse;
import com.draw.replica.raft.RaftNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/raft")
public class AppendEntriesController {

    private final RaftNode raftNode;

    public AppendEntriesController(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @PostMapping("/append")
    public AppendEntriesResponse appendEntries(@RequestBody AppendEntriesRequest request) {
        return raftNode.handleAppendEntries(request);
    }
}