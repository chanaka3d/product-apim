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
import java.net.MalformedURLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.SynapseDTO;
import org.wso2.carbon.apimgt.migration.client._200Specific.ResourceModifier200;
import org.wso2.carbon.apimgt.migration.client._200Specific.model.Policy;
import org.wso2.carbon.apimgt.migration.client.internal.ServiceHolder;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.apimgt.migration.util.ResourceUtil;
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

    public MigrateFrom110to200(String tenantArguments, String blackListTenantArguments, String tenantRange,
            RegistryService registryService, TenantManager tenantManager, boolean removeDecryptionFailedKeysFromDB)
            throws UserStoreException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
    }

    @Override
    public void databaseMigration() throws APIMigrationException, SQLException {
        String amScriptPath = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator;

        updateAPIManagerDatabase(amScriptPath);
    }

    @Override
    public void registryResourceMigration() throws APIMigrationException {
    	swaggerResourceMigration();
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
    
    void swaggerResourceMigration() throws APIMigrationException {
        log.info("Swagger migration for API Manager " + Constants.VERSION_2_0_0 + " started.");

        for (Tenant tenant : getTenantsArray()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Start swaggerResourceMigration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            }

            try {
                registryService.startTenantFlow(tenant);
                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();

                updateSwaggerResources(artifacts, tenant);
            } catch (Exception e) {
                // If any exception happen during a tenant data migration, we continue the other tenants
                log.error("Unable to migrate the swagger resources of tenant : " + tenant.getDomain(), e);
            } finally {
                registryService.endTenantFlow();
            }

            log.debug("End swaggerResourceMigration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        }

        log.info("Swagger resource migration done for all the tenants.");
    }

    private void updateSwaggerResources(GenericArtifact[] artifacts, Tenant tenant) throws APIMigrationException {
        log.debug("Calling updateSwaggerResources");
        log.info("Updating Swagger definition for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        for (GenericArtifact artifact : artifacts) {
            API api = registryService.getAPI(artifact);

            if (api != null) {
                APIIdentifier apiIdentifier = api.getId();
                String apiName = apiIdentifier.getApiName();
                String apiVersion = apiIdentifier.getVersion();
                String apiProviderName = apiIdentifier.getProviderName();
                try {
                    String swaggerlocation = ResourceUtil
                            .getSwagger2ResourceLocation(apiName, apiVersion, apiProviderName);
                    log.debug("Migrating swagger resurce for  : " + apiName + '-' + apiVersion + '-'
                            + apiProviderName);
                    String swaggerDocument = getMigratedSwaggerDefinition(tenant, swaggerlocation, api);
                    
                    if (swaggerDocument != null) {
	                    registryService
	                            .addGovernanceRegistryResource(swaggerlocation, swaggerDocument, "application/json");
	
	                    registryService
	                            .setGovernanceRegistryResourcePermissions(apiProviderName, null, null, swaggerlocation);
                    }
                } catch (RegistryException e) {
                    log.error("Registry error encountered for api " + apiName + '-' + apiVersion + '-' + apiProviderName
                            + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
                } catch (ParseException e) {
                    log.error("Error occurred while parsing swagger document for api " + apiName + '-' + apiVersion
                            + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')',
                            e);
                } catch (UserStoreException e) {
                    log.error(
                            "Error occurred while setting permissions of swagger document for api " + apiName + '-'
                                    + apiVersion + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant
                                    .getDomain() + ')', e);
                } catch (MalformedURLException e) {
                    log.error(
                            "Error occurred while creating swagger document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain()
                                    + ')', e);
                } catch (APIManagementException e) {
                    log.error(
                            "Error occurred while creating swagger document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain()
                                    + ')', e);
                }
            }            
        }
        log.info("End updating Swagger definition for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
    }

    /**
     * This method generates swagger definition for APIM 2.0.0
     *
     * @param tenant            Tenant
     * @param swaggerlocation the location of swagger doc
     * @return JSON string of swagger v2 doc
     * @throws java.net.MalformedURLException
     * @throws org.json.simple.parser.ParseException
     * @throws org.wso2.carbon.registry.core.exceptions.RegistryException
     */

    private String getMigratedSwaggerDefinition(Tenant tenant, String swaggerlocation, API api)
            throws MalformedURLException, ParseException, RegistryException, UserStoreException {
        log.debug("Calling getMigratedSwaggerDefinition");
        JSONParser parser = new JSONParser();

        Object rawResource = registryService.getGovernanceRegistryResource(swaggerlocation);
        if (rawResource == null) {
            return null;
        }
        String swaggerRes = ResourceUtil.getResourceContent(rawResource);
        if (swaggerRes == null) {
        	return null;
        }
        JSONObject swaggerdoc = (JSONObject) parser.parse(swaggerRes);
        JSONObject paths = (JSONObject) swaggerdoc.get(Constants.SWAGGER_PATHS);
        if (paths != null) {
        	Set<Map.Entry> res = paths.entrySet();
            for (Map.Entry e : res) {
                JSONObject methods = (JSONObject) e.getValue();
                Set<Map.Entry> mes = methods.entrySet();
                for (Map.Entry m : mes) {
                    if (!(m.getValue() instanceof JSONObject)) {
                        log.warn("path is expected to be json but string found on " + swaggerlocation);
                        continue;
                    }
                    JSONObject re = (JSONObject) m.getValue();
                    //setting produce type as array
                    Object produceObj = re.get(Constants.SWAGGER_PRODUCES);
                    if (produceObj != null && !(produceObj instanceof JSONArray)) {
                        JSONArray prodArr = new JSONArray();
                        prodArr.add((String) produceObj);
                        re.put(Constants.SWAGGER_PRODUCES, prodArr);
                    }

                    //for resources parameters schema changing
                    JSONArray parameters = (JSONArray) re.get(Constants.SWAGGER_PATH_PARAMETERS_KEY);
                    if (parameters != null) {
                        for (int i = 0; i < parameters.size(); i++) {
                            JSONObject parameterObj = (JSONObject) parameters.get(i);
                            JSONObject schemaObj = (JSONObject) parameterObj.get(Constants.SWAGGER_BODY_SCHEMA);
                            if (schemaObj != null) {
                                JSONObject propertiesObj = (JSONObject) schemaObj.get(Constants.SWAGGER_PROPERTIES_KEY);
                                if (propertiesObj == null) {
                                    JSONObject propObj = new JSONObject();
                                    JSONObject payObj = new JSONObject();
                                    payObj.put(Constants.SWAGGER_PARAM_TYPE, Constants.SWAGGER_STRING_TYPE);
                                    propObj.put(Constants.SWAGGER_PAYLOAD_KEY, payObj);
                                    schemaObj.put(Constants.SWAGGER_PROPERTIES_KEY, propObj);
                                }
                            }
                        }
                    }

                    if (re.get(Constants.SWAGGER_RESPONSES) instanceof JSONObject) {
                    	//for resources response object
                        JSONObject responses = (JSONObject) re.get(Constants.SWAGGER_RESPONSES);
                        if (responses == null) {
                            log.warn("responses attribute not present in swagger " + swaggerlocation);
                            continue;
                        }
                        JSONObject response;
                        Iterator itr = responses.keySet().iterator();
                        while (itr.hasNext()) {
                            String key = (String) itr.next();
                            if (responses.get(key) instanceof JSONObject) {
    	                        response = (JSONObject) responses.get(key);
    	                        boolean isExist = response.containsKey(Constants.SWAGGER_DESCRIPTION);
    	                        if (!isExist) {
    	                            response.put(Constants.SWAGGER_DESCRIPTION, "");
    	                        }
                            }
                        }
                    } else {
                    	log.error("Invalid Swagger responses element found in " + swaggerlocation);
                    }
                }
            }
        }        
        return swaggerdoc.toJSONString();
    }

    public static int safeLongToInt(long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                    (l + " cannot be cast to int without changing its value.");
        }
        return (int) l;
    }


}
