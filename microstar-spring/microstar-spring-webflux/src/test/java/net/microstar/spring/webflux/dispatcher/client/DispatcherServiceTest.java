package net.microstar.spring.webflux.dispatcher.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.microstar.spring.application.MicroStarApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class DispatcherServiceTest {
    private DispatcherService dispatcher;

    @BeforeEach
    void setup() {
        final MicroStarApplication app = Mockito.mock(MicroStarApplication.class);
        final WebClient.Builder webClientBuilder = Mockito.mock(WebClient.Builder.class);
        when(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder);
        when(webClientBuilder.defaultHeaders(any())).thenReturn(webClientBuilder);

        dispatcher = new DispatcherService(app, webClientBuilder, new ObjectMapper());
    }

    @SuppressWarnings("Convert2Diamond") // changing to <> leads to internal compiler error
    @Test
    void conversionsShouldApply() {
        final String dataIn = "[`1`,`2`,`3`]".replace("`", "\"");
        assertThat(dispatcher.convert(dataIn, new ParameterizedTypeReference<List<String>>() {}), is(List.of("1", "2", "3")));
        assertThat(dispatcher.convert(dataIn, new ParameterizedTypeReference<List<Integer>>() {}), is(List.of(1, 2, 3)));
    }
}
