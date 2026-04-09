package de.envite.connector.braket;

import de.envite.connector.braket.dto.BraketBaseRequestDto;
import de.envite.connector.braket.dto.BraketGetTaskResultRequestDto;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import de.envite.connector.braket.model.OperationMode;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import org.springframework.stereotype.Component;

@OutboundConnector(
        name = "AWSBraket",
        inputVariables = {
                "operationMode",
                "accessKeyId",
                "secretAccessKey",
                "sessionToken",
                "region",
                "taskArn",
                "deviceArn",
                "circuit",
                "shots",
                "s3ResultBucket",
                "s3KeyPrefix",
                "waitForResult",
                "timeoutSeconds",
                "pollIntervalSeconds"
        },
        type = "de.envite:aws-braket-connector:1"
)
@Component
public class BraketConnectorFunction implements OutboundConnectorFunction {

    private final BraketService braketService;

    public BraketConnectorFunction(BraketService braketService) {
        this.braketService = braketService;
    }

    @Override
    public Object execute(OutboundConnectorContext context) {
        OperationMode mode = context.bindVariables(BraketBaseRequestDto.class).getOperationMode();
        return switch (mode) {
            case SUBMIT_TASK     -> braketService.submitTask(context.bindVariables(BraketSubmitTaskRequestDto.class));
            case GET_TASK_RESULT -> braketService.getTaskResult(context.bindVariables(BraketGetTaskResultRequestDto.class));
        };
    }
}
