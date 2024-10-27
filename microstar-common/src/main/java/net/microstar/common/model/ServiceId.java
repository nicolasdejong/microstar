package net.microstar.common.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import net.microstar.common.util.Reflection;
import net.microstar.common.util.VersionComparator;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.StringUtils.getRegexGroups;

/**
 * Identifier of a service (branchName/serviceName/serviceVersion).
 * Note that there can be multiple instances of the same service. They can be uniquely
 * identified by their ServiceInstanceId. For example if a particular service needs
 * much CPU, it can be started on several machines where the Dispatcher then load
 * balances between. They will all have the same ServiceId, but a different ServiceInstanceId.<p>
 *
 * The format of the ServiceId should look similar to what is usual for jar names,
 * optionally preceded by a group name. So a typical service with version 1.0 should
 * be "service-1.0". A group before that would lead to "group_service-1.0". The delimiter
 * between group and service name should be non-alphanumeric and not a dash. So:<pre>
 *
 * Format of the ServiceId is:
 *
 * - Optional group name. Should be followed by a non-alphanumeric character that
 *   is not in group name, which separates it from the service name that follows.
 * - Service name. Should not contain numbers preceded by a non-alphanumeric character.
 * - Optional version that should start with a number and is preceded by a non-alphanumeric
 *   character.
 * NOTE: the dash is never seen as a delimiter between group and name but as a binder instead.
 * NOTE: parsing is done on the version first, found by non-alphanumeric followd by digit.
 *       only then the rest of the name is parsed.
 * NOTE: no version leads to no group: the whole input is interpreted to be a service name.
 */
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ServiceId implements Comparator<ServiceId> {
    public static final ServiceId NONE = ServiceId.of("", "none", "0");
    public static final String DEFAULT_GROUP_NAME = "main";
    private static final VersionComparator comparator = VersionComparator.OLDEST_TO_NEWEST;
    private static final String REMOVE_PATH_PREFIX_REGEX = "^.*/([^/]+)$";
    private static final String REMOVE_PATH_EXT_REGEX = "^(.*)\\.jar(!.*)?$";
    private static final AtomicReference<ServiceId> defaultId = new AtomicReference<>();

    public final String group;   // these three are all
    public final String name;    // derived from combined
    public final String version; // but exist for convenience
    @EqualsAndHashCode.Include
    public final String combined;

    public ServiceId(String codeLocationPathOrIdentifier) {
        final String slashedInput = codeLocationPathOrIdentifier.replaceAll("[\\\\/]+", "/").replace("/server/", "/");
        final String input = slashedInput.matches(REMOVE_PATH_EXT_REGEX)
            ? slashedInput
                .replaceAll(REMOVE_PATH_EXT_REGEX, "$1")
                .replaceAll(REMOVE_PATH_PREFIX_REGEX, "$1")
            : slashedInput;

        // The most simple use case: group/name/version
        final int slashCount = input.replaceAll("[^/]", "").length();
        if(slashCount > 0) {
            final String[] parts = (slashCount > 2 ? input.replaceAll("^.*?/([^/]+)/?$", "/$1/") : input).split("/", -3);
            group = notEmpty(parts[0], DEFAULT_GROUP_NAME);
            name = sanitizeName(parts[1]);
            version = parts.length < 3 ? "0" : notEmpty(parts[2], "0");
        } else {
            // Split version (which is preceded by non-alphanumeric) from the rest before version
            final List<String> restAndVersion = getRegexGroups(input, "^(.*?)[^A-Za-z0-9](\\d.*)$");

            // No version? Then no group when no slashes. The string is interpreted as just a service name.
            if (restAndVersion.isEmpty()) {
                final String[] parts = input.split("/");
                group = sanitize(parts.length == 2 ? parts[0] : "", DEFAULT_GROUP_NAME);
                name = sanitizeName(parts.length == 2 ? parts[1] : input);
                version = "0";
            } else {
                version = restAndVersion.get(1);
                final String groupAndName = restAndVersion.get(0);

                // Find delimiter to split on, which is the non-alphanumeric character that appears only once
                final Map<Character, Integer> charCount = new HashMap<>();
                groupAndName.chars()
                    .mapToObj(i -> (char) i)
                    .filter(cVal -> !Character.isLetterOrDigit(cVal))
                    .forEach(cVal -> charCount.compute(cVal, (ch, count) -> (count == null) ? 1 : count + 1))
                ;
                final String[] parts = charCount.getOrDefault('/', 0) == 1
                    ? groupAndName.split("/")
                    : charCount.entrySet().stream()
                    .filter(e -> e.getValue() == 1 && e.getKey() != '-')
                    .map(Map.Entry::getKey)
                    .map(Object::toString)
                    .findFirst()
                    .map(groupAndName::split)
                    .orElseGet(() -> new String[]{DEFAULT_GROUP_NAME, groupAndName});

                int i = 0;
                group = parts.length <= 1 ? DEFAULT_GROUP_NAME : parts[i++];
                name = sanitizeName(parts.length > i ? parts[i] : "");
            }
        }
        if(group.isBlank() || version.isBlank() ||
           name.replaceAll("[^A-Za-z\\d]", "").isBlank()) throw new IllegalArgumentException("Invalid serviceId provided: " + input);
        combined = String.join("/", group, name, version);
    }

    public static ServiceId get() {
        return Optional.ofNullable(defaultId.get())
            .or(() -> Optional.ofNullable(System.getProperty("spring.application.name")).map(ServiceId::of))
            .orElseGet(() -> of(Reflection.getCallerClass("*.common.*")));
    }
    public static ServiceId of(Class<?> serviceClass) {
            final String fullPath = serviceClass.getProtectionDomain().getCodeSource().getLocation().getPath();
            final String     path = fullPath.replace("\\","/").split("target/")[0].replace("/server/", "/");
            final boolean   isIDE = fullPath.contains("/target/") && !fullPath.contains(".jar");

            return new ServiceId(isIDE
                ? (getGitBranchName(path) + "_" + path.replaceAll("^.*/([^/]+)/?$", "$1-999-IDE"))
                : path
            );
    }
    public static ServiceId of(String codeLocationPathOrIdentifier) {
        return new ServiceId(codeLocationPathOrIdentifier);
    }
    public static ServiceId of(String group, String name, String version) {
        final String sGroup   = sanitize(group, DEFAULT_GROUP_NAME);
        final String sName    = sanitizeName(name);
        final String sVersion = sanitize(version, "0");
        return new ServiceId(sGroup, sName, sVersion, String.join("/", sGroup, sName, sVersion));
    }

    public static void setDefault(ServiceId defaultId) { ServiceId.defaultId.set(defaultId); }
    public ServiceId setAsDefault() { setDefault(this); return this; }

    @Override @JsonValue
    public String toString() { return combined; }
    public String withoutVersion() { return String.join("/", group, name); }

    public boolean isNewerThan(ServiceId other) {
        return comparator.compare(version, other.version) > 0;
    }

    @Override
    public int compare(ServiceId o1, ServiceId o2) {
        final int groupCmp = comparator.compare(o1.group, o2.group);
        if(groupCmp != 0) return groupCmp;

        final int nameCmp = comparator.compare(o1.name, o2.name);
        if(nameCmp != 0) return nameCmp;

        return comparator.compare(o1.version, o2.version);
    }


    private static String sanitize(String in, String valueIfEmpty) {
        return notEmpty(in, valueIfEmpty).replace("/", "-");
    }
    private static String sanitizeName(String name) {
        //noinspection ConstantConditions -- sanity check
        if(name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be empty");
        return name
            .replaceAll("^[^A-Za-z\\d]+", "")
            .replaceAll("[^A-Za-z\\d]+$", "")
            .replace("/", "-")
            ;
    }
    private static String notEmpty(String s, String valueIfEmpty) {
        //noinspection ConstantConditions -- sanity check
        return s == null || s.isBlank() ? valueIfEmpty : s;
    }

    private static Properties propertiesFrom(String propsText) {
        final Properties props = new Properties();
        noThrow(() -> props.load(new StringReader(propsText)));
        return props;
    }

    /** Called when running from IDE to determine service group */
    private static String getGitBranchName(String path) {
        Path p = pathOf(path).toAbsolutePath();
        for(; p.getParent() != null; p = p.getParent()) {
            if(Files.exists(p.resolve(".git"))) {
                final Path fp = p;
                return noThrow(() -> Files.readString(fp.resolve(".git/HEAD")))
                    .map(ServiceId::branchToGroupName)
                    .orElse(DEFAULT_GROUP_NAME);
            }
        }
        return DEFAULT_GROUP_NAME;
    }

    static String branchToGroupName(String branchName) {
        return branchName
            .replaceFirst("^.*?/([^/]+)$", "$1") // remove origin path to only keep name
            .replaceAll("^(.*?\\d{2,})[-_.\\s].*$", "$1") // e.g. "STORY1234-rest-is-removed"
            .replaceAll("^\\D{1,4}-(\\d+.*)$", "$1") // "AB-1234-etc" -> "1234-etc"
            .replaceAll("^(.{10})([^-]{1,5})?.*$", "$1$2") // truncate too long, try to stop at dash
            .trim()
            ;
    }
}
