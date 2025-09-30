package com.example.kafkadup;

import com.example.kafkadup.config.TcpProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.*;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) throws Exception {
        KafkaContainer kafka = null;
        TcpProxy proxy = null;
        ConfigurableApplicationContext ctx = null;

        try {
            // 1) Start KafkaContainer (Testcontainers)
            DockerImageName kafkaImage = DockerImageName.parse("confluentinc/cp-kafka:7.4.0");
            kafka = new KafkaContainer(kafkaImage);
            kafka.start();

            // Testcontainers provides a bootstrap string; normalize and parse host:port
            String bootstrap = kafka.getBootstrapServers(); // e.g. "PLAINTEXT://172.17.0.2:9092" or "localhost:58258"
            log.info("KafkaContainer bootstrap string = {}", bootstrap);

            String bs = bootstrap;
            if (bs.contains("://")) {
                bs = bs.substring(bs.indexOf("://") + 3);
            }
            String firstBroker = bs.split(",")[0].trim();
            int colon = firstBroker.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("Cannot parse bootstrap address: " + firstBroker);
            }
            String brokerHost = firstBroker.substring(0, colon);
            int brokerPort = Integer.parseInt(firstBroker.substring(colon + 1));
            log.info("Resolved Kafka host={} port={}", brokerHost, brokerPort);

            // 2) Start TcpProxy on ephemeral local port (0 = OS chooses free port)
            proxy = new TcpProxy(0, brokerHost, brokerPort);
            proxy.start();

            // after start(), fetch the actual listen port
            int proxyPort = proxy.getListenPort();
            if (proxyPort <= 0) {
                throw new IllegalStateException("TcpProxy failed to bind to a port");
            }
            log.info("Started TcpProxy listening on 127.0.0.1:{} forwarding to {}:{}", proxyPort, brokerHost, brokerPort);

            // 3) Prepare SpringApplication and register the running TcpProxy instance as a singleton bean
            SpringApplication app = new SpringApplication(Application.class);
            app.setBannerMode(Banner.Mode.OFF);

            // Ensure Spring Kafka will use the proxy for bootstrap
            System.setProperty("spring.kafka.bootstrap-servers", "127.0.0.1:" + proxyPort);

            TcpProxy finalProxy1 = proxy;
            ApplicationContextInitializer<GenericApplicationContext> initializer = applicationContext -> {
                // Register the running TcpProxy instance so controllers / services can @Autowired it
                applicationContext.getBeanFactory().registerSingleton("tcpProxy", finalProxy1);
                log.info("Registered TcpProxy singleton into Spring context as bean name 'tcpProxy'");
            };
            app.addInitializers(initializer);

            // 4) Start Spring context
            ctx = app.run(args);
            log.info("Spring context started, beans loaded: {}", ctx.getBeanDefinitionCount());

            // 5) Warmup: get ProducerService and send a message to ensure wiring works
            try {
                var producer = ctx.getBean(com.example.kafkadup.service.ProducerService.class);
                log.info("Sending warmup message...");
                producer.sendOneWithKey("warmup-" + System.currentTimeMillis());
                TimeUnit.SECONDS.sleep(2);
            } catch (Exception e) {
                log.warn("Warmup send failed: {}", e.getMessage(), e);
            }

            log.info("Application started. Use HTTP endpoints to /proxy/block, /proxy/unblock and /send?key=...");
            // Keep app alive so REST endpoints remain available
            Thread.currentThread().join();

        } finally {
            // Register shutdown hook to close things cleanly if JVM exits
            KafkaContainer finalKafka = kafka;
            TcpProxy finalProxy = proxy;
            ConfigurableApplicationContext finalCtx = ctx;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown hook triggered: stopping components...");
                try {
                    if (finalCtx != null) {
                        finalCtx.close();
                        log.info("Spring context closed.");
                    }
                } catch (Exception e) {
                    log.warn("Error closing Spring context", e);
                }
                try {
                    if (finalProxy != null) {
                        finalProxy.stop();
                        log.info("TcpProxy stopped.");
                    }
                } catch (Exception e) {
                    log.warn("Error stopping TcpProxy", e);
                }
                try {
                    if (finalKafka != null) {
                        finalKafka.stop();
                        log.info("KafkaContainer stopped.");
                    }
                } catch (Exception e) {
                    log.warn("Error stopping KafkaContainer", e);
                }
                log.info("Shutdown complete.");
            }));
        }
    }
}
