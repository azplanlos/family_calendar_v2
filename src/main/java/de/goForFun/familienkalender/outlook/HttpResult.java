package de.goForFun.familienkalender.outlook;

/** Antwort eines {@link GraphHttp}-Aufrufs. */
public record HttpResult(int status, String body) {

    @Override
    public String toString() {
        // Der Body kann Token oder Termininhalte enthalten.
        return "HttpResult[status=" + status + "]";
    }
}
