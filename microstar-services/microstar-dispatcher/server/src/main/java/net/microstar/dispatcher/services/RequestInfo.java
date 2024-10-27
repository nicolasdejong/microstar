package net.microstar.dispatcher.services;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import net.microstar.common.io.IOUtils;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static net.microstar.common.io.IOUtils.concatPath;

@Builder @ToString
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class RequestInfo {
    @Default public final String starUrl = "";
    @Default public final PathInfo path = new PathInfo("");
    @Default public final String queryParamsText = "";
    @Default public final String serviceGroup = "";
    @Default public final String serviceName = "";
    @Default public final String restPath = "";
    @Default public final Optional<ServiceVariations> serviceVariations = Optional.empty();
    @Default public final Optional<ServiceInfoRegistered> serviceInfo = Optional.empty();
    @Default public final Optional<WebClient> webClient = Optional.empty();
    @Default public final boolean isLocal = false;
    @Default public final boolean unknownTarget = false;

    public Mono<String> getUri() {
        return webClient.map(wc -> Mono.just(concatPath(path, restPath) + queryParamsText))
            .or(() -> serviceInfo.map(reg -> Mono.just(concatPath(reg.baseUrl, restPath) + queryParamsText)))
            .or(() -> serviceVariations.map(vars -> vars.getServiceToCall().map(reg -> concatPath(reg.baseUrl, restPath) + queryParamsText)))
            .orElse(Mono.empty());
    }

    public Mono<WebClient> getWebClient() {
        // when serviceInfo exists, a specific service UUID was given
        // otherwise use the serviceVariations (most use cases)
        return serviceInfo.map(serviceInfoRegistered -> Mono.justOrEmpty(serviceInfoRegistered.webClient))
            .or(() -> webClient.map(Mono::just))
            .orElseGet(() -> serviceVariations
                .map(variations -> variations.getServiceToCall() // this will start the service if not yet running
                    .map(service -> {
                    service.called();
                    return service.webClient;
                }))
                .orElseGet(Mono::empty)
            );
    }
    public Mono<WebClient.RequestBodySpec> getClientRequest(HttpMethod method) {
        final String restUri = restPath + queryParamsText;
        return getWebClient()
            .map(webClient -> webClient
                .method(method)
                .uri(restUri)
                .header("X-Forwarded-Prefix", IOUtils.concatPath(starUrl, serviceName))
            );
    }
}
