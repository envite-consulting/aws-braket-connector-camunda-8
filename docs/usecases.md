# Example Use Cases & HowTos

The following examples demonstrate how to use the AWS Braket Connector and the [AWS Braket Algorithm Accelerator pattern](use-predefined-algorithms.md) in real workflows.
Each example includes a ready-to-run BPMN workflow, element templates, and setup instructions.

| Example | Algorithm type | Reference |
|---|---|---|
| [Grover's Search Algorithm](#grovers-search-algorithm) | One-shot | [Grover, 1996](https://arxiv.org/abs/quant-ph/9605043) |
| [QAOA / MaxCut](#qaoa--maxcut) | Variational | [Farhi et al., 2014](https://arxiv.org/abs/1411.4028) |

---

## Grover's Search Algorithm

**Example workflow:** [`example/predefined-algorithms/grover/grover-search-workflow.bpmn`](../example/predefined-algorithms/grover/grover-search-workflow.bpmn)

Grover's search algorithm finds a marked element in an unstructured search space of size N with O(√N) quantum circuit evaluations, compared to O(N) for a classical linear scan.
This example demonstrates the [AWS Braket Algorithm Accelerator pattern](use-predefined-algorithms.md): a lightweight Python sidecar translates a classical problem description into an executable quantum circuit and interprets the raw measurement results back into a classical answer — the AWS Braket Connector itself is unchanged.

### Problem

Given a target bitstring (e.g. `"11"`), find it in the search space of all 2-qubit bitstrings using a single quantum circuit execution.

### Workflow structure

```
Start Form → Generate Circuit → Submit Task → [Poll Loop] → Process Results → Review → End
```

| Step | Component | What it does |
|---|---|---|
| Start Form | Camunda Form | Collects `problem` (target bitstring, shots), `sidecarUrl`, AWS credentials, `deviceArn`, S3 bucket/prefix |
| Generate Circuit | HTTP Connector → Sidecar `/generate-circuit` | Builds a Grover circuit using the Amazon Braket SDK; returns `circuit` (OpenQASM 3) and `shots` |
| Submit Task | AWS Braket Connector (`SUBMIT_TASK`) | Submits the circuit to the selected Braket device; AWS handles transpilation server-side; returns `braketTaskArn` |
| Poll Loop | Timer + AWS Braket Connector (`GET_TASK_RESULT`) | Waits 60 s, checks task status, loops until terminal state |
| Process Results | HTTP Connector → Sidecar `/process-results` | Extracts the highest-frequency bitstring from the measurements array; returns `classicalResult` with `answer`, `found`, `confidence`, and `counts` |
| Review | User Task | Presents the search result to a human |

### Process variables

| Variable | Set by | Used by |
|---|---|---|
| `problem` | Start form | Generate Circuit, Process Results |
| `sidecarUrl` | Start form | Generate Circuit, Process Results |
| `accessKeyId` | Start form | Submit Task, Check Task |
| `secretAccessKey` | Start form | Submit Task, Check Task |
| `region` | Start form | Submit Task, Check Task |
| `deviceArn` | Start form | Generate Circuit, Submit Task |
| `s3ResultBucket` | Start form | Submit Task |
| `s3KeyPrefix` | Start form | Submit Task |
| `circuit` | Generate Circuit | Submit Task |
| `shots` | Generate Circuit | Submit Task |
| `braketTaskArn` | Submit Task | Check Task |
| `braketStatus` | Check Task | Poll gateway |
| `braketResult` | Check Task | Process Results |
| `classicalResult` | Process Results | Review user task |

### Running the example

> **Note (Camunda SaaS only):** The sidecar is called by the built-in Camunda HTTP Connector, which executes inside Camunda SaaS infrastructure. This means `http://quantum-sidecar:5000` is not reachable from the cloud.
> **The sidecar must be publicly accessible when using Camunda SaaS.**
>
> Use [ngrok](https://ngrok.com/) to expose the sidecar during testing:
> ```bash
> ngrok http 5000
> ```
> Use the generated public URL (e.g. `https://xxxx.ngrok.io`) as `sidecarUrl` in the start form.
> For production, host the sidecar on a publicly reachable endpoint.

1. Create a `.env` file in the project root with your Camunda SaaS credentials (copy from `.env.example`):

   ```
   CAMUNDA_CLIENT_ID=<your-client-id>
   CAMUNDA_CLIENT_SECRET=<your-client-secret>
   CAMUNDA_CLUSTER_ID=<cluster-id>
   CAMUNDA_REGION=<region e.g. bru-2>
   ```

   Find these values in **Camunda Console → Clusters → your cluster → API**.

2. Start the connector and sidecar:

   ```bash
   cd example/predefined-algorithms
   docker compose up --build
   ```

3. Deploy the element templates to your Camunda Modeler project:
   - `element-templates/braket-connector.json` (main connector template)
   - `example/predefined-algorithms/element-templates/braket-sidecar-generate-circuit.json`
   - `example/predefined-algorithms/element-templates/braket-sidecar-process-results.json`

4. Deploy the workflow and forms to your Camunda cluster via Camunda Web Modeler by uploading the following files from `example/predefined-algorithms/grover/`:
   - `grover-search-workflow.bpmn`
   - `grover-input-form.form`
   - `grover-result-form.form`
   - `grover-sidecar-error-form.form`
   - `grover-task-failure-form.form`

5. Start a process instance via Camunda Tasklist with the following start form inputs:

   | Field | Example value |
   |---|---|
   | Target bitstring | `11` |
   | Shots | `1000` |
   | Sidecar URL | `http://quantum-sidecar:5000` (local) or your ngrok URL |
   | AWS Access Key ID | `{{secrets.AWS_ACCESS_KEY_ID}}` |
   | AWS Secret Access Key | `{{secrets.AWS_SECRET_ACCESS_KEY}}` |
   | AWS Region | `us-east-1` |
   | Device ARN | `arn:aws:braket:::device/quantum-simulator/amazon/sv1` |
   | S3 Result Bucket | `amazon-braket-us-east-1-<account-id>` |
   | S3 Key Prefix | `braket-results` |

6. After the quantum task completes, a **Review search result** user task appears in Tasklist. `classicalResult.answer` should equal the target bitstring, with `classicalResult.found = true` and a high `classicalResult.confidence`.

---

## QAOA / MaxCut

**Example workflow:** [`example/predefined-algorithms/qaoa/qaoa-max-cut-workflow.bpmn`](../example/predefined-algorithms/qaoa/qaoa-max-cut-workflow.bpmn)

The Quantum Approximate Optimization Algorithm (QAOA) is a variational quantum algorithm for combinatorial optimization problems.
This example solves the [MaxCut problem](https://en.wikipedia.org/wiki/Maximum_cut): partition the nodes of a graph into two sets to maximize the number of edges crossing the cut.

The workflow loops — running a quantum circuit and a classical SPSA optimizer step on each iteration — until the optimizer converges or the iteration cap is hit.

### Problem

Given an undirected weighted graph (adjacency matrix), find the node partition that maximizes the total weight of edges between the two partitions.

### Workflow structure

```
Start Form → Generate Circuit → Submit Task → [Poll Loop] → Evaluate Results → SPSA Optimize
                ▲                                                                     │
                │                                                       not converged │
                └─────────────────────────────────────────────────────────────────────┘
                                                                            converged ↓
                                                                              Review Result → End
```

| Step | Component | What it does |
|---|---|---|
| Start Form | Camunda Form | Collects `problem` (adj_matrix JSON, p, shots), `maxIterations`, `sidecarUrl`, AWS credentials, `deviceArn`, S3 config; initialises `iteration = 0` |
| Generate Circuit | HTTP Connector → Sidecar `/generate-circuit` | Builds a parameterised QAOA MaxCut circuit using the Amazon Braket SDK with the current SPSA parameters bound in; returns `circuit` (OpenQASM 3), `shots`, and `currentParams` |
| Submit Task | AWS Braket Connector (`SUBMIT_TASK`) | Submits the circuit to the selected Braket device; returns `braketTaskArn` |
| Poll Loop | Timer + AWS Braket Connector (`GET_TASK_RESULT`) | Waits 60 s, checks task status, loops until terminal state |
| Evaluate Results | HTTP Connector → Sidecar `/process-results` | Converts the `measurements` array to bitstring counts, delegates to the objective-evaluation-service; returns `objectiveValue` and `currentBestBitstring` |
| SPSA Optimize | HTTP Connector → Sidecar `/optimize` | Runs one stateless SPSA step; tracks the best bitstring seen across all iterations; returns `converged`, `next_params`/`optimal_params`, `bestPartition`, `bestCutWeight`, and the opaque `optimizerState` |
| Review Result | User Task | Presents `bestPartition`, `bestCutWeight`, `objectiveValue`, `convergenceReason`, and `iteration` |

### SPSA loop mechanics

Each SPSA gradient estimate requires three sequential quantum tasks, so each "SPSA step" maps to three BPMN loop iterations:

| BPMN iteration | Params evaluated | Sidecar response |
|---|---|---|
| k+0 | θ_k | θ_k + c_k·Δ_k (phase: `gradient_plus`) |
| k+1 | θ_k + c_k·Δ_k | θ_k − c_k·Δ_k (phase: `gradient_minus`) |
| k+2 | θ_k − c_k·Δ_k | θ_{k+1} = θ_k − a_k·ĝ (phase: `step`) |

The `optimizerState` blob returned by each `/optimize` call is passed back unchanged on the next call, keeping the sidecar fully stateless.

### Process variables

| Variable | Set by | Used by |
|---|---|---|
| `problem` | Start form | Generate Circuit, Evaluate Results, Optimize |
| `sidecarUrl` | Start form | Generate Circuit, Evaluate Results, Optimize |
| `maxIterations` | Start form | Optimize |
| `accessKeyId` | Start form | Submit Task, Check Task |
| `secretAccessKey` | Start form | Submit Task, Check Task |
| `region` | Start form | Submit Task, Check Task |
| `deviceArn` | Start form | Generate Circuit, Submit Task |
| `s3ResultBucket` | Start form | Submit Task |
| `s3KeyPrefix` | Start form | Submit Task |
| `iteration` | Start event (initialised to `0`), Optimize (incremented) | Optimize, result form |
| `circuit` | Generate Circuit | Submit Task |
| `shots` | Generate Circuit | Submit Task |
| `currentParams` | Generate Circuit (first call), Optimize (subsequent calls) | Generate Circuit, Optimize |
| `braketTaskArn` | Submit Task | Check Task |
| `braketStatus` | Check Task | Poll gateway |
| `braketResult` | Check Task | Evaluate Results |
| `objectiveValue` | Evaluate Results, Optimize (echoed) | Optimize |
| `currentBestBitstring` | Evaluate Results | Optimize |
| `counts` | Evaluate Results | (available for inspection) |
| `converged` | Optimize | Convergence gateway |
| `convergenceReason` | Optimize (on convergence) | Result form |
| `optimizerState` | Optimize | Optimize (next call) |
| `optimalParams` | Optimize (on convergence) | (available for inspection) |
| `bestPartition` | Optimize (on convergence) | Result form |
| `bestCutWeight` | Optimize (on convergence) | Result form |

### Running the example

> **Note (Camunda SaaS only):** The sidecar must be publicly accessible. See the [Grover example](#running-the-example) for details on using ngrok during testing.

1. Create a `.env` file in the project root with your Camunda SaaS credentials (copy from `.env.example`):

   ```
   CAMUNDA_CLIENT_ID=<your-client-id>
   CAMUNDA_CLIENT_SECRET=<your-client-secret>
   CAMUNDA_CLUSTER_ID=<cluster-id>
   CAMUNDA_REGION=<region e.g. bru-2>
   ```

   Find these values in **Camunda Console → Clusters → your cluster → API**.

2. Start the connector, sidecar, and QAOA backend services:

   ```bash
   cd example/predefined-algorithms
   docker compose --profile qaoa up --build
   ```

   The `--profile qaoa` flag additionally starts the [`objective-evaluation-service`](https://github.com/UST-QuAntiL/objective-evaluation-service) (port 5072), which the sidecar delegates MaxCut objective evaluation to.

3. Deploy the element templates to your Camunda Modeler project:
   - `element-templates/braket-connector.json` (main connector template)
   - `example/predefined-algorithms/element-templates/braket-sidecar-generate-circuit.json`
   - `example/predefined-algorithms/element-templates/braket-sidecar-process-results.json`
   - `example/predefined-algorithms/element-templates/braket-sidecar-optimize.json`

4. Deploy the workflow and forms to your Camunda cluster via Camunda Web Modeler by uploading the following files from `example/predefined-algorithms/qaoa/`:
   - `qaoa-max-cut-workflow.bpmn`
   - `qaoa-input-form.form`
   - `qaoa-result-form.form`
   - `qaoa-sidecar-error-form.form`
   - `qaoa-task-failure-form.form`

5. Start a process instance via Camunda Tasklist with the following start form inputs:

   | Field | Example value |
   |---|---|
   | Adjacency Matrix | `[[0,1,1,0],[1,0,1,1],[1,1,0,1],[0,1,1,0]]` |
   | QAOA Depth (p) | `1` |
   | Shots | `1000` |
   | Max Iterations | `30` |
   | Sidecar URL | `http://quantum-sidecar:5000` (local) or your ngrok URL |
   | AWS Access Key ID | `{{secrets.AWS_ACCESS_KEY_ID}}` |
   | AWS Secret Access Key | `{{secrets.AWS_SECRET_ACCESS_KEY}}` |
   | AWS Region | `us-east-1` |
   | Device ARN | `arn:aws:braket:::device/quantum-simulator/amazon/sv1` |
   | S3 Result Bucket | `amazon-braket-us-east-1-<account-id>` |
   | S3 Key Prefix | `braket-results` |

6. The workflow runs multiple Braket tasks. After the SPSA optimizer converges (or the iteration cap is reached), a **Review MaxCut result** user task appears in Tasklist showing:
   - **Best Partition** — bitstring encoding the optimal node partition (e.g. `0110` means nodes 1 and 2 in one set, nodes 0 and 3 in the other)
   - **Best Cut Weight** — the total weight of edges crossing the cut
   - **SPSA Objective Value** — the expected cut weight averaged over all measured bitstrings; may differ from the best cut weight found in a single shot
   - **Convergence Reason** — `converged` if the optimizer stabilised, `max_iterations` if the iteration cap was hit (the best partition found is still returned in either case)
