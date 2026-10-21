package com.draw.replica.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Bounds Raft RPC latency so an unavailable peer cannot stall elections,
 * heartbeats, or writes long enough to destabilize the cluster.
 */
@Configuration
public class RaftHttpClientConfig {

    @Bean
    public RestTemplate raftRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(150));
        requestFactory.setReadTimeout(Duration.ofMillis(250));
        return new RestTemplate(requestFactory);
    }
}
