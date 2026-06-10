package de.envite.connector.braket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Output returned by the Braket connector after task submission or status check.
 *
 * <p>Always contains the task ARN and current status. The result payload is populated
 * only in Phase 2 (S3 result fetching) once the task has completed successfully.</p>
 */
@Data
@AllArgsConstructor
public class BraketConnectorResponseDto {

  /**
   * AWS Braket task ARN.
   */
  private String taskArn;

  /**
   * Current task status: CREATED, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED.
   */
  private String status;

  /**
   * Task result payload fetched from S3.
   * {@code null} in Phase 1 and when the task has not yet completed.
   */
  private Object result;

  /**
   * S3 URI ({@code s3://bucket/prefix/taskId/results.json}) where Braket stores the result.
   * Useful for direct S3 access or debugging.
   * {@code null} for {@code GET_TASK_RESULT} operations (retrievable from task metadata).
   */
  private String resultS3Uri;
}
