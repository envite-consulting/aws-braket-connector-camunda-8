# Getting Started

This guide walks you through the AWS and Camunda prerequisites and your first end-to-end quantum circuit execution.
For connector installation and build steps see the [README](../README.md#-how-to-run).

---

## 1. AWS Braket Prerequisites

### 1.1 Create an IAM User

The connector authenticates against AWS using an IAM user's access credentials. To create a dedicated user:

1. Log in to the [AWS Console](https://console.aws.amazon.com) and navigate to **IAM → Users → Create user**.
2. Give the user a name, e.g. `braket-connector`.
3. On the permissions step, attach the following managed policies directly:
   - `AmazonBraketFullAccess` — grants permission to submit and query quantum tasks. It also covers the default Braket S3 bucket (`amazon-braket-*`).
4. Complete the wizard and open the newly created user.

### 1.2 Generate an Access Key

An **Access Key ID** and a **Secret Access Key** are the programmatic credentials the connector uses to sign API requests to AWS — similar to a username and password, but for API access.

1. Open the user you just created → **Security credentials** tab.
2. Scroll to **Access keys** → click **Create access key**.
3. Choose the use case **"Application running outside AWS"** and confirm.
4. Download the credentials file or copy both values immediately — the Secret Access Key is shown only once.

> Keep these credentials private. Never commit them to source control.

### 1.3 Find or Create your S3 Result Bucket

Amazon Braket writes task results to an S3 bucket — it does not return results inline. Braket automatically creates a default bucket the first time you run a task, named:

```
amazon-braket-<region>-<your-account-id>
```

To find this bucket after your first run, go to **S3** in the AWS Console and look for a bucket starting with `amazon-braket-`. Alternatively, create the bucket manually before your first run by following the same naming convention. The bucket must be in the **same AWS region** as the Braket tasks you will submit.

> To generate the bucket and get some first impressions of the service, AWS Braket Notebooks are a good entrypoint as they provide a set of preconfigured, simple exemplary quantum programs.

---

## 2. Camunda Prerequisites

### 2.1 Store AWS Credentials as Camunda Secrets

Never enter raw AWS credentials into a process form or connector configuration. Instead, store them as [Camunda Secrets](https://docs.camunda.io/docs/components/console/manage-clusters/manage-secrets/) so they are encrypted at rest and never exposed in process variables.

1. In **Camunda Console**, go to your cluster → **Secrets** → **Create secret**.
2. Create the following two secrets:

| Secret name | Value |
|---|---|
| `AWS_ACCESS_KEY_ID` | The Access Key ID from Section 1.2 |
| `AWS_SECRET_ACCESS_KEY` | The Secret Access Key from Section 1.2 |

Once created, you reference them in forms and connector fields using double curly braces, e.g. `{{secrets.AWS_ACCESS_KEY_ID}}`.

### 2.2 Connector Installation

Make sure the connector is running and connected to your Camunda cluster before deploying any process. Follow the [README → How to Run](../README.md#-how-to-run) for build and configuration steps.

---

## 3. Running your First Workflow

A ready-to-use example workflow is included in `example/getting-started/`. It submits a quantum circuit to AWS Braket and polls for the result using a BPMN timer loop — a non-blocking pattern suited for both simulators and real QPU backends.

### 3.1 Import the Element Template

The element template gives the Camunda Modeler all the input fields and the Braket icon for service tasks.

- **Web Modeler**: go to your project → *Create new* → *Upload files* → select `element-templates/braket-connector.json`. Then open the template and publish it to the project or organization.
- **Desktop Modeler**: copy the file into the `resources/element-templates` directory of the modeler.

### 3.2 Upload the Example Workflow

Upload all three files from `example/getting-started/` to the **same Camunda project** where you published the element template:

| File | Purpose |
|---|---|
| `braket-example-workflow-polling.bpmn` | The main process |
| `braket-input-form.form` | Start form for circuit parameters |
| `braket-result-form.form` | User task form to review the result |

In Web Modeler: go to your project → *Create new* → *Upload files* and select all three files. Then deploy the process to your cluster.

### 3.3 Start a Process Instance

1. In **Camunda Operate** or **Web Modeler**, start a new instance of `AWS Braket Example Workflow (Polling)`.
2. The start form asks for the following inputs:

| Field | Description | Example value |
|---|---|---|
| AWS Access Key ID | Reference the secret | `{{secrets.AWS_ACCESS_KEY_ID}}` |
| AWS Secret Access Key | Reference the secret | `{{secrets.AWS_SECRET_ACCESS_KEY}}` |
| AWS Region | Region of the Braket device and S3 bucket | `us-east-1` |
| Device ARN | Target quantum device or simulator | `arn:aws:braket:::device/quantum-simulator/amazon/sv1` |
| Circuit Input Mode | Format of the circuit | `OpenQASM 3` |
| Quantum Circuit | The circuit to execute | see below |
| Shots | Number of repetitions | `1000` |
| S3 Result Bucket | Bucket for results (Section 1.3) | `amazon-braket-us-east-1-<account-id>` |
| S3 Key Prefix | Folder prefix inside the bucket | `braket-results` |

A minimal OpenQASM 3 circuit that flips a single qubit (X gate) is pre-filled in the form:

```
OPENQASM 3.0; 
qubit[1] q; 
bit[1] c; 
h q[0]; 
c[0] = measure q[0];
```

This is a good first circuit to verify end-to-end connectivity: it runs instantly on the SV1 simulator and always produces a deterministic result.

### 3.4 Monitor and Review the Result

After submission the workflow polls AWS every 20 seconds until the task reaches a terminal state (`COMPLETED`, `FAILED`, or `CANCELLED`). For the SV1 simulator this typically takes under a minute.

Once complete, a **Review quantum result** user task appears in your Camunda Tasklist. Open it to inspect the measurement counts and the S3 URI where the full result JSON is stored.
