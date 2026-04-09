package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketBaseRequestDto;
import de.envite.connector.braket.dto.BraketConnectorResponseDto;
import de.envite.connector.braket.dto.BraketGetTaskResultRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import de.envite.connector.braket.model.CircuitInputMode;
import de.envite.connector.braket.model.OperationMode;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static de.envite.connector.braket.BraketConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("connector")
@ExtendWith(MockitoExtension.class)
class BraketConnectorFunctionTest {

    private static final String TASK_ARN    = "arn:aws:braket:us-east-1:123456789012:quantum-task/test-task-id";
    private static final String DEVICE_ARN  = "arn:aws:braket:::device/quantum-simulator/amazon/sv1";
    private static final String S3_BUCKET   = "my-braket-bucket";
    private static final String S3_PREFIX   = "results";

    @Mock
    private BraketService braketService;

    @InjectMocks
    private BraketConnectorFunction function;

    @Test
    void execute_withSubmitTask_delegatesToSubmitTask() {
        BraketSubmitTaskRequestDto submitRequest = buildSubmitRequest();
        BraketConnectorResponseDto expected = new BraketConnectorResponseDto(TASK_ARN, STATUS_QUEUED, null, null);

        OutboundConnectorContext context = mock(OutboundConnectorContext.class);
        when(context.bindVariables(BraketBaseRequestDto.class)).thenReturn(baseRequest(OperationMode.SUBMIT_TASK));
        when(context.bindVariables(BraketSubmitTaskRequestDto.class)).thenReturn(submitRequest);
        when(braketService.submitTask(submitRequest)).thenReturn(expected);

        Object result = function.execute(context);

        assertThat(result).isEqualTo(expected);
        verify(braketService).submitTask(submitRequest);
        verify(braketService, never()).getTaskResult(any());
    }

    @Test
    void execute_withGetTaskResult_delegatesToGetTaskResult() {
        BraketGetTaskResultRequestDto getRequest = buildGetTaskResultRequest();
        BraketConnectorResponseDto expected = new BraketConnectorResponseDto(TASK_ARN, STATUS_COMPLETED, null, null);

        OutboundConnectorContext context = mock(OutboundConnectorContext.class);
        when(context.bindVariables(BraketBaseRequestDto.class)).thenReturn(baseRequest(OperationMode.GET_TASK_RESULT));
        when(context.bindVariables(BraketGetTaskResultRequestDto.class)).thenReturn(getRequest);
        when(braketService.getTaskResult(getRequest)).thenReturn(expected);

        Object result = function.execute(context);

        assertThat(result).isEqualTo(expected);
        verify(braketService).getTaskResult(getRequest);
        verify(braketService, never()).submitTask(any());
    }

    @Test
    void execute_withDirectParams_delegatesToSubmitTask() {
        BraketSubmitTaskRequestDto submitRequest = buildDirectParamsRequest();
        BraketConnectorResponseDto expected = new BraketConnectorResponseDto(TASK_ARN, STATUS_QUEUED, null, null);

        OutboundConnectorContext context = mock(OutboundConnectorContext.class);
        when(context.bindVariables(BraketBaseRequestDto.class)).thenReturn(baseRequest(OperationMode.SUBMIT_TASK));
        when(context.bindVariables(BraketSubmitTaskRequestDto.class)).thenReturn(submitRequest);
        when(braketService.submitTask(submitRequest)).thenReturn(expected);

        Object result = function.execute(context);

        assertThat(result).isEqualTo(expected);
        verify(braketService).submitTask(submitRequest);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BraketBaseRequestDto baseRequest(OperationMode mode) {
        return BraketBaseRequestDto.builder().operationMode(mode)
                .accessKeyId("key").secretAccessKey("secret").region("us-east-1").build();
    }

    private BraketSubmitTaskRequestDto buildSubmitRequest() {
        return BraketSubmitTaskRequestDto.builder()
                .accessKeyId("key").secretAccessKey("secret").region("us-east-1")
                .deviceArn(DEVICE_ARN)
                .circuitInputMode(CircuitInputMode.OPEN_QASM)
                .circuit("OPENQASM 3.0; qubit[1] q; x q[0];")
                .shots(1000)
                .s3ResultBucket(S3_BUCKET).s3KeyPrefix(S3_PREFIX)
                .waitForResult(false)
                .build();
    }

    private BraketSubmitTaskRequestDto buildDirectParamsRequest() {
        return BraketSubmitTaskRequestDto.builder()
                .accessKeyId("key").secretAccessKey("secret").region("us-east-1")
                .deviceArn(DEVICE_ARN)
                .circuitInputMode(CircuitInputMode.DIRECT_PARAMS)
                .params("{\"braketSchemaHeader\":{\"name\":\"braket.ir.openqasm.program\",\"version\":\"1\"},\"source\":\"OPENQASM 3.0; qubit[1] q; x q[0];\",\"inputs\":{}}")
                .shots(1000)
                .s3ResultBucket(S3_BUCKET).s3KeyPrefix(S3_PREFIX)
                .waitForResult(false)
                .build();
    }

    private BraketGetTaskResultRequestDto buildGetTaskResultRequest() {
        return BraketGetTaskResultRequestDto.builder()
                .accessKeyId("key").secretAccessKey("secret").region("us-east-1")
                .taskArn(TASK_ARN)
                .build();
    }
}
