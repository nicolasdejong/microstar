package net.microstar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.microstar.common.conversions.ObjectMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonBeans {

    @Bean public ObjectMapper objectMapper() {
        return ObjectMapping.get();
    }
}
