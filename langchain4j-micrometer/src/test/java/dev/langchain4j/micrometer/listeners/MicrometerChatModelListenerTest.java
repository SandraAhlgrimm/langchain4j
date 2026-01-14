package dev.langchain4j.micrometer.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.micrometer.conventions.OTelGenAiAttributes;
import dev.langchain4j.micrometer.conventions.OTelGenAiMetricName;
import dev.langchain4j.micrometer.conventions.OTelGenAiTokenType;
import dev.langchain4j.micrometer.observation.ChatModelMeterObservationHandler;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrometerChatModelListenerTest {

    private TestObservationRegistry observationRegistry;
    private MicrometerChatModelListener listener;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        observationRegistry = TestObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new ChatModelMeterObservationHandler(meterRegistry));
        listener = new MicrometerChatModelListener(observationRegistry, "test_system");
    }

    @Test
    void should_require_observation_registry() {
        assertThatThrownBy(() -> new MicrometerChatModelListener(null, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observationRegistry");
    }

    @Test
    void should_require_ai_system_name() {
        assertThatThrownBy(() -> new MicrometerChatModelListener(observationRegistry, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aiSystemName");
    }

    @Test
    void should_observe_successful_request_and_response() {
        // Given
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from("Hello"))
                .parameters(ChatRequestParameters.builder().modelName("test-model").build())
                .build();
        Map<Object, Object> attributes = new HashMap<>();
        ModelProvider modelProvider = ModelProvider.OPEN_AI;
        ChatModelRequestContext requestContext = new ChatModelRequestContext(chatRequest, modelProvider, attributes);

        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("Hi there!"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("test-model")
                        .tokenUsage(new TokenUsage(10, 5))
                        .build())
                .build();
        ChatModelResponseContext responseContext = new ChatModelResponseContext(
                chatResponse, chatRequest, modelProvider, attributes);

        // When
        listener.onRequest(requestContext);
        listener.onResponse(responseContext);

        // Then
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(OTelGenAiMetricName.OPERATION_DURATION.value())
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue(KeyValue.of(OTelGenAiAttributes.OPERATION_NAME.value(), "chat"))
                .hasLowCardinalityKeyValue(KeyValue.of(OTelGenAiAttributes.SYSTEM.value(), "test_system"))
                .hasLowCardinalityKeyValue(KeyValue.of(OTelGenAiAttributes.REQUEST_MODEL.value(), "test-model"))
                .hasLowCardinalityKeyValue(KeyValue.of(OTelGenAiAttributes.RESPONSE_MODEL.value(), "test-model"))
                .hasLowCardinalityKeyValue(KeyValue.of("outcome", "SUCCESS"));

        // Verify token usage metrics
        assertThat(meterRegistry
                        .find(OTelGenAiMetricName.TOKEN_USAGE.value())
                        .tag(OTelGenAiAttributes.TOKEN_TYPE.value(), OTelGenAiTokenType.INPUT.value())
                        .counter())
                .isNotNull();
        assertThat(meterRegistry
                        .get(OTelGenAiMetricName.TOKEN_USAGE.value())
                        .tag(OTelGenAiAttributes.TOKEN_TYPE.value(), OTelGenAiTokenType.INPUT.value())
                        .counter()
                        .count())
                .isEqualTo(10.0);
        assertThat(meterRegistry
                        .get(OTelGenAiMetricName.TOKEN_USAGE.value())
                        .tag(OTelGenAiAttributes.TOKEN_TYPE.value(), OTelGenAiTokenType.OUTPUT.value())
                        .counter()
                        .count())
                .isEqualTo(5.0);
    }

    @Test
    void should_observe_error_with_outcome_tag() {
        // Given
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from("Hello"))
                .parameters(ChatRequestParameters.builder().modelName("test-model").build())
                .build();
        Map<Object, Object> attributes = new HashMap<>();
        ModelProvider modelProvider = ModelProvider.OPEN_AI;
        ChatModelRequestContext requestContext = new ChatModelRequestContext(chatRequest, modelProvider, attributes);

        RuntimeException error = new RuntimeException("Test error");
        ChatModelErrorContext errorContext = new ChatModelErrorContext(
                error, chatRequest, modelProvider, attributes);

        // When
        listener.onRequest(requestContext);
        listener.onError(errorContext);

        // Then
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(OTelGenAiMetricName.OPERATION_DURATION.value())
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue(KeyValue.of("outcome", "ERROR"))
                .hasLowCardinalityKeyValue(KeyValue.of(OTelGenAiAttributes.ERROR_TYPE.value(), "RuntimeException"));

        // No token usage metrics on error
        assertThat(meterRegistry.find(OTelGenAiMetricName.TOKEN_USAGE.value()).counter())
                .isNull();
    }

    @Test
    void should_handle_concurrent_requests_with_separate_scopes() {
        // Given - two concurrent requests
        ChatRequest chatRequest1 = ChatRequest.builder()
                .messages(UserMessage.from("Request 1"))
                .parameters(ChatRequestParameters.builder().modelName("model-1").build())
                .build();
        Map<Object, Object> attributes1 = new HashMap<>();
        ModelProvider modelProvider1 = ModelProvider.OPEN_AI;
        ChatModelRequestContext requestContext1 = new ChatModelRequestContext(chatRequest1, modelProvider1, attributes1);

        ChatRequest chatRequest2 = ChatRequest.builder()
                .messages(UserMessage.from("Request 2"))
                .parameters(ChatRequestParameters.builder().modelName("model-2").build())
                .build();
        Map<Object, Object> attributes2 = new HashMap<>();
        ModelProvider modelProvider2 = ModelProvider.AZURE_OPEN_AI;
        ChatModelRequestContext requestContext2 = new ChatModelRequestContext(chatRequest2, modelProvider2, attributes2);

        // When - interleaved request/response calls
        listener.onRequest(requestContext1);
        listener.onRequest(requestContext2);

        // Response for request 2 comes first
        ChatResponse chatResponse2 = ChatResponse.builder()
                .aiMessage(AiMessage.from("Response 2"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("model-2")
                        .tokenUsage(new TokenUsage(20, 10))
                        .build())
                .build();
        ChatModelResponseContext responseContext2 = new ChatModelResponseContext(
                chatResponse2, chatRequest2, modelProvider2, attributes2);
        listener.onResponse(responseContext2);

        // Response for request 1 comes second
        ChatResponse chatResponse1 = ChatResponse.builder()
                .aiMessage(AiMessage.from("Response 1"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("model-1")
                        .tokenUsage(new TokenUsage(15, 8))
                        .build())
                .build();
        ChatModelResponseContext responseContext1 = new ChatModelResponseContext(
                chatResponse1, chatRequest1, modelProvider1, attributes1);
        listener.onResponse(responseContext1);

        // Then - both observations should be properly recorded
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo(OTelGenAiMetricName.OPERATION_DURATION.value(), 2);

        // Verify total token usage (10+20 input, 8+10 output)
        double inputTokens = meterRegistry
                .get(OTelGenAiMetricName.TOKEN_USAGE.value())
                .tag(OTelGenAiAttributes.TOKEN_TYPE.value(), OTelGenAiTokenType.INPUT.value())
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
        double outputTokens = meterRegistry
                .get(OTelGenAiMetricName.TOKEN_USAGE.value())
                .tag(OTelGenAiAttributes.TOKEN_TYPE.value(), OTelGenAiTokenType.OUTPUT.value())
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();

        assertThat(inputTokens).isEqualTo(35.0); // 15 + 20
        assertThat(outputTokens).isEqualTo(18.0); // 8 + 10
    }
}
