package com.example.kafkadup.controller;

import com.example.kafkadup.config.TcpProxy;
import com.example.kafkadup.service.ProducerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/")
public class TestController {

    private final ProducerService producer;
    private final TcpProxy tcpProxy;

    public TestController(ProducerService producer, TcpProxy tcpProxy) {
        this.producer = producer;
        this.tcpProxy = tcpProxy;
    }

    /**
     * Send a message with optional key param.
     * POST /send?key=order-123
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(@RequestParam(value = "key", required = false) String key) {
        String actualKey = producer.sendOneWithKey(key);
        return ResponseEntity.ok(Map.of("key", actualKey));
    }

    /**
     * Block upstream (broker -> producer) so ACKs are dropped.
     * POST /proxy/block
     */
    @PostMapping("/proxy/block")
    public ResponseEntity<String> blockProxy() {
        tcpProxy.setBlockUpstream(true);
        return ResponseEntity.ok("blocked");
    }

    /**
     * Unblock upstream
     * POST /proxy/unblock
     */
    @PostMapping("/proxy/unblock")
    public ResponseEntity<String> unblockProxy() {
        tcpProxy.setBlockUpstream(false);
        return ResponseEntity.ok("unblocked");
    }

    /**
     * Status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "proxyBlocked", tcpProxy.isBlockUpstream()
        ));
    }
}
