package com.draw.gateway.websocket;

import com.draw.gateway.model.StrokeEvent;
import com.draw.gateway.service.LeaderService;

import tools.jackson.databind.ObjectMapper;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class DrawingSocketHandler extends TextWebSocketHandler {

    private final LeaderService leaderService;

    private final ObjectMapper mapper = new ObjectMapper();

    public DrawingSocketHandler(LeaderService leaderService) {
        this.leaderService = leaderService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        StrokeEvent event = mapper.readValue(message.getPayload(), StrokeEvent.class);

        try {
            leaderService.sendStroke(event);
        } catch (Exception e) {
            System.out.println("[GATEWAY] Failed to send to leader, rediscovering...");
            leaderService.invalidateLeader();
            leaderService.discoverLeader();
            leaderService.sendStroke(event);
        }
    }
}