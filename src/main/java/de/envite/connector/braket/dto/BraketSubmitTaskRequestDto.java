package de.envite.connector.braket.dto;

import de.envite.connector.braket.model.CircuitInputMode;
import de.envite.connector.braket.model.OperationMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Input parameters for the Braket connector when submitting a new quantum task
 * ({@link OperationMode#SUBMIT_TASK}).
 *
 * <p>Authentication and region configuration are inherited from {@link BraketBaseRequestDto}.</p>
 */
@Getter
@SuperBuilder
@Jacksonized
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class BraketSubmitTaskRequestDto extends BraketBaseRequestDto {

    /**
     * Full ARN of the target quantum device.
     * Examples:
     * <ul>
     *   <li>Amazon SV1 simulator: {@code arn:aws:braket:::device/quantum-simulator/amazon/sv1}</li>
     *   <li>IonQ Aria: {@code arn:aws:braket:us-east-1::device/qpu/ionq/Aria-1}</li>
     *   <li>Rigetti Ankaa: {@code arn:aws:braket:us-west-1::device/qpu/rigetti/Ankaa-9Q-3}</li>
     * </ul>
     */
    @NotEmpty
    private final String deviceArn;

    /**
     * Determines how the quantum circuit is provided.
     * <ul>
     *   <li>{@link CircuitInputMode#OPEN_QASM} – provide an OpenQASM 3 string via {@code circuit}.</li>
     *   <li>{@link CircuitInputMode#DIRECT_PARAMS} – provide the full Braket IR action JSON via {@code params}.</li>
     * </ul>
     */
    @NotNull
    @Builder.Default
    private final CircuitInputMode circuitInputMode = CircuitInputMode.OPEN_QASM;

    /**
     * OpenQASM 3 circuit to execute.
     * Required when {@link #circuitInputMode} is {@link CircuitInputMode#OPEN_QASM}.
     * The circuit must use gates supported by the target device.
     * Braket does not transpile circuits — unsupported gates will cause the task to fail.
     */
    private final String circuit;

    /**
     * Complete Braket IR action JSON string.
     * Required when {@link #circuitInputMode} is {@link CircuitInputMode#DIRECT_PARAMS}.
     * Must include a valid {@code braketSchemaHeader} and {@code source} field.
     */
    private final String params;

    /**
     * Number of shots (circuit repetitions).
     * Used when {@link #circuitInputMode} is {@link CircuitInputMode#OPEN_QASM}.
     * Simulators support up to 100,000 shots; QPU limits vary by device.
     */
    @Min(1)
    @Builder.Default
    private final Integer shots = 1000;

    /**
     * S3 bucket where Braket writes task results.
     * The bucket must be in the same AWS region as the task and accessible
     * by the IAM identity used to submit the task.
     */
    @NotEmpty
    private final String s3ResultBucket;

    /**
     * Key prefix within the S3 bucket for result objects.
     * Braket appends the task ID and {@code /results.json} automatically.
     */
    @NotEmpty
    private final String s3KeyPrefix;

    /** When {@code true} the connector polls until the task reaches a terminal state. */
    @Builder.Default
    private final Boolean waitForResult = true;

    /** Maximum time in seconds to wait for a result before failing. */
    @Min(1)
    @Builder.Default
    private final Integer timeoutSeconds = 300;

    /** Polling interval in seconds when {@code waitForResult} is {@code true}. */
    @Min(1)
    @Builder.Default
    private final Integer pollIntervalSeconds = 10;
}
