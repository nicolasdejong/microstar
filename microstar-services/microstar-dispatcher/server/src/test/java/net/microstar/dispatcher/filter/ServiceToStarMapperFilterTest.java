package net.microstar.dispatcher.filter;

import net.microstar.common.MicroStarConstants;
import net.microstar.dispatcher.services.StarsManager;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ServiceToStarMapperFilterTest {
    private static final String settingsText = String.join("\n",
        "app.config.dispatcher.stars.serviceTargets:",
        "  sso: sso-star",
        "  foo-bar: foo-bar-star",
        "  loc: local-star",
        "  first: first-available-star",
        ""
    );

    @BeforeEach void setup() { AppSettings.handleExternalSettingsText(settingsText); }
    @AfterEach void cleanupProperties() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void filterShouldSetTargetAsConfigured() {
        assertThat(getFilteredStarTarget("/sso",                    createFilter("sso-star")), is("sso-star"));
        assertThat(getFilteredStarTarget("/sso/rest/of/path",       createFilter("sso-star")), is("sso-star"));
        assertThat(getFilteredStarTarget("/group/sso/rest/of/path", createFilter("sso-star")), is("sso-star"));
        assertThat(getFilteredStarTarget("/foo-bar/rest/of/path",   createFilter("sso-star", "foo-bar-star")), is("foo-bar-star"));
        assertThat(getFilteredStarTarget("/some/other/path",        createFilter("foo-star")), is(nullValue()));
    }
    @Test void filterShouldIgnoreInactiveStars() {
        assertThat(getFilteredStarTarget("/sso",       createFilter("foo-star")), is(nullValue()));
        assertThat(getFilteredStarTarget("/sso",       createFilter("foo-star"), "existing-target"), is("existing-target"));
    }
    @Test void filterShouldNotOverwriteExistingTarget() {
        assertThat(getFilteredStarTarget("/sso",       createFilter("sso-star", "other-star"), "other-star"), is("other-star"));
        assertThat(getFilteredStarTarget("/whatever",  createFilter("sso-star", "other-star"), "other-star"), is("other-star"));
    }
    @Test void filterShouldOverwriteExistingTargetIfInactive() {
        assertThat(getFilteredStarTarget("/sso",       createFilter("sso-star"), "other-star"), is("sso-star"));
    }
    @Test void filterShouldNotTargetLocalStar() {
        assertThat(getFilteredStarTarget("/loc",       createFilter()), is(nullValue()));
    }
    @Test void filterShouldSupportFirstAvailableStar() {
        assertThat(getFilteredStarTarget("/first",     createFilter("star1", "star2", "star3")), is("star1"));
    }
    @Test void filterShouldIgnoreIllegalPath() {
        assertThat(getFilteredStarTarget("", createFilter("star1", "star2")), is(nullValue()));
        assertThat(getFilteredStarTarget("/", createFilter("star1", "star2")), is(nullValue()));
        assertThat(getFilteredStarTarget("first/", createFilter("star1", "star2")), is(nullValue()));
    }

    private static ServiceToStarMapperFilter createFilter(String... activeStars) {
        final StarsManager starsManager = Mockito.mock(StarsManager.class);
        when(starsManager.isActive(any(String.class))).thenAnswer(inv -> "local-star".equals(inv.getArgument(0)) || Set.of(activeStars).contains(inv.<String>getArgument(0)));
        when(starsManager.isLocal(any(String.class))).thenAnswer(inv -> "local-star".equals(inv.<String>getArgument(0)));
        when(starsManager.getFirstAvailableStarName()).thenAnswer(inv -> activeStars[0]);

        return new ServiceToStarMapperFilter(starsManager);
    }

    private static @Nullable String getFilteredStarTarget(String path, ServiceToStarMapperFilter filter) { return getFilteredStarTarget(path, filter, null); }
    private static @Nullable String getFilteredStarTarget(String path, ServiceToStarMapperFilter filter, @Nullable String existingStarTarget) {
        final ServerWebExchange exchange = MockServerWebExchange.builder(
            MockServerHttpRequest
                .get(path)
                .header(MicroStarConstants.HEADER_X_STAR_TARGET, existingStarTarget == null ? new String[0] : new String[] { existingStarTarget })
        ).build();

        final ServerWebExchange[] result = { null };
        final WebFilterChain chain = new WebFilterChain() {
            @Override @Nonnull
            public Mono<Void> filter(@Nonnull ServerWebExchange swe) {
                result[0] = swe;
                return Mono.empty();
            }
        };
        filter.filter(exchange, chain).block();

        return result[0].getRequest().getHeaders().getFirst(MicroStarConstants.HEADER_X_STAR_TARGET);
    }
}