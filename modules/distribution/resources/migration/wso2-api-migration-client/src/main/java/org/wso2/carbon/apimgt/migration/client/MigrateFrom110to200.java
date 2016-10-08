/*
* Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.apimgt.migration.client;


import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.client._110Specific.ResourceModifier;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.AppKeyMappingTableDTO;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.ConsumerAppsTableDTO;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.ConsumerKeyDTO;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.KeyDomainMappingTableDTO;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.SynapseDTO;
import org.wso2.carbon.apimgt.migration.client._200Specific.ResourceModifier200;
import org.wso2.carbon.apimgt.migration.client._200Specific.model.Policy;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.apimgt.migration.util.ResourceUtil;
import org.wso2.carbon.apimgt.migration.util.StatDBUtil;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.FileUtil;

public class MigrateFrom110to200 extends MigrationClientBase implements MigrationClient {

    private static final Log log = LogFactory.getLog(MigrateFrom110to200.class);
    private RegistryService registryService;
    private boolean removeDecryptionFailedKeysFromDB;

    public MigrateFrom110to200(String tenantArguments, String blackListTenantArguments, String tenantRange,
            RegistryService registryService, TenantManager tenantManager, boolean removeDecryptionFailedKeysFromDB)
            throws UserStoreException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
        this.removeDecryptionFailedKeysFromDB = removeDecryptionFailedKeysFromDB;
    }

    @Override
    public void databaseMigration() throws APIMigrationException, SQLException {
        String amScriptPath = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator;

        updateAPIManagerDatabase(amScriptPath);
    }

    @Override
    public void registryResourceMigration() throws APIMigrationException {
        rxtMigration();
    }

    @Override
    public void fileSystemMigration() throws APIMigrationException {
        synapseAPIMigration();
    }

    @Override
    public void cleanOldResources() throws APIMigrationException {

    }

    @Override
    public void statsMigration() throws APIMigrationException {
        log.info("Stat Database migration for API Manager started");
        String statScriptPath = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator + "stat" + File.separator;
        try {
            updateAPIManagerDatabase(statScriptPath);
        } catch (SQLException e) {
            log.error("error executing stat migration script", e);
            throw new APIMigrationException(e);
        }
        log.info("Stat DB migration Done");
    }

    /**
     * Implementation of new throttling migration using optional migration argument
     *
     * @param options list of command line options
     * @throws APIMigrationException throws if any exception occured
     */
    @Override
    public void tierMigration(List<String> options) throws APIMigrationException {
        if (options.contains("migrateThrottling")) {
            for (Tenant tenant : getTenantsArray()) {
                String apiPath = ResourceUtil.getApiPath(tenant.getId(), tenant.getDomain());
                List<SynapseDTO> synapseDTOs = ResourceUtil.getVersionedAPIs(apiPath);
                ResourceModifier200.updateThrottleHandler(synapseDTOs); // Update Throttle Handler

                //Read Throttling Tier from Registry and update databases
                try {
                    readThrottlingTiersFromRegistry(tenant);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            log.info("Throttling migration is finished.");
        }
    }

    private void synapseAPIMigration() {
        for (Tenant tenant : getTenantsArray()) {
            String apiPath = ResourceUtil.getApiPath(tenant.getId(), tenant.getDomain());
            List<SynapseDTO> synapseDTOs = ResourceUtil.getVersionedAPIs(apiPath);
            ResourceModifier200.updateSynapseConfigs(synapseDTOs);

            for (SynapseDTO synapseDTO : synapseDTOs) {
                ResourceModifier200.transformXMLDocument(synapseDTO.getDocument(), synapseDTO.getFile());
            }
        }
    }

    
    /**
     * This method is used to migrate rxt and rxt data
     * This adds three new attributes to the api rxt
     *
     * @throws APIMigrationException
     */
    private void rxtMigration() throws APIMigrationException {
        log.info("Rxt migration for API Manager started.");
        
        String rxtName = "api.rxt";
        String rxtDir = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator + "rxts" + File.separator + rxtName;

        
        for (Tenant tenant : getTenantsArray()) {       
            try {                
                registryService.startTenantFlow(tenant);
                
                log.info("Updating api.rxt for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                //Update api.rxt file
                String rxt = FileUtil.readFileToString(rxtDir);
                registryService.updateRXTResource(rxtName, rxt);                
                log.info("End Updating api.rxt for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                
                log.info("Start rxt data migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();
                for (GenericArtifact artifact : artifacts) {
                    String val="{\"corsConfigurationEnabled\": false,"
                            + "\"accessControlAllowOrigins\": [\"*\"],"
                            + "\"accessControlAllowCredentials\": false,"
                            + "\"accessControlAllowHeaders\": [\"authorization\",   \"Access-Control-Allow-Origin\", \"Content-Type\", \"SOAPAction\"],"
                            + "\"accessControlAllowMethods\": [\"GET\", \"PUT\", \"POST\", \"DELETE\", \"PATCH\", \"OPTIONS\"]"
                            + "}";
                    artifact.setAttribute("overview_corsConfiguration", val);
                    artifact.setAttribute("overview_endpointSecured", "false");
                    artifact.setAttribute("overview_endpointAuthDigest", "false");

                    String env = artifact.getAttribute("overview_environments");
                    if (env == null) {
                        artifact.setAttribute("overview_environments", "Production and Sandbox");
                    }
                    String trans = artifact.getAttribute("overview_transports");
                    if (trans == null) {
                        artifact.setAttribute("overview_transports", "http,https");
                    }
                    String versionType = artifact.getAttribute("overview_versionType");
                    if (versionType == null) {
                        artifact.setAttribute("overview_versionType", "-");
                    }
                }
                registryService.updateGenericAPIArtifacts(artifacts);
                log.info("End rxt data migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            
            } catch (GovernanceException e) {
                log.error("Error when accessing API artifact in registry for tenant "+ tenant.getId() + '(' 
                          + tenant.getDomain() + ')', e);
            } catch (IOException e) {
                log.error("Error when reading api.rxt from " + rxtDir + "for tenant " + tenant.getId() + '(' 
                          + tenant.getDomain() + ')', e);
            } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
                log.error("Error while updating api.rxt in the registry for tenant " + tenant.getId() + '(' 
                          + tenant.getDomain() + ')', e);
            } catch (UserStoreException e) {
                log.error("Error while updating api.rxt in the registry for tenant " + tenant.getId() + '(' 
                          + tenant.getDomain() + ')', e);
            }
            finally {
                registryService.endTenantFlow();
            }
        }

        log.info("Rxt resource migration done for all the tenants");
    }
    
    private void readThrottlingTiersFromRegistry(Tenant tenant) throws APIMigrationException  {
        registryService.startTenantFlow(tenant);
        Connection connection = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);

            // update or insert all three tiers
            readTiersAndUpdateDatabase(tenant, APIConstants.API_TIER_LOCATION, connection, Constants.AM_POLICY_SUBSCRIPTION);
            readTiersAndUpdateDatabase(tenant, APIConstants.APP_TIER_LOCATION, connection, Constants.AM_POLICY_APPLICATION);
            readTiersAndUpdateDatabase(tenant, APIConstants.RES_TIER_LOCATION, connection, Constants.AM_API_THROTTLE_POLICY);

            connection.commit();
        } catch (SQLException ex)   {
            log.error("Error occurred while doing database operations ", ex);
            throw new APIMigrationException(ex);
        } finally {
            APIMgtDBUtil.closeAllConnections(null, connection, null);
        }

        registryService.endTenantFlow();

    }

    private void readTiersAndUpdateDatabase(Tenant tenant, String tierFile, Connection connection, String tableName)
            throws APIMigrationException   {
        String apiTier;
        try {
            apiTier = ResourceUtil.getResourceContent(registryService.getGovernanceRegistryResource(tierFile));
        } catch (UserStoreException ex)    {
            log.error("Error occurred while reading Registry for " + tierFile + ", ", ex);
            throw new APIMigrationException(ex);
        } catch (RegistryException ex)    {
            log.error("Error occurred while reading Registry for " + tierFile + ", ", ex);
            throw new APIMigrationException(ex);
        }

        Document doc = ResourceUtil.buildDocument(apiTier, tierFile);

        if (doc != null) {
            Element rootElement = doc.getDocumentElement();

            Element throttleAssertion = (Element) rootElement
                    .getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                            Constants.TIER_MEDIATOR_THROTTLE_ASSERTION_TAG).item(0);

            NodeList tierNodes = throttleAssertion.getChildNodes();


            for (int i = 0; i < tierNodes.getLength(); ++i) {
                Node tierNode = tierNodes.item(i);

                if (tierNode.getNodeType() == Node.ELEMENT_NODE) {
                    Policy policy = readPolicyFromRegistry(tierNode);
                    updateThrottlingEntriesInDatabase(connection, policy, tenant, tableName);
                }
            }
        }
    }

    private Policy readPolicyFromRegistry(Node tierNode) {
        Element tierTag = (Element) tierNode;

        Node throttleID = tierTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS, Constants.TIER_ID_TAG).item(0);

        Policy policy = new Policy();
        policy.setName(throttleID.getTextContent());
        policy.setType(((Element)throttleID).getAttribute("throttle:type"));

        Element childPolicy = (Element) tierTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                                                                       Constants.TIER_POLICY_TAG).item(0);

        Element controlTag = (Element) childPolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                                                          Constants.TIER_CONTROL_TAG)
                .item(0);

        Element controlPolicyTag = (Element) controlTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                                                                               Constants.TIER_POLICY_TAG)
                .item(0);

        policy.setMaxCount(Integer.valueOf(controlPolicyTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                                                                   Constants.TIER_MAX_COUNT_TAG)
                                                   .item(0).getTextContent()));


        policy.setUnitTime(Long.valueOf(controlPolicyTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                                                                Constants.TIER_UNIT_COUNT_TAG)
                                                .item(0).getTextContent()));

        Element attributePolicy = (Element) controlPolicyTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                                                                                    Constants.TIER_POLICY_TAG)
                .item(0);

        if (attributePolicy != null)    {
            Node billingPlan = attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                                                      Constants.TIER_BILLING_PLAN_TAG).item(0);

            policy.setBillingPlan(billingPlan.getTextContent());

            Node stopOnQuotaReach = attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                                                           Constants.TIER_STOP_ON_QUOTA_TAG).item(0);
            policy.setStopOnQuotaReach(Boolean.valueOf(stopOnQuotaReach.getTextContent()));
        }

        return policy;

    }

    private void updateThrottlingEntriesInDatabase(Connection connection, Policy policy, Tenant tenant, String tableName)
            throws APIMigrationException {

        String entryCheckQuery = "SELECT 1 FROM " + tableName +  " WHERE TENANT_ID = ? AND NAME = ?";
        ResultSet resultSet = null;
        PreparedStatement entryCheckSt = null;
        boolean isPolicyAvailableInDB = false;

        try {
            entryCheckSt = connection.prepareStatement(entryCheckQuery);
            entryCheckSt.setInt(1, tenant.getId());
            entryCheckSt.setString(2, policy.getName());
            resultSet = entryCheckSt.executeQuery();

            if (resultSet.next())   {
                isPolicyAvailableInDB = true;
            }

        } catch (SQLException ex)  {
            log.error("Error occurred while Querying database for table " + tableName + ". " , ex);
            throw new APIMigrationException(ex);
        } finally {
            APIMgtDBUtil.closeAllConnections(entryCheckSt, null, resultSet);
        }

        if (isPolicyAvailableInDB)   {
            log.info("Updating Policy " + policy + " for tenant " + tenant.getId() + " to the table " + tableName);
            PreparedStatement updateStmt = null;

            if (Constants.AM_API_THROTTLE_POLICY.equals(tableName)) {
                String update = "UPDATE "
                       + tableName +
                        " SET DEFAULT_QUOTA = ?, " +
                                "DEFAULT_UNIT_TIME = ?, " +
                                "DEFAULT_TIME_UNIT = ?, " +
                                "APPLICABLE_LEVEL = ?, " +
                                "DEFAULT_QUOTA_TYPE = ?, " +
                                "DESCRIPTION = ? " +
                                "WHERE NAME = ? AND TENANT_ID = ?";
                try {
                    updateStmt = connection.prepareStatement(update);
                    updateStmt.setInt(1, policy.getMaxCount());
                    updateStmt.setInt(2, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    updateStmt.setString(3, Constants.TIER_TIME_UNIT_MINUTE);
                    updateStmt.setString(4, Constants.TIER_API_LEVEL);
                    updateStmt.setString(5, Constants.TIER_REQUEST_COUNT);
                    updateStmt.setString(6, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    updateStmt.setString(7, policy.getName());
                    updateStmt.setInt(8, tenant.getId());
                    updateStmt.execute();
                } catch (SQLException ex)   {
                    log.error("Error occurred while updating database for table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(updateStmt, null, null);
                }
            } else if (Constants.AM_POLICY_SUBSCRIPTION.equals(tableName)){

                String query = "UPDATE " +
                               tableName +
                               " SET QUOTA = ?, " +
                               "UNIT_TIME = ?, " +
                               "TIME_UNIT = ?, " +
                               "BILLING_PLAN = ?, " +
                               "STOP_ON_QUOTA_REACH = ?, " +
                               "DESCRIPTION = ? " +
                               "WHERE NAME = ? AND TENANT_ID = ?";

                try {
                    updateStmt = connection.prepareStatement(query);
                    updateStmt.setInt(1, policy.getMaxCount());
                    updateStmt.setInt(2, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    updateStmt.setString(3, Constants.TIER_TIME_UNIT_MINUTE);
                    if (policy.getBillingPlan() != null)    {
                        updateStmt.setString(4, policy.getBillingPlan());
                    } else {
                        updateStmt.setString(4, Constants.TIER_BILLING_PLAN_FREE);
                    }
                    updateStmt.setBoolean(5, policy.isStopOnQuotaReach());
                    updateStmt.setString(6, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    updateStmt.setString(7, policy.getName());
                    updateStmt.setInt(8, tenant.getId());
                    updateStmt.execute();

                } catch (SQLException ex)   {
                    log.error("Error occurred while updating database for table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(updateStmt, null, null);
                }

            } else {
                String query = "UPDATE " +
                               tableName +
                               " SET QUOTA = ?, " +
                               "UNIT_TIME = ?, " +
                               "TIME_UNIT = ?, " +
                               "DESCRIPTION = ?, " +
                               "IS_DEPLOYED = ?" +
                               " WHERE NAME = ? AND TENANT_ID = ?";


                try {
                    updateStmt = connection.prepareStatement(query);
                    updateStmt.setInt(1, policy.getMaxCount());
                    updateStmt.setInt(2, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    updateStmt.setString(3, Constants.TIER_TIME_UNIT_MINUTE);
                    updateStmt.setString(4, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    updateStmt.setBoolean(5, true);
                    updateStmt.setString(6, policy.getName());
                    updateStmt.setInt(7, tenant.getId());
                    updateStmt.execute();
                } catch (SQLException ex)   {
                    log.error("Error occurred while updating database for table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(updateStmt, null, null);
                }
            }

        } else {
            log.info("Inserting Policy " + policy + " for tenant " + tenant.getId() + " to the table " + tableName);
            PreparedStatement insertSmt = null;

            if (Constants.AM_API_THROTTLE_POLICY.equals(tableName)) {
                String insertQuery = "INSERT INTO " +
                                     tableName +
                                     " (NAME, TENANT_ID, APPLICABLE_LEVEL, DEFAULT_QUOTA, DEFAULT_TIME_UNIT, " +
                                     "DEFAULT_UNIT_TIME, DISPLAY_NAME, DEFAULT_QUOTA_TYPE, DESCRIPTION)" +
                                     " VALUES (?,?,?,?,?,?,?,?,?)";

                try {
                    insertSmt = connection.prepareStatement(insertQuery);
                    insertSmt.setString(1, policy.getName());
                    insertSmt.setInt(2, tenant.getId());
                    insertSmt.setString(3, Constants.TIER_API_LEVEL);
                    insertSmt.setInt(4, policy.getMaxCount());
                    insertSmt.setString(5, Constants.TIER_TIME_UNIT_MINUTE);
                    insertSmt.setInt(6, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    insertSmt.setString(7, policy.getName());
                    insertSmt.setString(8, Constants.TIER_REQUEST_COUNT);
                    insertSmt.setString(9, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    insertSmt.execute();
                } catch (SQLException ex)   {
                    log.error("Error occurred while inserting entry to table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(insertSmt, null, null);
                }
            } else if (Constants.AM_POLICY_SUBSCRIPTION.equals(tableName))    {

                String query = "INSERT INTO " +
                               tableName +
                               " (NAME, DISPLAY_NAME, TENANT_ID, QUOTA, QUOTA_TYPE, UNIT_TIME, TIME_UNIT, " +
                               "BILLING_PLAN, DESCRIPTION) " +
                               "VALUES (?,?,?,?,?,?,?,?,?)";

                try {
                    insertSmt = connection.prepareStatement(query);
                    insertSmt.setString(1, policy.getName());
                    insertSmt.setString(2, policy.getName());
                    insertSmt.setInt(3, tenant.getId());
                    insertSmt.setInt(4, policy.getMaxCount());
                    insertSmt.setString(5, Constants.TIER_REQUEST_COUNT);
                    insertSmt.setInt(6, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    insertSmt.setString(7, Constants.TIER_TIME_UNIT_MINUTE);
                    if(policy.getBillingPlan() != null) {
                        insertSmt.setString(8, policy.getBillingPlan());
                    } else {
                        insertSmt.setString(8, Constants.TIER_BILLING_PLAN_FREE);
                    }
                    insertSmt.setString(9, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    insertSmt.execute();
                } catch (SQLException ex)   {
                    log.error("Error occurred while inserting entry to table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(insertSmt, null, null);
                }
            } else {
                String query = "INSERT INTO " +
                               tableName +
                               " (NAME, DISPLAY_NAME, TENANT_ID, QUOTA, QUOTA_TYPE, UNIT_TIME, TIME_UNIT, " +
                               "DESCRIPTION, IS_DEPLOYED) " +
                               "VALUES (?,?,?,?,?,?,?,?,?)";

                try {
                    insertSmt = connection.prepareStatement(query);
                    insertSmt.setString(1, policy.getName());
                    insertSmt.setString(2, policy.getName());
                    insertSmt.setInt(3, tenant.getId());
                    insertSmt.setInt(4, policy.getMaxCount());
                    insertSmt.setString(5, Constants.TIER_REQUEST_COUNT);
                    insertSmt.setInt(6, safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
                    insertSmt.setString(7, Constants.TIER_TIME_UNIT_MINUTE);
                    insertSmt.setString(8, MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
                    insertSmt.setBoolean(9, true);
                    insertSmt.execute();
                } catch (SQLException ex)   {
                    log.error("Error occurred while inserting entry to table " + tableName + ". " , ex);
                    throw new APIMigrationException(ex);
                } finally {
                    APIMgtDBUtil.closeAllConnections(insertSmt, null, null);
                }
            }
        }
    }

    public static int safeLongToInt(long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                    (l + " cannot be cast to int without changing its value.");
        }
        return (int) l;
    }


}
