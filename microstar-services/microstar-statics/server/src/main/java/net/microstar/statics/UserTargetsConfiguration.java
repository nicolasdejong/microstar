package net.microstar.statics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.StringUtils;
import net.microstar.spring.DataStores;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.IllegalInputException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.settings.client.SettingsService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;
import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.statics.DataService.DATASTORE_NAME;

/** Class to set userTarget configuration in configuration file on settings server */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTargetsConfiguration {
    private static final String SETTINGS_FILENAME = "microstar-statics.yaml"; // doesn't support imports
    private final SettingsService settingsService;
    private final AtomicReference<Map<String,String>> userToGroup = new AtomicReference<>(new HashMap<>());
    private static final DynamicReferenceNotNull<DataStore> root = DataStores.get(DATASTORE_NAME);
    private final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class)
        .onChange(statProps ->
            userToGroup.set(
                statProps.userGroups.entrySet().stream() // groupName -> users
                    .flatMap(e -> e.getValue().stream().map(user -> Map.entry(user, e.getKey()))) // user -> groupName
                    .collect(ImmutableUtil.toImmutableMap())
            )
        ).callOnChangeHandlers();

    public String getTargetForUser(String userName, String pathNameIn) {
        final String pathName = pathNameIn.replace("\\","/");

        return getUserGroupOfUser(userName)
            .or(() -> Optional.of(userName))
            .flatMap(this::getTargetsOfGroup)
            .or(this::getDefaultTargets)
            .flatMap(targets -> getFirstTargetThatHasRequestedResource(targets, pathName))
            .or(this::getFirstDefaultTarget)
            .or(this::getFirstTarget)
            .orElse("");
    }

    private Optional<String> getUserGroupOfUser(String userName) {
        return Optional.ofNullable(userToGroup.get().get(userName));
    }
    private Optional<List<String>> getTargetsOfGroup(String groupName) {
        return Optional.ofNullable(props.get().userTargets.get(groupName))
            .filter(not(List::isEmpty));
    }
    private Optional<String> getFirstTargetThatHasRequestedResource(List<String> targets, String resourceName) {
        return targets.stream() // find first target that has the requested resource
            .filter(target -> getStore().exists(concatPath(target, resourceName)))
            .findFirst();
    }
    private Optional<List<String>> getDefaultTargets() {
        return Optional.ofNullable(props.get().userTargets.get("default")).filter(not(List::isEmpty));
    }
    private Optional<String> getFirstDefaultTarget() {
        return getDefaultTargets().map(list -> list.get(0));
    }
    private Optional<String> getFirstTarget() {
        return props.get().userTargets.values().stream().findFirst().filter(not(List::isEmpty)).map(list -> list.get(0));
    }

    /** Returns targets map */
    public Mono<Map<String,List<String>>> set(Map<String,List<String>> newUserTargets, UserToken userToken) {
        log.info("set new userTargets: {}", newUserTargets);
        return settingsService.getFile(SETTINGS_FILENAME)
            .flatMap(settings -> Mono.justOrEmpty(updateSettingsForNewUserTargets(settings, newUserTargets)))
            .flatMap(newSettings -> settingsService.updateSettingsFile(SETTINGS_FILENAME, newSettings, userToken))
            .thenReturn(newUserTargets);
    }

    /** Returns true on changed -- Note: target can be empty, meaning ignore */
    public Mono<Map<String,List<String>>> set(String user, String targetToAdd, UserToken userToken) {
        log.info("set-target for user '{}' to: '{}'", user, targetToAdd);
        validateTargetPath(targetToAdd);
        final boolean targetHasIndex = hasIndex(targetToAdd);
        final Map<String,List<String>> userTargets = new LinkedHashMap<>(props.get().userTargets);
        final List<String> targets = new ArrayList<>(Optional.ofNullable(userTargets.get(user)).orElse(Collections.emptyList())
            .stream()
            .filter(target -> !targetHasIndex || !hasIndex(target))
            .toList());

        targets.add(targetToAdd);
        userTargets.put(user, targets);
        return set(userTargets, userToken);
    }

    private boolean hasIndex(String path) {
        return getStore().exists(concatPath(path, "index.html"));
    }

    private void validateTargetPath(String target) {
        if(!target.isEmpty() && !getStore().exists(target)) throw new IllegalInputException("Target does not exist: " + target).log();
    }

    /** Simple search-replace has its drawbacks (brittle) but low complexity -- good enough for now */
    Optional<String> updateSettingsForNewUserTargets(String settingsTextIn, Map<String,List<String>> newUserTargets) {
        final String settingsText = settingsTextIn.replaceAll("\r\n|\r", "\n");
        final Map<String,String> remarks = new HashMap<>();
        final String textWithoutRemarks = StringUtils.replaceGroups(settingsText, "\\s+#([^\n]+)", rem -> {
           final String key = "#" + remarks.size();
           remarks.put(key, rem);
           return key;
        });
        final String replacement = toYaml(newUserTargets);
        String newSettingsText = StringUtils.replaceRegex(
            textWithoutRemarks,
            "(^|\\n)([^\\S\n]*)?(\\S+\\.)?userTargets:\\s*?\\n(\\2\\s+[^\n]+\n)+(\\s*\n)*",
            groups ->
                groups[1] + groups[2] + notNull(groups[3])
                    + (replacement.trim().isEmpty()
                        ? "userTargets: {}\n\n"
                        : "userTargets:\n" + prefixWithSpaces(replacement, groups[2].length() + 2) + "\n\n"
                      )
        );
        if(newSettingsText.equals(textWithoutRemarks) && !replacement.trim().isEmpty()) { // Nothing to replace? Then add at end
            newSettingsText = textWithoutRemarks.trim() + "\n\n"
                + "app.config.statics.userTargets:\n"
                + prefixWithSpaces(replacement, 2)
                + "\n\n";
        }
        final String result = StringUtils.replaceGroups(newSettingsText, "\\s+#(#\\d+)", remarks::get).replaceFirst("\\s*$", "\n");
        return result.equals(settingsText) ? Optional.empty() : Optional.of(result);
    }

    private static String toYaml(Map<String, List<String>> map) {
        return map.entrySet().stream()
            .map(entry -> entry.getKey() + ": [ "
                + entry.getValue().stream().map(s -> s.matches(".*[,:{}\\s].*") ? "\"" + s + "\"" : s).collect(Collectors.joining(", "))
                + " ]")
            .collect(Collectors.joining("\n"));
    }
    private static String prefixWithSpaces(String input, int count) {
        return input.replaceAll("(?m)^", " ".repeat(count));
    }
    private static String notNull(@Nullable String s) { return s == null ? "" : s; }
    private static BlockingDataStore getStore() {
        return BlockingDataStore.forStore(root);
    }
}
