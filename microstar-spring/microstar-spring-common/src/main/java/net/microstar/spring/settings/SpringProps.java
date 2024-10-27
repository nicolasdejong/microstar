package net.microstar.spring.settings;

import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.StringUtils;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.application.AppSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.microstar.common.util.CollectionUtils.reverse;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.noThrowMap;

/**
 * Utility class for handling Spring property (yaml) data.<pre>
 *
 * Includes functionality to flatten and unflatten maps. There are actually three different types:
 * - flattened, multiples of:
 *     keyA.keyB.[dotted.key].etc -> string or number value
 * - unflattened, deep structure:
 *     keyA -> keyB -> dottedKey -> etc -> value
 *          -> keyC -> etc -> value
 * - combination of flattened and unflattened. User configuration files are like this. So e.g.:
 *     keyA.keyB -> keyC.[dotted.key] -> etc
 *               -> keyD -> etc
 *
 * The mapFromYaml()/documentsFromYaml() call unflatten() to convert from combined to unflattened
 */
@Slf4j
@SuppressWarnings({"unchecked", "rawtypes", "squid:S3740", "OverlyComplexClass", "varargs"}) // Recursive Map<String,Object> where Object can be multiple types, like list, map, string or number
public final class SpringProps {
    private static final String PROFILES_KEY = "spring.profiles.active";
    private static final String PROFILES_DEFAULT = "default";
    private static final String ENCRYPTED_VALUE_PREFIX = "{cipher}";
    private static final DynamicPropertiesRef<EncryptionSettings> encryptionSettings = DynamicPropertiesRef.of(EncryptionSettings.class);
    private static final Set<String> failedDecrypts = Collections.synchronizedSet(new HashSet<>());
    private static long lastFailedDecryptTime = 0;
    private SpringProps() {/*singleton*/}


    public static List<String> getActiveProfileNames(String... commandLineArgs) {
        return List.of(fromCommandLine(PROFILES_KEY, commandLineArgs)
            .or(() -> fromSystemProperties(PROFILES_KEY))
            .or(() -> Optional.ofNullable(System.getenv(PROFILES_KEY.replace(".","_"))))
            .orElse(PROFILES_DEFAULT)
            .split("\\s*,\\s*"));
    }

    public static Optional<String> fromCommandLine(String key, String... args) {
        return Stream.of(args)
            .filter(arg -> arg.startsWith("--%s=".formatted(key)))
            .map(arg -> arg.split("=")[1])
            .map(val -> decrypt(val, key))
            .findFirst();
    }
    public static Optional<String> fromSystemProperties(String key) {
        return StringUtils.getObfuscatedSystemProperty(key).map(val -> decrypt(val, key));
    }
    public static Optional<String> fromResource(String key, String... names) {
        return Stream.of(names)
            .flatMap(name -> getPropsResource(name).stream())
            .flatMap(yamlText -> PropsMap.fromYamlMultiple(yamlText).stream())
            .flatMap(document -> document.getString(key).stream())
            .findFirst();
    }

    public static PropsMap mergePropertySources(PropertySources sources) {
        return reverse(sources.stream()) // first overrides second, second overrides third, etc
            .filter(ps -> ps instanceof EnumerablePropertySource<?>)
            .map(ps -> toPropsMap((EnumerablePropertySource) ps))
            .map(map -> decrypt(map, ""))
            .reduce(PropsMap.empty(), (a, b) -> PropsMap.getWithOverrides(a,b));
    }
    public static PropsMap toPropsMap(EnumerablePropertySource props) {
        return PropsMap.fromFlatMap(
            Stream.of(props.getPropertyNames())
                .map(propName -> Optional.ofNullable(props.getProperty(propName)).map(val -> Map.entry(propName, val)).orElse(null))
                .filter(Objects::nonNull)
                .collect(ImmutableUtil.toImmutableMap())
        );
    }

    public static List<String>  getConfigurationPropertiesNamesOf(Object propertiesTypeOrInstance) {
        return getConfigurationPropertiesNamesOf(typeOf(propertiesTypeOrInstance), getConfigurationPropertiesPrefixOf(propertiesTypeOrInstance));
    }
    public static List<String>  getConfigurationPropertiesNamesOf(Class<?> propsClass, String prefix) {
        final class Inner {
            private Inner() {}
            private static String prependPrefix(String prefix, String name) {
                return prefix.isEmpty() ? name : String.join(".", prefix, name);
            }
            private static boolean hasPublicGetterFor(Field field) {
                final String getterSuffix = field.getName().substring(0, 1).toUpperCase() + (field.getName().length() > 1 ? field.getName().substring(1) : "");
                return getDeclaredMethod(field.getDeclaringClass(), "get" + getterSuffix, "is" + getterSuffix)
                    .map(method -> Modifier.isPublic(method.getModifiers()) && method.getReturnType() == field.getType())
                    .orElse(false);
            }
            private static Optional<Method> getDeclaredMethod(Class<?> clazz, String... methodNames) {
                for(final String methodName : methodNames) {
                    try {
                        return Optional.of(clazz.getDeclaredMethod(methodName));
                    } catch (final NoSuchMethodException ignored) {/*no method for this name*/}
                }
                return Optional.empty();
            }
        }
        final Set<Class<?>> innerClasses = Stream.of(propsClass.getDeclaredClasses())
            .filter(type -> !type.isEnum())
            .collect(Collectors.toSet());
        return Stream.of(propsClass.getDeclaredFields())
            .filter(field -> Modifier.isPublic(field.getModifiers()) || Inner.hasPublicGetterFor(field))
            .flatMap(field ->
                innerClasses.contains(field.getType())
                    ? getConfigurationPropertiesNamesOf(field.getType(), Inner.prependPrefix(prefix, field.getName())).stream()
                    : Stream.of(Inner.prependPrefix(prefix, field.getName()))
            ).toList();
    }
    public static String        getConfigurationPropertiesPrefixOf(Object typeOrInstance) {
        @Nullable final ConfigurationProperties props = typeOf(typeOrInstance).getAnnotation(ConfigurationProperties.class);
        return Optional.ofNullable(props).map(ConfigurationProperties::prefix)
            .filter(s -> !s.isEmpty())
            .orElseGet(() -> Optional.ofNullable(props).map(ConfigurationProperties::value).orElse(""));
    }
    private static Class<?> typeOf(Object obj) { return obj instanceof Class type ? type : obj.getClass(); }

    @SafeVarargs
    public static MutablePropertySources propertySources(Map.Entry<String, Map<String,Object>>... maps) {
        final List<PropertySource<?>> listOfPropertySources = new ArrayList<>();
        for(final Map.Entry<String,Map<String,Object>> entry : decrypt(List.of(maps), "property source")) {
            listOfPropertySources.add(new MapPropertySource(entry.getKey(), entry.getValue()));
        }
        return propertySources(listOfPropertySources);
    }
    public static MutablePropertySources propertySources(List<PropertySource<?>> listOfPropertySources) {
        return new MutablePropertySources(new PropertySources() {
            final List<PropertySource<?>> propertySources = listOfPropertySources;

            @Override
            public boolean contains(String name) {
                return propertySources.stream().anyMatch(ps -> ps.getName().equals(name));
            }
            @Override @Nullable
            public PropertySource<?> get(String name) {
                return propertySources.stream()
                    .filter(ps -> ps.getName().equals(name))
                    .findFirst()
                    .orElse(null);
            }
            @Override
            public Iterator<PropertySource<?>> iterator() {
                return propertySources.iterator();
            }
        });
    }


    public static <T> T decrypt(T in, String errorHint) {
        return in instanceof String s  ? (T)decrypt(s, errorHint) :
               in instanceof Map map   ? (T)decrypt((Map<String,Object>)map, errorHint) : // NOSONAR -- casting
               in instanceof List list ? (T)decrypt(list, errorHint)
             : in;
    }
    public static String decrypt(String in, String errorHint) {
        try {
            return in.startsWith(ENCRYPTED_VALUE_PREFIX)
                ? encryptionSettings.get().decrypt(in.substring(ENCRYPTED_VALUE_PREFIX.length()))
                : in;
        } catch(final Exception e) {
            final long now = System.currentTimeMillis();
            if(now - lastFailedDecryptTime > 10 * 1000) failedDecrypts.clear();
            lastFailedDecryptTime = now;
            if(!failedDecrypts.contains(errorHint)) {
                failedDecrypts.add(errorHint);
                log.warn("DECRYPTION FAILED for {}", errorHint);
            }
            return in;
        }
    }
    public static <T> List<T> decrypt(List<T> list, String errorHint) {
        return list.stream()
                .map(item -> {
                    //Optional.of(decrypt(item)).stream()
                    if(item instanceof String stringValue) return (T)decrypt(stringValue, errorHint + "[]");
                    if(item instanceof List childList) return (T)decrypt(childList, errorHint + "[]");
                    if(item instanceof Map map) return (T)decrypt(map, errorHint + "[]");
                    return item;
                })
                .toList();
    }
    public static Map<String,Object> decrypt(Map<String,Object> kvMap, String errorHint) {
        return kvMap.entrySet().stream()
                .map(entry -> {
                    final String key = entry.getKey();
                    final Object val = entry.getValue();
                    if(val instanceof String stringValue) return Map.entry(key, (Object)decrypt(stringValue, dotJoin(errorHint, key)));
                    if(val instanceof List childList) return Map.entry(key, (Object)decrypt(childList, dotJoin(errorHint, key)));
                    if(val instanceof Map map) return Map.entry(key, (Object)decrypt(map, dotJoin(errorHint, key)));
                    return entry;
                })
                .collect(ImmutableUtil.toImmutableMap());
    }
    public static Set<String> getFailedDecrypts() {
        return ImmutableSet.copyOf(failedDecrypts);
    }

    /** Normalize a deep map recursively by making sure all values are one of: map, list, string, number, null.
      * Other values will be replaced by their string equivalent (by calling Object.toString() on them).
      */
    public static Map<String,?> normalizeDeepMap(Map<String,?> map) {
        class Inner {
            static Map<String,?> normalize(Map<String,?> map) {
                return map.entrySet().stream().map(entry -> Map.entry(entry.getKey(), normalize(entry.getValue()))).collect(ImmutableUtil.toImmutableMap());
            }
            static List<Object> normalize(List<?> list) {
                return new ArrayList<>(list.stream().map(Inner::normalize).toList());
            }
            static List<Object> normalize(Set<?> set) {
                return new ArrayList<>(set.stream().map(Inner::normalize).toList());
            }
            static Object normalize(Object obj) {
                if(obj instanceof Map<?,?> m) return normalize((Map<String,?>)m);
                if(obj instanceof List<?> l) return normalize(l);
                if(obj instanceof Set<?> s) return normalize(s);
                if(obj instanceof Number n) return n;
                if(obj instanceof String s) return s;
                // now obj is an unsupported type that may lead to invalid results (or throw) when yaml dumping, so replace it with string representation
                return noThrowMap(() -> (Object)obj.toString(), ex -> obj.getClass().getSimpleName() + "(" + ex.getMessage() + ")");
            }
        }
        return Inner.normalize(map);
    }

    private static String dotJoin(String... elements) {
        return Arrays.stream(elements).filter(s -> !s.isEmpty()).collect(Collectors.joining("."));
    }
    private static Optional<String> getPropsResource(String name) {
        return Optional.of("/" + name.replaceFirst("^/+", ""))
            .flatMap(s -> Stream.of(".yml", ".yaml", ".properties")
                .map(ext -> s + ext)
                .map(AppSettings.class::getResourceAsStream)
                .filter(Objects::nonNull)
                .flatMap(dataStream -> noThrow(dataStream::readAllBytes).stream())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .findFirst()
            );
    }
}