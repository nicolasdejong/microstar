package net.microstar.settings;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

@DynamicProperties("app.config.settings")
@Builder @Jacksonized @ToString
@SuppressWarnings("FieldMayBeStatic")
public class SettingsProperties {
    @Default public final boolean syncBetweenStars = true;
}
