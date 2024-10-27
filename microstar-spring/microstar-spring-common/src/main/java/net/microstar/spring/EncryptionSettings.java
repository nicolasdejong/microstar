package net.microstar.spring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.Encryption;
import net.microstar.common.util.StringUtils;
import net.microstar.spring.settings.DynamicProperties;
import net.microstar.spring.settings.DynamicPropertiesManager;

import javax.annotation.Nullable;
import java.util.Optional;

@DynamicProperties("encryption")
@Builder @ToString @Jacksonized
public class EncryptionSettings {
    public final String encPassword;
    public final Encryption.Settings settings;
    @JsonIgnore
    public final Encryption encryption;

    EncryptionSettings(@Nullable String encPassword, @Nullable Encryption.Settings settings, @SuppressWarnings("unused") Encryption unused) {
        this.encPassword = Optional.ofNullable(encPassword).orElse("defaultEncryptionPassword");
        this.settings    = Optional.ofNullable(settings).orElseGet(() -> Encryption.Settings.builder().build());
        this.encryption  = new Encryption(this.settings);
    }

    // The below is not settings but added here because it uses these settings and almost nothing else.

    public String hash   (String toHash)    { return encryption.hashToString(toHash); }
    public String encrypt(String toEncrypt) { return encryption.encrypt(toEncrypt, getEncPassword()); }
    public String decrypt(String toDecrypt) { return encryption.decrypt(toDecrypt, getEncPassword()); }

    private String getEncPassword() {
        // This call is also made before Spring has started (loading settings before spring, settings
        // that may contain encrypted values for which the encPassword is required). The Dynamic
        // Properties Manager won't have the value then, so in that case fall back on command-line
        // provided password.
        return DynamicPropertiesManager.getProperty("encryption.encPassword")
            .or(() -> StringUtils.getObfuscatedSystemProperty("encryption.encPassword"))
            .orElse(encPassword);
    }
}
