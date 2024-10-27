package net.microstar.webfluxtester;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@SuppressWarnings("QuestionableName") // The whole reflector service is mostly for testing anyway, so there will be many foo and bars
@ConfigurationProperties("needing-restart")
@AllArgsConstructor @ToString
@Builder
public class TestProperties {
    public final String foo;
    public final int bar;
    public final int zoo;
    public final int nonExisting;
}
