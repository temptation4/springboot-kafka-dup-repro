package com.example.kafkadup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyConfig {

    // NOTE: ensure the proxy is already started in Application.main before Spring needs it,
    // or manage lifecycle here. For simplicity, this bean creates a TcpProxy that is expected
    // to be already started externally (we start it in Application.main).
    @Bean
    public TcpProxy tcpProxy() {
        // return a stub; Application will have started a separate TcpProxy instance used by docs.
        // But to let the controller autowire, return a proxy that can be managed.
        return new TcpProxy(29099, "127.0.0.1", 9092);
    }
}
