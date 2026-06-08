"""
AWS Braket Quantum Algorithm Sidecar
=====================================
Implements the three-endpoint API contract from docs/use-predefined-algorithms.md.
Handles both one-shot (Grover) and variational (QAOA) algorithms.

Run:
    pip install -r requirements.txt
    python quantum_sidecar.py

Environment variables:
    OBJECTIVE_EVAL_URL      Base URL of the objective-evaluation-service
                            (default: http://objective-evaluation-service:5072)

Endpoints:
    POST /generate-circuit   Builds a quantum circuit from classical problem parameters.
                             Uses the Amazon Braket SDK to construct the circuit and exports
                             it as OpenQASM 3. AWS Braket handles transpilation server-side —
                             no local transpilation step is needed.
    POST /process-results    Post-processes raw AWS Braket measurement results.
                             Grover: extracts the most frequent bitstring from measurements.
                             QAOA:   extracts counts, then delegates to objective-evaluation-service.
    POST /optimize           Runs one stateless SPSA step. The workflow passes all optimizer
                             state back on every call — no server-side session is maintained.

Key difference from the IBM Q sidecar:
    AWS Braket returns measurements as a list of shot results ([[0,1],[1,0],...]),
    not as hex-encoded bitstring samples. The _extract_counts helper handles this format.
    AWS credentials are NOT needed in the sidecar — they are passed per-task through the
    AWS Braket Connector and resolved from Camunda Secrets.
"""

import os
import json as _json
import numpy as np
import requests as http
from flask import Flask, request, jsonify
from braket.circuits import Circuit
from braket.circuits.serialization import IRType

app = Flask(__name__)

OBJECTIVE_EVAL_URL = os.environ.get(
    "OBJECTIVE_EVAL_URL", "http://objective-evaluation-service:5072"
)


def _circuit_to_openqasm(circuit: Circuit) -> str:
    """Convert a Braket Circuit to an OpenQASM 3 string."""
    return circuit.to_ir(ir_type=IRType.OPENQASM).source


# ─── /generate-circuit ────────────────────────────────────────────────────────

@app.route("/generate-circuit", methods=["POST"])
def generate_circuit():
    data      = request.get_json(force=True)
    algorithm = data.get("algorithm", "grover")
    problem   = data.get("problem", {})
    params    = data.get("params")          # current variational params (QAOA only)

    app.logger.info(
        "Generating circuit for algorithm=%s problem=%s params=%s",
        algorithm, problem, params,
    )

    if algorithm == "grover":
        circuit, shots = _generate_grover_circuit(problem)
        return jsonify({"circuit": circuit, "shots": shots})

    if algorithm == "qaoa":
        circuit, shots, used_params = _generate_qaoa_circuit(problem, params)
        return jsonify({"circuit": circuit, "shots": shots, "params": used_params})

    return jsonify({"error": f"Unsupported algorithm: {algorithm}"}), 400


def _apply_nqubit_phase_flip(circuit: Circuit, qubits: list) -> None:
    """
    Apply a phase flip on the |11...1⟩ state (multi-qubit controlled-Z).

    n=1: Z gate
    n=2: CZ gate
    n=3: H – CCNot – H decomposition (= controlled-controlled-Z)
    n>3: raises ValueError (use DIRECT_PARAMS mode for larger circuits)
    """
    n = len(qubits)
    if n == 1:
        circuit.z(qubits[0])
    elif n == 2:
        circuit.cz(qubits[0], qubits[1])
    elif n == 3:
        circuit.h(qubits[2])
        circuit.ccnot(qubits[0], qubits[1], qubits[2])
        circuit.h(qubits[2])
    else:
        raise ValueError(
            f"Built-in Grover's search supports up to 3 qubits, got {n}. "
            "For larger circuits, provide your own OpenQASM 3 string via the connector's "
            "DIRECT_PARAMS circuit input mode."
        )


def _generate_grover_circuit(problem: dict) -> tuple[str, int]:
    """
    Build a Grover's search circuit using the Amazon Braket SDK and return it as OpenQASM 3.

    AWS Braket handles circuit transpilation server-side for all supported devices,
    so no local transpilation is required.

    One Grover iteration is applied. For most 2- and 3-qubit search problems this
    is sufficient for near-deterministic results.

    problem fields:
        target  – bitstring to search for, e.g. "11" (default "11")
        shots   – number of circuit repetitions (default 1000)
    """
    target: str = problem.get("target", "11")
    shots:  int = int(problem.get("shots", 1000))
    n           = len(target)
    qubits      = list(range(n))

    circuit = Circuit()

    # Initial superposition
    circuit.h(qubits)

    # Oracle: phase flip on target state
    # X-gates convert target → |11...1⟩, apply CZ/CCZ, X-gates revert
    for i, bit in enumerate(target):
        if bit == "0":
            circuit.x(i)
    _apply_nqubit_phase_flip(circuit, qubits)
    for i, bit in enumerate(target):
        if bit == "0":
            circuit.x(i)

    # Grover diffusion operator: 2|ψ⟩⟨ψ| − I  =  H (2|0⟩⟨0| − I) H
    circuit.h(qubits)
    circuit.x(qubits)
    _apply_nqubit_phase_flip(circuit, qubits)
    circuit.x(qubits)
    circuit.h(qubits)

    app.logger.info("Successfully generated Grover circuit: target=%s n=%d", target, n)
    return _circuit_to_openqasm(circuit), shots


def _generate_qaoa_circuit(
    problem: dict, params: list | None
) -> tuple[str, int, list]:
    """
    Build a QAOA MaxCut circuit using the Amazon Braket SDK and return it as OpenQASM 3.

    AWS Braket handles circuit transpilation server-side, so no local transpilation
    is required.

    The cost layer uses the RZZ decomposition CNOT – RZ – CNOT. The mixer layer uses RX.

    problem fields:
        adj_matrix  – 2-D list of floats representing the graph
        p           – QAOA depth / number of layers (default 1)
        shots       – number of circuit repetitions (default 1000)
    """
    adj_matrix = problem["adj_matrix"]
    if isinstance(adj_matrix, str):
        adj_matrix = _json.loads(adj_matrix)

    p     = int(problem.get("p", 1))
    shots = int(problem.get("shots", 1000))

    if not params:
        params = [0.5] * (2 * p)

    n     = len(adj_matrix)
    edges = [
        (i, j, float(adj_matrix[i][j]))
        for i in range(n)
        for j in range(i + 1, n)
        if adj_matrix[i][j] != 0
    ]

    circuit = Circuit()
    circuit.h(range(n))

    for layer in range(p):
        gamma = float(params[layer])
        beta  = float(params[p + layer])

        # Cost layer: RZZ(2γw) for each weighted edge, decomposed as CNOT–RZ–CNOT
        for i, j, w in edges:
            circuit.cnot(i, j)
            circuit.rz(j, 2.0 * gamma * w)
            circuit.cnot(i, j)

        # Mixer layer: RX(2β) on each qubit
        for i in range(n):
            circuit.rx(i, 2.0 * beta)

    app.logger.info(
        "Successfully generated QAOA circuit: n=%d qubits, p=%d layers", n, p
    )
    return _circuit_to_openqasm(circuit), shots, params


# ─── /process-results ─────────────────────────────────────────────────────────

@app.route("/process-results", methods=["POST"])
def process_results():
    data      = request.get_json(force=True)
    algorithm = data.get("algorithm", "grover")
    problem   = data.get("problem", {})
    results   = data.get("results", {})

    if algorithm == "grover":
        return jsonify(_process_grover_results(problem, results))

    if algorithm == "qaoa":
        return jsonify(_process_qaoa_results(problem, results))

    return jsonify({"error": f"Unsupported algorithm: {algorithm}"}), 400


def _process_grover_results(problem: dict, results: object) -> dict:
    """Extract the highest-frequency bitstring from Braket measurement data."""
    target: str = problem.get("target", "11")
    counts      = _extract_counts(results)

    if not counts:
        return {"answer": None, "target": target, "found": False,
                "confidence": 0.0, "details": {}}

    best  = max(counts, key=counts.__getitem__)
    total = sum(counts.values())
    return {
        "answer":     best,
        "target":     target,
        "found":      best == target,
        "confidence": round(counts[best] / total, 4),
        "details":    {"counts": counts},
    }


def _maxcut_value(bitstring: str, adj_matrix: list) -> float:
    """Sum of edge weights crossing the cut encoded by bitstring."""
    cut = 0.0
    for i, row in enumerate(adj_matrix):
        for j in range(i + 1, len(row)):
            if bitstring[i] != bitstring[j]:
                cut += float(row[j])
    return cut


def _process_qaoa_results(problem: dict, results: object) -> dict:
    """
    Extract bitstring counts from Braket measurement data, then delegate objective
    evaluation to the objective-evaluation-service.

    POST /objective/max-cut
    The service accepts:
        counts                  – {bitstring: frequency} dict
        adj_matrix              – graph adjacency matrix
        objFun                  – "expectation" | "cvar" | "gibbs"
        objFun_hyperparameters  – {"alpha": 0.2} for CVaR, {"eta": 10} for Gibbs
        visualization           – false
    """
    adj_matrix = problem["adj_matrix"]
    if isinstance(adj_matrix, str):
        adj_matrix = _json.loads(adj_matrix)

    counts = _extract_counts(results)

    payload = {
        "counts":                 counts,
        "adj_matrix":             adj_matrix,
        "objFun":                 problem.get("objFun", "expectation"),
        "objFun_hyperparameters": problem.get("objFun_hyperparameters", {}),
        "visualization":          False,
    }

    resp = http.post(
        f"{OBJECTIVE_EVAL_URL}/objective/max-cut",
        json=payload,
        timeout=30,
    )
    resp.raise_for_status()
    data = resp.json()

    best_bitstring = max(counts, key=lambda b: _maxcut_value(b, adj_matrix)) if counts else None

    return {
        "objective_value": data["objective_value"],
        "best_bitstring":  best_bitstring,
        "costs":           data.get("costs", []),
        "counts":          counts,
    }


# ─── /optimize ────────────────────────────────────────────────────────────────

@app.route("/optimize", methods=["POST"])
def optimize():
    """
    Stateless SPSA optimiser step.

    The workflow passes back the full optimizer_state blob on every call so the
    sidecar never holds session state. The BPMN loop evaluates exactly the params
    returned as next_params and calls this endpoint again with the objective value.

    Request:
        algorithm          – "spsa" (only supported option for now)
        iteration          – current BPMN loop iteration count
        problem            – original problem context (passed through for reference)
        current_params     – flat list of floats: the params just evaluated
        objective_value    – scalar objective for current_params (from /process-results)
        optimizer_state    – opaque dict from the previous call (empty {} on first call)
        hyperparams        – optional SPSA tuning knobs (see _spsa_step for defaults)

    Response (not converged):
        converged          – false
        next_params        – flat list of floats to evaluate next
        iteration          – incremented counter
        optimizer_state    – updated state blob to pass back on the next call

    Response (converged):
        converged          – true
        optimal_params     – best params found
        objective_value    – best objective value achieved
        iteration          – final iteration count

    SPSA loop mechanics
    ───────────────────
    Three BPMN iterations per SPSA gradient step:

        iter k+0:  evaluate θ_k  (convergence check + calibration)
                   → sidecar returns θ_k + c_k·Δ_k  [phase: gradient_plus]
        iter k+1:  evaluate θ_k + c_k·Δ_k   (f_plus)
                   → sidecar returns θ_k − c_k·Δ_k  [phase: gradient_minus]
        iter k+2:  evaluate θ_k − c_k·Δ_k   (f_minus)
                   → sidecar computes ĝ = (f_plus−f_minus)/(2·c_k·Δ_k)
                      and returns θ_{k+1} = θ_k − a_k·ĝ  [phase: step]
    """
    data            = request.get_json(force=True)
    algorithm       = data.get("algorithm", "spsa")
    iteration       = int(data.get("iteration", 0))
    current_params  = data.get("current_params", [])
    objective_value = float(data.get("objective_value"))
    best_bitstring  = data.get("best_bitstring")
    optimizer_state = data.get("optimizer_state") or {}
    hyperparams     = data.get("hyperparams") or {}
    problem         = data.get("problem") or {}

    if algorithm != "spsa":
        return jsonify({"error": f"Unsupported optimizer: {algorithm}"}), 400

    result = _spsa_step(
        iteration, current_params, objective_value,
        best_bitstring, optimizer_state, hyperparams,
    )

    if result.get("converged") and result.get("best_partition"):
        adj_matrix = problem.get("adj_matrix", [])
        if isinstance(adj_matrix, str):
            adj_matrix = _json.loads(adj_matrix)
        result["best_cut_weight"] = _maxcut_value(result["best_partition"], adj_matrix)

    return jsonify(result)


def _spsa_step(
    iteration: int,
    current_params: list,
    objective_value: float,
    best_bitstring: str | None,
    state: dict,
    hyperparams: dict,
) -> dict:
    """
    One step of the Simultaneous Perturbation Stochastic Approximation algorithm.

    Hyperparameters (all optional):
        a              gain sequence numerator for step size  (default 0.1)
        c              gain sequence numerator for perturbation magnitude (default 0.1)
        A              stability constant in step-size sequence (default 10)
        alpha          decay exponent for step size  (default 0.602, SPSA theory optimum)
        gamma          decay exponent for perturbation (default 0.101, SPSA theory optimum)
        tolerance      min improvement in objective to count as progress (default 1e-3)
        patience       consecutive non-improving gradient steps before declaring convergence
                       (default 5)
        max_iterations hard cap on total BPMN loop iterations before forcing convergence
                       (default 100)
    """
    params = np.array(current_params, dtype=float)
    phase  = state.get("phase")            # None | "gradient_plus" | "gradient_minus" | "step"

    a              = float(hyperparams.get("a",             0.1))
    c              = float(hyperparams.get("c",             0.1))
    A              = float(hyperparams.get("A",             10.0))
    alpha          = float(hyperparams.get("alpha",         0.602))
    gamma_exp      = float(hyperparams.get("gamma",         0.101))
    tol            = float(hyperparams.get("tolerance",     1e-3))
    patience       = int(hyperparams.get("patience",        5))
    max_iterations = int(hyperparams.get("max_iterations",  100))

    # ── Convergence check + start new gradient step (phase None or "step") ────

    if phase is None or phase == "step":
        best_obj       = state.get("best_objective", float("inf"))
        best_partition = state.get("best_partition")
        no_improve     = state.get("no_improve_count", 0)
        k              = state.get("spsa_k", 0)

        if objective_value < best_obj - tol:
            best_obj, no_improve = objective_value, 0
            best_partition = best_bitstring
        else:
            no_improve += 1

        if (no_improve >= patience and k > 0) or iteration >= max_iterations:
            return {
                "converged":          True,
                "convergence_reason": "max_iterations" if iteration >= max_iterations else "converged",
                "optimal_params":     params.tolist(),
                "objective_value":    best_obj,
                "best_partition":     best_partition,
                "iteration":          iteration,
            }

        # Generate Rademacher perturbation vector
        ck    = c / (k + 1) ** gamma_exp
        delta = np.where(np.random.random(params.shape) > 0.5, 1.0, -1.0)

        return {
            "converged":       False,
            "objective_value": objective_value,
            "next_params":     (params + ck * delta).tolist(),
            "iteration":       iteration + 1,
            "optimizer_state": {
                "phase":            "gradient_plus",
                "spsa_k":           k,
                "ck":               ck,
                "delta":            delta.tolist(),
                "theta_k":          params.tolist(),
                "best_objective":   best_obj,
                "best_partition":   best_partition,
                "no_improve_count": no_improve,
            },
        }

    # ── Got f(θ+cΔ), return θ−cΔ for evaluation ──────────────────────────────

    if phase == "gradient_plus":
        delta   = np.array(state["delta"])
        ck      = float(state["ck"])
        theta_k = np.array(state["theta_k"])

        return {
            "converged":       False,
            "objective_value": objective_value,
            "next_params":     (theta_k - ck * delta).tolist(),
            "iteration":       iteration + 1,
            "optimizer_state": {**state, "phase": "gradient_minus",
                                "f_plus": objective_value},
        }

    # ── Got f(θ−cΔ), compute gradient, update θ ──────────────────────────────

    if phase == "gradient_minus":
        f_plus  = float(state["f_plus"])
        f_minus = objective_value
        delta   = np.array(state["delta"])
        ck      = float(state["ck"])
        theta_k = np.array(state["theta_k"])
        k       = int(state["spsa_k"])

        ak         = a / (A + k + 1) ** alpha
        grad       = (f_plus - f_minus) / (2.0 * ck * delta)
        theta_next = theta_k - ak * grad

        return {
            "converged":       False,
            "objective_value": objective_value,
            "next_params":     theta_next.tolist(),
            "iteration":       iteration + 1,
            "optimizer_state": {
                "phase":            "step",
                "spsa_k":           k + 1,
                "best_objective":   state.get("best_objective", float("inf")),
                "best_partition":   state.get("best_partition"),
                "no_improve_count": state.get("no_improve_count", 0),
            },
        }

    return jsonify({"error": f"Unknown SPSA phase: {phase}"}), 400


# ─── Shared utility ───────────────────────────────────────────────────────────

def _extract_counts(results: object) -> dict[str, int]:
    """
    Convert AWS Braket measurement results to a {bitstring: count} dict.

    AWS Braket returns measurements as a list of shot results, where each entry
    is a list of bit values (0 or 1) in measuredQubits order.

    Expected shape of braketResult.result after JSON round-trip:
        {
          "measurements": [[0, 1], [1, 1], [0, 0], ...],   # one list per shot
          "measuredQubits": [0, 1],
          "taskMetadata": {...},
          ...
        }

    This differs from the IBM Q sidecar which decodes hex-encoded bitstring samples.
    """
    counts: dict[str, int] = {}
    try:
        measurements = results.get("measurements", [])
        for shot in measurements:
            bitstring = "".join(str(b) for b in shot)
            counts[bitstring] = counts.get(bitstring, 0) + 1
    except (AttributeError, TypeError):
        pass
    return counts


# ─── Health check ─────────────────────────────────────────────────────────────

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


# ─── Entry point ──────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
