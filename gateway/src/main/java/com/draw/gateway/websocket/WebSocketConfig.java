package com.draw.gateway.websocket;

import com.draw.gateway.service.LeaderService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LeaderService leaderService;

    public WebSocketConfig(LeaderService leaderService) {
        this.leaderService = leaderService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        registry.addHandler(
                new DrawingSocketHandler(leaderService),
                "/draw"
        ).setAllowedOrigins("*");

    }
}