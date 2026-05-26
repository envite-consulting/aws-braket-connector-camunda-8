# Using Predefined Quantum Algorithms via a Sidecar

- [Architecture Overview](#architecture-overview)
- [Sidecar API Contract](#sidecar-api-contract)
  - [`POST /generate-circuit`](#post-generate-circuit)
  - [`POST /process-results`](#post-process-results)
  - [`POST /optimize`](#post-optimize)
- [BPMN Workflow Structure](#bpmn-workflow-structure)
  - [One-shot algorithms](#one-shot-algorithms-grovers-search-etc)
  - [Variational algorithms](#variational-algorithms-qaoa-vqe)
- [Post-Processing by Algorithm Type](#post-processing-by-algorithm-type)
- [Deploying the Sidecar](#deploying-the-sidecar)
- [Known Limitations](#known-limitations)
- [Why the sidecar is separate from the connector](#why-the-sidecar-is-separate-from-the-connector)
- [Examples](#examples)

---

Modeling a quantum circuit by hand using OpenQASM is impractical for most real-world algorithms.
Instead, a **quantum circuit generation sidecar** — a lightweight Python service deployed alongside the connector — translates classical problem inputs into executable quantum circuits and interprets raw measurement results back into classical answers.

This document describes the architecture, the sidecar API contract, how classical post-processing fits in, and how to wire everything together in a BPMN workflow.

---

## Architecture Overview

```
Start Form (problem params)
    │
    ▼
[Generate Circuit]  ──── HTTP ────▶  Sidecar: POST /generate-circuit
    │                               Returns: circuit (OpenQASM 3) / params
    ▼
[AWS Braket Connector]              Submits task, polls for result
    │                               Returns: braketResult (raw measurement data)
    ▼
[Process Results]   ──── HTTP ────▶  Sidecar: POST /process-results
    │                               Returns: classicalResult
    ▼
User Task / End
```

The AWS Braket Connector is unchanged — the sidecar contains all algorithm-specific logic.
The BPMN workflow only orchestrates data flow between the three steps.

The sidecar is a separate Docker container deployed alongside the connector, reachable at `http://localhost:<port>` or by Docker Compose service name.

**Key difference from IBM Q:** AWS Braket credentials are **not** needed in the sidecar. They are passed per-task via the connector's input fields and resolved from Camunda Secrets. The sidecar generates circuits locally using the Amazon Braket SDK and AWS Braket handles transpilation server-side.

---

## Sidecar API Contract

### `POST /generate-circuit`

Translates classical problem parameters into a quantum circuit string.

**Request:**
```json
{
  "algorithm": "grover",
  "problem": { "target": "11", "shots": 1000 },
  "deviceArn": "arn:aws:braket:::device/quantum-simulator/amazon/sv1",
  "params": null
}
```

The `deviceArn` field is forwarded from the BPMN start form. Although the sidecar does not perform local transpilation (AWS Braket handles this server-side), it is included for future use and logging.

For variational algorithms (QAOA), include `params` with the current variational parameters:
```json
{
  "algorithm": "qaoa",
  "problem": { "adj_matrix": [[0,1],[1,0]], "p": 1, "shots": 1000 },
  "params": [0.5, 0.3]
}
```

**Response:**
```json
{
  "circuit": "OPENQASM 3.0; ...",
  "shots": 1000
}
```

For QAOA, the response also includes the params used (initial or echoed):
```json
{
  "circuit": "OPENQASM 3.0; ...",
  "shots": 1000,
  "params": [0.5, 0.3]
}
```

The response maps directly to the AWS Braket Connector's `circuit` and `shots` input fields via the result expression of the HTTP connector task.

### `POST /process-results`

Applies classical post-processing to the raw AWS Braket measurement data and returns a human-readable answer.

**Request:**
```json
{
  "algorithm": "grover",
  "problem": { "target": "11" },
  "results": { }
}
```

The `results` field contains `braketResult.result` as set by the connector's `GET_TASK_RESULT` result expression — the raw measurement data fetched from S3.

AWS Braket returns measurements as a list of shot results, where each entry is a list of bit values in `measuredQubits` order:

```json
{
  "measurements": [[0, 1], [1, 1], [0, 0], [1, 1], "..."],
  "measuredQubits": [0, 1],
  "taskMetadata": { "..." }
}
```

This format is simpler than IBM Q's hex-encoded samples — each inner list directly encodes the qubit measurement outcome for one shot.

**Response (Grover):**
```json
{
  "answer": "11",
  "target": "11",
  "found": true,
  "confidence": 0.94,
  "details": { "counts": { "11": 940, "00": 30, "01": 30 } }
}
```

### `POST /optimize`

Called after each quantum execution for variational algorithms.
Runs one stateless SPSA optimizer step and returns either updated circuit parameters for the next iteration or a convergence signal.

**Request:**
```json
{
  "algorithm": "spsa",
  "iteration": 3,
  "problem": { },
  "current_params": [0.5, 0.3],
  "objective_value": -2.5,
  "best_bitstring": "1010",
  "optimizer_state": { },
  "hyperparams": { "max_iterations": 30 }
}
```

**Response (not converged):**
```json
{
  "converged": false,
  "next_params": [0.48, 0.35],
  "iteration": 4,
  "optimizer_state": { "phase": "gradient_plus", "..." }
}
```

**Response (converged):**
```json
{
  "converged": true,
  "convergence_reason": "converged",
  "optimal_params": [0.52, 0.28],
  "objective_value": -2.7,
  "best_partition": "1010",
  "best_cut_weight": 8.5,
  "iteration": 45
}
```

The BPMN gateway after the optimize task routes on `converged`:
- `= converged = false` → loop back to Generate Circuit with `next_params` as the new parameters
- `= converged = true` → proceed to Review Result

The sidecar is fully stateless — the workflow passes the complete `optimizer_state` blob on every call and receives an updated version back.

---

## BPMN Workflow Structure

### One-shot algorithms (Grover's search, etc.)

Algorithms that require a single circuit execution add two HTTP service tasks around the standard polling workflow:

```
Start → Generate Circuit → Submit Task → [Poll Loop] → Process Results → Review → End
```

**Process variables:**

| Variable | Set by | Used by |
|---|---|---|
| `problem` | Start form | Generate Circuit, Process Results |
| `sidecarUrl` | Start form | Generate Circuit, Process Results |
| `deviceArn` | Start form | Generate Circuit, Submit Task |
| `circuit` | Generate Circuit | Submit Task |
| `shots` | Generate Circuit | Submit Task |
| `braketTaskArn` | Submit Task | Check Task |
| `braketStatus` | Check Task | Poll gateway |
| `braketResult` | Check Task | Process Results |
| `classicalResult` | Process Results | Review user task |

### Variational algorithms (QAOA, VQE)

Variational algorithms loop between a quantum execution and a classical optimizer until convergence:

```
Start
  │
  ▼
Generate Circuit (with current params)
  │
  ▼
Submit Task → [Poll Loop]  ─────────────────────────────────┐
  │                                                         │
  ▼                                                         │
Evaluate Results (sidecar /process-results)                 │
  │                                                         │
  ▼                                                         │
SPSA Optimize (sidecar /optimize)                           │
  │                                                         │
  ├── Not converged ──▶ Generate Circuit (updated params) ──┘
  │
  └── Converged ──▶ Review Result → End
```

---

## Post-Processing by Algorithm Type

| Algorithm | Raw Braket output | Post-processing |
|---|---|---|
| Grover's search | `measurements` array | Extract highest-frequency bitstring |
| QAOA MaxCut | `measurements` array | Map bitstrings to cut weights, delegate objective to evaluation service |
| Bernstein-Vazirani | `measurements` array | Read hidden bitstring directly from most frequent outcome |
| VQE | `measurements` array | Compute expectation value of Hamiltonian |

All post-processing is encapsulated in the sidecar's `/process-results` endpoint. The BPMN workflow only receives the final `classicalResult`.

---

## Deploying the Sidecar

The sidecar runs as a Docker container alongside the connector. Start both with Docker Compose from `example/predefined-algorithms/`:

**Grover's search (no extra services needed):**
```bash
cd example/predefined-algorithms
docker compose up --build
```

**QAOA MaxCut (requires the objective evaluation service):**
```bash
cd example/predefined-algorithms
docker compose --profile qaoa up --build
```

The `--profile qaoa` flag additionally starts the [`objective-evaluation-service`](https://github.com/UST-QuAntiL/objective-evaluation-service) on port 5072, which the sidecar delegates MaxCut objective evaluation to.

**AWS credentials** do not need to be provided to the sidecar. They are passed per-task through the connector's start form using Camunda Secrets (`{{secrets.AWS_ACCESS_KEY_ID}}`, `{{secrets.AWS_SECRET_ACCESS_KEY}}`).

> **Note (Camunda SaaS only):** The sidecar is called by the built-in Camunda HTTP Connector (`io.camunda:http-json:1`), which executes inside Camunda SaaS infrastructure — not locally.
> This means `http://quantum-sidecar:5000` is not reachable from the cloud.
> **The sidecar must be publicly accessible when using Camunda SaaS.**
>
> Use a tunneling tool such as [ngrok](https://ngrok.com/) to expose the sidecar during testing:
> ```bash
> ngrok http 5000
> ```
> Then use the generated public URL (e.g. `https://xxxx.ngrok.io`) as the `sidecarUrl` in the start form.

---

## Known Limitations

### Grover's search: up to 3 qubits

The built-in Grover implementation supports target bitstrings of 1–3 qubits. The multi-controlled Z phase flip is decomposed as:
- 1 qubit: Z gate
- 2 qubits: CZ gate
- 3 qubits: H – CCNot – H (Toffoli decomposition)

For 4+ qubits, provide your own circuit via the connector's `DIRECT_PARAMS` circuit input mode.

### Sidecar error details on Camunda SaaS

When a sidecar HTTP task fails and the `SIDECAR_ERROR` boundary event fires, the error code and message are **not accessible as process variables** on Camunda SaaS (tested on 8.8). The boundary event itself fires and routing works correctly.

**Workaround:** The `sidecarUrl` process variable is always available at the error review user task. For sidecar-side failures (HTTP 500), consult the sidecar container logs directly.

---

## Why the sidecar is separate from the connector

Integrating circuit generation as connector operations (`GENERATE_CIRCUIT`, `PROCESS_RESULTS`, `OPTIMIZE`) was deliberately rejected for the following reasons:

- **Separation of concerns** — the connector's responsibility is AWS Braket API interaction. Circuit generation is algorithm-specific and optional; bundling it would couple an infrastructure component to domain logic.
- **Replaceability** — keeping the sidecar as an independent HTTP service means it can be exchanged, versioned separately, or replaced by a different quantum SDK (e.g. PennyLane, Cirq) without touching the connector.
- **Marketplace discoverability** — the connector listing stays clean and installable without any Python dependency, which is critical for a low-friction marketplace experience.

---

## Examples

Concrete end-to-end workflow examples are documented in [Example Use Cases & HowTos](usecases.md):

- [Grover's Search Algorithm](usecases.md#grovers-search-algorithm) — one-shot algorithm
- [QAOA / MaxCut](usecases.md#qaoa--maxcut) — variational algorithm with SPSA optimization loop
