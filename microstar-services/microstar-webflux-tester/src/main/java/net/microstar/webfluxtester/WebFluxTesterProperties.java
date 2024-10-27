package net.microstar.webfluxtester;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

import java.util.List;

@SuppressWarnings("QuestionableName") // The whole reflector service is mostly for testing anyway, so there will be many foo and bars
@DynamicProperties("experiment")
@Builder @Jacksonized @ToString
public class WebFluxTesterProperties {
    public final String foo;
    public final int bar;
    public final int number;
    public final String text;
    public final List<Integer> numbers;
}
