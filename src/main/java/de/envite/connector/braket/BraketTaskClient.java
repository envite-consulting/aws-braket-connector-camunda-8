package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketBaseRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import de.envite.connector.braket.dto.BraketTaskDetailsDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.braket.BraketClient;
import software.amazon.awssdk.services.braket.model.CreateQuantumTaskRequest;
import software.amazon.awssdk.services.braket.model.GetQuantumTaskRequest;
import software.amazon.awssdk.services.braket.model.GetQuantumTaskResponse;

import java.time.Instant;
import java.util.Set;

import static de.envite.connector.braket.BraketConstants.*;

/**
 * AWS SDK client for the Braket quantum task lifecycle.
 *
 * <p>Covers task submission, status polling, and status retrieval.
 * A new {@link BraketClient} is created per operation using the credentials
 * supplied with each connector request, then closed immediately after use.</p>
 */
@Slf4j
@AllArgsConstructor
@Component
public class BraketTaskClient {

    private static final Set<String> TERMINAL_STATES = Set.of(
            STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED
    );

    private final BraketAuthProvider authProvider;

    /**
     * Submits a quantum task to AWS Braket and returns the assigned task ARN.
     *
     * @param request connector request carrying device, S3, and shot configuration
     * @param action  Braket IR JSON string representing the circuit
     * @return the task ARN assigned by AWS Braket
     */
    public String submitTask(BraketSubmitTaskRequestDto request, String action) {
        log.debug("[BraketTaskClient] Submitting task: device={} shots={}", request.getDeviceArn(), request.getShots());
        try (BraketClient client = buildClient(request)) {
            return client.createQuantumTask(
                    CreateQuantumTaskRequest.builder()
                            .deviceArn(request.getDeviceArn())
                            .action(action)
                            .shots(request.getShots().longValue())
                            .outputS3Bucket(request.getS3ResultBucket())
                            .outputS3KeyPrefix(request.getS3KeyPrefix())
                            .build()
            ).quantumTaskArn();
        }
    }

    /**
     * Fetches the current status and S3 output coordinates of a task (single request, no polling).
     *
     * <p>The S3 coordinates are always present in the response regardless of task status,
     * so they can be used immediately once the task is {@code COMPLETED}.</p>
     *
     * @param request connector request carrying AWS credentials and region
     * @param taskArn ARN of the task to query
     * @return task status together with the S3 bucket and key prefix for the result
     */
    public BraketTaskDetailsDto getTaskDetails(BraketBaseRequestDto request, String taskArn) {
        try (BraketClient client = buildClient(request)) {
            GetQuantumTaskResponse response = client.getQuantumTask(
                    GetQuantumTaskRequest.builder().quantumTaskArn(taskArn).build()
            );
            return new BraketTaskDetailsDto(
                    response.statusAsString(),
                    response.outputS3Bucket(),
                    response.outputS3Directory()
            );
        }
    }

    /**
     * Polls the task status until it reaches a terminal state or the configured timeout expires.
     *
     * @param request connector request carrying timeout and poll interval configuration
     * @param taskArn ARN of the task to poll
     * @return the terminal status ({@code COMPLETED}, {@code FAILED}, or {@code CANCELLED})
     * @throws RuntimeException if the timeout expires or polling is interrupted
     */
    public String pollUntilTerminal(BraketSubmitTaskRequestDto request, String taskArn) {
        Instant deadline = Instant.now().plusSeconds(request.getTimeoutSeconds());
        try (BraketClient client = buildClient(request)) {
            while (Instant.now().isBefore(deadline)) {
                String status = fetchStatus(client, taskArn);
                log.debug("[BraketTaskClient] Polled task status: arn={} status={}", taskArn, status);
                if (TERMINAL_STATES.contains(status)) {
                    return status;
                }
                try {
                    Thread.sleep(request.getPollIntervalSeconds() * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Polling interrupted", e);
                }
            }
        }
        throw new RuntimeException("Timed out after %d seconds waiting for task %s".formatted(
                request.getTimeoutSeconds(), taskArn));
    }

    private String fetchStatus(BraketClient client, String taskArn) {
        return client.getQuantumTask(
                GetQuantumTaskRequest.builder().quantumTaskArn(taskArn).build()
        ).statusAsString();
    }

    private BraketClient buildClient(BraketBaseRequestDto request) {
        return BraketClient.builder()
                .credentialsProvider(authProvider.getCredentialsProvider(request))
                .region(Region.of(request.getRegion()))
                .build();
    }
}
