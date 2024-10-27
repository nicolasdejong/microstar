package net.microstar.dispatcher;

import lombok.RequiredArgsConstructor;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.services.Services;
import net.microstar.dispatcher.services.StarsManager;
import net.microstar.spring.authorization.UserToken;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MiniBusDispatcher {
    private final Services services;
    private final StarsManager starsManager;

    public void sendToAllServices(String messageText, String className, boolean onlyLocalStar) {
        if(!onlyLocalStar) sendToOtherStars(messageText, className);
        Threads.execute(() ->
            services.getAllRunningServices().forEach(service ->
                service.webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                        .path("miniBus")
                        .queryParam("className", className)
                        .build())
                    .bodyValue(messageText)
                    .header(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET)
                    .retrieve()
                    .toBodilessEntity()
                    .block()
            )
        );
    }

    private void sendToOtherStars(String messageText, String className) {
        Threads.execute(() ->
            starsManager.relay(RelayRequest.builder()
                .excludeLocalStar()
                .method(HttpMethod.POST)
                .serviceName("Dispatcher")
                .servicePath("/miniBusDispatcher")
                .payload(messageText)
                .param("className", className)
                .param("onlyLocalStar", "true")
                .userToken(UserToken.SERVICE_TOKEN)
                .build()
            )
                .then()
                .block()
        );
    }
}
