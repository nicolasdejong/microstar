package net.microstar.dispatcher.controller;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Caching;
import net.microstar.common.util.VersionComparator;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.dispatcher.services.ServiceJarsManager;
import net.microstar.dispatcher.services.StarsManager;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static net.microstar.common.MicroStarConstants.HEADER_X_STAR_NAME;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RestController
@RequestMapping(
    consumes = MediaType.ALL_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class StarController {
    private final StarsManager starsManager;
    private final ServiceJarsManager jarsManager;

    @PostMapping("/relay") @RequiresRole(ROLE_SERVICE)
    public Flux<RelayResponse<String>> relay(@RequestBody RelayRequest req) {
        return starsManager.relay(req);
    }

    @PostMapping("/relay-single") @RequiresRole(ROLE_SERVICE)
    public Mono<RelayResponse<String>> relaySingle(@RequestBody RelayRequest req) {
        return starsManager.relaySingle(req);
    }

    @Jacksonized @Builder
    public static final class LocalStarInfo {
        public final String starName;
        public final String starUrl;
        public final String dispatcherUrl;
        public final List<String> ipAddresses;
    }

    @GetMapping("/star") @RequiresRole(ROLE_ADMIN)
    public LocalStarInfo getStarInfo() {
        return Caching.cache(() -> LocalStarInfo.builder()
            .starName(starsManager.getLocalStar().name)
            .starUrl(starsManager.getLocalStar().url)
            .dispatcherUrl(DynamicPropertiesManager.getProperty("app.config.dispatcher.url", "?"))
            .ipAddresses(noThrow(() -> Collections.list(NetworkInterface.getNetworkInterfaces())).orElseGet(ArrayList::new).stream()
                .flatMap(NetworkInterface::inetAddresses)
                .map(InetAddress::getHostAddress)
                .filter(addr -> !"127.0.0.1".equals(addr) && !"0:0:0:0:0:0:0:1".equals(addr)) // no loopback addresses
                .toList())
            .build()
        );
    }

    @Jacksonized @Builder
    public static final class StarInfo {
        public final String name;
        public final String url;
        public final boolean isActive;
        public final boolean isLocal;
    }

    @GetMapping("/stars") @RequiresRole(ROLE_ADMIN)
    public List<StarInfo> getStars() {
        return starsManager.getStars().stream()
            .map(star -> StarInfo.builder().name(star.name).url(star.url).isActive(starsManager.isActive(star)).isLocal(starsManager.isLocal(star.name)).build())
            .toList();
    }

    @GetMapping("/jar") @RequiresRole(ROLE_ADMIN)
    public List<String> getAvailableJarsOnThisStar() {
        return jarsManager.getJars().stream()
            .map(jarInfo -> jarInfo.name)
            .filter(name -> !ServiceId.of(name).name.toLowerCase(Locale.ROOT).contains("watchdog"))
            .sorted(VersionComparator.OLDEST_TO_NEWEST)
            .toList();
    }

    @PostMapping("/copy-jar/{jarName}") @RequiresRole(ROLE_ADMIN)
    public Mono<Void> copyJarFromStar(@PathVariable("jarName") String jarName, @RequestParam("fromStar") String fromStar) {
        log.info("Copy jar {} from star {}", jarName, fromStar);
        return jarsManager.downloadJar(fromStar, jarName);
    }

    @Jacksonized @Builder @EqualsAndHashCode
    public static class JarStarInfo implements Comparable<JarStarInfo> {
        public final String name;
        public final String star;

        @Override
        public int compareTo(JarStarInfo other) {
            final ServiceId id1 = ServiceId.of(name);
            final ServiceId id2 = ServiceId.of(other.name);
            return id1.name.equals(id2.name)
                ? -id1.compare(id1, id2)
                : id1.name.compareTo(id2.name);
        }
    }

    @GetMapping("/all-jars") @RequiresRole(ROLE_ADMIN)
    public Mono<List<JarStarInfo>> getAvailableJarsOnStars() {
        return jarsManager.getAvailableJarsOnStars();
    }

    @PostMapping("/star-started-jar/{jarName}") @RequiresRole(ROLE_ADMIN)
    public void starStartedJar(@PathVariable String jarName, @RequestHeader(HEADER_X_STAR_NAME) String starName) {
        jarsManager.anotherStarStartedJar(starName, jarName);
    }

    @PostMapping("/star-added-jar/{jarName}") @RequiresRole(ROLE_ADMIN)
    public void starAddedJar(@PathVariable String jarName, @RequestHeader(HEADER_X_STAR_NAME) String starName) {
        jarsManager.anotherStarAddedJar(starName, jarName);
    }

    @PostMapping("/star-removed-jar/{jarName}") @RequiresRole(ROLE_ADMIN)
    public void starRemovedJar(@PathVariable String jarName, @RequestHeader(HEADER_X_STAR_NAME) String starName) {
        jarsManager.anotherStarRemovedJar(starName, jarName);
    }
}
