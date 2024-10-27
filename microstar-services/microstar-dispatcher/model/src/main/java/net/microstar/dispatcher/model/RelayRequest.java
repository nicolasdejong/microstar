package net.microstar.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.ToString;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.UserToken;
import org.springframework.http.HttpMethod;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A Relay Request to be sent to the Dispatcher which will call the
 * service as defined in this request on the current star or, if asked
 * in this request, on other stars as well. So there can be multiple
 * replies on a single request.
 */
@SuppressWarnings("unused") // be complete without using all yet (like the http methods); this is util
// not using Lombok builder so @Jacksonized won't work
@JsonDeserialize(builder=RelayRequest.Builder.class)
@ToString
public class RelayRequest {
    public static final    String LOCAL_STAR_REF_NAME = "LOCAL";
    public final           String method; // see builder for default field values
    public final           String serviceName;
    public final @Nullable String servicePath;
    public final @Nullable Object payload;
    public final @Nullable String star;
    public final @Nullable String userToken;
    public final           boolean includeLocalStar;
    public final           boolean binary;
    public final           Map<String,String> params;

    RelayRequest(String method, String serviceName, @Nullable String servicePath, @Nullable Object payload,
                 @Nullable String star, @Nullable String userToken, boolean includeLocalStar, boolean binary,
                 Map<String, String> params) {
        this.method = method;
        this.serviceName = serviceName;
        this.servicePath = servicePath;
        this.payload = payload;
        this.star = star;
        this.userToken = userToken;
        this.includeLocalStar = includeLocalStar;
        this.binary = binary;
        this.params = params;
    }

    public static Builder forThisService() { return builder().serviceName(getNameOfThisService()); }

    public static Builder forGet()    { return forGet(getNameOfThisService()); }
    public static Builder forPost()   { return forPost(getNameOfThisService()); }
    public static Builder forPut()    { return forPut(getNameOfThisService()); }
    public static Builder forDelete() { return forDelete(getNameOfThisService()); }

    public static Builder forGet(String serviceName)    { return builder().method(HttpMethod.GET   ).serviceName(serviceName); }
    public static Builder forPost(String serviceName)   { return builder().method(HttpMethod.POST  ).serviceName(serviceName); }
    public static Builder forPut(String serviceName)    { return builder().method(HttpMethod.PUT   ).serviceName(serviceName); }
    public static Builder forDelete(String serviceName) { return builder().method(HttpMethod.DELETE).serviceName(serviceName); }

    public static Builder builder() { return new Builder(); }

    @JsonIgnore
    public HttpMethod methodAsHttpMethod() {
        return HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
    }

    @JsonPOJOBuilder(withPrefix="")
    public static class Builder {
        private           String method = HttpMethod.GET.name();
        private           String serviceName = "";
        private @Nullable String servicePath;
        private @Nullable Object payload;
        private @Nullable String star;
        private @Nullable String userToken;
        private           boolean includeLocalStar = true;
        private           boolean binary = false;
        private           Map<String,String> params = new LinkedHashMap<>();

        public Builder method(HttpMethod method) { return method(method.name()); }
        public Builder method(String method) { this.method = method; return this; }
        public Builder get() { return method(HttpMethod.GET); }
        public Builder post() { return method(HttpMethod.POST); }
        public Builder put() { return method(HttpMethod.PUT); }
        public Builder delete() { return method(HttpMethod.DELETE); }
        public Builder serviceName(String serviceName) {
            final String[] parts = serviceName.split("/", 2);
            this.serviceName = parts[0];
            return parts.length == 2 ? servicePath(parts[1]) : this;
        }
        public Builder servicePath(@Nullable String servicePath) { this.servicePath = servicePath; return this; }
        public Builder payload(@Nullable Object payload) { this.payload = payload; return this; }
        public Builder star(@Nullable String star) { this.star = star; return this; }
        public Builder userToken(UserToken userToken) { this.userToken = userToken.toTokenString(); return this; }
        public Builder userToken(String tokenString) { this.userToken = tokenString; return this; }
        public Builder includeLocalStar(boolean includeLocalStar) { this.includeLocalStar = includeLocalStar; return this; }
        public Builder excludeLocalStar() { return includeLocalStar(false); }
        public Builder onlyLocalStar() { this.star = LOCAL_STAR_REF_NAME; return this; }
        public Builder binary() { return binary(true); }
        public Builder binary(boolean set) { this.binary = set; return this; }
        public Builder params(Map<String, String> params) { this.params = new LinkedHashMap<>(params); return this; }
        public Builder param(@Nullable String paramKey, @Nullable String paramValue) {
            if(paramKey != null) {
                if (paramValue == null) params.remove(paramKey);
                else params.put(paramKey, paramValue);
            }
            return this;
        }

        public RelayRequest build() {
            return new RelayRequest(method, serviceName, servicePath, payload, star, userToken, includeLocalStar, binary, params);
        }
    }

    private static String getNameOfThisService() {
        return MicroStarApplication.get().map(app -> app.serviceId.name).orElse("unknown-service-name");
    }
}