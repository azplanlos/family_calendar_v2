package de.goForFun.familienkalender.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/** Smoke-Test der geteilten {@link GraphArbitraries}-Generatoren und Fakes (keine Correctness-Property). */
class GraphArbitrariesSmokeTest extends GraphArbitraries {

    @Property(tries = 100)
    void graphEventsFollowRealGraphShape(@ForAll("graphEvents") JsonNode event) {
        assertThat(event.isObject()).isTrue();
        JsonNode start = event.get("start");
        if (start != null && start.isObject()) {
            assertThat(start.get("dateTime").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}");
        }
        JsonNode originalStart = event.get("originalStart");
        if (originalStart != null && !originalStart.isNull()) {
            assertThat(originalStart.asString()).matches(".*[+-]\\d{2}:\\d{2}$");
        }
    }

    @Property(tries = 100)
    void graphPagesFormNextLinkChain(@ForAll("graphPages") List<JsonNode> pages) {
        assertThat(pages).isNotEmpty().hasSizeLessThanOrEqualTo(60);
        for (int i = 0; i < pages.size(); i++) {
            JsonNode page = pages.get(i);
            assertThat(page.get("value").isArray()).isTrue();
            assertThat(page.has("@odata.nextLink")).isEqualTo(i < pages.size() - 1);
        }
    }

    @Property(tries = 100)
    void graphEventListsAreProduced(@ForAll("graphEventLists") List<JsonNode> events) {
        assertThat(events).hasSizeLessThanOrEqualTo(8);
    }

    @Property(tries = 100)
    void urisAreProduced(@ForAll("uris") URI uri) {
        assertThat(uri.getScheme()).isNotNull();
    }

    @Property(tries = 100)
    void remainingGeneratorsProduceValues(
            @ForAll("tokenResponses") TokenResponse token,
            @ForAll("ssmResults") SsmResult ssm,
            @ForAll("deviceCodeScripts") DeviceCodeScript script,
            @ForAll("scopeStrings") String scope,
            @ForAll("canaryStrings") String canary) {
        assertThat(token).isNotNull();
        assertThat(ssm).isNotNull();
        assertThat(script.pollErrors()).isNotNull();
        assertThat(scope).isNotNull();
        assertThat(canary).startsWith("CANARY").endsWith("ZZ");
    }

    @org.junit.jupiter.api.Test
    void fakesWork() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        clock.advance(java.time.Duration.ofSeconds(5));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
        RecordingLog log = new RecordingLog();
        log.warn("w");
        log.error("e");
        assertThat(log.all()).containsExactly("w", "e");
    }
}
