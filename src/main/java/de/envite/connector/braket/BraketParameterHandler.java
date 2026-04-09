package de.envite.connector.braket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.envite.connector.braket.dto.BraketSubmitTaskRequestDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static de.envite.connector.braket.BraketConstants.OPENQASM_SCHEMA_NAME;
import static de.envite.connector.braket.BraketConstants.OPENQASM_SCHEMA_VERSION;

/**
 * Builds the Braket task action payload from a connector request.
 *
 * <p>Wraps the OpenQASM circuit string in the Braket IR envelope required by the
 * {@code CreateQuantumTask} API:
 * <pre>
 * {
 *   "braketSchemaHeader": { "name": "braket.ir.openqasm.program", "version": "1" },
 *   "source": "&lt;openqasm circuit&gt;",
 *   "inputs": {}
 * }
 * </pre>
 * </p>
 */
@AllArgsConstructor
@Component
public class BraketParameterHandler {

    private final ObjectMapper objectMapper;

    /**
     * Builds the JSON action string for the given submit request.
     *
     * @param request the submit task request carrying the OpenQASM circuit
     * @return Braket IR JSON string to be passed as the {@code action} field
     * @throws RuntimeException if the action payload cannot be serialised
     */
    public String buildAction(BraketSubmitTaskRequestDto request) {
        try {
            ObjectNode schemaHeader = objectMapper.createObjectNode();
            schemaHeader.put("name", OPENQASM_SCHEMA_NAME);
            schemaHeader.put("version", OPENQASM_SCHEMA_VERSION);

            ObjectNode action = objectMapper.createObjectNode();
            action.set("braketSchemaHeader", schemaHeader);
            action.put("source", request.getCircuit());
            action.set("inputs", objectMapper.createObjectNode());

            return objectMapper.writeValueAsString(action);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenQASM action payload: " + e.getMessage(), e);
        }
    }
}
