package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.util.Map;

/**
 * Ein HTTP-Aufruf.
 *
 * @param formOrNullBody Request-Body (z. B. form-urlencoded) oder {@code null} ohne Body
 */
public record HttpCall(String method, URI uri, Map<String, String> headers, String formOrNullBody) {

    @Override
    public String toString() {
        // Header und Body können Token enthalten und werden nie ausgegeben.
        return "HttpCall[" + method + " " + uri.getHost() + "]";
    }
}
