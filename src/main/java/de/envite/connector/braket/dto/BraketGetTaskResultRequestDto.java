package de.envite.connector.braket.dto;

import de.envite.connector.braket.model.OperationMode;
import jakarta.validation.constraints.NotEmpty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Input parameters for the Braket connector when checking the result of a previously submitted task
 * ({@link OperationMode#GET_TASK_RESULT}).
 *
 * <p>Makes a single status request with no polling. Intended for use inside a BPMN polling loop
 * driven by a timer intermediate event. The S3 location is retrieved automatically from the task
 * metadata so the caller only needs to supply the task ARN.
 * Authentication and region configuration are inherited from {@link BraketBaseRequestDto}.</p>
 */
@Getter
@SuperBuilder
@Jacksonized
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class BraketGetTaskResultRequestDto extends BraketBaseRequestDto {

    /** ARN of the previously submitted AWS Braket task. */
    @NotEmpty
    private final String taskArn;
}
