package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketConnectorResponseDto;
import de.envite.connector.braket.dto.BraketGetTaskResultRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import de.envite.connector.braket.dto.BraketTaskDetailsDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static de.envite.connector.braket.BraketConstants.*;

/**
 * Orchestrates the AWS Braket connector workflow.
 *
 * <p>Delegates credential handling to {@link BraketAuthProvider}, circuit serialisation
 * to {@link BraketParameterHandler}, task lifecycle to {@link BraketTaskClient},
 * and S3 result retrieval to {@link BraketS3Client}.</p>
 */
@Slf4j
@AllArgsConstructor
@Service
public class BraketService {

    private final BraketTaskClient taskClient;
    private final BraketParameterHandler parameterHandler;
    private final BraketS3Client s3Client;

    /**
     * Submits a quantum task to AWS Braket and optionally waits for completion.
     *
     * <p>When {@link BraketSubmitTaskRequestDto#getWaitForResult()} is {@code false}, the task
     * is submitted and the response is returned immediately with status {@code QUEUED} and no result.
     * Otherwise, the connector polls until the task reaches a terminal state or the configured
     * timeout is exceeded. If the terminal state is {@code COMPLETED}, the result is fetched from S3.</p>
     *
     * @param request the connector request describing the task to run
     * @return task ARN, terminal status, result payload (when COMPLETED), and S3 URI
     */
    public BraketConnectorResponseDto submitTask(BraketSubmitTaskRequestDto request) {
        log.debug("[BraketService] Received request: device={} shots={}", request.getDeviceArn(), request.getShots());

        String action = parameterHandler.buildAction(request);
        String taskArn = taskClient.submitTask(request, action);
        log.debug("[BraketService] Task submitted: arn={}", taskArn);

        if (!request.getWaitForResult()) {
            log.debug("[BraketService] waitForResult=false, returning immediately with status QUEUED");
            return new BraketConnectorResponseDto(taskArn, STATUS_QUEUED, null, buildS3Uri(request, taskArn));
        }

        String status = taskClient.pollUntilTerminal(request, taskArn);
        log.debug("[BraketService] Task reached terminal state: arn={} status={}", taskArn, status);

        String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
        Object result = STATUS_COMPLETED.equals(status)
                ? s3Client.fetchResult(request, request.getS3ResultBucket(), request.getS3KeyPrefix() + "/" + taskId)
                : null;

        return new BraketConnectorResponseDto(taskArn, status, result, buildS3Uri(request, taskId));
    }

    /**
     * Checks the current status of a previously submitted task and returns its result if completed.
     *
     * <p>Makes a single status request — no polling. Intended for use inside a BPMN polling loop
     * where a timer intermediate event controls the wait between invocations.
     * The S3 output location is retrieved from the task metadata so the caller only needs
     * to supply the task ARN.</p>
     *
     * @param request the connector request; must contain the {@code taskArn}, credentials, and region
     * @return task ARN, current status, and result payload (only when COMPLETED)
     */
    public BraketConnectorResponseDto getTaskResult(BraketGetTaskResultRequestDto request) {
        log.debug("[BraketService] Checking task result: arn={}", request.getTaskArn());

        BraketTaskDetailsDto details = taskClient.getTaskDetails(request, request.getTaskArn());
        log.debug("[BraketService] Task status: arn={} status={}", request.getTaskArn(), details.getStatus());

        Object result = STATUS_COMPLETED.equals(details.getStatus())
                ? s3Client.fetchResult(request, details.getS3Bucket(), details.getS3Directory())
                : null;

        return new BraketConnectorResponseDto(request.getTaskArn(), details.getStatus(), result, null);
    }

    /**
     * Constructs the S3 URI where Braket stores the task result.
     *
     * <p>Braket writes results to {@code s3://<bucket>/<prefix>/<taskId>/results.json}.
     * The task ID is the last path segment of the task ARN.</p>
     */
    private String buildS3Uri(BraketSubmitTaskRequestDto request, String taskId) {
        return S3_URI_PREFIX + request.getS3ResultBucket() + "/" + request.getS3KeyPrefix() + "/" + taskId + "/results.json";
    }
}
