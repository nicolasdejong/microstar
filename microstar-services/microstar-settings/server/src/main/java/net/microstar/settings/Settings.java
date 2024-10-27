package net.microstar.settings;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.settings.SpringProps;
import net.microstar.spring.webflux.MicrostarSpringWebflux;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = { Settings.class, MicrostarSpringWebflux.class })
public class Settings extends MicroStarApplication {
    public static void main(String... args) {
        start(Settings.class, args);
    }

    @Override protected void started() {
        loadSettings(); // *before* super.started() because that will register with dispatcher whose url is loaded at this line
        super.started();
    }

    /**
     * The default behaviour of loading the settings from the settings service won't work
     * here as *this* is the settings service which hasn't started yet. So act as if this
     * service is called for the settings for itself. This way this service will be
     * updated as well when any of its settings change. (it remembers which services
     * require what files so knows who to call when a settings file changes).<p>
     *
     * Other services do the initial loading of the settings *before* Spring starts as
     * that gives the option of loading settings used by the spring bootstrap (like
     * logging levels) and won't trigger any 'requiresRestart when changed' settings.
     * For the Settings service setting up all required beans and connecting them together
     * before Spring starts add more code so the choice was made to do the loading of
     * the settings *after* Spring starts. If that gives unwanted side effects this
     * may change in the future.
     */
    private static void loadSettings() {
        log.info("Loading settings from local repository");
        final SettingsController controller = getContext().getBean(SettingsController.class);
        final ImmutableMap<String,Object> settings = controller.getCombinedSettings(String.join(",", SpringProps.getActiveProfileNames()), staticServiceInstanceId, ServiceId.get());
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromDeepMap(settings));
    }
}
