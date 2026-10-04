package com.sheltr.ai.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheltr.SheltrBackendApplication;
import com.sheltr.ai.domain.Conversation;
import com.sheltr.ai.domain.ConversationId;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.domain.MessageRole;
import com.sheltr.knowledge.application.ChunkEmbeddingBackfillService;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.JooqChunkRepository;
import io.lumina.web.LuminaServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@ActiveProfiles("gemini")
@SpringBootTest(
        classes = SheltrBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lumina.port=0", "spring.ai.google.genai.chat.options.temperature=0.0"})
class LuminaChatEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static volatile boolean embeddingsBackfilled = false;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    LuminaServer luminaServer;

    @Autowired
    DataSource dataSource;

    @Autowired
    EmbeddingGenerator embeddingGenerator;

    @Autowired
    ConversationRepository conversationRepository;

    @BeforeEach
    synchronized void backfillEmbeddingsForAllSeededChunksOnce() {
        if (embeddingsBackfilled) {
            return;
        }
        int backfilled = new ChunkEmbeddingBackfillService(new JooqChunkRepository(dataSource), embeddingGenerator)
                .backfillAll();
        assertThat(backfilled).isEqualTo(32);
        embeddingsBackfilled = true;
    }

    @Test
    void inCorpusQuestionStreamsCitationBearingAnswerViaGemini() throws Exception {
        CollectingListener listener = new CollectingListener();
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + luminaServer.port() + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        webSocket.sendText("{\"type\":\"intent\",\"name\":\"connect\",\"payload\":{\"path\":\"/\"}}", true);
        String snapshot = listener.nextMessage();
        String chatId = findId(MAPPER.readTree(snapshot).path("root"), "chat_input");
        assertThat(chatId).isNotBlank();

        webSocket.sendText(
                "{\"type\":\"intent\",\"name\":\"submit_chat\",\"targetId\":\""
                        + chatId
                        + "\",\"payload\":{\"value\":\"Is it safe to drink flood water?\"}}",
                true)
                .join();

        List<String> messages = new ArrayList<>();
        boolean sawEnd = false;
        for (int i = 0; i < 400 && !sawEnd; i++) {
            String message = listener.nextMessage();
            messages.add(message);
            sawEnd = message.contains("\"op\":\"end\"");
        }
        assertThat(sawEnd).as("WebSocket messages: %s", messages).isTrue();
        assertThat(String.join("\n", messages)).contains("Flood Safety");

        UUID conversationId = awaitCitationBearingConversation();
        Conversation conversation = conversationRepository.findById(new ConversationId(conversationId)).orElseThrow();
        assertThat(conversation.messages()).anyMatch(message ->
                message.role() == MessageRole.ASSISTANT && !message.citations().isEmpty());

        webSocket.abort();
    }

    private UUID awaitCitationBearingConversation() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT DISTINCT m.conversation_id FROM ai.messages m "
                                 + "JOIN ai.citations c ON c.message_id = m.id")) {
                if (rs.next()) {
                    return rs.getObject("conversation_id", UUID.class);
                }
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for a citation-bearing conversation");
    }

    private static String findId(JsonNode node, String type) {
        if (type.equals(node.path("type").asText())) {
            return node.path("id").asText();
        }
        for (JsonNode child : node.path("children")) {
            String found = findId(child, type);
            if (found != null && !found.isBlank()) {
                return found;
            }
        }
        return null;
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(120, TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("Timed out waiting for WebSocket message");
            }
            return message;
        }
    }
}
