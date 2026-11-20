package de.goForFun.familienkalender.outlook;

import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

/**
 * {@link RefreshTokenStore} auf Basis des SSM Parameter Store. Jeder Fehler wird auf
 * {@link GraphErrorCategory#SSM_UNAVAILABLE} abgebildet; Wert und SDK-Meldungstexte gelangen nie
 * in die Exception.
 */
public final class SsmRefreshTokenStore implements RefreshTokenStore, AutoCloseable {

    private final SsmClient ssm;

    public SsmRefreshTokenStore(SsmClient ssm) {
        this.ssm = Objects.requireNonNull(ssm, "ssm");
    }

    @Override
    public String read(String parameterName, Duration timeout) throws GraphException {
        String value;
        try {
            GetParameterResponse response = ssm.getParameter(GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .overrideConfiguration(c -> c.apiCallTimeout(timeout))
                    .build());
            value = response == null || response.parameter() == null
                    ? null : response.parameter().value();
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (value == null || value.isEmpty()) {
            throw unavailable();
        }
        return value;
    }

    @Override
    public void write(String parameterName, String value, Duration timeout) throws GraphException {
        try {
            ssm.putParameter(PutParameterRequest.builder()
                    .name(parameterName)
                    .value(value)
                    .type(ParameterType.SECURE_STRING)
                    .overwrite(true)
                    .overrideConfiguration(c -> c.apiCallTimeout(timeout))
                    .build());
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @Override
    public void close() {
        ssm.close();
    }

    private static GraphException unavailable() {
        return new GraphException(GraphErrorCategory.SSM_UNAVAILABLE);
    }
}
