package net.microstar.dispatcher.services;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.util.function.Predicate.not;
import static net.microstar.common.io.IOUtils.concatPath;

@Slf4j
class RequestResolver {
    private static final int UUID_LENGTH = UUID.randomUUID().toString().length();
    private static final Pattern HTTP_LINK_PATTERN = Pattern.compile("^https?://.*$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[\\da-fA-F-]+$");

    private final Services services;
    private final String starUrl;
    private final AtomicReference<WebClient> fallbackWebClient = new AtomicReference<>();
    private final DynamicPropertyRef<String> fallbackPath = DynamicPropertyRef.of("app.config.dispatcher.fallback").withDefault("")
        .onChange(fbp -> {
            final DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(fbp);
            factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

            fallbackWebClient.set(HTTP_LINK_PATTERN.matcher(fbp).matches()
                ? WebClient.builder().uriBuilderFactory(factory).baseUrl(fbp).build()
                : null
            );
        })
        .callOnChangeHandlers();


    public RequestResolver(Services services, String starUrl) {
        this.services = services;
        this.starUrl = starUrl;
    }

    public  RequestInfo getRequestInfoForTarget(ServerHttpRequest req) {
        final String path = req.getPath().value();
        final String query = Optional.ofNullable(req.getURI().getRawQuery()).map(q -> "?" + q).orElse("");
        return getRequestInfoForTarget(path, query, /*noFallback=*/false, req.getHeaders());
    }
    public RequestInfo getRequestInfoForTarget(String pathIn, String queryParamsText, boolean noFallback, HttpHeaders headers) {
        // This is called often (for each request) so should be fast! <-------------------------

        final PathInfo pathInfo = new PathInfo(pathIn);

        // Path is in any of these forms:
        // - /{serviceInstance-UUID}/restOfPath               -> /restOfPath to specific client
        // - /knownServiceGroup/knownServiceName/restOfPath   -> use as-is (there may be multiple services for this)
        // - /unknownServiceGroup/knownServiceName/restOfPath -> interpret as: /main/knownServiceName/restOfPath
        // - /knownServiceName/restOfPath                     -> interpret as: /main/knownServiceName/restOfPath
        // - any other                                        -> use fallback or else set unknownTarget (which should lead to a 404 result)
        //
        // The rest of the path should be set in the WebClient
        //
        final String serviceGroup = pathInfo.first; // this may turn out to be the service name!
        final String serviceName  = pathInfo.second;
        final RequestInfo.RequestInfoBuilder requestInfoBuilder = RequestInfo.builder()
            .path(pathInfo)
            .queryParamsText(queryParamsText)
            .serviceVariations(Optional.empty())
            .serviceInfo(Optional.empty())
            ;

        // serviceInstance-UUID refers directly to specific service
        if(isSimilarToUUID(serviceGroup)) {
            // If this uuid is not referencing an instance, don't use fallback
            return getRequestInfoForInstance(serviceGroup, requestInfoBuilder);
        }

        return findTargetFor(serviceGroup, serviceName, pathInfo.afterSecond, requestInfoBuilder)
            .or(() -> findTargetWhenNoGroup(serviceGroup, pathInfo.afterFirst, headers, requestInfoBuilder))
            .orElseGet(() -> getFallbackOrUnknownTarget(noFallback, headers, requestInfoBuilder));
    }

    /**
     * Find a service for the given serviceGroup and serviceName. As both serviceGroup/serviceName/rest
     * and serviceName/rest are allowed, this call will result in Optional.empty() when no serviceGroup
     * is given (which here means that serviceGroup that is actually the serviceName).
     */
    private Optional<RequestInfo> findTargetFor(String serviceGroup, String serviceName, String restPath, RequestInfo.RequestInfoBuilder requestInfoBuilder) {
        return services.getServiceVariations(serviceGroup, serviceName)
            .map(serviceVariations ->
                    requestInfoBuilder
                        .serviceGroup(serviceGroup)
                        .serviceName(serviceName)
                        .serviceVariations(Optional.of(serviceVariations))
                        .restPath(restPath)
                        .build()
            );
    }

    /**
     * serviceGroup / serviceName not found probably means that what was
     * interpreted as serviceGroup/serviceName/restOfPath is serviceName/restOfPath.
     * ServiceGroup then is not given and should become the same group as the
     * calling service (if a service is the caller).
     * If still no known group, 'main' will be used unless no main service
     * is running in which case the first found service with serviceName
     * is used.
     */
    private Optional<RequestInfo> findTargetWhenNoGroup(String serviceGroupWhichIsName, String restPath, HttpHeaders headers, RequestInfo.RequestInfoBuilder requestInfoBuilder) {
        final String actualServiceName = serviceGroupWhichIsName;
        final String actualServiceGroup = Optional.ofNullable(headers.getFirst(MicroStarConstants.HEADER_X_SERVICE_ID))
            .map(ServiceId::new)
            .map(sid -> sid.group)
            .filter(groupName -> services.getAllRunningServices().stream() // see if target service exists for given group
                .anyMatch(service -> service.id.group.equals(groupName) && service.id.name.equals(actualServiceName))
            )
            .orElseGet(() -> services.getAllRunningServices().stream() // or else find service in any known group
                .filter(service -> service.id.name.equals(actualServiceName)).findFirst()
                .map(service -> service.id.group)
                .orElse(ServiceId.DEFAULT_GROUP_NAME)
            );

        return services.getServiceVariations(actualServiceGroup, actualServiceName)
            .map(serviceVariations ->
                requestInfoBuilder
                    .serviceGroup(actualServiceGroup)
                    .serviceName(actualServiceName)
                    .serviceVariations(Optional.of(serviceVariations))
                    .restPath(restPath)
                    .build()
            );
    }

    /**
     * When no target is found use fallback when allowed and possible, otherwise
     * return an 'unknownTarget' RequestInfo.
     */
    private RequestInfo getFallbackOrUnknownTarget(boolean noFallback, HttpHeaders headers, RequestInfo.RequestInfoBuilder requestInfoBuilder) {
        if(!noFallback) {
            return getFallbackRequestInfo(requestInfoBuilder, headers);
        }
        return requestInfoBuilder
            .unknownTarget(true).build();
    }

    private RequestInfo getRequestInfoForInstance(String uuidString, RequestInfo.RequestInfoBuilder requestInfoBuilder) {
        try {
            final UUID instanceId = UUID.fromString(uuidString); // NOSONAR -- false positive (unused var)
            final String restPath = requestInfoBuilder.build().path.afterFirst;  // NOSONAR -- false positive (unused var)

            // Call turns out to be for the dispatcher? Then call this without the UUID
            if(instanceId.equals(services.getDispatcherApplication().serviceInstanceId)) {
                return requestInfoBuilder
                    .isLocal(true)
                    .serviceInfo(Optional.of(services.getDispatcherService()))
                    .restPath(restPath)
                    .build();
            }

            final Optional<ServiceInfoRegistered> serviceInfo = services.getRegisteredService(instanceId);

            return RequestInfo.builder()
                .serviceInfo(serviceInfo)
                .restPath(restPath)
                .unknownTarget(serviceInfo.isEmpty())
                .build();
        } catch(final IllegalArgumentException notUuid) {
            log.warn("Not a UUID: " + uuidString);
        }
        return RequestInfo.builder().unknownTarget(true).build();
    }

    private RequestInfo getFallbackRequestInfo(RequestInfo.RequestInfoBuilder reqInfoBuilder, HttpHeaders headers) {
        final RequestInfo reqInfo = reqInfoBuilder.build();
        return Optional
            .of(fallbackPath.get())
            .filter(not(String::isEmpty))
            .map(fallback -> {

                // fallback is url
                final @Nullable WebClient webClient = fallbackWebClient.get();
                if(webClient != null) {
                    reqInfoBuilder.restPath(reqInfo.path.all);
                    reqInfoBuilder.path(new PathInfo(fallback));
                    reqInfoBuilder.webClient(Optional.of(webClient));
                    return reqInfoBuilder.build();
                }

                return getRequestInfoForTarget(concatPath(fallback, reqInfo.path.all), reqInfo.queryParamsText, /*noFallback*/true, headers);
            })
            .orElseGet(() -> reqInfoBuilder.unknownTarget(true).build());
    }

    private static boolean isSimilarToUUID(String text) {
        return text.length() == UUID_LENGTH && text.contains("-") && UUID_PATTERN.matcher(text).matches();
    }
}