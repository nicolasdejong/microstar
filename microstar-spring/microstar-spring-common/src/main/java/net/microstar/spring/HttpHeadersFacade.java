package net.microstar.spring;

import org.springframework.http.HttpHeaders;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/**
 * MVC has different http request classes than Reactive and also sometimes requests
 * are wrapped in different request classes (like the FirewalledRequest).
 * This class provides a single way to access http headers.
 */
@SuppressWarnings("SameParameterValue")
public class HttpHeadersFacade {
    private final UnaryOperator<String> getFirst;

    /** Currently supported objects: <pre>{@code
     *  HttpHeaders,
     *  Map<String,String>,
     *  object with method HttpHeaders getHeaders()
     *  object with method String getHeader(String),
     *  }</pre>
     */
    public HttpHeadersFacade(Object headersHolder) {
        getFirst = Optional.<UnaryOperator<String>>empty() // make all below lines orthogonal
            .or(() -> (headersHolder instanceof HttpHeaders hh) ? Optional.of(hh::getFirst) : Optional.empty())
            .or(() -> (headersHolder instanceof Map<?,?> map ? Optional.of(name -> (String)map.get(name)) : Optional.empty()))
            .or(() -> noThrow(() -> getMethodOf(headersHolder, "getHeaders"))
                .map(gh -> name -> noThrow(() -> (HttpHeaders)gh.invoke(headersHolder)).map(headers -> headers.getFirst(name)).orElse(null)))
            .or(() -> noThrow(() -> getMethodOf(headersHolder, "getHeader", "test"))
                          .map(method -> name -> noThrow(() -> method.invoke(headersHolder, name)).map(Object::toString).orElse(null)))
            .orElseGet(() -> name -> (String)null);
    }

    public Optional<String> getFirst(String headerName) {
        return Optional.ofNullable(getFirst.apply(headerName));
    }

    // Some methods are public but still are not allowed (perhaps because it is a private/protected inner class?)
    private static Method getMethodOf(Object instance, String name) throws NoSuchMethodException, InvocationTargetException {
        Method method = instance.getClass().getMethod(name);
        try { method.invoke(instance); } catch(final IllegalAccessException e) { method = instance.getClass().getSuperclass().getMethod(name); }
        return method;
    }
    private static Method getMethodOf(Object instance, String name, String testParam) throws NoSuchMethodException, InvocationTargetException {
        Method method = instance.getClass().getMethod(name, String.class);
        try { method.invoke(instance, testParam); } catch(final IllegalAccessException e) { method = instance.getClass().getSuperclass().getMethod(name, String.class); }
        return method;
    }
}
