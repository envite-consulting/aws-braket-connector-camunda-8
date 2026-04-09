package de.envite.connector.braket.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Task metadata returned by {@code GetQuantumTask}, used internally to carry
 * the current status together with the S3 coordinates where Braket wrote the result.
 */
@Getter
@AllArgsConstructor
public class BraketTaskDetailsDto {

    /** Current task status string, e.g. {@code QUEUED}, {@code COMPLETED}, {@code FAILED}. */
    private final String status;

    /** S3 bucket name where Braket stores the result. */
    private final String s3Bucket;

    /**
     * Full S3 directory path for the result, e.g. {@code my-prefix/<taskId>}.
     * Corresponds to {@code outputS3Directory} in the AWS Braket API response.
     * The result object key is this value with {@code /results.json} appended.
     */
    private final String s3Directory;
}
