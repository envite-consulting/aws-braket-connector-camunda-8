package de.envite.connector.braket.dto;

import de.envite.connector.braket.model.OperationMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Fields common to all AWS Braket connector operations.
 */
@Getter
@SuperBuilder
@Jacksonized
@EqualsAndHashCode
@ToString
public class BraketBaseRequestDto {

  /**
   * Determines which operation to perform.
   * Defaults to {@link OperationMode#SUBMIT_TASK} for backwards compatibility.
   */
  @NotNull
  @Builder.Default
  private final OperationMode operationMode = OperationMode.SUBMIT_TASK;

  /**
   * AWS access key ID.
   * Reference a Camunda secret via {@code {{secrets.AWS_ACCESS_KEY_ID}}}.
   */
  @NotEmpty
  private final String accessKeyId;

  /**
   * AWS secret access key.
   * Reference a Camunda secret via {@code {{secrets.AWS_SECRET_ACCESS_KEY}}}.
   */
  @NotEmpty
  private final String secretAccessKey;

  /**
   * AWS session token for temporary credentials (e.g., assumed roles).
   * Leave empty when using long-term IAM user credentials.
   * Reference a Camunda secret via {@code {{secrets.AWS_SESSION_TOKEN}}}.
   */
  private final String sessionToken;

  /**
   * AWS region where tasks are submitted, e.g. {@code us-east-1}.
   * Not all quantum devices are available in all regions.
   */
  @NotEmpty
  private final String region;
}
