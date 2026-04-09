package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketBaseRequestDto;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Converts connector request credentials into an AWS {@link AwsCredentialsProvider}.
 *
 * <p>Supports both long-term IAM user credentials (access key + secret) and temporary
 * credentials issued by STS (access key + secret + session token).</p>
 */
@Component
public class BraketAuthProvider {

    /**
     * Returns a {@link StaticCredentialsProvider} built from the request credentials.
     * When a session token is present, {@link AwsSessionCredentials} are used; otherwise
     * {@link AwsBasicCredentials} are used.
     *
     * @param request connector request carrying AWS credentials
     * @return credentials provider ready for use with any AWS SDK client
     */
    public AwsCredentialsProvider getCredentialsProvider(BraketBaseRequestDto request) {
        if (request.getSessionToken() != null && !request.getSessionToken().isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(
                            request.getAccessKeyId(),
                            request.getSecretAccessKey(),
                            request.getSessionToken()
                    )
            );
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(request.getAccessKeyId(), request.getSecretAccessKey())
        );
    }
}
