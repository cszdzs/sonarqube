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

import com.google.common.collect.ImmutableSet;
import org.sonar.api.issue.internal.DefaultIssue;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.component.DbComponentsRefCache;
import org.sonar.server.computation.component.DbComponentsRefCache.DbComponent;
import org.sonar.server.computation.issue.IssueCache;
import org.sonar.server.computation.issue.RuleCache;
import org.sonar.server.issue.notification.IssueChangeNotification;
import org.sonar.server.issue.notification.MyNewIssuesNotification;
import org.sonar.server.issue.notification.NewIssuesNotification;
import org.sonar.server.issue.notification.NewIssuesNotificationFactory;
import org.sonar.server.issue.notification.NewIssuesStatistics;
import org.sonar.server.notifications.NotificationService;
import org.sonar.server.util.CloseableIterator;

import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Reads issues from disk cache and send related notifications. For performance reasons,
 * the standard notification DB queue is not used as a temporary storage. Notifications
 * are directly processed by {@link org.sonar.server.notifications.NotificationService}.
 */
public class SendIssueNotificationsStep implements ComputationStep {
  /**
   * Types of the notifications sent by this step
   */
  static final Set<String> NOTIF_TYPES = ImmutableSet.of(IssueChangeNotification.TYPE, NewIssuesNotification.TYPE, MyNewIssuesNotification.MY_NEW_ISSUES_NOTIF_TYPE);

  private final IssueCache issueCache;
  private final RuleCache rules;
  private final DbComponentsRefCache dbComponentsRefCache;
  private final NotificationService service;
  private NewIssuesNotificationFactory newIssuesNotificationFactory;

  public SendIssueNotificationsStep(IssueCache issueCache, RuleCache rules, DbComponentsRefCache dbComponentsRefCache, NotificationService service,
    NewIssuesNotificationFactory newIssuesNotificationFactory) {
    this.issueCache = issueCache;
    this.rules = rules;
    this.dbComponentsRefCache = dbComponentsRefCache;
    this.service = service;
    this.newIssuesNotificationFactory = newIssuesNotificationFactory;
  }

  @Override
  public void execute(ComputationContext context) {
    DbComponent project = dbComponentsRefCache.getByRef(context.getReportMetadata().getRootComponentRef());
    if (service.hasProjectSubscribersForTypes(project.getUuid(), NOTIF_TYPES)) {
      doExecute(context, project);
    }
  }

  private void doExecute(ComputationContext context, DbComponent project) {
    NewIssuesStatistics newIssuesStats = new NewIssuesStatistics();
    CloseableIterator<DefaultIssue> issues = issueCache.traverse();
    String projectName = context.getReportReader().readComponent(context.getReportMetadata().getRootComponentRef()).getName();
    try {
      while (issues.hasNext()) {
        DefaultIssue issue = issues.next();
        if (issue.isNew() && issue.resolution() == null) {
          newIssuesStats.add(issue);
        } else if (issue.isChanged() && issue.mustSendNotifications()) {
          IssueChangeNotification changeNotification = new IssueChangeNotification();
          changeNotification.setRuleName(rules.ruleName(issue.ruleKey()));
          changeNotification.setIssue(issue);
          changeNotification.setProject(project.getKey(), projectName);
          service.deliver(changeNotification);
        }
      }

    } finally {
      issues.close();
    }
    sendNewIssuesStatistics(context, newIssuesStats, project, projectName);
  }

  private void sendNewIssuesStatistics(ComputationContext context, NewIssuesStatistics statistics, DbComponent project, String projectName) {
    if (statistics.hasIssues()) {
      NewIssuesStatistics.Stats globalStatistics = statistics.globalStatistics();
      NewIssuesNotification notification = newIssuesNotificationFactory
        .newNewIssuesNotication()
        .setProject(project.getKey(), project.getUuid(), projectName)
        .setAnalysisDate(new Date(context.getReportMetadata().getAnalysisDate()))
        .setStatistics(projectName, globalStatistics)
        .setDebt(globalStatistics.debt());
      service.deliver(notification);

      // send email to each user having issues
      for (Map.Entry<String, NewIssuesStatistics.Stats> assigneeAndStatisticsTuple : statistics.assigneesStatistics().entrySet()) {
        String assignee = assigneeAndStatisticsTuple.getKey();
        NewIssuesStatistics.Stats assigneeStatistics = assigneeAndStatisticsTuple.getValue();
        MyNewIssuesNotification myNewIssuesNotification = newIssuesNotificationFactory
          .newMyNewIssuesNotification()
          .setAssignee(assignee);
        myNewIssuesNotification
          .setProject(project.getKey(), project.getUuid(), projectName)
          .setAnalysisDate(new Date(context.getReportMetadata().getAnalysisDate()))
          .setStatistics(projectName, assigneeStatistics)
          .setDebt(assigneeStatistics.debt());

        service.deliver(myNewIssuesNotification);
      }
    }
  }

  @Override
  public String getDescription() {
    return "Send issue notifications";
  }

}
