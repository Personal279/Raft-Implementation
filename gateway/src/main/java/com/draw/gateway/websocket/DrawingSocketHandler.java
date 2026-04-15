////package com.draw.gateway.websocket;
////
////import com.draw.gateway.model.StrokeEvent;
////import com.draw.gateway.service.LeaderService;
////
////import tools.jackson.databind.ObjectMapper;
////
////import org.springframework.web.socket.*;
////import org.springframework.web.socket.handler.TextWebSocketHandler;
////
////public class DrawingSocketHandler extends TextWebSocketHandler {
////
////    private final LeaderService leaderService;
////
////    private final ObjectMapper mapper = new ObjectMapper();
////
////    public DrawingSocketHandler(LeaderService leaderService) {
////        this.leaderService = leaderService;
////    }
////
////    @Override
////    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
////
////        StrokeEvent event = mapper.readValue(message.getPayload(), StrokeEvent.class);
////
////        try {
////            leaderService.sendStroke(event);
////        } catch (Exception e) {
////            System.out.println("[GATEWAY] Failed to send to leader, rediscovering...");
////            leaderService.invalidateLeader();
////            leaderService.discoverLeader();
////            leaderService.sendStroke(event);
////        }
////    }
////}
//
//
//
//package com.draw.gateway.websocket;
//
//import com.draw.gateway.model.StrokeEvent;
//import com.draw.gateway.service.LeaderService;
//
//import tools.jackson.databind.ObjectMapper;
//
//import org.springframework.web.socket.*;
//import org.springframework.web.socket.handler.TextWebSocketHandler;
//
//import java.util.Collections;
//import java.util.HashSet;
//import java.util.Set;
//
//public class DrawingSocketHandler extends TextWebSocketHandler {
//
//    private final LeaderService leaderService;
//    private final ObjectMapper mapper = new ObjectMapper();
//
//    private static final Set<WebSocketSession> sessions =
//            Collections.synchronizedSet(new HashSet<>());
//
//    public DrawingSocketHandler(LeaderService leaderService) {
//        this.leaderService = leaderService;
//    }
//
//    @Override
//    public void afterConnectionEstablished(WebSocketSession session) {
//        sessions.add(session);
//        System.out.println("[GATEWAY] Client connected: " + session.getId());
//    }
//
//    @Override
//    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
//        sessions.remove(session);
//        System.out.println("[GATEWAY] Client disconnected: " + session.getId());
//    }
//
//    @Override
//    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
//
//        StrokeEvent event = mapper.readValue(message.getPayload(), StrokeEvent.class);
//
//        try {
//            leaderService.sendStroke(event);
//        } catch (Exception e) {
//            System.out.println("[GATEWAY] Failed to send to leader, rediscovering...");
//            leaderService.invalidateLeader();
//            leaderService.discoverLeader();
//            leaderService.sendStroke(event);
//        }
//
//        // Broadcast to all connected clients
//        String payload = mapper.writeValueAsString(event);
//        synchronized (sessions) {
//            for (WebSocketSession s : sessions) {
//                if (s.isOpen()) {
//                    s.sendMessage(new TextMessage(payload));
//                }
//            }
//        }
//    }
//}


package com.draw.gateway.websocket;

import com.draw.gateway.model.StrokeEvent;
import com.draw.gateway.service.LeaderService;

import tools.jackson.databind.ObjectMapper;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.*;
import java.util.*;

public class DrawingSocketHandler extends TextWebSocketHandler {

    private final LeaderService leaderService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Set<WebSocketSession> sessions =
            Collections.synchronizedSet(new HashSet<>());

    // ✅ File path for persistence
    private static final String FILE_PATH = "canvas.json";

    // ✅ In-memory canvas state
    private static final List<StrokeEvent> canvasState =
            Collections.synchronizedList(new ArrayList<>());

    public DrawingSocketHandler(LeaderService leaderService) {
        this.leaderService = leaderService;
        loadFromFile(); // ✅ load previous data on startup
    }
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            sessions.add(session);
            System.out.println("[GATEWAY] Client connected: " + session.getId());

            try {
                // ✅ Replay all strokes like real-time events
                for (StrokeEvent event : canvasState) {
                    String payload = mapper.writeValueAsString(event);
                    session.sendMessage(new TextMessage(payload));
                }
                System.out.println("[REPLAY] Sent " + canvasState.size() + " strokes");
            } catch (Exception e) {
                System.out.println("[ERROR] Replay failed");
            }
        }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("[GATEWAY] Client disconnected: " + session.getId());
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

        // ✅ Save to in-memory state
        canvasState.add(event);

        // ✅ Persist to file
        saveToFile();

        // ✅ Broadcast to all connected clients
        String payload = mapper.writeValueAsString(event);
        synchronized (sessions) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(payload));
                }
            }
        }
    }

    // ✅ Load data from file on startup
    private void loadFromFile() {
        try {
            Path path = Paths.get(FILE_PATH);
            if (Files.exists(path)) {
                String content = Files.readString(path);
                if (!content.isEmpty()) {
                    StrokeEvent[] events = mapper.readValue(content, StrokeEvent[].class);
                    canvasState.addAll(Arrays.asList(events));
                }
                System.out.println("[LOAD] Canvas loaded from file");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Loading file failed");
        }
    }

    // ✅ Save data to file
    private void saveToFile() {
        try {
            String json = mapper.writeValueAsString(canvasState);
            Files.writeString(Paths.get(FILE_PATH), json);
        } catch (Exception e) {
            System.out.println("[ERROR] Saving file failed");
        }
    }
}