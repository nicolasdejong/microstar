package net.microstar.dispatcher.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.DurationString;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Encryption;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.ProcessInfo;
import net.microstar.common.util.Threads;
import net.microstar.common.util.UserTokenBase;
import net.microstar.common.util.Utils;
import net.microstar.dispatcher.MiniBusDispatcher;
import net.microstar.dispatcher.model.DispatcherProperties.BootstrapProperties;
import net.microstar.dispatcher.services.ServiceJarsManager;
import net.microstar.dispatcher.services.ServiceProcessInfos;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotAllowedException;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.DefaultEndpoints;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.MiniBus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.function.Predicate.not;
import static net.microstar.common.MicroStarConstants.CLUSTER_SECRET;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
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
public class DispatcherController {
    private final MicroStarApplication application;
    private final Services services;
    private final DynamicPropertiesRef<EncryptionSettings> encSettings = DynamicPropertiesRef.of(EncryptionSettings.class);
    private final DynamicPropertiesRef<BootstrapProperties> bootstrapProps = DynamicPropertiesRef.of(BootstrapProperties.class);
    private final ServiceJarsManager jarsManager;
    private final EventEmitter eventEmitter;
    private final MiniBusDispatcher miniBusDispatcher;
    private final ObjectMapper objectMapper;
    private final MiniBus miniBus;
    private final ServiceProcessInfos serviceProcessInfos;

    /** Special bootstrap endpoint to login as admin, to be used when no other services are running
      * that can perform authentication (like microstar-authorization or sso).<p>
      *
      * The password should be set in app.config.dispatcher.bootstrap.adminPassword which can be provided
      * on the command line, so it is available even if the settings-service is not running.
      * If not set, the password is 'admin'.<p>
      *
      * This endpoint will not be available if any service is connected with names configured
      * in app.config.dispatcher.bootstrap.disableForServices or if app.config.dispatcher.bootstrap.enabled
      * is set to false.
      */
    @PostMapping("/admin-login")
    public String adminLogin(@RequestHeader("X-AUTH-PASSWORD") String adminLoginPassword) {
        final BootstrapProperties props = bootstrapProps.get();

        if(!props.adminEnabled || props.disableAdminWhenServices.stream()
            .map(ServiceId::of)
            .anyMatch(sid -> services.getListOfRegisteredServices().stream().anyMatch(reg -> reg.id.name.equals(sid.name)))
        ) throw new NotFoundException("adminLogin");

        if(!adminLoginPassword.equals(props.adminPassword)) throw new NotAllowedException("Wrong username/password");

        return UserTokenBase.builder().name("admin").id("0").build().toTokenString(CLUSTER_SECRET);
    }

    /** The DefaultEndpoints contains this endpoint as well but the Dispatcher
      * does not use the DefaultEndpoints because they use the Dispatcher as
      * proxy. The Dispatcher itself should directly call the settings service.
      */
    @GetMapping("/refresh-settings")
    @RequiresRole({ROLE_ADMIN,ROLE_SERVICE})
    public ResponseEntity<Void> refreshSettings(@Value("${spring.profiles.active:default}") String activeProfiles) {
        Threads.execute(() -> services.getClientForPath(HttpMethod.GET, "/microstar-settings/combined/" + activeProfiles)
            .flatMap(req -> req
                .headers(application::setHeaders)
                .exchangeToMono(response -> response.bodyToMono(byte[].class))
                .switchIfEmpty(Mono.just(new byte[0]))
            )
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .doOnNext(AppSettings::handleExternalSettingsText)
            .block()
        );
        return ResponseEntity.ok().build();
    }

    /** Returns combined settings (from all property sources) at a given path */
    @GetMapping(value = "/combined-settings/**", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public Map<String,Object> getCombinedSettingsMap(@WildcardParam String path) {
        return DynamicPropertiesManager.getCombinedSettings().getMap(path).orElse(ImmutableUtil.emptyMap());
    }

    /** Returns combined settings (from all property sources) as YAML at a given path */
    @GetMapping(value = "/combined-settings/**", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public String getCombinedSettingsYaml(@WildcardParam String path) {
        return new DefaultEndpoints(application, null, eventEmitter, null, null).getCombinedSettingsYaml(path);
    }

    /** Validate given settings text as yaml for all known classes in
      * this service that are annotated with DynamicProperties.
      * <p>
      * The validator will throw in case of errors but still a 200 will be returned,
      * containing the result of the validation (the thrown Exceptions as text).
      * An empty 200 result means validation showed all is well.
      */
    @PostMapping(value = "/validate-settings", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresRole(ROLE_ADMIN)
    public String validateSettings(@RequestBody String settingsText) {
        return new DefaultEndpoints(application, null, eventEmitter, null, null).validateSettings(settingsText);
    }

    @GetMapping("/default-settings")
    @RequiresRole({ROLE_ADMIN,ROLE_SERVICE})
    public String getDefaultSettings() {
        return IOUtils.getResourceAsString(MicroStarApplication.getApplicationClass(), "application.yml", "application.yaml", "application.properties")
            .orElseThrow(() -> new NotFoundException("This service has no application.yml/yaml/properties"));
    }

    @GetMapping("/hash/**")
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public String hash(@WildcardParam String input, @RequestHeader(value = "X-HASH-SALT", required = false) Optional<String> hashSalt) {
        final EncryptionSettings settings = encSettings.get();
        final Encryption encryption = new Encryption(
            hashSalt
                .filter(hs->!hs.isEmpty())
                .map(hs -> settings.settings.toBuilder().hashSalt(hs).build())
                .orElse(settings.settings)
        );
        return encryption.hashToString(input);
    }

    @GetMapping("/encrypt/**")
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public String encrypt(@WildcardParam String input,
                          @RequestHeader(value = "X-ENC-PASSWORD", required = false) Optional<String> encPassword,
                          @RequestHeader(value = "X-ENC-SALT", required = false) Optional<String> encSalt) {
        final EncryptionSettings settings = encSettings.get();
        final Encryption encryption = new Encryption(
            encSalt
                .filter(es->!es.isEmpty())
                .map(es -> settings.settings.toBuilder().encSalt(es).build())
                .orElse(settings.settings)
        );
        return encryption.encrypt(input, encPassword.orElse(settings.encPassword));
    }

    @GetMapping("/decrypt/**")
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public String decrypt(@WildcardParam String input,
                          @RequestHeader(value = "X-ENC-PASSWORD", required = false) Optional<String> encPassword,
                          @RequestHeader(value = "X-ENC-SALT", required = false) Optional<String> encSalt) {
        final EncryptionSettings settings = encSettings.get();
        final Encryption encryption = new Encryption(
            encSalt
                .filter(es->!es.isEmpty())
                .map(es -> settings.settings.toBuilder().encSalt(es).build())
                .orElse(settings.settings)
        );
        log.info("decrypt({}, {});", input, encPassword.orElse(settings.encPassword));
        return encryption.decrypt(input, encPassword.orElse(settings.encPassword));
    }

    @GetMapping("/generate-user-token")
    @RequiresRole({ROLE_ADMIN})
    public String generateUserToken(@RequestParam("id") String id,
                                    @RequestParam("name") String name,
                                    @RequestParam("email") String email,
                                    @RequestParam("lifetime") String lifetimeText,
                                    @RequestParam(value="customSecret", required = false) Optional<String> customSecret) {
        final UserToken userToken = UserToken.builder()
            .id(id.isEmpty() ? "1" : id)
            .name(name.isEmpty() ? "name" : name)
            .email(email.isEmpty() ? "name@domain.net" : email)
            .build();
        final Duration lifetime0 = noThrow(() -> DurationString.toDuration(lifetimeText)).orElse(Duration.ofHours(24));
        final Duration lifetime = Duration.ofSeconds(lifetime0.getSeconds() + 1); // to make result 24h instead of 23h59m59s950ms
        return customSecret.filter(not(String::isEmpty)).map(secret -> userToken.toTokenString(secret, lifetime))
            .orElseGet(() -> userToken.toTokenString(CLUSTER_SECRET, lifetime));
    }

    @GetMapping("/user-token/{token}")
    public Map<String,String> getUserToken(@PathVariable("token") String token) {
        try {
            final UserToken userToken = UserToken.fromTokenString(token);
            return Map.of(
                "id", userToken.id,
                "name", userToken.name,
                "email", userToken.email,
                "timeLeft", Optional.of(UserTokenBase.getTokenExpire(token, CLUSTER_SECRET) - System.currentTimeMillis())
                    .map(timeLeft -> timeLeft <= 0 ? "expired" : DurationString.toString(Duration.ofMillis(timeLeft)))
                    .orElseThrow()
            );
        } catch(final Exception e) {
            return Map.of(
                "error", e.getMessage()
            );
        }
    }

    @Builder
    public static final class MeData {
        public final String id;
        public final String name;
        public final String email;
        public final String token;
        public final String tokenExpire;
    }

    @GetMapping({"/whoami/**", "/who-am-i/**"})
    public MeData whoAmI(@WildcardParam String tokenText) {
        return localWhoAmI(tokenText);
    }

    @GetMapping({"/whoami", "/who-am-i"})
    public MeData whoAmI(ServerWebExchange exchange) {
        return localWhoAmI(UserToken.raw(new HttpHeadersFacade(exchange.getRequest())).orElse(""));
    }

    private MeData localWhoAmI(String rawTokenText) {
        try {
            final UserToken userToken = UserToken.fromTokenString(rawTokenText, /*ignoreExpire*/true);
            return MeData.builder()
                .id(userToken.id)
                .name(userToken.name)
                .email(userToken.email)
                .token(rawTokenText)
                .tokenExpire(Optional.of(rawTokenText).map(token -> UserTokenBase.getTokenExpire(token, CLUSTER_SECRET) - System.currentTimeMillis())
                    .map(timeLeft -> timeLeft <= 0 ? "expired" : DurationString.toString(Duration.ofMillis(timeLeft)))
                    .orElse("0"))
                .build();
        } catch(final Exception invalidTokenException) {
            return MeData.builder()
                .id("invalid")
                .name("")
                .email("")
                .token("")
                .tokenExpire("invalid")
                .build();
        }
    }

    @GetMapping("/random-uuid")
    @RequiresRole(ROLE_ADMIN)
    public String getRandomUUID() {
        return UUID.randomUUID().toString();
    }

    @GetMapping("/version") // Open for all. Also used by Dispatcher to check version of another Dispatcher
    public ServiceId getVersion() {
        return application.serviceId;
    }

    @PostMapping("/stop")
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public ResponseEntity<Void> stopDispatcher() {
        log.info("Received request to stop this Dispatcher");
        application.stop();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop/{serviceInstanceId}")
    @RequiresRole(ROLE_ADMIN)
    public Mono<ResponseEntity<Void>> stop(@PathVariable("serviceInstanceId") UUID serviceInstanceId) {
        log.info("Received request to stop service {}/{}", services.getServiceFrom(serviceInstanceId).map(reg -> reg.id.combined).orElse("<not running>"), serviceInstanceId);
        return services.stop(serviceInstanceId);
    }

    @PostMapping("/start/**") // {serviceId} PathVariable not possible because of slashes in the name
    @RequiresRole(ROLE_ADMIN)
    public Mono<ResponseEntity<Void>> start(@WildcardParam String serviceIdText,
                                      @RequestParam(name="replaceAll", required = false, defaultValue = "false") boolean replaceRunning,
                                      @RequestParam(name="replaceInstanceId", required = false) Optional<UUID> replaceInstanceId) {
        final ServiceId serviceId = ServiceId.of(serviceIdText);
        if(serviceId.name.equals("microstar-dispatcher")) {
            log.info("Received request to start a new Dispatcher: {}", serviceId);
            log.info("Stopping this Dispatcher. Watchdog should pick up the given version to start");
            final Path versionFile = Path.of("microstar-dispatcher-version-to-run");
            IOUtils.del(versionFile);
            noCheckedThrow(() -> Files.writeString(versionFile, serviceId.toString()));
            application.stop();
            return Mono.just(ResponseEntity.ok().build());
        }
        log.info("Received request to start{} a {}", replaceRunning ? " & replace" : "", serviceId);
        services.getOrCreateServiceVariations(serviceId).startService(serviceId, replaceRunning, replaceInstanceId);

        return Mono.just(ResponseEntity.ok().build());
    }

    @DeleteMapping("/delete/**")
    @RequiresRole(ROLE_ADMIN)
    public ResponseEntity<Void> delete(@WildcardParam String serviceIdText) {
        final ServiceId serviceId = ServiceId.of(serviceIdText);
        log.info("Received request to delete {}", serviceId);
        jarsManager.getJars().stream()
            .filter(jar -> serviceId.equals(ServiceId.of(jar.name)))
            .findFirst()
            .ifPresent(jarsManager::deleteJar);
        return ResponseEntity.ok().build();
    }

    @GetMapping("jars/{jarName}") @RequiresRole(ROLE_SERVICE)
    public Mono<ResponseEntity<InputStreamResource>> getJar(@PathVariable("jarName") String jarName) {
        log.info("Jar requested: {}", jarName);
        return Mono.justOrEmpty(jarsManager.getJar(jarName))
            .flatMap(jarInfo -> Mono.fromFuture(jarInfo.store.get().readStream(jarInfo.name))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(InputStreamResource::new))
            .map(res -> ResponseEntity.ok().body(res))
            .switchIfEmpty(Mono.defer(() -> Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build())));
    }

    @PostMapping(value = "/jar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresRole({ROLE_ADMIN, "DEPLOYER"})
    public Mono<Void> uploadServiceJar(ServerWebExchange exchange) {
        return exchange.getMultipartData()
            .flatMapIterable(map -> map.toSingleValueMap().values())
            .filter(FilePart.class::isInstance)
            .map(FilePart.class::cast)
            .flatMap(filePart -> jarsManager.addJar(Mono.just(filePart)))
            .then();
    }

    // fallback for the case that the websocket for events is not working
    // (at the time of writing there is a problem with secure websockets)
    // Note that these requests will often result in a gateway timeout.
    // In those cases the client should just redo the request.
    @GetMapping("/poll-for-event") @RequiresRole(ROLE_ADMIN)
    public Mono<EventEmitter.ServiceEvent<?>> pollForEvent(@RequestParam(name="since", required = false) Optional<Long> since) { // NOSONAR -- ServiceEvent supports any data class
        return new DefaultEndpoints(application, null, eventEmitter, null, null).pollForEvent(since);
    }

    @GetMapping("/processInfo")
    @RequiresRole(ROLE_ADMIN)
    public ProcessInfo getProcessInfo() {
        return ProcessInfo.getLatest();
    }

    @GetMapping("/systemInfo")
    @RequiresRole(ROLE_ADMIN)
    public Map<String,Object> getSystemInfo() {
        return ProcessInfo.getSystemInfo();
    }

    @PostMapping("/miniBus")
    @RequiresRole(ROLE_SERVICE)
    public void handleMiniBusMessage(@RequestBody String messageText, @RequestParam("className") String className) throws ClassNotFoundException, JsonProcessingException {
        final MiniBus.BusMessage message = (MiniBus.BusMessage)objectMapper.readValue(messageText, Class.forName(className));
        Threads.execute(() -> miniBus.handleExternalMessage(message));
    }

    @PostMapping("/miniBusDispatcher")
    @RequiresRole(ROLE_SERVICE)
    public void dispatchMiniBusMessage(@RequestBody String messageText, @RequestParam("className") String className, @RequestParam("onlyLocalStar") boolean onlyLocalStar) {
        miniBusDispatcher.sendToAllServices(messageText, className, onlyLocalStar);
    }

    @PostMapping("/gc")
    @RequiresRole(ROLE_ADMIN)
    public ResponseEntity<Void> forceGarbageCollection() {
        log.info("Received garbage collect command");
        Utils.forceGarbageCollection();
        ProcessInfo.update();
        serviceProcessInfos.emitProcessInfosEvent();
        return ResponseEntity.ok().build();
    }
}