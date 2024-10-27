package net.microstar.spring.authorization;

import net.microstar.common.util.Utils;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;


class UserTokenTest {
    private static final String SECRET = UUID.randomUUID().toString();

    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    private static UserToken createTokenForUser(String name) {
        return UserToken.builder().id("12345").name(name).email(name + "@email.net").build();
    }

    @Test void shouldHoldBasicData() {
        final UserToken token = UserToken.builder().id("123").name("someName").email("some.one@some.where").build();
        assertThat(token.name, is("someName"));
        assertThat(token.email, is("some.one@some.where"));
        assertThat(token.hasRole("GUEST"), is(false));
    }
    @Test void shouldSupportRoles() {
        final UserToken tokenSomeAdmin = createTokenForUser("someAdmin");
        final UserToken tokenOther = createTokenForUser("other");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "authentication", Map.of("userRoles",
                Map.of(
                    "someAdmin", Set.of("ADMIN"),
                    "other", Set.of("EDITOR", "MAINTAINER")
                ))
        )));
        assertThat(tokenSomeAdmin.hasRole("EDITOR"), is(false));
        assertThat(tokenOther.hasRole("EDITOR"), is(true));

        assertThat(tokenOther.hasAllRoles(List.of("EDITOR", "MAINTAINER")), is(true));
        assertThat(tokenOther.hasAllRoles("EDITOR", "MAINTAINER"), is(true));
        assertThat(tokenOther.hasAnyRoles(List.of("EDITOR", "MAINTAINER")), is(true));
        assertThat(tokenOther.hasAnyRoles("EDITOR", "MAINTAINER"), is(true));
        assertThat(tokenOther.hasAnyRoles("MAINTAINER"), is(true));
        assertThat(tokenOther.hasAnyRoles("ADMIN"), is(false));
        assertThat(tokenOther.hasAnyRoles(), is(false));
    }
    @Test void usersShouldBeCaseSensitive() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("authentication.userRoles", Map.of(
                "namE", Set.of("ROLE_NAME"),
                "[emaiL@email.net]", Set.of("ROLE_EMAIL")
            ))
        ));
        assertThat(createTokenForUser("name").hasRole("ROLE_NAME"), is(true));
        assertThat(createTokenForUser("email").hasRole("ROLE_EMAIL"), is(true));
    }
    @Test void shouldCreateValidTokenFromTokenString() {
        final UserToken tokenIn = createTokenForUser("last name, first name (something)");
        final String tokenString = tokenIn.toTokenString(SECRET);
        final UserToken token = UserToken.fromTokenString(tokenString, SECRET);
        assertThat(token, is(tokenIn));
        assertThat(token.name, is("last name, first name (something)"));
    }
    @Test void encryptionShouldPreventTampering() {
        final String tokenString = createTokenForUser("someAdmin").toTokenString(SECRET);
        assertThrows(NotAuthorizedException.class, () -> UserToken.fromTokenString(tokenString.substring(1)));
    }
    @Test void tokenShouldExpireAfterTimeout() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of("authentication.tokenTimeout", "100ms")));
        final UserToken token = createTokenForUser("someName");
        final String tokenString = token.toTokenString();

        assertThat(UserToken.fromTokenString(tokenString), CoreMatchers.is(token));
        Utils.sleep(250);
        assertThrows(NotAuthorizedException.class, () -> UserToken.fromTokenString(tokenString));
    }
    @Test void notAuthorizedOnWeirdInput() {
        assertThrows(NotAuthorizedException.class, () -> UserToken.fromTokenString(" "));
        assertThrows(NotAuthorizedException.class, () -> UserToken.fromTokenString("foobar"));
    }
    @Test void noTokenShouldLeadToGuestToken() {
        assertThat(UserToken.fromTokenString(null), CoreMatchers.is(UserToken.GUEST_TOKEN));
        assertThat(UserToken.fromTokenString(""), CoreMatchers.is(UserToken.GUEST_TOKEN));
    }
    @Test void noRolesShouldAllowAll() {
        assertThat(createTokenForUser("foobar").hasAllRoles(UserToken.ROLE_ADMIN, "whatever_role"), is(true));
        assertThat(createTokenForUser("foobar").hasAnyRoles(UserToken.ROLE_ADMIN, "whatever_role"), is(true));
    }
    @Test void guestTokenIsGuest() {
        assertThat(UserToken.GUEST_TOKEN.isGuest(), is(true));
    }
    @Test void nonGuestTokensShouldBeLoggedIn() {
        assertThat(UserToken.GUEST_TOKEN.isLoggedIn(), is(false));
        assertThat(UserToken.builder().id("123").name("user").email("user@some.org").build().isLoggedIn(), is(true));
    }
    @Test void tokenShouldBeCreatedFromHttpHeader() {
        final UserToken token = UserToken.builder().name("user").build();
        final HttpHeadersFacade headers = new HttpHeadersFacade(Map.of(UserToken.HTTP_HEADER_NAME, token.toTokenString()));
        assertThat(UserToken.from(headers).orElseThrow(), is(token));
    }
    @Test void tokenShouldBeCreatedFromCookie() {
        final UserToken token = UserToken.builder().name("user").build();
        final HttpHeadersFacade headers = new HttpHeadersFacade(Map.of("Cookie", "foo=bar; " + UserToken.HTTP_HEADER_NAME + "="+ token.toTokenString() + "; expires"));
        assertThat(UserToken.from(headers).orElseThrow(), is(token));
    }
    @Test void shouldReturnEmailName() {
        assertThat(UserToken.builder().name("name").email("email@domain").build().getEmailName(), is("email"));
        assertThat(UserToken.builder().name("name").email("email").build().getEmailName(), is("email"));
        assertThat(UserToken.builder().name("name").build().getEmailName(), is("name"));
    }
    @Test void shouldGetTokenFromHttpHeaders() {
        final HttpHeadersFacade headers = new HttpHeadersFacade(Map.of(UserToken.HTTP_HEADER_NAME, SECRET));
        assertThat(UserToken.raw(headers).orElse(""), is(SECRET));
    }
    @Test void shouldGetTokenFromHttpCookie() {
        final HttpHeadersFacade headers = new HttpHeadersFacade(Map.of("Cookie", "foo=bar; " + UserToken.HTTP_HEADER_NAME + "=" + SECRET + "; rest=etc;"));
        assertThat(UserToken.raw(headers).orElse(""), is(SECRET));
    }
}
