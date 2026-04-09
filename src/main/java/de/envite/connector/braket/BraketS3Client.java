package de.envite.connector.braket;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.connector.braket.dto.BraketBaseRequestDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Fetches and parses quantum task results stored in S3 by AWS Braket.
 *
 * <p>Braket writes results to {@code s3://<bucket>/<s3Directory>/results.json}
 * once a task reaches the {@code COMPLETED} status. The {@code s3Directory} is
 * the {@code outputS3Directory} field returned by {@code GetQuantumTask}
 * (format: {@code <keyPrefix>/<taskId>}).</p>
 */
@Slf4j
@AllArgsConstructor
@Component
public class BraketS3Client {

    private final BraketAuthProvider authProvider;
    private final ObjectMapper objectMapper;

    /**
     * Downloads and parses the result JSON for the given task.
     *
     * @param request     connector request carrying AWS credentials and region
     * @param s3Bucket    S3 bucket name
     * @param s3Directory full S3 directory path, e.g. {@code my-prefix/<taskId>}
     * @return parsed result payload as a {@code JsonNode}
     * @throws RuntimeException if the S3 object cannot be retrieved or parsed
     */
    public Object fetchResult(BraketBaseRequestDto request, String s3Bucket, String s3Directory) {
        String key = s3Directory + "/results.json";
        log.debug("[BraketS3Client] Fetching result: bucket={} key={}", s3Bucket, key);

        try (S3Client client = buildClient(request)) {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(key)
                            .build()
            );
            return objectMapper.readTree(response.asUtf8String());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch task result from S3 (bucket=%s key=%s): %s"
                    .formatted(s3Bucket, key, e.getMessage()), e);
        }
    }

    private S3Client buildClient(BraketBaseRequestDto request) {
        return S3Client.builder()
                .credentialsProvider(authProvider.getCredentialsProvider(request))
                .region(Region.of(request.getRegion()))
                .build();
    }
}
