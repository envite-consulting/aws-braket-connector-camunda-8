# AWS Braket Connector for Camunda 8 ⚛️

*TODO 🚀*

[![Build](https://github.com/wederbn/aws-braket-connector-camunda-8/actions/workflows/build.yml/badge.svg)](https://github.com/wederbn/aws-braket-connector-camunda-8/actions/workflows/build.yml)
[![Compatible with: Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-26d07c)](https://docs.camunda.io/)
[![Camunda Marketplace](https://img.shields.io/badge/Find_on-Camunda_Marketplace-brightgreen?style=flat&color=orange)](https://marketplace.camunda.com/en-US/listing?q=aws%20braket&page=1)
[![sponsored](https://img.shields.io/badge/sponsoredBy-envite-g.svg)](https://envite.de/)
[![Apache 2.0 License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](/LICENSE)

TODO

---

# Table of Contents

* 🚀 [How to Run](#-how-to-run)
* 📚 [Connector Documentation](#-connector-documentation)
    * [Getting Started](docs/getting-started.md)
    * [Connector Configuration and Output Reference](docs/connector-reference.md)
    * [Using Predefined Quantum Algorithms via a Sidecar](docs/use-predefined-algorithms.md)
    * [Example Use Cases & HowTos](docs/usecases.md)
* 🛠️ [Development and Project Setup](#️-development-and-project-setup)

---

## 🚀 How to Run

### Prerequisites

- **Java 21**
- **Maven 3.8+**
- A running **Camunda 8** instance (SaaS or Self-Managed)
- An **AWS account** with an IAM user or role that has permission to call `braket:CreateQuantumTask`, `braket:GetQuantumTask`, and `s3:GetObject` on the result bucket

### 1. Configure the Connector

Copy `.env.example` to `.env` and fill in your values (never commit `.env` — it is gitignored):


Export your Camunda Cluster credentials from **Camunda Console → Clusters → \<your cluster\> → API**:

| Variable | Description |
|---|---|
| `CAMUNDA_CLIENT_ID` | OAuth client ID |
| `CAMUNDA_CLIENT_SECRET` | OAuth client secret |
| `CAMUNDA_CLUSTER_ID` | Zeebe cluster UUID |
| `CAMUNDA_REGION` | Cluster region (e.g. `bru-2`) |

AWS credentials are **not** configured here — they are supplied per task via the connector input fields (`accessKeyId`, `secretAccessKey`, and optionally `sessionToken`).
Use [Camunda Secrets](https://docs.camunda.io/docs/components/console/manage-clusters/manage-secrets/) to store them securely and reference them as `secrets.AWS_ACCESS_KEY_ID`, `secrets.AWS_SECRET_ACCESS_KEY`, etc.

### 2. Build and Run

```bash
mvn spring-boot:run
```

The connector registers itself as a Camunda job worker and starts polling for jobs of type `de.envite:aws-braket-connector:1`.

### 3. Import the Element Template

Import `element-templates/braket-connector.json` into your Camunda Modeler to get the pre-configured service task with all input fields and the AWS Braket icon:

- **Camunda Web Modeler**: go to your project → *Create new* → *Upload files* → select `element-templates/braket-connector.json`. Afterward, open the element template and publish it to the project or organization.
- **Camunda Desktop Modeler**: copy the file into the `resources/element-templates` directory of the modeler.

### 4. Model and Deploy a Process

An example polling workflow is provided in `example/getting-started/` (see description above).
Upload the workflow together with its forms to your Camunda cluster — either via Camunda Web Modeler or the Zeebe API:

- `example/getting-started/braket-example-workflow-polling.bpmn`
- `example/getting-started/braket-input-form.form`
- `example/getting-started/braket-result-form.form`

In case you published the element template to a project, upload the workflow to the **same project** so Web Modeler automatically links the template and displays the connector with its icon.

To model your own process, add a service task and apply the **AWS Braket Connector** element template, then fill in the required properties.

## 📚 Connector Documentation

* [Getting Started](docs/getting-started.md): Details of how to get started with the AWS Braket Connector
* [Connector Configuration and Output Reference](docs/connector-reference.md): All configuration properties and the connector output fields available for use in result expressions and downstream tasks
* [Using Predefined Quantum Algorithms via a Sidecar](docs/use-predefined-algorithms.md): Architecture and integration guide for using a AWS Braket sidecar to generate quantum circuits from classical problem inputs and post-process measurement results — including support for variational algorithms (VQE, QAOA) with classical optimizer loops.
* [Example Use Cases & HowTos](docs/usecases.md): End-to-end workflow examples including Grover's search algorithm



## 🛠️ Development and Project Setup

TODO

## License

This project is developed under

[![Apache 2.0 License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](/LICENSE)

## Sponsors and Customers

[![sponsored](https://img.shields.io/badge/sponsoredBy-envite-g.svg)](https://envite.de/)
