package net.microstar.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.spring.settings.PropsMap;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

@Slf4j
@RequiredArgsConstructor
public class SettingsCollector {
    private static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile("\\s*,\\s*");
    private final SettingsRepository repo;

    public static final class OrderedSettings {
        public final ImmutableSet<String> files;
        public final ImmutableList<PropsMap> documents;
        public final PropsMap settings;

        OrderedSettings() { this(Collections.emptySet(), Collections.emptyList()); }
        OrderedSettings(Set<String> files, List<PropsMap> docs) { this(ImmutableSet.copyOf(files), ImmutableList.copyOf(docs)); }
        OrderedSettings(ImmutableSet<String> files, ImmutableList<PropsMap> documents) {
            this.files = files;
            this.documents = documents;
            this.settings = documents.stream().reduce(PropsMap.empty(), (a, b) -> PropsMap.getWithOverrides(a, b));
        }

        public static OrderedSettings combine(OrderedSettings os1, OrderedSettings os2) {
            return new OrderedSettings(
                Stream.concat(os1.files.stream(), os2.files.stream()).collect(Collectors.toSet()),
                Stream.concat(os1.documents.stream().filter(not(PropsMap::isEmpty)),
                              os2.documents.stream().filter(not(PropsMap::isEmpty))).distinct().toList()
            );
        }
    }

    /** @see #getOrderedSettings(ServiceId, ImmutableList) */
    public OrderedSettings getOrderedSettings(ServiceId serviceId, String... profiles) {
        return getOrderedSettings(serviceId, ImmutableList.copyOf(List.of(profiles)));
    }

    /** <pre>
     * Try to load and override (yml can be yaml or properties as well):
     * - services.yml
     * - services-{profile}.yml (multiple times when multi-profile)
     * - {service}.yml
     * - {service}-{profile}.yml (multiple times when multi-profile)
     * - {serviceGroup}-{service}.yml
     * - {serviceGroup}-{service}-{profile}.yml (multiple times when multi-profile)
     *
     * Remove configurations that have a "spring.config.activate.on-profile" that not includes given profiles
     * Include configurations that are in the "spring.config.import", recursively
     *
     * Returns a list of settings documents and a list of read settings files, in order.
     * Since each file can contain multiple documents (via the --- separator) and
     * also documents can be inserted between (using spring.config.import) the
     * list of documents can be larger than the list of read files.
     * Finally, the returned OrderedSettings contains the merged settings.
     */
    public OrderedSettings getOrderedSettings(ServiceId serviceId, ImmutableList<String> profiles) {
        return Stream.of(
            Stream.of(                       getResourceForName("services")),
            profiles.stream().map(profile -> getResourceForName("services", profile)),
            Stream.of(                       getResourceForName(serviceId.name)),
            Stream.of(                       getResourceForName(serviceId.name, serviceId.version)),
            profiles.stream().map(profile -> getResourceForName(serviceId.name, profile)),
            Stream.of(                       getResourceForName(serviceId.group, serviceId.name)),
            profiles.stream().map(profile -> getResourceForName(serviceId.group, serviceId.name, profile))
        )
            .flatMap(Function.identity())
            .flatMap(Optional::stream)
            .flatMap(imports -> addImports(new OrderedSettings(), imports, profiles, new HashSet<>()))
            .reduce(new OrderedSettings(), OrderedSettings::combine);
    }

    private Optional<String> getResourceForName(String... parts) {
        return Optional.of(String.join("-", parts))
            .flatMap(s -> Stream.of("", ".yml", ".yaml", ".properties") // include empty in case ext is already given
                .map(ext -> (ext.isEmpty() ? s : s.replaceFirst("\\.[^.]+$", "") + ext)) // ext replaces existing ext (e.g .yml vs .yaml)
                .filter(repo::hasResourceWithName)
                .findFirst()
            );
    }
    private Stream<OrderedSettings> addImports(OrderedSettings orderedSettings, @Nullable Object imports, ImmutableList<String> profiles, Set<String> usedNames) {
        return Stream.concat(
            Stream.of(orderedSettings),
            toMultipleValues(imports)
                .filter(name -> !orderedSettings.files.contains(name) && !usedNames.contains(name))
                .peek(usedNames::add) // side effect for endless-loop detection (a -> b -> c -> a)
                .flatMap(name -> repo.getContentsOfOpt(name).stream()
                    .flatMap(yaml -> notEmpty(PropsMap.fromYamlMultiple(yaml.isEmpty() ? "{}" : yaml)).stream()) // to keep list of touched files, stream should not be empty
                    .filter(doc -> isActiveForProfile(doc, profiles))
                    .flatMap(doc -> addImports(new OrderedSettings(Set.of(name), List.of(doc)), getImportsFrom(doc), profiles, usedNames))
                )
        );
    }

    private Stream<String> getImportsFrom(PropsMap map) {
        return toMultipleValues(map.get("spring.config.import"))
            .flatMap(importName -> getResourceForName(importName).stream());
    }

    @SuppressWarnings({"unchecked", "rawtypes", "RedundantSuppression"}) // support value: "a,b,c" as well as value: [a, b, c] and value: -a\n -b\n -c
    private static Stream<String> toMultipleValues(@Nullable Object obj) {
        if(obj instanceof Optional opt && opt.isPresent()) return toMultipleValues(opt.get());
        if(obj instanceof Stream stream) return stream; // NOSONAR unchecked
        if(obj instanceof String text) return Stream.of(COMMA_SPLIT_PATTERN.split(text));
        if(obj instanceof List list && (list.isEmpty() || list.get(0) instanceof String)) return list.stream(); // NOSONAR unchecked
        return Stream.empty();
    }
    private static boolean isActiveForProfile(PropsMap map, List<String> profiles) {
        return map.get("spring.config.activate.on-profile")
            .map(act -> toMultipleValues(act).anyMatch(profile -> "*".equals(profile) || profiles.contains(profile)))
            .orElse(true); // on-profile key does not exist means allow all
    }
    private static List<PropsMap> notEmpty(List<PropsMap> in) {
        return in.isEmpty() ? List.of(PropsMap.empty()) : in;
    }
}
