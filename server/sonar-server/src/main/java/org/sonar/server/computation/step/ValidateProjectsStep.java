/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation.step;

import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.ComponentKeys;
import org.sonar.core.persistence.DbSession;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DepthTraversalTypeAwareVisitor;
import org.sonar.server.db.DbClient;

/**
 * Validate project and modules. It will fail in the following cases :
 * <ol>
 * <li>property {@link org.sonar.api.CoreProperties#CORE_PREVENT_AUTOMATIC_PROJECT_CREATION} is set to true and project does not exists</li>
 * <li>branch is not valid</li>
 * <li>project or module key is not valid</li>
 * <li>module key already exists in another project (same module key cannot exists in different projects)</li>
 * <li>module key is already used as a project key</li>
 * </ol>
 */
public class ValidateProjectsStep implements ComputationStep {

  private final DbClient dbClient;
  private final Settings settings;

  public ValidateProjectsStep(DbClient dbClient, Settings settings) {
    this.dbClient = dbClient;
    this.settings = settings;
  }

  @Override
  public void execute(ComputationContext context) {
    DbSession session = dbClient.openSession(false);
    try {
      ValidateProjectsVisitor visitor = new ValidateProjectsVisitor(session, dbClient.componentDao(), context.getReportMetadata(),
        settings.getBoolean(CoreProperties.CORE_PREVENT_AUTOMATIC_PROJECT_CREATION));
      visitor.visit(context.getRoot());

      if (!visitor.validationMessages.isEmpty()) {
        throw new IllegalArgumentException("Validation of project failed:\n  o " + Joiner.on("\n  o ").join(visitor.validationMessages));
      }
    } finally {
      session.close();
    }
  }

  @Override
  public String getDescription() {
    return "Validate project and modules keys";
  }

  private static class ValidateProjectsVisitor extends DepthTraversalTypeAwareVisitor {
    private final DbSession session;
    private final ComponentDao componentDao;
    private final BatchReport.Metadata reportMetadata;
    private final boolean preventAutomaticProjectCreation;

    private final List<String> validationMessages;

    public ValidateProjectsVisitor(DbSession session, ComponentDao componentDao, BatchReport.Metadata reportMetadata, boolean preventAutomaticProjectCreation) {
      super(Component.Type.MODULE, Order.PRE_ORDER);
      this.session = session;
      this.componentDao = componentDao;
      this.reportMetadata = reportMetadata;
      this.preventAutomaticProjectCreation = preventAutomaticProjectCreation;
      this.validationMessages = new ArrayList<>();
    }

    @Override
    public void visitProject(Component project) {
      String branch = reportMetadata.hasBranch() ? reportMetadata.getBranch() : null;
      validateBranch(branch);

      String projectKey = project.getKey();
      ComponentDto projectDto = componentDao.selectNullableByKey(session, projectKey);
      if (projectDto == null && preventAutomaticProjectCreation) {
        validationMessages.add(String.format("Unable to scan non-existing project '%s'", projectKey));
      }
      validateKey(projectKey);
    }

    @Override
    public void visitModule(Component module) {
      validateKey(module.getKey());
    }

    private void validateKey(String moduleKey) {
      if (!ComponentKeys.isValidModuleKey(moduleKey)) {
        validationMessages.add(String.format("\"%s\" is not a valid project or module key. "
          + "Allowed characters are alphanumeric, '-', '_', '.' and ':', with at least one non-digit.", moduleKey));
      }
    }

    private void validateBranch(@Nullable String branch) {
      if (StringUtils.isNotEmpty(branch) && !ComponentKeys.isValidBranch(branch)) {
        validationMessages.add(String.format("\"%s\" is not a valid branch name. "
          + "Allowed characters are alphanumeric, '-', '_', '.' and '/'.", branch));
      }
    }
  }
}
