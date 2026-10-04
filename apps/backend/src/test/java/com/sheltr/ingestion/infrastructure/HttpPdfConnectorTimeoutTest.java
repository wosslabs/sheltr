package com.sheltr.ingestion.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPdfConnectorTimeoutTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);

        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target.pdf", exchange -> {
            byte[] body = "%PDF-1.4".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                release.await(70, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void refusesToFollowRedirectAndReportsStatus() {
        HttpPdfConnector connector = new HttpPdfConnector(Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> connector.fetch(url("/redirect")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 302");
    }

    @Test
    void failsWhenRequestExceedsRequestTimeout() {
        HttpPdfConnector connector = new HttpPdfConnector(Duration.ofSeconds(1), Duration.ofSeconds(1));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> connector.fetch(url("/slow")))
                .isInstanceOf(RuntimeException.class);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);

        assertThat(elapsedSeconds).isLessThan(65);
    }

    @Test
    void defaultConstructorUsesProductionTimeouts() {
        HttpPdfConnector connector = new HttpPdfConnector();

        assertThat(connector.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(connector.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}
