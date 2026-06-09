package de.envite.connector.braket.model;

/**
 * Determines which operation the connector performs.
 *
 * <ul>
 *   <li>{@link #SUBMIT_TASK} – submits a new quantum task to AWS Braket and optionally waits for the result.</li>
 *   <li>{@link #GET_TASK_RESULT} – checks the status of a previously submitted task (single check, no polling).</li>
 * </ul>
 */
public enum OperationMode {
  SUBMIT_TASK,
  GET_TASK_RESULT
}
