package de.envite.connector.braket.model;

/**
 * Determines how the quantum circuit is supplied to the connector.
 *
 * <ul>
 *   <li>{@link #OPEN_QASM} – provide an OpenQASM 3 circuit string; the connector builds
 *       the Braket IR action envelope automatically.</li>
 *   <li>{@link #DIRECT_PARAMS} – provide the complete Braket IR action JSON string directly,
 *       giving full control over the action payload (e.g. for non-OpenQASM formats or
 *       advanced input fields).</li>
 * </ul>
 */
public enum CircuitInputMode {
    OPEN_QASM,
    DIRECT_PARAMS
}
