package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketConnectorResponseDto;
import de.envite.connector.braket.dto.BraketGetTaskResultRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static de.envite.connector.braket.BraketConstants.*;

/**
 * Orchestrates the AWS Braket connector workflow.
 *
 * <p>Delegates credential handling to {@link BraketAuthProvider}, circuit serialisation
 * to {@link BraketParameterHandler}, and all AWS SDK calls to {@link BraketTaskClient}.</p>
 */
@Slf4j
@AllArgsConstructor
@Service
public class BraketService {

    private final BraketTaskClient taskClient;
    private final BraketParameterHandler parameterHandler;

    /**
     * Submits a quantum task to AWS Braket and optionally waits for completion.
     *
     * <p>When {@link BraketSubmitTaskRequestDto#getWaitForResult()} is {@code false}, the task
     * is submitted and the response is returned immediately with status {@code QUEUED} and no result.
     * Otherwise, the connector polls until the task reaches a terminal state or the configured
     * timeout is exceeded.</p>
     *
     * @param request the connector request describing the task to run
     * @return task ARN, terminal status, and S3 URI of the result location
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

        // Phase 2: fetch result from S3 when status is COMPLETED
        return new BraketConnectorResponseDto(taskArn, status, null, buildS3Uri(request, taskArn));
    }

    /**
     * Checks the current status of a previously submitted task.
     *
     * <p>Makes a single status request — no polling. Intended for use inside a BPMN polling loop
     * where a timer intermediate event controls the wait between invocations.</p>
     *
     * @param request the connector request; must contain the {@code taskArn}, credentials, and region
     * @return task ARN, current status, and result payload (Phase 2: only when COMPLETED)
     */
    public BraketConnectorResponseDto getTaskResult(BraketGetTaskResultRequestDto request) {
        log.debug("[BraketService] Checking task result: arn={}", request.getTaskArn());

        String status = taskClient.getTaskStatus(request, request.getTaskArn());
        log.debug("[BraketService] Task status: arn={} status={}", request.getTaskArn(), status);

        // Phase 2: fetch result from S3 when status is COMPLETED
        return new BraketConnectorResponseDto(request.getTaskArn(), status, null, null);
    }

    /**
     * Constructs the S3 URI where Braket stores the task result.
     *
     * <p>Braket writes results to {@code s3://<bucket>/<prefix>/<taskId>/results.json}.
     * The task ID is the last path segment of the task ARN.</p>
     */
    private String buildS3Uri(BraketSubmitTaskRequestDto request, String taskArn) {
        String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
        return S3_URI_PREFIX + request.getS3ResultBucket() + "/" + request.getS3KeyPrefix() + "/" + taskId + "/results.json";
    }
}
