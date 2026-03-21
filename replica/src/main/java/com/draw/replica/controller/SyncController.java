package com.draw.replica.controller;

import com.draw.replica.model.StrokeEvent;
import com.draw.replica.raft.ReplicationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/draw")
public class SyncController {

    private final ReplicationService replicationService;

    public SyncController(ReplicationService replicationService) {
        this.replicationService = replicationService;
    }

    @PostMapping("/stroke")
    public void receiveStroke(@RequestBody StrokeEvent event) {

        replicationService.replicate(event);

    }
}