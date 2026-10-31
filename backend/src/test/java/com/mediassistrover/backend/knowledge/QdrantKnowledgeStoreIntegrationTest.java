package com.mediassistrover.backend.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.config.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class QdrantKnowledgeStoreIntegrationTest {
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.15.4"))
            .withExposedPorts(6333);

    @Test
    void createsCollectionAndSearchesAnEmptyStore() {
        AssistantProperties properties = new AssistantProperties();
        properties.setQdrantUrl("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        properties.setQdrantCollection("mediassist_test");
        QdrantKnowledgeStore store = new QdrantKnowledgeStore(properties, new ObjectMapper());

        assertThat(store.healthy()).isTrue();
        float[] query = new float[1536];
        query[0] = 1.0f;
        assertThat(store.search(query, false)).isEmpty();
    }
}
