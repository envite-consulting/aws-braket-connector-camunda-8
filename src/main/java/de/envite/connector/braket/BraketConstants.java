package de.envite.connector.braket;

public final class BraketConstants {

  public static final String STATUS_CREATED = "CREATED";

  // -------------------------------------------------------------------------
  // Task statuses
  // -------------------------------------------------------------------------
  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_CANCELLED = "CANCELLED";
  public static final String STATUS_CANCELLING = "CANCELLING";
  public static final String OPENQASM_SCHEMA_NAME = "braket.ir.openqasm.program";

  // -------------------------------------------------------------------------
  // OpenQASM Braket IR schema header fields
  // -------------------------------------------------------------------------
  public static final String OPENQASM_SCHEMA_VERSION = "1";
  public static final String S3_URI_PREFIX = "s3://";

  // -------------------------------------------------------------------------
  // S3
  // -------------------------------------------------------------------------

  private BraketConstants() {
  }
}
