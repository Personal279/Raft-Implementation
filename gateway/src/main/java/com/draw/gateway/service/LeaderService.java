package com.draw.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.draw.gateway.model.StrokeEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class LeaderService {

    @Value("${REPLICAS}")
    private String replicasEnv;

    private volatile String leaderUrl = null;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendStroke(StrokeEvent event) {
        String url = resolveLeaderUrl();
        restTemplate.postForObject(url + "/draw/stroke", event, Void.class);
    }

    private synchronized String resolveLeaderUrl() {
        if (leaderUrl != null) {
            return leaderUrl;
        }
        return discoverLeader();
    }

    public synchronized String discoverLeader() {
        List<String> replicas = Arrays.asList(replicasEnv.split(","));

        for (String replica : replicas) {
            try {
                String url = "http://" + replica + "/raft/leader";
                Map response = restTemplate.getForObject(url, Map.class);

                if (response != null) {
                    String role = (String) response.get("role");
                    String nodeId = (String) response.get("nodeId");
                    String knownLeader = (String) response.get("leader");

                    if ("LEADER".equals(role)) {
                        leaderUrl = "http://" + replica;
                        System.out.println("[GATEWAY] Leader found: " + nodeId + " at " + leaderUrl);
                        return leaderUrl;
                    }

                    if (knownLeader != null && !knownLeader.isEmpty()) {
                        String leaderAddr = resolveAddressForNodeId(knownLeader, replicas);
                        if (leaderAddr != null) {
                            leaderUrl = "http://" + leaderAddr;
                            System.out.println("[GATEWAY] Leader resolved via hint: " + knownLeader + " at " + leaderUrl);
                            return leaderUrl;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[GATEWAY] Could not reach replica: " + replica);
            }
        }

        throw new RuntimeException("[GATEWAY] No leader found in cluster");
    }

    public synchronized void invalidateLeader() {
        System.out.println("[GATEWAY] Leader invalidated, will rediscover on next request");
        leaderUrl = null;
    }

    private String resolveAddressForNodeId(String nodeId, List<String> replicas) {
        for (String replica : replicas) {
            try {
                String url = "http://" + replica + "/raft/leader";
                Map response = restTemplate.getForObject(url, Map.class);
                if (response != null && nodeId.equals(response.get("nodeId"))) {
                    return replica;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}