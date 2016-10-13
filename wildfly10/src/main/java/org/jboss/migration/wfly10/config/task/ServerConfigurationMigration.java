/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.migration.wfly10.config.task;

import org.jboss.migration.core.ServerMigrationTask;
import org.jboss.migration.core.ServerMigrationTaskContext;
import org.jboss.migration.core.ServerMigrationTaskName;
import org.jboss.migration.core.ServerMigrationTaskResult;
import org.jboss.migration.core.console.ConsoleWrapper;
import org.jboss.migration.wfly10.WildFly10Server;
import org.jboss.migration.wfly10.config.management.ManageableServerConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implementation of a server config migration.
 * @param <S> the source for the configuration
 * @param <T> the manageable config type
 * @author emmartins
 */
public class ServerConfigurationMigration<S, T extends ManageableServerConfiguration> {

    public static final String MIGRATION_REPORT_TASK_ATTR_SOURCE = "source";

    private final String configType;
    protected final XMLConfigurationProvider xmlConfigurationProvider;
    protected final ManageableConfigurationProvider<T> manageableConfigurationProvider;
    protected final List<ManageableConfigurationSubtaskFactory<S, T>> manageableConfigurationSubtaskFactories;
    protected final List<XMLConfigurationSubtaskFactory<S>> xmlConfigurationSubtaskFactories;

    protected ServerConfigurationMigration(Builder builder) {
        this.configType = builder.configType;
        this.xmlConfigurationProvider = builder.xmlConfigurationProvider;
        this.manageableConfigurationProvider = builder.manageableConfigurationProvider;
        this.manageableConfigurationSubtaskFactories = Collections.unmodifiableList(builder.manageableConfigurationSubtaskFactories);
        this.xmlConfigurationSubtaskFactories = Collections.unmodifiableList(builder.xmlConfigurationSubtaskFactories);
    }

    public String getConfigType() {
        return configType;
    }

    protected ServerMigrationTask getServerMigrationTask(final S source, final Path targetConfigDir, final WildFly10Server target) {
        final ServerMigrationTaskName taskName = new ServerMigrationTaskName.Builder(getConfigType()+"-configuration").addAttribute(MIGRATION_REPORT_TASK_ATTR_SOURCE, source.toString()).build();
        return new ServerMigrationTask() {
            @Override
            public ServerMigrationTaskName getName() {
                return taskName;
            }

            @Override
            public ServerMigrationTaskResult run(ServerMigrationTaskContext context) throws Exception {
                final ConsoleWrapper consoleWrapper = context.getServerMigrationContext().getConsoleWrapper();
                consoleWrapper.printf("%n");
                context.getLogger().infof("Migrating %s configuration %s", getConfigType(), source);
                // create xml config
                final Path xmlConfigurationPath = xmlConfigurationProvider.getXMLConfiguration(source, targetConfigDir, target, context);
                // execute xml config subtasks
                for (XMLConfigurationSubtaskFactory subtaskFactory : xmlConfigurationSubtaskFactories) {
                    final ServerMigrationTask subtask = subtaskFactory.getXMLConfigurationSubtask(source, xmlConfigurationPath, target);
                    if (subtask != null) {
                        context.execute(subtask);
                    }
                }
                // config through management
                if (manageableConfigurationProvider != null) {
                    final T configurationManagement = manageableConfigurationProvider.getManageableConfiguration(xmlConfigurationPath, target);
                    //context.getServerMigrationContext().getConsoleWrapper().printf("%n%n");
                    context.getLogger().debugf("Starting target configuration %s", xmlConfigurationPath.getFileName());
                    configurationManagement.start();
                    try {
                        // execute config management subtasks
                        for (ManageableConfigurationSubtaskFactory subtaskFactory : manageableConfigurationSubtaskFactories) {
                            final ServerMigrationTask subtask = subtaskFactory.getManageableConfigurationSubtask(source, configurationManagement);
                            if (subtask != null) {
                                context.execute(subtask);
                            }
                        }
                    } finally {
                        configurationManagement.stop();
                    }
                }
                //consoleWrapper.printf("%n");
                return ServerMigrationTaskResult.SUCCESS;
            }
        };
    }

    /**
     * Component responsible for providing the target XML configuration.
     * @param <S>
     */
    public interface XMLConfigurationProvider<S> {
        Path getXMLConfiguration(S source, Path targetConfigDir, WildFly10Server target, ServerMigrationTaskContext context) throws Exception;
    }

    /**
     * XML Config Subtasks factory.
     * @param <S> the source for the configuration
     */
    public interface XMLConfigurationSubtaskFactory<S> {
        ServerMigrationTask getXMLConfigurationSubtask(S source, Path xmlConfigurationPath, WildFly10Server target);
    }

    /**
     * Manageable Config Subtasks factory.
     * @param <S> the source for the configuration
     * @param <T> the manageable config type
     */
    public interface ManageableConfigurationSubtaskFactory<S, T extends ManageableServerConfiguration> {
        ServerMigrationTask getManageableConfigurationSubtask(S source, T configuration) throws Exception;
    }

    /**
     * Provider for the manageable configuration
     * @param <T>
     */
    public interface ManageableConfigurationProvider<T extends ManageableServerConfiguration> {
        T getManageableConfiguration(Path targetConfigFilePath, WildFly10Server target) throws Exception;
    }

    /**
     * The ServerConfigurationMigration builder.
     * @param <S> the source for the configuration
     * @param <T> the manageable config type
     */
    public static class Builder<B extends Builder, S, T extends ManageableServerConfiguration> {

        private final String configType;
        private final XMLConfigurationProvider xmlConfigurationProvider;
        private ManageableConfigurationProvider<T> manageableConfigurationProvider;
        private final List<ManageableConfigurationSubtaskFactory<S, T>> manageableConfigurationSubtaskFactories;
        private final List<XMLConfigurationSubtaskFactory<S>> xmlConfigurationSubtaskFactories;

        public Builder(String configType, XMLConfigurationProvider xmlConfigurationProvider) {
            this.configType = configType;
            this.xmlConfigurationProvider = xmlConfigurationProvider;
            manageableConfigurationSubtaskFactories = new ArrayList<>();
            xmlConfigurationSubtaskFactories = new ArrayList<>();
        }

        public B manageableConfigurationProvider(ManageableConfigurationProvider<T> manageableConfigurationProvider) {
            this.manageableConfigurationProvider = manageableConfigurationProvider;
            return (B) this;
        }

        public B addManageableConfigurationSubtaskFactory(ManageableConfigurationSubtaskFactory<S, T> subtaskFactory) {
            manageableConfigurationSubtaskFactories.add(subtaskFactory);
            return (B) this;
        }

        public B addXMLConfigurationSubtaskFactory(XMLConfigurationSubtaskFactory<S> subtaskFactory) {
            xmlConfigurationSubtaskFactories.add(subtaskFactory);
            return (B) this;
        }

        public ServerConfigurationMigration<S, T> build() {
            return new ServerConfigurationMigration(this);
        }
    }
}