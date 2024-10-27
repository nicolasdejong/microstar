package net.microstar.spring;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

import java.util.Collections;
import java.util.Map;

@DynamicProperties("contentTypes")
@Builder @Jacksonized @ToString
public class ContentTypesProperties {
    @Default public final String defaultType = "application/octet-stream";
    @Default public final Map<String,String> extToType = Collections.emptyMap();
}
