package net.microstar.settings.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Jacksonized @Builder
public class SettingsFile {
    public final String name;
    public final boolean isDeleted;
}
