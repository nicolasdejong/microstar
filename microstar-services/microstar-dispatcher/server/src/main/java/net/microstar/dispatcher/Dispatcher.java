package net.microstar.dispatcher;

import net.microstar.common.model.ServiceId;
import net.microstar.spring.CommonBeans;
import net.microstar.spring.logging.LogLevelHandler;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.ExceptionHandlers;
import net.microstar.spring.webflux.MiniBus;
import net.microstar.spring.webflux.WebConfiguration;
import net.microstar.spring.webflux.authorization.RequiresRoleFilter;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import net.microstar.spring.webflux.logging.LoggingEndpoints;
import net.microstar.spring.webflux.settings.client.SettingsService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import({ExceptionHandlers.class, CommonBeans.class, EventEmitter.class, LoggingEndpoints.class, LogLevelHandler.class,
    WebConfiguration.class, RequiresRoleFilter.class, SettingsService.class, MiniBus.class, DispatcherService.class })
public class Dispatcher extends DispatcherApplication {
    public static void main(String... args) {
        System.setProperty("spring.application.name", ServiceId.get().combined);
        start(Dispatcher.class, args);
    }
}
