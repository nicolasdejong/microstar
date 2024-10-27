package net.microstar.dispatcher.services;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.extern.jackson.Jacksonized;
import net.microstar.dispatcher.DispatcherApplication;
import net.microstar.dispatcher.model.DispatcherProperties;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Jacksonized
@Builder(toBuilder = true, access = AccessLevel.PACKAGE)
@EqualsAndHashCode(exclude = {"webClient"})
public class Star {
    public final String name;
    public final String url;
    public final transient WebClient webClient;

    public static Star from(DispatcherProperties.StarsProperties.StarProperties starProps, WebClient.Builder webClientBuilder) {
        final DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(starProps.url);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

        //noinspection ConstantConditions -- null sanity check
        return Star.builder()
            .name(starProps.name == null || starProps.name.isBlank() ? urlToName(starProps.url) : starProps.name)
            .url(starProps.url)
            .webClient(webClientBuilder
                .uriBuilderFactory(factory)
                .baseUrl(starProps.url)
                .defaultHeaders(headers -> DispatcherApplication.get().ifPresent(app -> app.setHeaders(headers)))
                .build())
            .build();
    }

    public String toString() {
        return "[Star: " + name + " url: " + url + "]";
    }

    private static String urlToName(String url) {
        return url.replaceAll("^(\\w+://)?(.+)$", "$2");
    }
}
