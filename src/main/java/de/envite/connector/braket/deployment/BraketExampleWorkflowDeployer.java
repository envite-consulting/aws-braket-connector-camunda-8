package de.envite.connector.braket.deployment;

import io.camunda.client.CamundaClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@AllArgsConstructor
@Component
@ConditionalOnProperty(name = "braket.example.deploy", havingValue = "true")
public class BraketExampleWorkflowDeployer implements ApplicationRunner {

  private final CamundaClient camundaClient;

  @Override
  public void run(ApplicationArguments args) {
    log.info("[BraketExampleWorkflowDeployer] Deploying example workflows and forms");
    camundaClient.newDeployResourceCommand()
        .addResourceFromClasspath("example/getting-started/braket-input-form.form")
        .addResourceFromClasspath("example/getting-started/braket-result-form.form")
        .addResourceFromClasspath("example/getting-started/braket-example-workflow-blocking.bpmn")
        .addResourceFromClasspath("example/getting-started/braket-example-workflow-polling.bpmn")
        .send()
        .join();
    log.info("[BraketExampleWorkflowDeployer] Example workflows deployed successfully");
  }
}
