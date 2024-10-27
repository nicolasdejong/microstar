package net.microstar.spring.mvc;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.settings.PropsMap;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Slf4j
@Component
public class RestHelper {
    private final RestTemplate restTemplate;

    public RestHelper(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
    }

    public <R> Optional<R> call(String method, String url, Class<R> responseType) {
        return call(method, url, responseType, (status, msg) -> {});
    }
    public <R> Optional<R> call(String method, String url, Class<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(HttpMethod.valueOf(method.toUpperCase()), url, null, responseType, errorCallback);
    }
    public <R> Optional<R> call(String method, String url, ParameterizedTypeReference<R> responseType) {
        return call(method, url, responseType, (status, msg) -> {});
    }
    public <R> Optional<R> call(String method, String url, ParameterizedTypeReference<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(HttpMethod.valueOf(method.toUpperCase()), url, null, responseType, errorCallback);
    }

    public <R> Optional<R> get(String url, Class<R> responseType) {
        return exchange(HttpMethod.GET, url, null, responseType, (status, msg) -> {});
    }
    public <R> Optional<R> get(String url, Class<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(HttpMethod.GET, url, null, responseType, errorCallback);
    }
    public <R> Optional<R> get(String url, ParameterizedTypeReference<R> responseType) {
        return exchange(HttpMethod.GET, url, null, responseType, (status, msg) -> {});
    }
    public <R> Optional<R> get(String url, ParameterizedTypeReference<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(HttpMethod.GET, url, null, responseType, errorCallback);
    }

    public void post(String path) { post(path, null, Void.class); }
    public <S,R> Optional<R> post(String url, @Nullable S toSend, Class<R> responseType) {
        return exchange(HttpMethod.POST, url, toSend, responseType, (status, msg) -> {});
    }
    public <S,R> Optional<R> post(String url, @Nullable S toSend, ParameterizedTypeReference<R> responseType) {
        return exchange(HttpMethod.POST, url, toSend, responseType, (status, msg) -> {});
    }
    public <S,R> Optional<R> post(String url, @Nullable S toSend, Class<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(HttpMethod.POST, url, toSend, responseType, errorCallback);
    }

    private <S,R> Optional<R> exchange(HttpMethod method, String url, @Nullable S toSend, ParameterizedTypeReference<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(url, toSend, requestEntity -> restTemplate.exchange( url, method, requestEntity, responseType).getBody(), errorCallback);
    }
    private <S,R> Optional<R> exchange(HttpMethod method, String url, @Nullable S toSend, Class<R> responseType, BiConsumer<Integer,String> errorCallback) {
        return exchange(url, toSend, requestEntity -> restTemplate.exchange( url, method, requestEntity, responseType).getBody(), errorCallback);
    }
    private static <S, R> Optional<R> exchange(String url, @Nullable S toSend, Function<HttpEntity<S>, R> caller, BiConsumer<Integer,String> errorCallback) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        MicroStarApplication.get().orElseThrow().setHeaders(headers);
        final HttpEntity<S> requestEntity = new HttpEntity<>(toSend, headers);

        try {
            return Optional.ofNullable(caller.apply(requestEntity));
        } catch(final HttpStatusCodeException e) {
            final int statusCode = e.getStatusCode().value();
            String error = e.getStatusText();
            final String body = e.getResponseBodyAsString();
            try {
                final PropsMap map = PropsMap.fromYaml(body);
                error = map.get("error").map(Objects::toString).orElse(error + " -- " + body);
            } catch(final Exception notJson) {/*keep error as is*/}

            log.error("Sending '{}' FAILED: {} {}", url, statusCode, error);
            errorCallback.accept(statusCode, error);
            return Optional.empty();
        } catch(final ResourceAccessException e) {
            return Optional.empty();
        }
    }
}
