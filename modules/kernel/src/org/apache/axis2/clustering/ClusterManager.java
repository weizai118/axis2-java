/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.axis2.clustering;

import org.apache.axis2.clustering.configuration.ConfigurationManager;
import org.apache.axis2.clustering.context.ContextManager;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.description.ParameterInclude;

/**
 * This is the main interface in the Axis2 clustering implementation.
 * In order to plug-in a new clustering implementation, this interface has to be
 * implmented.
 */
public interface ClusterManager extends ParameterInclude {

    /**
     * Initialize the ClusteManager
     *
     * @throws ClusteringFault
     */
    void init() throws ClusteringFault;

    /**
     * @return The ContextManager
     */
    ContextManager getContextManager();

    /**
     * @return The ConfigurationManager
     */
    ConfigurationManager getConfigurationManager();

    /**
     * @param contextManager
     */
    void setContextManager(ContextManager contextManager);

    /**
     * @param configurationManager
     */
    void setConfigurationManager(ConfigurationManager configurationManager);

    /**
     * @throws ClusteringFault
     */
    void shutdown() throws ClusteringFault;

    /**
     * Set the configuration context
     *
     * @param configurationContext
     */
    void setConfigurationContext(ConfigurationContext configurationContext);

    /**
     * Set the static members of the cluster. This is used only with static group membership.
     *
     * @param members Members to be added
     */
    void setMembers(Member[] members);

    /**
     * Get the list of members in a static group
     *
     * @return The members if static group membership is used. If any other membership scheme is used,
     *         the values returned may not be valid
     */
    Member[] getMembers();

}