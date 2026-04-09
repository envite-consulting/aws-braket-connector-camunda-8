# Connector Configuration and Output Reference

The connector provides the following configuration properties for submitting quantum circuits to AWS Braket and retrieving their results.

---

## Operation

| Field | Description | Default |
|---|---|---|
| **Operation** | `Submit Task` — queue a new quantum task. `Get Task Result` — check the status of a previously submitted task. | `Submit Task` |

---

## AWS Authentication

These fields apply to both operations.

| Field | Description | Default |
|---|---|---|
| **AWS Access Key ID** | IAM access key ID. Use a Camunda secret: `{{secrets.AWS_ACCESS_KEY_ID}}` | — |
| **AWS Secret Access Key** | IAM secret access key. Use a Camunda secret: `{{secrets.AWS_SECRET_ACCESS_KEY}}` | — |
| **AWS Session Token** | Optional. Required when using temporary credentials obtained via STS AssumeRole. Use a Camunda secret: `{{secrets.AWS_SESSION_TOKEN}}` | — |
| **AWS Region** | AWS region where tasks are submitted, e.g. `us-east-1`. Not all quantum devices are available in all regions. | `us-east-1` |

The IAM identity must have at minimum the following permissions:
- `braket:CreateQuantumTask` (Submit Task)
- `braket:GetQuantumTask` (Get Task Result, and polling in Submit Task)
- `s3:GetObject` on the result bucket (whenever the task completed)

---

## Task Configuration

### Get Task Result

| Field | Description | Default |
|---|---|---|
| **Task ARN** | Full ARN of the previously submitted Braket task, e.g. `arn:aws:braket:us-east-1:123456789012:quantum-task/...` | — |

### Submit Task

| Field | Description | Default |
|---|---|---|
| **Device ARN** | Full ARN of the target quantum device or simulator. See [device ARNs](#device-arns) below. | `arn:aws:braket:::device/quantum-simulator/amazon/sv1` |
| **Circuit Input Mode** | How the circuit is supplied: `OpenQASM 3` or `Direct Params (JSON)`. | `OpenQASM 3` |
| **Quantum Circuit (OpenQASM 3)** | OpenQASM 3 circuit string to execute. Braket does not transpile — use gates supported by the target device. | — |
| **Shots** | Number of circuit repetitions. Simulators support up to 100,000 shots; QPU limits vary by device. | `1000` |
| **Braket IR Action (JSON)** | Complete Braket IR action JSON string with `braketSchemaHeader` and `source` fields. Required when Circuit Input Mode is `Direct Params`. | — |
| **S3 Result Bucket** | S3 bucket where Braket writes task results. Must be in the same AWS region as the task. | — |
| **S3 Key Prefix** | Key prefix within the S3 bucket. Braket appends `/<taskId>/results.json` automatically. | `braket-results` |

#### Device ARNs

Common device ARNs for reference:

| Device | ARN |
|---|---|
| Amazon SV1 Simulator | `arn:aws:braket:::device/quantum-simulator/amazon/sv1` |
| Amazon TN1 Simulator | `arn:aws:braket:us-west-2::device/quantum-simulator/amazon/tn1` |
| Amazon DM1 Simulator | `arn:aws:braket:::device/quantum-simulator/amazon/dm1` |
| IonQ Aria 1 | `arn:aws:braket:us-east-1::device/qpu/ionq/Aria-1` |
| IonQ Aria 2 | `arn:aws:braket:us-east-1::device/qpu/ionq/Aria-2` |
| Rigetti Ankaa-9Q-3 | `arn:aws:braket:us-west-1::device/qpu/rigetti/Ankaa-9Q-3` |

---

## Execution

| Field | Description | Default | Condition |
|---|---|---|---|
| **Wait for Result** | Poll until the task reaches a terminal state before completing the BPMN task. **Defaults to `false`** — see note below. | `false` | Submit Task |
| **Timeout (seconds)** | Maximum time in seconds to wait for a result before throwing a timeout exception. | `300` | Submit Task |
| **Poll Interval (seconds)** | How often to check the task status when `waitForResult` is enabled. | `10` | Submit Task |

---

## Connector Output

The connector returns a `response` object after the service task completes. Use the **Result Expression** in the element template (or task headers) to map fields into process variables.

| Field | Type | Description |
|---|---|---|
| `response.taskArn` | String | Full AWS Braket task ARN |
| `response.status` | String | Task status. See [task statuses](#task-statuses) below. |
| `response.result` | FEEL context | Result payload fetched from S3. Only populated when the task completed successfully (`COMPLETED`). Contains `measurementProbabilities` (map of bitstring → probability) and/or `measurements` (list of shot outcomes). `null` otherwise. |
| `response.resultS3Uri` | String | S3 URI of the result file (`s3://bucket/prefix/taskId/results.json`). Populated after submission so it can be stored before the task completes. |

### Task statuses

| Status | Description |
|---|---|
| `CREATED` | Task has been accepted by Braket but not yet queued |
| `QUEUED` | Task is in the device queue |
| `RUNNING` | Task is actively executing |
| `COMPLETED` | Task finished successfully; result is available in S3 |
| `FAILED` | Task encountered an error |
| `CANCELLING` | Cancellation requested but not yet confirmed |
| `CANCELLED` | Task was cancelled |

> **Why `waitForResult` defaults to `false`**
>
> Camunda job workers have a configurable job timeout (default: 5 minutes for SaaS clusters). AWS Braket QPU tasks can queue for far longer — IonQ and Rigetti devices regularly have queue times measured in tens of minutes or more. If the connector polls beyond the job timeout, Camunda marks the job as timed out and re-activates it, causing the circuit to be submitted a second time and consuming additional QPU credits.
>
> The safe default is `waitForResult=false`: submit the task immediately, store the `taskArn` in a process variable, and poll via a BPMN intermediate timer event driving a `Get Task Result` service task until a terminal status is reached. This pattern is demonstrated in `example/braket-example-workflow-polling.bpmn`.
>
> Only set `waitForResult=true` when targeting a **simulator** (SV1, TN1, DM1) where execution times are short and predictable.

### Behavior by operation

**`SUBMIT_TASK` with `waitForResult=false`** (default) — the task completes immediately after submission. `status` is `QUEUED` and `result` is `null`. `resultS3Uri` is already populated with the expected S3 path. Use `Get Task Result` in a subsequent BPMN timer loop to retrieve the final result. This is the recommended pattern for all QPU devices.

**`SUBMIT_TASK` with `waitForResult=true`** — the task blocks until a terminal state is reached. All four output fields are populated; `result` is non-null only for `COMPLETED` tasks. Only use this for simulators (SV1, TN1, DM1) where execution completes within the Camunda job timeout.

**`GET_TASK_RESULT`** — performs a single status check. `result` is populated only once `status` is `COMPLETED`. `resultS3Uri` is `null` for this operation (it is available from the `SUBMIT_TASK` response or from the task metadata).

### Example result expressions

Map all output fields into a single process variable:
```
= {taskArn: response.taskArn, status: response.status, result: response.result, resultS3Uri: response.resultS3Uri}
```

Extract only the task ARN after a non-blocking submission (polling pattern):
```
= {braketTaskArn: response.taskArn, braketResultS3Uri: response.resultS3Uri}
```

Access the measurement probabilities from the result payload in a downstream FEEL expression:
```
= braketResult.result.measurementProbabilities
```

Read the most probable bitstring:
```
= (sort(entries(braketResult.result.measurementProbabilities), function(a, b) a.value > b.value))[1].key
```
