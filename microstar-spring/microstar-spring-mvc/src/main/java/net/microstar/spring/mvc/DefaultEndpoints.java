package net.microstar.spring.mvc;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.exceptions.ValidationException;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.ProcessInfo;
import net.microstar.common.util.Threads;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.mvc.facade.settings.SettingsService;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.settings.SpringProps;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.microstar.common.util.StringUtils.startsWithUpperCase;
import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RestController
@RequestMapping(
    consumes = MediaType.ALL_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
)
@AllArgsConstructor
public class DefaultEndpoints {
    private final MicroStarApplication microstarApplication;
    private final SettingsService settingsService;
    private final DynamicPropertiesRef<EncryptionSettings> encSettings = DynamicPropertiesRef.of(EncryptionSettings.class);

    @RequestMapping("/version")
    public ResponseEntity<ServiceId> version() {
        return ResponseEntity.ok(microstarApplication.serviceId);
    }

    @PostMapping("/restart")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public ResponseEntity<Void> restart() {
        log.info("Received restart command");
        microstarApplication.restart();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public ResponseEntity<Void> stop() {
        microstarApplication.stop();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/hash/**")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String hash(@WildcardParam String input) {
        return encSettings.get().hash(input);
    }

    @GetMapping("/encrypt/**")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String encrypt(@WildcardParam String input) {
        return encSettings.get().encrypt(input);
    }

    @GetMapping("/decrypt/**")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String decrypt(@WildcardParam String input) {
        return encSettings.get().decrypt(input);
    }


    // TODO '/delete' for a service to delete itself from storage and stop executing


    @GetMapping("/refresh-settings")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public ResponseEntity<Void> refreshSettings() {
        log.info("refresh settings");

        Threads.execute(() -> AppSettings.handleExternalSettingsText(settingsService.getSettings()));

        return ResponseEntity.ok().build();
    }

     /** Returns combined settings (from all property sources) as JSON at a given path */
    @GetMapping(value = "/combined-settings/**", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public Map<String,Object> getCombinedSettingsMap(@WildcardParam String path) {
        return DynamicPropertiesManager.getCombinedSettings().getMap(path).orElse(ImmutableUtil.emptyMap());
    }

    /** Returns combined settings (from all property sources) as YAML at a given path */
    @SuppressWarnings("unchecked")
    @GetMapping(value = "/combined-settings/**", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String getCombinedSettingsYaml(@WildcardParam String path) {
        final String loggingKey = "logging";
        final String levelKey = "level";
        final Map<String,Object> map = DynamicPropertiesManager.getCombinedSettings().getMap(path).orElse(ImmutableUtil.emptyMap());
        final Map<String,Object> sortedMap = new TreeMap<>((s1, s2) ->
            startsWithUpperCase(s1) && !startsWithUpperCase(s2) ? 1 :
           !startsWithUpperCase(s1) &&  startsWithUpperCase(s2) ? -1 :
            s1.compareTo(s2));

        sortedMap.putAll(ImmutableUtil.toMutable(SpringProps.normalizeDeepMap(map)));
        @Nullable final Map<?,?> logging = (Map<?,?>)sortedMap.get(loggingKey);
        if(logging != null) {
            @Nullable final Map<String,Object> levels = (Map<String,Object>)logging.remove(levelKey);
            if(levels != null) sortedMap.put("logging.level", new TreeMap<>(PropsMap.fromFlatMap(levels).getMap()));
            if(logging.isEmpty()) sortedMap.remove(loggingKey);
        }

        final DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        return new Yaml(dumperOptions).dump(sortedMap);
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
        try {
            DynamicPropertiesManager.validateYaml(settingsText);

            //
            final Set<String> fails = SpringProps.getFailedDecrypts();
            if(!fails.isEmpty()) throw new ValidationException("Failed decrypts: " + String.join(", ", fails));

            // No exceptions? Then all went well and no error has to be returned
            return "";
        } catch(final Exception e) {
            log.info("Verify is returning error");
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @GetMapping("/default-settings")
    @RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
    public String getDefaultSettings() {
        return IOUtils.getResourceAsString(MicroStarApplication.getApplicationClass(), "application.yml", "application.yaml", "application.properties")
            .orElse("# no application.yml/yaml/properties found");
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
}
