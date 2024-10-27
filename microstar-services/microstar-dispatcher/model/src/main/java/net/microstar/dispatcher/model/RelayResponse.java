package net.microstar.dispatcher.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import org.springframework.http.HttpStatus;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

@Jacksonized @Builder
public class RelayResponse<T> {
    public final String starName;
    public final String starUrl;
    public final HttpStatus status;
    public final Optional<T> content;

    public RelayResponse(@Nullable String starName, @Nullable String starUrl, @Nullable HttpStatus status, @Nullable Optional<T> content) {
        this.starName = Objects.requireNonNull(starName, "Star name is mandatory");
        this.starUrl  = Objects.requireNonNull(starUrl, "Star url is mandatory");
        this.status   = Objects.requireNonNullElse(status, HttpStatus.OK);
        this.content  = Objects.requireNonNullElse(content, Optional.empty());
    }

    public static class RelayResponseBuilder<T> {
        public RelayResponse<T> failed() { return status(HttpStatus.SERVICE_UNAVAILABLE).build(); }
        public RelayResponse<T> ok() { return status(HttpStatus.OK).build(); }
        public RelayResponse<T> ok(@Nullable T payload) { return status(HttpStatus.OK).content(Optional.ofNullable(payload)).build(); }
    }

    public <U> RelayResponse<U> setNewContent(Optional<U> body) {
        return RelayResponse.<U>builder() // toBuilder() not possible due to changed generic type
            .starName(starName)
            .starUrl(starUrl)
            .status(status)
            .content(body)
            .build();
    }
}
