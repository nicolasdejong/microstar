package net.microstar.webfluxtester;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping(produces = "text/plain")
@RequiredArgsConstructor
public class WebFluxTesterController {
    private final DynamicPropertiesRef<WebFluxTesterProperties> props = DynamicPropertiesRef.of(WebFluxTesterProperties.class);
    private final ApplicationContext appContext;
    private final TestProperties testProps;
    private final DispatcherService dispatcher;

    @GetMapping("/ok")
    public ResponseEntity<String> ok(UserToken userToken) {
        return ResponseEntity.ok("ok -- token: " + userToken);
    }

    @RequestMapping("/status/{num}")
    public ResponseEntity<String> status(@PathVariable("num") String num) {
        return ResponseEntity.status(HttpStatus.valueOf(num)).body("Forced status of " + num);
    }

    @RequestMapping("/error")
    public Mono<String> error() {
        return Mono.error(new RuntimeException("test error"));
    }

    @GetMapping("/file/**") // also matches on "/file"
    public Mono<String> getFile(ServerWebExchange exchange) {
        return Mono.just("pathName=" + exchange.getRequest().getPath().value().substring("/file".length()));
    }

    @GetMapping(value = "/test-relay", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> testRelay() {
        return dispatcher.relay(RelayRequest.forGet("microstar-settings/files").build(), new ParameterizedTypeReference<List<Map<String,Object>>>(){})
            .map(resp -> resp.content.orElseGet(Collections::emptyList))
            .doOnNext(list -> log.info("relay response list: " + list))
            .map(Object::toString)
            .reduce((a, b) -> a + "\n---\n" + b);
    }

    final int[] requestCount = { 0 };

    @RequestMapping("/reflect/**")
    public Mono<String> reflect(ServerWebExchange exchange, @Value("${experiment.foo:unknown}") String paramFoo, WebFluxTesterProperties paramProps) {
        return Mono.zip(
            exchange
            .getRequest()
            .getBody()
            .map(dataBuffer -> {
                final byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .collectList()
            .switchIfEmpty(Mono.just(Collections.emptyList())),
            dispatcher.getLocalStarName()
            )
            .map(tuple -> String.join("\n",
                "Method: " + exchange.getRequest().getMethod(),
                "URI: " + exchange.getRequest().getURI(),
                "Path: " + exchange.getRequest().getPath(),
                "QueryParams: " + exchange.getRequest().getQueryParams(),
                "Headers:\n - " + exchange
                    .getRequest()
                    .getHeaders().entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n - ")),
                "Body: " + (tuple.getT1().isEmpty() ? "<no body>" : ("\n" + (tuple.getT1().size() == 1 ? tuple.getT1().get(0) : tuple.getT1()))),
                "Star: " + tuple.getT2(),
                "requestCount: " + requestCount[0]++,
                "Properties from constructor:",
                "  foo: " + props.get().foo,
                "  bar: " + props.get().bar,
                "  number: " + props.get().number,
                "  text: " + props.get().text,
                "  numbers:" + props.get().numbers,
                "",
                "  via env foo: " + appContext.getEnvironment().getProperty("experiment.foo", "?"),
                "  constructor foo: " + props.get().foo,
                "  paramFoo: " + paramFoo,
                "  paramProps.foo: " + paramProps.foo,
                "  paramProps.bar: " + paramProps.bar,
                "",
                "  testProps.foo: " + testProps.foo,
                "  testProps.bar: " + testProps.bar,
                "  testProps.zoo: " + testProps.zoo
            ));
    }
}