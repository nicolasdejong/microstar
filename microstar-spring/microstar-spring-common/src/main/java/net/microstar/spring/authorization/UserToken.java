package net.microstar.spring.authorization;

import com.google.common.collect.ImmutableMap;
import lombok.experimental.SuperBuilder;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.UserTokenBase;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.settings.DynamicPropertiesRef;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.function.Predicate.not;
import static net.microstar.common.util.Utils.firstSupplyNotNullOrElseEmpty;

/** <pre>
 * User info and user authorizations.
 * This class will be serialized and will be sent to the server on each request.
 *
 * The UserToken can be attained by adding it as an argument in the rest method
 * (an ArgumentResolver for it is added in the WebConfiguration) or using the
 * RequiresRole annotation who extract it from the client request. An instance
 * will always be returned, even if no token is provided by the client
 * (leading to a UserToken.GUEST). If guest use is not allowed for an endpoint,
 * call userToken.noGuest() which will result in a 401 on GUEST user tokens
 * (or similar, use @ RequiresRole(UserToken.ROLE_ADMIN))
 *
 * The client needs to call /login/user to login (or single sign on when
 * available). The returned token should be added to each request in the
 * X-AUTH-TOKEN http header (UserToken.HTTP_HEADER_NAME).
 *
 * (De)serialization from/to string is included here. The string token should
 * be returned to the client and used in each client request. The string token
 * is an encrypted serialization of the UserToken plus expiryTime.
 *
 * UserToken uses the 'authorization.roles' map to determine the roles for a
 * user. The map key can be user name or user email which both are evaluated
 * case insensitive. The map value is one or more roles.
 */
@SuppressWarnings({"NonBooleanMethodNameMayNotStartWithQuestion"}) // mustBe...
@SuperBuilder
public final class UserToken extends UserTokenBase {
    private static ImmutableMap<String, Set<String>> userToRoles = ImmutableUtil.emptyMap(); // new value given in settingsRef listener
    @SuppressWarnings("unused") // needed to prevent garbage collection
    private static final DynamicPropertiesRef<AuthSettings> settingsRef = DynamicPropertiesRef.of(AuthSettings.class)
        .onChange(settings -> {
            UserTokenBase.tokenLifetime = settings.tokenTimeout;
            userToRoles = ImmutableUtil.mapKeys(settings.userRoles, key -> key.toLowerCase(Locale.ROOT));
        })
        .callOnChangeHandlers();
    public static final String HTTP_HEADER_NAME = "X-AUTH-TOKEN";
    public static final UserToken GUEST_TOKEN   = from(UserTokenBase.GUEST_TOKEN);
    public static final UserToken SERVICE_TOKEN = UserToken.builder().id("-1").name("Service").build();
    public static final String    ROLE_GUEST    = "GUEST";
    public static final String    ROLE_ADMIN    = "ADMIN";
    public static final String    ROLE_SERVICE  = "SERVICE";

    public String toString() {
        return this == SERVICE_TOKEN ? "SERVICE" :
               this == GUEST_TOKEN ? "GUEST" :
               "UserToken(id=" + id + ", name=" + name + ", email=" + email + ")";
    }

    public String getEmailName() {
        return Optional.of(email.split("@")[0])
            .filter(not(String::isEmpty))
            .orElse("".equals(email) ? name : email)
            .toLowerCase(Locale.ROOT);
    }

    public boolean hasRole(String role) {
        if(ROLE_SERVICE.equals(role)) return this == SERVICE_TOKEN;
        final boolean roleIsGuest = ROLE_GUEST.equals(role);
        if(roleIsGuest && GUEST_TOKEN.name.equals(name)) return true; // cover both static guest tokens
        if(userToRoles.isEmpty()) return !roleIsGuest; // no user-roles configured? Then all users have all roles (except guest) (to prevent bootstrap problems)

        final Optional<Set<String>> rolesForThisUser = firstSupplyNotNullOrElseEmpty(
            () -> userToRoles.get(name.toLowerCase(Locale.ROOT)),
            () -> userToRoles.get(email.toLowerCase(Locale.ROOT)),
            () -> userToRoles.get(email.split("@",2)[0].toLowerCase(Locale.ROOT))
        );
        return rolesForThisUser.filter(r -> r.contains(role)).isPresent();
    }
    public boolean hasAnyRoles(Iterable<String> roles) {
        return StreamSupport.stream(roles.spliterator(), false).anyMatch(this::hasRole);
    }
    public boolean hasAllRoles(Iterable<String> roles) {
        return StreamSupport.stream(roles.spliterator(), false).allMatch(this::hasRole);
    }
    public boolean hasAnyRoles(String... roles) {
        return Stream.of(roles).anyMatch(this::hasRole);
    }
    public boolean hasAllRoles(String... roles) {
        return Stream.of(roles).allMatch(this::hasRole);
    }

    /** Alias for hasRole(ROLE_GUEST) */
    public boolean isGuest() { return hasRole(ROLE_GUEST); }
    /** True when this contains a valid username */
    public boolean isLoggedIn() { return !GUEST_TOKEN.name.equals(name); }

    public String toTokenString() { return toTokenString(MicroStarConstants.CLUSTER_SECRET); }
    public String toTokenString(Duration lifetime) { return toTokenString(MicroStarConstants.CLUSTER_SECRET, lifetime); }

    public static UserToken from(UserTokenBase other) { return UserToken.builder().id(other.id).name(other.name).email(other.email).build(); }
    public static Optional<UserToken> from(HttpHeadersFacade headers) { return raw(headers).map(UserToken::fromTokenString); }

    // Don't use getCookies() but use header instead as cookies cannot be rewritten in a WebFilter
    private static final Pattern COOKIE_PATTERN = Pattern.compile(UserToken.HTTP_HEADER_NAME + "=(.*?)(?:;|$)");

    public  static Optional<String> raw(HttpHeadersFacade headers) { return rawFromHeader(headers).or(() -> rawFromCookie(headers)); }
    private static Optional<String> rawFromHeader(HttpHeadersFacade headers) { return headers.getFirst(UserToken.HTTP_HEADER_NAME); }
    private static Optional<String> rawFromCookie(HttpHeadersFacade headers) { return headers.getFirst("Cookie").flatMap(UserToken::rawFromCookie); }
    private static Optional<String> rawFromCookie(String cookie) { return StringUtils.getRegexGroup(cookie, COOKIE_PATTERN); }


    public static UserToken fromTokenString(@Nullable String tokenString) { return fromTokenString(tokenString, false); }
    public static UserToken fromTokenString(@Nullable String tokenString, boolean ignoreExpire) {
        if(tokenString == null || tokenString.isEmpty()) return GUEST_TOKEN;
        try {
            final UserTokenBase base = UserTokenBase.fromTokenString(tokenString, MicroStarConstants.CLUSTER_SECRET, ignoreExpire);
            return UserToken.builder().id(base.id).name(base.name).email(base.email).build();
        } catch(final NotAuthorizedException cause) {
            throw new net.microstar.spring.exceptions.NotAuthorizedException(cause.getMessage());
        }
    }
    public static UserToken fromTokenString(@Nullable String tokenString, String secret) { return from(UserTokenBase.fromTokenString(tokenString, secret)); }

    public boolean equals(final Object o) {
        if(o == this) return true;
        return o instanceof final UserToken other
            && Objects.equals(id, other.id)
            && Objects.equals(name, other.name)
            && Objects.equals(email, other.email);
    }
    public int hashCode() {
        return Objects.hash(id, name, email);
    }
}
