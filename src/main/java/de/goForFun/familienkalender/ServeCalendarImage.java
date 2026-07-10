package de.goForFun.familienkalender;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Base64;
import java.util.Map;

/**
 * Lambda Function URL Handler – liefert die calendar.bin aus S3 an den ESP32.
 * Authentifizierung via Bearer Token (Shared Secret aus ENV).
 *
 * Erwartet:
 *   Header "authorization: Bearer &lt;API_SECRET&gt;"
 *
 * Antwort:
 *   200 + Base64-encoded binary body (application/octet-stream)
 *   401 bei fehlendem/falschem Token
 *   404 wenn calendar.bin nicht auf S3 existiert
 */
public class ServeCalendarImage implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String API_SECRET = System.getenv("API_SECRET");
    private static final String S3_BUCKET = "familienkalender";
    private static final String S3_KEY_BIN = "calendar.bin";

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        // Authenticate request
        if (!isAuthorized(event)) {
            context.getLogger().log("WARN: Unauthorized request");
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withBody("Unauthorized")
                    .build();
        }

        // Fetch calendar.bin from S3
        try (S3Client s3Client = S3Client.builder().region(Region.of(AWS_REGION)).build()) {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(S3_BUCKET)
                            .key(S3_KEY_BIN)
                            .build()
            );

            String base64Body = Base64.getEncoder().encodeToString(objectBytes.asByteArray());

            context.getLogger().log("Serving calendar.bin: " + objectBytes.asByteArray().length + " bytes");

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Content-Type", "application/octet-stream",
                            "Content-Length", String.valueOf(objectBytes.asByteArray().length)
                    ))
                    .withBody(base64Body)
                    .withIsBase64Encoded(true)
                    .build();

        } catch (NoSuchKeyException e) {
            context.getLogger().log("ERROR: calendar.bin not found on S3");
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(404)
                    .withBody("calendar.bin not found")
                    .build();
        }
    }

    private boolean isAuthorized(APIGatewayV2HTTPEvent event) {
        if (API_SECRET == null || API_SECRET.isBlank()) {
            // No secret configured = no auth (dev mode)
            return true;
        }

        Map<String, String> headers = event.getHeaders();
        if (headers == null) {
            return false;
        }

        // Lambda Function URL lowercases all header names
        String authHeader = headers.get("authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }

        String token = authHeader.substring("Bearer ".length());
        return API_SECRET.equals(token);
    }
}
