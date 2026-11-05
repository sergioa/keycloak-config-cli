/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.exception.ImportProcessingException;
import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.model.WorkflowRepresentation;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.repository.WorkflowRepository;
import de.adorsys.keycloak.config.util.CloneUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static de.adorsys.keycloak.config.properties.ImportConfigProperties.ImportManagedProperties.ImportManagedPropertiesValues;

@Service
@ConditionalOnProperty(prefix = "run", name = "operation", havingValue = "IMPORT", matchIfMissing = true)
public class WorkflowImportService {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowImportService.class);

    private final WorkflowRepository workflowRepository;
    private final ImportConfigProperties importConfigProperties;

    @Autowired
    public WorkflowImportService(WorkflowRepository workflowRepository, ImportConfigProperties importConfigProperties) {
        this.workflowRepository = workflowRepository;
        this.importConfigProperties = importConfigProperties;
    }

    public void doImport(RealmImport realmImport) {
        List<WorkflowRepresentation> workflows = realmImport.getWorkflows();
        if (workflows == null) return;

        String realmName = realmImport.getRealm();
        List<WorkflowRepresentation> existingWorkflows = workflowRepository.getAll(realmName);

        if (existingWorkflows == null) {
            logger.warn("Workflows API not available in realm '{}', skipping workflow import", realmName);
            return;
        }

        if (importConfigProperties.getManaged().getWorkflow() == ImportManagedPropertiesValues.FULL) {
            deleteWorkflowsMissingInImport(realmName, workflows, existingWorkflows);
        }

        for (WorkflowRepresentation workflow : workflows) {
            createOrUpdateWorkflow(realmName, workflow);
        }
    }

    private void deleteWorkflowsMissingInImport(
            String realmName,
            List<WorkflowRepresentation> workflows,
            List<WorkflowRepresentation> existingWorkflows
    ) {
        for (WorkflowRepresentation existing : existingWorkflows) {
            if (!hasWorkflowWithName(workflows, existing.getName())) {
                logger.debug("Delete workflow '{}' in realm '{}'", existing.getName(), realmName);
                workflowRepository.delete(realmName, existing.getId());
            }
        }
    }

    private void createOrUpdateWorkflow(String realmName, WorkflowRepresentation workflow) {
        Optional<WorkflowRepresentation> maybeExisting = workflowRepository.search(realmName, workflow.getName());

        if (maybeExisting.isPresent()) {
            updateWorkflowIfNecessary(realmName, workflow, maybeExisting.get());
        } else {
            logger.debug("Create workflow '{}' in realm '{}'", workflow.getName(), realmName);
            workflowRepository.create(realmName, workflow);
        }
    }

    private void updateWorkflowIfNecessary(
            String realmName,
            WorkflowRepresentation workflow,
            WorkflowRepresentation existingWorkflow
    ) {
        WorkflowRepresentation patched = CloneUtil.patch(existingWorkflow, workflow, "id");

        if (CloneUtil.deepEquals(existingWorkflow, patched)) {
            logger.debug("No need to update workflow '{}' in realm '{}'", workflow.getName(), realmName);
        } else {
            logger.debug("Update workflow '{}' in realm '{}'", workflow.getName(), realmName);
            try {
                workflowRepository.update(realmName, patched);
            } catch (ImportProcessingException error) {
                if (isNullWorkflowTypeConditionMismatch(error)) {
                    logger.warn(
                            "Workflow '{}' in realm '{}' has incompatible persisted type (null). "
                                    + "Recreating workflow to repair state.",
                            workflow.getName(), realmName
                    );
                    workflowRepository.delete(realmName, existingWorkflow.getId());
                    workflowRepository.create(realmName, workflow);
                    return;
                }
                throw error;
            }
        }
    }

    private boolean hasWorkflowWithName(List<WorkflowRepresentation> workflows, String name) {
        return workflows.stream().anyMatch(w -> Objects.equals(w.getName(), name));
    }

    private boolean isNullWorkflowTypeConditionMismatch(ImportProcessingException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("provided condition types")
                && normalized.contains("workflow type (null)");
    }
}
