package de.envite.connector.braket;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.connector.braket.dto.BraketConnectorResponseDto;
import de.envite.connector.braket.dto.BraketGetTaskResultRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import de.envite.connector.braket.dto.BraketTaskDetailsDto;
import de.envite.connector.braket.model.CircuitInputMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static de.envite.connector.braket.BraketConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("service")
@ExtendWith(MockitoExtension.class)
class BraketServiceTest {

    private static final String TASK_ARN    = "arn:aws:braket:us-east-1:123456789012:quantum-task/test-task-id";
    private static final String TASK_ID     = "test-task-id";
    private static final String DEVICE_ARN  = "arn:aws:braket:::device/quantum-simulator/amazon/sv1";
    private static final String S3_BUCKET   = "my-braket-bucket";
    private static final String S3_PREFIX   = "results";
    private static final String S3_DIR      = S3_PREFIX + "/" + TASK_ID;
    private static final String CIRCUIT     = "OPENQASM 3.0; qubit[1] q; x q[0];";
    private static final Object MOCK_RESULT = new ObjectMapper().createObjectNode().put("measurementProbabilities", "{}");
    private static final String OPEN_QASM_ACTION = "{\"braketSchemaHeader\":{},\"source\":\"" + CIRCUIT + "\",\"inputs\":{}}";

    @Mock private BraketTaskClient taskClient;
    @Mock private BraketParameterHandler parameterHandler;
    @Mock private BraketS3Client s3Client;
    @InjectMocks private BraketService service;

    // -------------------------------------------------------------------------
    // submitTask – no wait
    // -------------------------------------------------------------------------

    @Test
    void submitTask_withNoWait_returnsQueuedWithoutFetchingResult() {
        when(parameterHandler.buildAction(any())).thenReturn(OPEN_QASM_ACTION);
        when(taskClient.submitTask(any(), eq(OPEN_QASM_ACTION))).thenReturn(TASK_ARN);

        BraketConnectorResponseDto result = service.submitTask(openQasmRequest().waitForResult(false).build());

        assertThat(result.getTaskArn()).isEqualTo(TASK_ARN);
        assertThat(result.getStatus()).isEqualTo(STATUS_QUEUED);
        assertThat(result.getResult()).isNull();
        assertThat(result.getResultS3Uri()).contains(S3_BUCKET).contains(TASK_ID);
        verify(taskClient, never()).pollUntilTerminal(any(), any());
        verify(s3Client, never()).fetchResult(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // submitTask – wait for result
    // -------------------------------------------------------------------------

    @Test
    void submitTask_whenCompleted_fetchesAndReturnsResult() {
        when(parameterHandler.buildAction(any())).thenReturn(OPEN_QASM_ACTION);
        when(taskClient.submitTask(any(), eq(OPEN_QASM_ACTION))).thenReturn(TASK_ARN);
        when(taskClient.pollUntilTerminal(any(), eq(TASK_ARN))).thenReturn(STATUS_COMPLETED);
        when(s3Client.fetchResult(any(), eq(S3_BUCKET), eq(S3_DIR))).thenReturn(MOCK_RESULT);

        BraketConnectorResponseDto result = service.submitTask(openQasmRequest().build());

        assertThat(result.getTaskArn()).isEqualTo(TASK_ARN);
        assertThat(result.getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(result.getResult()).isEqualTo(MOCK_RESULT);
        assertThat(result.getResultS3Uri()).isEqualTo(S3_URI_PREFIX + S3_BUCKET + "/" + S3_DIR + "/results.json");
    }

    @Test
    void submitTask_whenFailed_returnsFailedWithNullResult() {
        when(parameterHandler.buildAction(any())).thenReturn(OPEN_QASM_ACTION);
        when(taskClient.submitTask(any(), eq(OPEN_QASM_ACTION))).thenReturn(TASK_ARN);
        when(taskClient.pollUntilTerminal(any(), eq(TASK_ARN))).thenReturn(STATUS_FAILED);

        BraketConnectorResponseDto result = service.submitTask(openQasmRequest().build());

        assertThat(result.getStatus()).isEqualTo(STATUS_FAILED);
        assertThat(result.getResult()).isNull();
        verify(s3Client, never()).fetchResult(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // submitTask – DIRECT_PARAMS mode
    // -------------------------------------------------------------------------

    @Test
    void submitTask_withDirectParams_passesRawActionToClient() {
        String rawAction = "{\"braketSchemaHeader\":{\"name\":\"braket.ir.openqasm.program\",\"version\":\"1\"},\"source\":\"" + CIRCUIT + "\",\"inputs\":{}}";
        when(parameterHandler.buildAction(any())).thenReturn(rawAction);
        when(taskClient.submitTask(any(), eq(rawAction))).thenReturn(TASK_ARN);

        BraketConnectorResponseDto result = service.submitTask(directParamsRequest().waitForResult(false).build());

        assertThat(result.getTaskArn()).isEqualTo(TASK_ARN);
        assertThat(result.getStatus()).isEqualTo(STATUS_QUEUED);
        verify(parameterHandler).buildAction(any());
    }

    // -------------------------------------------------------------------------
    // getTaskResult
    // -------------------------------------------------------------------------

    @Test
    void getTaskResult_whenCompleted_fetchesAndReturnsResult() {
        when(taskClient.getTaskDetails(any(), eq(TASK_ARN)))
                .thenReturn(new BraketTaskDetailsDto(STATUS_COMPLETED, S3_BUCKET, S3_DIR));
        when(s3Client.fetchResult(any(), eq(S3_BUCKET), eq(S3_DIR))).thenReturn(MOCK_RESULT);

        BraketConnectorResponseDto result = service.getTaskResult(getTaskResultRequest().build());

        assertThat(result.getTaskArn()).isEqualTo(TASK_ARN);
        assertThat(result.getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(result.getResult()).isEqualTo(MOCK_RESULT);
    }

    @Test
    void getTaskResult_whenRunning_returnsStatusWithNullResult() {
        when(taskClient.getTaskDetails(any(), eq(TASK_ARN)))
                .thenReturn(new BraketTaskDetailsDto(STATUS_RUNNING, S3_BUCKET, S3_DIR));

        BraketConnectorResponseDto result = service.getTaskResult(getTaskResultRequest().build());

        assertThat(result.getStatus()).isEqualTo(STATUS_RUNNING);
        assertThat(result.getResult()).isNull();
        verify(s3Client, never()).fetchResult(any(), any(), any());
    }

    @Test
    void getTaskResult_whenFailed_returnsFailedWithNullResult() {
        when(taskClient.getTaskDetails(any(), eq(TASK_ARN)))
                .thenReturn(new BraketTaskDetailsDto(STATUS_FAILED, S3_BUCKET, S3_DIR));

        BraketConnectorResponseDto result = service.getTaskResult(getTaskResultRequest().build());

        assertThat(result.getStatus()).isEqualTo(STATUS_FAILED);
        assertThat(result.getResult()).isNull();
        verify(s3Client, never()).fetchResult(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void submitTask_whenPollTimesOut_throwsRuntimeException() {
        when(parameterHandler.buildAction(any())).thenReturn(OPEN_QASM_ACTION);
        when(taskClient.submitTask(any(), any())).thenReturn(TASK_ARN);
        when(taskClient.pollUntilTerminal(any(), eq(TASK_ARN)))
                .thenThrow(new RuntimeException("Timed out after 1 seconds waiting for task " + TASK_ARN));

        assertThatThrownBy(() -> service.submitTask(openQasmRequest().timeoutSeconds(1).build()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Timed out");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BraketSubmitTaskRequestDto.BraketSubmitTaskRequestDtoBuilder<?, ?> openQasmRequest() {
        return BraketSubmitTaskRequestDto.builder()
                .accessKeyId("AKIAIOSFODNN7EXAMPLE")
                .secretAccessKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                .region("us-east-1")
                .deviceArn(DEVICE_ARN)
                .circuitInputMode(CircuitInputMode.OPEN_QASM)
                .circuit(CIRCUIT)
                .shots(1000)
                .s3ResultBucket(S3_BUCKET)
                .s3KeyPrefix(S3_PREFIX)
                .waitForResult(true)
                .timeoutSeconds(30)
                .pollIntervalSeconds(1);
    }

    private BraketSubmitTaskRequestDto.BraketSubmitTaskRequestDtoBuilder<?, ?> directParamsRequest() {
        return BraketSubmitTaskRequestDto.builder()
                .accessKeyId("AKIAIOSFODNN7EXAMPLE")
                .secretAccessKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                .region("us-east-1")
                .deviceArn(DEVICE_ARN)
                .circuitInputMode(CircuitInputMode.DIRECT_PARAMS)
                .params("{\"braketSchemaHeader\":{\"name\":\"braket.ir.openqasm.program\",\"version\":\"1\"},\"source\":\"" + CIRCUIT + "\",\"inputs\":{}}")
                .shots(1000)
                .s3ResultBucket(S3_BUCKET)
                .s3KeyPrefix(S3_PREFIX)
                .waitForResult(true)
                .timeoutSeconds(30)
                .pollIntervalSeconds(1);
    }

    private BraketGetTaskResultRequestDto.BraketGetTaskResultRequestDtoBuilder<?, ?> getTaskResultRequest() {
        return BraketGetTaskResultRequestDto.builder()
                .accessKeyId("AKIAIOSFODNN7EXAMPLE")
                .secretAccessKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                .region("us-east-1")
                .taskArn(TASK_ARN);
    }
}
