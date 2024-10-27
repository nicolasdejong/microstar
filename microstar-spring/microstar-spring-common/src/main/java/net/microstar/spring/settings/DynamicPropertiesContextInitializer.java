package net.microstar.spring.settings;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;

import java.util.Map;

import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

/** Insert a DynamicProperties property source into Spring configuration stack */
public class DynamicPropertiesContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final String SOURCE_NAME = "From Settings service";

    public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
        final MutablePropertySources propertySources =
        configurableApplicationContext
            .getEnvironment()
            .getPropertySources();

        propertySources.addAfter(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new ExternalPropertiesSource(SOURCE_NAME));
        propertySources.addFirst(new DecipherPropertySource(propertySources));

        DynamicPropertiesManager.setConfigurableApplicationContext(configurableApplicationContext);
    }

    private static class ExternalPropertiesSource extends EnumerablePropertySource<Map<String, Object>> {
        private static final PropsMap EMPTY = PropsMap.empty();
        private PropsMap lastMapRef = EMPTY;
        private PropsMap flattened = EMPTY;

        ExternalPropertiesSource(String name) { super(name); }

        @Nullable @Override
                  public Object getProperty(String propName) {
            return flattened.get(propName).orElse(null);
        }
        @Override public String[] getPropertyNames() {
            return getSource().keySet().stream()
                .toList()
                .toArray(new String[0]);
        }
        @Override public boolean containsProperty(String propName) {
            return flattened.get(propName).isPresent();
        }
        @Override public Map<String, Object> getSource() {
            final PropsMap mapRef = DynamicPropertiesManager.getDynamicSettings();
            if (lastMapRef == EMPTY || flattened == EMPTY  || mapRef != lastMapRef) {
                flattened = mapRef.asFlatMap();
                lastMapRef = mapRef;
            }
            return flattened.getMap();
        }
        @Override public boolean equals(@Nullable Object other) {
            return this == other
                || other instanceof ExternalPropertiesSource eps && ObjectUtils.nullSafeEquals(this.getName(), eps.getName());
        }
        @Override public int hashCode() {
            return ObjectUtils.nullSafeHashCode(this.getName());
        }
    }

    /** This property source does not contain any data but deciphers values from other property sources
      * when the value starts with "{cipher}".
      */
    private static class DecipherPropertySource extends PropertySource<Object> {
        private final MutablePropertySources propertySources;
        private final ThreadLocal<Boolean> gettingProperty = ThreadLocal.withInitial(() -> false);

        DecipherPropertySource(MutablePropertySources propertySources) {
            super("DecipherPropertySource");
            this.propertySources = propertySources;
        }

        @Nullable
        public Object getProperty(String nameAndPerhapsDefault) {
            if(gettingProperty.get()) return null; // prevent recursion by other property sources
            gettingProperty.set(true);
            try {
                final String name = nameAndPerhapsDefault.split(":", 2)[0];
                return propertySources.stream()
                    .filter(ps -> ps != this)
                    .map(ps -> ps.getProperty(name))
                    .filter(obj -> obj instanceof String)
                    .map(String.class::cast)
                    .findFirst()
                    .filter(s -> s.startsWith("{cipher}"))
                    .map(enc -> SpringProps.decrypt(enc, name))
                    .orElse(null);
            } finally {
                gettingProperty.set(false);
            }
        }
    }
}
