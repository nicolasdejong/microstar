package net.microstar.spring.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.common.throwingfunctionals.ThrowingRunnable;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.Utils;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.application.RestartableApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.getRootCause;

/**
 * Manager that keeps the external settings (loaded from the settings server)
 * and which keeps a reference to all DynamicProperty instances which it will
 * update when new settings arrive. The dynamic properties that are affected
 * by new settings will be notified, so they can call their listeners that they
 * have been replaced.</p><p>
 *
 * Dynamic properties classes need to be annotated by @DynamicProperties(path)
 * and will be created using the Jackson ObjectMapper so need to be @Jacksonized.
 * For example:</p>
 *
 * <pre>
 * {@code
 *     @DynamicProperties('configuration.path')
 *     @Builder @Jacksonized
 *     public class MyProps {
 *         public final int number;
 *         public String int text;
 *     }
 * }</pre>
 * Which in the class that uses it is added like this:
 * <pre> {@code
 *
 *     private final DynamicPropertiesRef<MyProps> props = DynamicPropertiesRef.of(MyProps.class);
 *     ...
 *     private void someMethod() {
 *       final MyProps myProps = props.get();
 *       ...
 *     }
 *
 *     // Code can be notified of changes as well:
 *     private final DynamicPropertiesRef<MyProps> props = DynamicPropertiesRef.of(MyProps.class).onChange(...)
 *
 *     // A less safe alternative can be directly setting a field but
 *     // that means that props can change at any time, which is risky.
 *     private volatile MyProperties props = DynamicPropertiesManager.getInstanceOf(MyProps.class, this, newProps -> props = newProps);
 *
 *     // Code can also request an instance when needed
 *     private void someMethod() {
 *       final MyProps myProps = DynamicPropertyManager.getInstanceOf(MyProps.class);
 *       ...
 *     }
 * }</pre> <br/>
 *
 * This class cannot be a @Component because it is used before Spring starts
 * (on loading initial external settings) so it exists as a singleton with state
 * (which should be prevented when there is a non-trivial workaround which is
 * not the case here). This makes it possible to set bootstrap settings
 * (like log levels) as well from the settings service.
 */
@Slf4j
@SuppressWarnings({"unchecked", "squid:S4738"})
public final class DynamicPropertiesManager {
    private static final AtomicReference<PropsMap> localSettings = new AtomicReference<>(PropsMap.empty()); // from local files
    private static final AtomicReference<PropsMap> externalSettings = new AtomicReference<>(PropsMap.empty()); // from settings service
    private static final AtomicReference<PropsMap> dynamicSettings = new AtomicReference<>(PropsMap.empty()); // localSettings + externalSettings
    private static final AtomicReference<PropsMap> combinedSettings = new AtomicReference<>(PropsMap.empty()); // all propertySources combined
    private static final AtomicReference<ImmutableMap<Class<?>,?>> propTypeToInstance = new AtomicReference<>(ImmutableUtil.emptyMap());
    private static final Map<Class<?>,Map<DynamicPropertyListener<?>,Void>> propTypeListeners = new WeakHashMap<>();
    private static final Map<String, Map<DynamicPropertyListener<Object>,Class<?>>> propListeners = new WeakHashMap<>();
    private static final Set<String> dynamicPropsThatRequireRestart = Collections.synchronizedSet(new HashSet<>()); // Not very dynamic if they require a restart, but give the option nonetheless
    private static Optional<ConfigurableApplicationContext> springAppContext = Optional.empty();


    private DynamicPropertiesManager() {
        throw new IllegalStateException("This is a static singleton class");
    }

    /** Clear all state, references and references to instances of classes
      * that were created from properties (used by e.g. DynamicPropertiesRef).
      * After this call the DynamicPropertiesManager is uninitialized as if the VM just
      * started and setConfigurableApplicationContext() needs to be called again
      * before it can be used in Spring.
      */
    public static void clearAllState() {
        localSettings.set(PropsMap.empty());
        externalSettings.set(PropsMap.empty());
        dynamicSettings.set(PropsMap.empty());
        combinedSettings.set(PropsMap.empty());
        propTypeToInstance.set(ImmutableUtil.copyAndMutate((ImmutableMap<Class<?>,Object>)propTypeToInstance.get(), map ->
            map.replaceAll((type, instance) -> createInstanceOf(type))
        ));
        springAppContext = Optional.empty();
        Utils.forceGarbageCollection();

        // (Often static) property and type listeners have state that should be *reset*
        // As they are static, they shouldn't be removed because static constructors won't be called again
        synchronized (propTypeListeners) {
            propTypeListeners.keySet().forEach(type -> callChangeListenersForType(type, Collections.emptySet()));
            propListeners.keySet().forEach(name -> callChangeListenersForProps(null));
        }
    }

    static PropsMap getDynamicSettings() { return dynamicSettings.get(); }

    /**
     * The DynamicPropertiesManager here only sets the given map (actually adds it to the existing),
     * but uses the Spring environment to retrieve it when creating instances.
     * Therefore, make sure the ExternalPropertiesSource is added to the configuration
     * so that both this manager as Spring use the given newSettings.<p>
     *
     * Setting this map also replaces any previously set props that were set using
     * setProperty() (which added the properties to the externalSettings that are
     * overwritten here).
     */
    public static void setExternalSettings(PropsMap newSettings) {
        synchronized (externalSettings) {
            externalSettings.set(newSettings);
        }
        setDynamicSettings();
        refresh();
    }
    /** Returns a (deep) copy of a previously set external settings map */
    public static PropsMap getExternalSettings() {
        return externalSettings.get().asFlatMap().asDeepMap();
    }

    /** Configuration from local files like services.yaml and {serviceName}.yaml are set here. */
    public static void setLocalSettings(PropsMap newSettings) {
        synchronized (localSettings) {
            localSettings.set(newSettings);
        }
        setDynamicSettings();
        refresh();
    }


    /** Set an individual property in the external settings. This is typically used in tests. */
    public static void setProperty(String path, Object value) {
        synchronized (externalSettings) {
            externalSettings.set(externalSettings.get().set(path, value));
        }
        setDynamicSettings();
        refresh();
    }

    /** Changes to properties that are not managed here are not detected. A refresh will detect them. */
    public static void refresh() {
        restartWhenNeededForChanges(
            updateForChangedSettingsAndReturnChangedProps(combinedSettings.get())
        );
    }

    /** Get an available instance for the given properties type that is needed for a very short time only
      * so no updates will be received. If use is for a longer period, call the other getInstanceOf with
      * update callback. The type should be annotated with {@link DynamicProperties}. If no configuration
      * exists for the prefix provided in {@link DynamicProperties} an instance will be returned but the
      * fields in the class will be unset.
      */
    public static <T> T    getInstanceOf(Class<T> type) {
        //noinspection unchecked
        return Optional.ofNullable((T) propTypeToInstance.get().get(type))
            .orElseGet(() -> memorizeInstance(type, createInstanceOf(type)));
    }

    /** Like #getInstanceOf(Class) but also subscribes to a consumer of new instances when
      * the dynamic properties are updated. The lifecycle of the consumer will be linked to
      * that of the caller instance (owner of the consumer, typically 'this') meaning that
      * when the caller is garbage collected, so will the consumer. (an anonymous consumer
      * is often not referenced from the caller leading to it being garbage collected. That
      * link is created here.)
      * <p>
      * NOTE: listener is stored here with a WEAK reference so the caller needs to take care of keeping
      *       a reference to the listener, or it will be garbage collected.
      */
    public static <T> T    getInstanceOf(Class<T> type, DynamicPropertyListener<T> listener) {
        final T instance = getInstanceOf(type);
        addChangeListenerFor(type, listener);
        return instance;
    }

    public static Optional<Object> getObjectProperty(String key) {
        return springAppContext
            .map(ConfigurableApplicationContext::getEnvironment)
            .map(env -> (Object)env.getProperty(key))
            .or(() -> combinedSettings.get().get(key))
            .or(() -> externalSettings.get().get(key)); // extra or() in case Spring is not running
    }
    public static Optional<String> getProperty(String key) {
        return getObjectProperty(key).map(Object::toString);
    }
    public static String           getProperty(String key, String defaultValue) {
        return springAppContext
            .map(ConfigurableApplicationContext::getEnvironment)
            .map(env -> env.getProperty(key, defaultValue))
            .or(() -> getObjectProperty(key).map(Object::toString))
            .orElse(defaultValue);
    }
    public static <T> Optional<T>  getProperty(String key, Class<T> targetType) {
        return springAppContext
            .map(ConfigurableApplicationContext::getEnvironment)
            .map(env -> env.getProperty(key, targetType))
            .or(() -> getObjectProperty(key)
                .map(value -> {
                    try {
                        return ObjectMapping.get().convertValue(value, targetType);
                    } catch(final RuntimeException failed) {
                        throw new IllegalStateException("Failed to convert value of '" + key + "' to type " + targetType, failed);
                    }
                })
            );
    }
    public static <T> T            getProperty(String key, Class<T> targetType, T defaultValue) {
        return springAppContext
            .map(ConfigurableApplicationContext::getEnvironment)
            .map(env -> env.getProperty(key, targetType, defaultValue))
            .or(() -> getProperty(key, targetType)
                    .map(obj -> ObjectMapping.get().convertValue(obj, targetType))
            )
            .orElse(defaultValue);
    }

    /** Call consumer with a new properties instance whenever the instance is replaced because any of its
      * inner values have been changed into a new instance due to newly received configuration.
      * <p>
      * NOTE: listener is stored here with a WEAK reference so the caller needs to take care of keeping
      *       a reference to the listener, or it will be garbage collected.
      */
    public static <T> void addChangeListenerFor(Class<T> type, DynamicPropertyListener<T> listener) {
        synchronized(propTypeListeners) {
            final Map<DynamicPropertyListener<?>,Void> typeListeners = propTypeListeners
                .computeIfAbsent(type, t -> new WeakHashMap<>());

            typeListeners.put(listener, null);
        }
    }
    public static <T> void addChangeListenerFor(String propertyKey, Class<T> type, DynamicPropertyListener<T> listener) {
        synchronized(propListeners) {
            final Map<DynamicPropertyListener<Object>,Class<?>> listeners = propListeners
                .computeIfAbsent(propertyKey, key -> new WeakHashMap<>());

            listeners.remove(listener); // prevent the same listener from being added twice
            listeners.put((DynamicPropertyListener<Object>)listener, type);
        }
    }
    public static <T> void removeChangeListener(DynamicPropertyListener<T> listener) {
        synchronized(propListeners) {
            final Set<String> propsToRemove = propListeners.entrySet().stream()
                .flatMap(propAndListenersToType -> {
                    propAndListenersToType.getValue().remove(listener);
                    return propAndListenersToType.getValue().isEmpty() ? Stream.of(propAndListenersToType.getKey()) : Stream.empty();
                }).collect(Collectors.toSet());
            propsToRemove.forEach(propListeners::remove);

            final Set<Class<?>> typesToRemove = propTypeListeners.entrySet().stream()
                .flatMap(typeAndListeners -> {
                    typeAndListeners.getValue().remove(listener);
                    return typeAndListeners.getValue().isEmpty() ? Stream.of(typeAndListeners.getKey()) : Stream.empty();
                }).collect(Collectors.toSet());
            typesToRemove.forEach(propTypeListeners::remove);
        }
    }

    public static     void addPropertyThatRequiresRestart(String fullPropertyPath) {
        dynamicPropsThatRequireRestart.add(fullPropertyPath);
    }
    public static     void addPropertiesThatRequireRestart(Class<?> propertiesType) {
        dynamicPropsThatRequireRestart.addAll(SpringProps.getConfigurationPropertiesNamesOf(propertiesType, getPrefixOf(propertiesType)));
    }

    /** Returns the currently known DynamicProperties classes. More dynamic property classes
      * may exist on the classpath, but only those currently used are known by the
      * DynamicPropertiesManager.
      */
    public static ImmutableSet<Class<?>> getKnownDynamicPropertiesTypes() {
        return propTypeToInstance.get().keySet();
    }

    /** Validate given settings text by parsing it as yaml (which throws in case of error)
      * and then attempts to create all known classes annotated with DynamicProperties
      * (which throws when the ObjectMapper cannot deserialize, for example when a number
      * is set on a boolean field). When all is well this method will just return without
      * throwing.
      */
    public static void validateYaml(String settingsText) {
        // When the given yaml is invalid, this will throw
        final List<PropsMap> settingsMaps = PropsMap.fromYamlMultipleOrThrow(settingsText);

        // Use current settings as a base
        final PropsMap currentSettings = DynamicPropertiesManager.getCombinedSettings();

        settingsMaps.forEach(testSettings -> {
            final PropsMap currentMergedWithTestSettings = currentSettings.getWithOverrides(testSettings);

            // Attempt to create a class annotated with DynamicProperties. If the given data
            // contains any errors (like a number for a boolean field) the objectMapper will
            // throw.
            DynamicPropertiesManager.getKnownDynamicPropertiesTypes().forEach(propsType ->
                currentMergedWithTestSettings.asDeepMap().getMap(DynamicPropertiesManager.getPrefixOf(propsType))
                    .ifPresent(typeSettings -> ObjectMapping.get().convertValue(typeSettings, propsType))
            );
        });
    }

    /** Returns the combined properties from the various Spring property sources, including this one */
    public static PropsMap getCombinedSettings() {
        return springAppContext
                .map(appContext -> appContext.getEnvironment().getPropertySources())
                .map(SpringProps::mergePropertySources)
                .orElseGet(dynamicSettings::get);
    }

    public static void setConfigurableApplicationContext(@Nullable ConfigurableApplicationContext cac) {
        DynamicPropertiesManager.springAppContext = Optional.ofNullable(cac);
        updateForChangedSettingsAndReturnChangedProps(PropsMap.empty());
    }


    private static void setDynamicSettings() {
        synchronized (dynamicSettings) {
            dynamicSettings.set(localSettings.get().getWithOverrides(externalSettings.get()));
        }
    }
    private static Set<String> updateForChangedSettingsAndReturnChangedProps(PropsMap previousCombinedSettings) {
        final Set<String> justChangedProps = recombineAndReturnChangedProps(previousCombinedSettings);

        updatePropertyTypesAndCallListenersForChanges(justChangedProps);
        return justChangedProps;
    }
    private static Set<String> recombineAndReturnChangedProps(PropsMap previousCombinedSettings) {
        combinedSettings.set(getCombinedSettings().asDeepMap());

        final Set<String> justChangedProps = getChangedKeys(combinedSettings.get(), previousCombinedSettings);
        @SuppressWarnings("rawtypes")
        class Inner {
            private Inner() {} // keep Sonar happy
            static int sizeOf(Object obj) {
                if(obj instanceof Optional opt) return (Integer)opt.map(Inner::sizeOf).orElse(false);
                return (obj instanceof Collection c) ? c.size() :
                       (obj instanceof Map m) ? m.size() : -1;
            }
        }

        springAppContext
            .filter(sac -> !previousCombinedSettings.isEmpty())
            .filter(appContext -> !justChangedProps.isEmpty())
            .ifPresent(appContext ->
                log.debug(("Settings changed:\n" + justChangedProps.stream()
                    .filter(key -> !key.matches("^(jna\\.|jni).*$"))
                    .map(key -> " - " + key + ": " + getObjectProperty(key).map(val -> Inner.sizeOf(val) >= 0 ? ("[size=" + Inner.sizeOf(val) + "]") : val).orElse(" (removed)") +
                        previousCombinedSettings.get(key).map(val -> " (was " + (Inner.sizeOf(val) >= 0 ? ("size=" + Inner.sizeOf(val)) : val) + ")").orElse(" (new)"))
                    .sorted()
                    .collect(Collectors.joining("\n"))).trim())
            );
        return justChangedProps;
    }

    private static void updatePropertyTypesAndCallListenersForChanges(Set<String> justChangedProps) {
        if(!justChangedProps.isEmpty()) {
            replaceAffectedTypeInstances(justChangedProps);
            callChangeListenersForProps(justChangedProps);
        }
    }
    private static void restartWhenNeededForChanges(Set<String> justChangedProps) {
        final Set<String> propsRequiringRestart = getPropertiesThatRequireRestart();
        final Set<String> restartForTheseProps1  = intersectionOf(propsRequiringRestart, justChangedProps);
        final Set<String> restartForTheseProps2 = justChangedProps.stream()
            .filter(name -> name.startsWith("spring."))
            .collect(Collectors.toSet());
        final Set<String> restartForTheseProps  = join(restartForTheseProps1, restartForTheseProps2);

        if(!restartForTheseProps.isEmpty() && springAppContext.isPresent()) {
            log.info("RESTART NEEDED for changed settings: " + restartForTheseProps);
            MicroStarApplication.get().ifPresent(RestartableApplication::restart);
        }
    }

    private static Set<String> getChangedKeys(PropsMap currentCombinedSettings, PropsMap previousCombinedSettings) {
        final Map<String,Object> previousFlatMap = previousCombinedSettings.asFlatMap().getMap();
        final Map<String,Object> currentFlatMap  = currentCombinedSettings.asFlatMap().getMap();
        class Inner {
            private Inner() {} // Keep Sonar happy
            static Stream<String> addParentOf(String in) {
                final int dot = in.lastIndexOf('.');
                return dot < 0 ? Stream.of(in) : Stream.of(in, in.substring(0, dot));
            }
        }
        return Stream.concat(
            currentFlatMap.entrySet().stream()
                .filter(entry -> !Objects.equals(entry.getValue(), previousFlatMap.get(entry.getKey())))
                .map(entry -> entry.getKey().contains("[") ? Map.entry(entry.getKey().replaceFirst("\\[[^\\[]+$",""),"") : entry)
                .map(Map.Entry::getKey),
            previousFlatMap.keySet().stream()
                .filter(key -> !currentFlatMap.containsKey(key))
                .map(key -> key.contains("[") ? key.replaceFirst("\\[[^\\[]+$","") : key)
        ).flatMap(Inner::addParentOf)
            .collect(Collectors.toSet());
    }
    private static Set<String> getPropertiesThatRequireRestart() {
        return Stream.concat(getSpringPropertiesThatRequireRestart(), getDynamicPropertiesThatRequireRestart())
            .collect(Collectors.toSet());
    }
    private static Stream<String> getSpringPropertiesThatRequireRestart() {
        return springAppContext
            .map(appContext -> appContext
                .getBeansWithAnnotation(ConfigurationProperties.class).values().stream()
                .flatMap(props -> SpringProps.getConfigurationPropertiesNamesOf(props).stream())
            ).orElse(Stream.empty())
            .filter(name -> !name.matches("^spring\\.config(\\.import)?$"));
    }
    private static Stream<String> getDynamicPropertiesThatRequireRestart() {
        return dynamicPropsThatRequireRestart.stream();
    }

    private static <T> T   createInstanceOf(Class<T> typeIn) {
        final Map<String,Object> properties = Optional.of(getPrefixOf(typeIn))
            .filter(path -> !path.isEmpty())
            .flatMap(path -> combinedSettings.get().getMap(path))
            .orElseGet(() -> combinedSettings.get().getMap());
        try {
            return ObjectMapping.get().convertValue(properties, typeIn);
        } catch(final Exception deserializeFailure) {
            log.warn("Unable to deserialize type {}: {}", typeIn, deserializeFailure.getMessage());
            log.info("combinedSettings: {}", combinedSettings);
            throw deserializeFailure;
        }
    }
    private static String  getPrefixOf(Class<?> typeIn) {
        @Nullable Class<?> type = typeIn;
        String prefix = "";
        while(type != null) {
            @Nullable final DynamicProperties anno = type.getAnnotation(DynamicProperties.class);
            if(anno == null) {
                // This type class has no annotation. To find the prefix name,
                // look for a field in parent that has the same type and use its name.
                prefix = dotConcat(fieldNameForType(type.getDeclaringClass(), type), prefix);
            } else {
                prefix = dotConcat(anno.value(), prefix);
                if(anno.changeRequiresRestart()) {
                    final String propsPrefix = prefix;
                    SpringProps.getConfigurationPropertiesNamesOf(type)
                        .forEach(name -> addPropertyThatRequiresRestart(dotConcat(propsPrefix, name)));
                }
            }
            type = type.getDeclaringClass();
        }
        return prefix;
    }
    private static String  fieldNameForType(@Nullable Class<?> container, Class<?> fieldType) {
        if(container == null) return "";
        return Stream.of(container.getDeclaredFields())
            .filter(field -> field.getType().isAssignableFrom(fieldType))
            .map(Field::getName)
            .findFirst()
            .orElse("");
    }
    private static String  dotConcat(@Nullable String left, @Nullable String right) {
        return left == null || left.isEmpty()
            ? (right == null ? "" : right)
            : right == null || right.isEmpty()
              ? left
              : (left + "." + right);
    }
    private static <T> T   memorizeInstance(Class<T> type, T instance) {
        synchronized (propTypeToInstance) {
            propTypeToInstance.set(ImmutableMap.<Class<?>,Object>builder()
                .putAll(propTypeToInstance.get())
                .put(type, instance)
                .buildKeepingLast()
            );
        }
        return instance;
    }

    private static <T> void replaceAffectedTypeInstances(Set<String> keysOfChanges) {
        final List<Class<T>> changedPropertyTypes = new ArrayList<>();
        synchronized(propTypeToInstance) {
            propTypeToInstance.set(ImmutableMap.copyOf(
                propTypeToInstance.get().entrySet().stream().map(entry ->
                    Map.entry((Class<T>)entry.getKey(), newInstanceWhenChanged(entry.getValue())
                            .filter(newInstance -> { changedPropertyTypes.add((Class<T>)entry.getKey()); return true; })
                            .orElse(entry.getValue())
                    )
                ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
            );
        }
        changedPropertyTypes.forEach(propsType -> {
            final String prefix0 = getPrefixOf(propsType);
            final String prefix = prefix0.isEmpty() ? "" : (prefix0 + ".");
            final Set<String> keysOfChangesForType = keysOfChanges.stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .collect(Collectors.toSet());
            callChangeListenersForType(propsType, keysOfChangesForType);
        });
    }
    private static <T> Optional<T> newInstanceWhenChanged(T oldInstance) {
        final T newInstance = (T) createInstanceOf(oldInstance.getClass());

        return equalsProperties(oldInstance, newInstance)
            ? Optional.empty()
            : Optional.of(newInstance);
    }

    private static <T> void callChangeListenersForType(Class<T> type, Set<String> keysOfChanges) {
        // Also do this when there are no changes (to reset instances)
        final T instance = getInstanceOf(type);
        final List<ThrowingRunnable> toCall;
        synchronized (propTypeListeners) {
            toCall = propTypeListeners.keySet().stream()
                .filter(propsType -> propsType.equals(type))
                .flatMap(key -> propTypeListeners.get(key).keySet().stream())
                .map(propsListener -> (ThrowingRunnable)(() -> ((DynamicPropertyListener<T>)propsListener).changed(instance, keysOfChanges)))
                .toList();
        }
        // call listeners outside synchronized
        toCall.forEach(runner -> {
            try {
                runner.run();
            } catch(final Exception handlingError) {
                log.error("Dynamic property change listener has thrown exception: {}", getRootCause(handlingError).getMessage(), handlingError);
            }
        });
    }
    private static     void callChangeListenersForProps(@Nullable Set<String> changedKeys) {
        // Also do this when there are no changes (to reset instances)
        final List<Runnable> toCall;
        synchronized (propListeners) { // key=path, value=Map<Listener,Type>
            toCall = new ArrayList<>(propListeners.keySet().stream()
                .filter(key -> changedKeys == null || isKeyAffected(key, changedKeys))
                .flatMap(key -> propListeners.get(key).entrySet().stream() // entry: key=Listener, value=type
                    .map(listenerAndType -> ((Runnable) () -> {
                        final DynamicPropertyListener<Object> listener = listenerAndType.getKey();
                        final Class<?> type = listenerAndType.getValue();
                        getProperty(key, type).ifPresentOrElse(value -> {
                            final Set<String> changedTypeKeys = (changedKeys == null ? Stream.<String>empty() : changedKeys.stream())
                                .filter(ck -> ck.startsWith(key + "."))
                                .map(ck -> ck.substring(key.length() + 1))
                                .collect(ImmutableUtil.toImmutableSet());
                            listener.changed(value, new TreeSet<>(changedTypeKeys));
                        }, listener::cleared);
                    }))
                ).toList());
        }
        // call listeners outside synchronized
        toCall.forEach(runner -> {
            try {
                runner.run();
            } catch(final Exception cause) {
                log.error("Property change listener has thrown an Exception", cause);
            }
        });
    }
    private static boolean isKeyAffected(String keyToCheck, Set<String> touchedKeys) {
        return touchedKeys.contains(keyToCheck)
            || touchedKeys.stream().anyMatch(touchedKey -> touchedKey.startsWith(keyToCheck + "."));
    }
    private static boolean equalsProperties(Object propsA, Object propsB) {
        // Use ObjectMapper instead of equals because there is a pretty good chance
        // the equals() and hashCode() have not been implemented by the props class.
        try {
            return ObjectMapping.get().writeValueAsString(propsA).equals(ObjectMapping.get().writeValueAsString(propsB));
        } catch (final JsonProcessingException cause) {
            log.error("Unable to compare two instances of type " + propsA.getClass(), cause);
            return propsA.equals(propsB); // fallback to equals()
        }
    }

    @SafeVarargs
    private static <T> Set<T> intersectionOf(Set<T>... sets) {
        final Set<T> intersection = new LinkedHashSet<>();
        for(int i=0; i<sets.length; i++) {
            if(i == 0) intersection.addAll(sets[0]);
            else intersection.retainAll(sets[1]);
        }
        return intersection;
    }
    @SafeVarargs
    private static <T> Set<T> join(Set<T>... sets) {
        final Set<T> joined = new LinkedHashSet<>();
        for(final Set<T> set : sets) joined.addAll(set);
        return joined;
    }
}
