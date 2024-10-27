package net.microstar.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.settings.model.SettingsFile;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.application.RestartableApplication;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class SettingsController {
    private final DispatcherService dispatcher;
    private final SettingsRepository repository;
    @SuppressWarnings("unused") // reference needs to be kept, or it may be garbage collected
    private static final DynamicPropertiesRef<EncryptionSettings> encSettings = DynamicPropertiesRef.of(EncryptionSettings.class)
        .onChange(() -> MicroStarApplication.get().ifPresent(RestartableApplication::restart));


    @GetMapping(value = "/file/**", produces = "text/plain") // also matches on "/file"
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String getFile(@WildcardParam String path,
                          @RequestParam(required = false) Optional<Integer> version) {
        return version
            .map(versionNumber -> repository.getContentsOf(path, versionNumber))
            .orElseGet(() -> repository.getContentsOf(path));
    }

    @GetMapping(value = "/files", produces = "application/json")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public List<SettingsFile> getFiles() {
        return repository.getAllFilenames();
    }

    // These names/methods are not very REST compliant, but I kept forgetting
    // whether to use POST or PUT or PATCH so decided to name them instead.

    @PostMapping(value = "/store/**", produces = "text/plain")
    @RequiresRole(ROLE_ADMIN)
    public Mono<Void> store(@Nullable @RequestBody(required = false) String body,
                            @WildcardParam String path,
                            @RequestParam(value="message", required=false) @Nullable String message,
                            @RequestParam(value="user", required=false) @Nullable String user,
                            @RequestParam(value="onlyLocal", defaultValue = "false") boolean onlyLocal,
                            UserToken userToken) {
        Threads.execute(() -> repository.store(path, body == null ? "" : body, user == null ? userToken.name : user, message));
        return onlyLocal
            ? Mono.empty()
            : dispatcher.relay(RelayRequest
                .forPost()
                .servicePath("/store/" + path)
                .payload(body)
                .excludeLocalStar()
                .param("message", message)
                .param("user", user)
                .param("onlyLocal", "true")
                .userToken(userToken)
                .build(), Void.class)
            .then();
    }

    @PostMapping(value = "/rename/**", produces = "text/plain")
    @RequiresRole(ROLE_ADMIN)
    public Mono<Void> rename(@WildcardParam String path,
                             @RequestParam("newName") String newName,
                             @RequestParam(value="user", required=false) @Nullable String user,
                             @RequestParam(value="onlyLocal", defaultValue = "false") boolean onlyLocal,
                             UserToken userToken) {
        Threads.execute(() -> repository.rename(path, newName, user == null ? userToken.name : user));
        return onlyLocal
            ? Mono.empty()
            : dispatcher.relay(RelayRequest
                .forPost()
                .servicePath("/rename/" + path)
                .excludeLocalStar()
                .param("user", user)
                .param("onlyLocal", "true")
                .userToken(userToken)
                .build(), Void.class)
            .then();
    }

    @DeleteMapping(value = "/delete/**", produces = "text/plain")
    @RequiresRole(ROLE_ADMIN)
    public Mono<Void> delete(@WildcardParam String path,
                             @RequestParam(value="user", required=false) @Nullable String user,
                             @RequestParam(value="onlyLocal", defaultValue = "false") boolean onlyLocal,
                             UserToken userToken) {
        Threads.execute(() -> repository.delete(path, user == null ? userToken.name : user));
        return onlyLocal
            ? Mono.empty()
            : dispatcher.relay(RelayRequest
                .forDelete()
                .servicePath("/delete/" + path)
                .excludeLocalStar()
                .param("user", user)
                .param("onlyLocal", "true")
                .userToken(userToken)
                .build(), Void.class)
            .then();
    }

    @PostMapping(value = "/restore/**", produces = "text/plain")
    @RequiresRole(ROLE_ADMIN)
    public Mono<Void> restore(@WildcardParam String path,
                              @RequestParam(required = false) Optional<Integer> version,
                              @RequestParam(value="user", required=false) @Nullable String user,
                              @RequestParam(value="onlyLocal", defaultValue = "false") boolean onlyLocal,
                              UserToken userToken) {
        Threads.execute(() -> repository.restore(path, user == null ? userToken.name : user, version));
        return onlyLocal
            ? Mono.empty()
            : dispatcher.relay(RelayRequest
                .forPost()
                .servicePath("/restore/" + path)
                .excludeLocalStar()
                .param("user", user)
                .param("onlyLocal", "true")
                .userToken(userToken)
                .build(), Void.class)
            .then();
    }

    @GetMapping(value = "/versions/**", produces = "application/json")
    @RequiresRole(ROLE_ADMIN)
    public List<FileHistory.FileVersion> getVersions(@WildcardParam String path) {
        return repository.getVersions(path);
    }

    @GetMapping(value = "/all-history-names", produces = "application/json")
    @RequiresRole({ROLE_ADMIN,ROLE_SERVICE})
    public List<String> getAllHistoryNames() {
        return repository.getAllHistoryNames();
    }

    @GetMapping(value = "/combined/{profiles}", produces = "application/json")
    @RequiresRole({ROLE_ADMIN,ROLE_SERVICE})
    public ImmutableMap<String,Object> getCombinedSettings(@PathVariable("profiles") String profiles,
                                                           @RequestHeader(MicroStarConstants.HEADER_X_SERVICE_UUID) UUID serviceInstanceId,
                                                           @RequestHeader(MicroStarConstants.HEADER_X_SERVICE_ID) ServiceId serviceId) {
        log.info("Got settings request from " + serviceId + " of instance " + serviceInstanceId + " with profiles " + profiles);
        return repository.getCombinedSettings(serviceInstanceId, serviceId, ImmutableList.copyOf(List.of(profiles.split(",")))).getSettingsMap();
    }

    @GetMapping(value = "/services-using/**", produces = "application/json")
    @RequiresRole(ROLE_ADMIN)
    public ImmutableList<ServiceId> getServicesUsingFile(@WildcardParam String path) {
        return repository.getServicesUsingFile(path);
    }

    @PostMapping(value = { "/refresh/**", "/refresh" }, produces = "text/plain")
    @RequiresRole({ROLE_ADMIN,ROLE_SERVICE})
    public void refresh(@WildcardParam String serviceName) {
        Threads.execute(() -> {
            log.info("Refresh requested of serviceName:{}", serviceName);
            if (serviceName.isEmpty()) {
                repository.updateAllServicesForChangedContent();
            } else {
                repository.updateServiceForChangeContent(ServiceId.of(serviceName));
            }
        });
    }

    @PostMapping(value = "/sync", produces = "application/json")
    @RequiresRole(ROLE_ADMIN)
    public Mono<Set<String>> syncWithOtherStars() {
        return repository.syncWithSettingsFromOtherStars();
    }

}
