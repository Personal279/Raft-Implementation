package com.draw.replica.controller;

import com.draw.replica.model.VoteRequest;
import com.draw.replica.model.VoteResponse;
import com.draw.replica.raft.ElectionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/raft")
public class VoteController {

    private final ElectionService electionService;

    public VoteController(ElectionService electionService) {
        this.electionService = electionService;
    }

    @PostMapping("/vote")
    public VoteResponse requestVote(@RequestBody VoteRequest request) {
        return electionService.handleVoteRequest(request);
    }
}