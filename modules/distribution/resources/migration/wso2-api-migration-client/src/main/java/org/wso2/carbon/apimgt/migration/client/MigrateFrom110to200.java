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


import org.apache.commons.io.IOUtils;
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
import org.wso2.carbon.apimgt.api.model.APIStatus;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.client._110Specific.ResourceModifier;
import org.wso2.carbon.apimgt.migration.client._200Specific.ResourceModifier200;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.*;
import org.wso2.carbon.apimgt.migration.client._200Specific.model.Policy;
import org.wso2.carbon.apimgt.migration.client.internal.ServiceHolder;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.apimgt.migration.util.ResourceUtil;
import org.wso2.carbon.apimgt.migration.util.StatDBUtil;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.FileUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.xml.stream.XMLStreamException;

import java.io.*;
import java.net.MalformedURLException;
import java.sql.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MigrateFrom110to200 extends MigrationClientBase implements MigrationClient {

    private static final Log log = LogFactory.getLog(MigrateFrom110to200.class);
    private RegistryService registryService;
    private boolean removeDecryptionFailedKeysFromDB;

    private static class AccessTokenInfo {
        AccessTokenInfo(String usernameWithoutDomain, String authzUser) {
            this.usernameWithoutDomain = usernameWithoutDomain;
            this.authzUser = authzUser;
        }
        public String usernameWithoutDomain;
        public String authzUser;
    }

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

    /**
     * This method is used to workflow-extensions.xml configuration by handling the addition of the executors
     * to handle work flow executors
     *
     * @throws APIMigrationException
     */
    private void workflowExtensionsMigration() throws APIMigrationException {
        log.info("Workflow Extensions configuration file migration for API Manager started.");
        for (Tenant tenant : getTenantsArray()) {
            log.info("Start workflow extensions configuration migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

            try {
                registryService.startTenantFlow(tenant);

                if (!registryService.isGovernanceRegistryResourceExists(APIConstants.WORKFLOW_EXECUTOR_LOCATION)) {
                    log.debug("Workflow extensions resource does not exist for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                    continue;
                }

                String workFlowExtensions = ResourceUtil.getResourceContent(registryService.getGovernanceRegistryResource(
                                                                            APIConstants.WORKFLOW_EXECUTOR_LOCATION));
                String updatedWorkFlowExtensions = ResourceModifier.modifyWorkFlowExtensions(workFlowExtensions);
                registryService.updateGovernanceRegistryResource(
                                                    APIConstants.WORKFLOW_EXECUTOR_LOCATION, updatedWorkFlowExtensions);
            } catch (RegistryException e) {
                log.error("Error occurred while accessing the registry for tenant " + tenant.getId() + 
                          '(' + tenant.getDomain() + ')', e);
            } catch (UserStoreException e) {
                log.error("Error occurred while accessing the user store " + tenant.getId() + 
                          '(' + tenant.getDomain() + ')', e);
            }
            finally {
                registryService.endTenantFlow();
            }

            log.info("End workflow extensions configuration migration for tenant " + tenant.getId() 
                      + '(' + tenant.getDomain() + ')');
        }

        log.info("Workflow Extensions configuration file migration done for all the tenants");
    }

    private void updateTiers() throws APIMigrationException {
        log.info("Tiers configuration migration for API Manager started.");

        for (Tenant tenant : getTenantsArray()) {
            log.info("Start tiers configuration migration for tenant " + tenant.getId() 
                      + '(' + tenant.getDomain() + ')');

            try {
                registryService.startTenantFlow(tenant);

                if (!registryService.isGovernanceRegistryResourceExists(APIConstants.API_TIER_LOCATION)) {
                    continue;
                } else {
                    String apiTiers = ResourceUtil.getResourceContent(
                            registryService.getGovernanceRegistryResource(APIConstants.API_TIER_LOCATION));

                    String updatedApiTiers = ResourceModifier.modifyTiers(apiTiers, APIConstants.API_TIER_LOCATION);
                    registryService.updateGovernanceRegistryResource(
                                                    APIConstants.API_TIER_LOCATION, updatedApiTiers);
                }

                // Since API tiers.xml has been updated it will be used as app and resource tier.xml
                if (!registryService.isGovernanceRegistryResourceExists(APIConstants.APP_TIER_LOCATION)) {
                    String apiTiers = ResourceUtil.getResourceContent(
                            registryService.getGovernanceRegistryResource(APIConstants.API_TIER_LOCATION));

                    registryService.addGovernanceRegistryResource(
                                                    APIConstants.APP_TIER_LOCATION, apiTiers, "application/xml");
                }

                if (!registryService.isGovernanceRegistryResourceExists(APIConstants.RES_TIER_LOCATION)) {
                    String apiTiers = ResourceUtil.getResourceContent(
                            registryService.getGovernanceRegistryResource(APIConstants.API_TIER_LOCATION));

                    registryService.addGovernanceRegistryResource(
                                                    APIConstants.RES_TIER_LOCATION, apiTiers, "application/xml");
                }
            } catch (UserStoreException e) {
                log.error("Error occurred while accessing the user store " + + tenant.getId() + 
                          '(' + tenant.getDomain() + ')', e);
            } catch (RegistryException e) {
                log.error("Error occurred while accessing the registry " + + tenant.getId() + 
                                             '(' + tenant.getDomain() + ')', e);
            }
            finally {
                registryService.endTenantFlow();
            }
            log.info("End tiers configuration migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        }
        log.info("Tiers configuration migration for API Manager completed.");
    }

    private void migrateLifeCycles() throws APIMigrationException {
        log.info("Life Cycles executor migration for API Manager started.");

        String apiLifeCycleXMLPath = CarbonUtils.getCarbonHome() + File.separator + APIConstants.RESOURCE_FOLDER_LOCATION +
                File.separator + Constants.LIFE_CYCLES_FOLDER + File.separator +
                APIConstants.API_LIFE_CYCLE + ".xml";
        String executorlessApiLifeCycle = null;
        String apiLifeCycle = null;

        try (FileInputStream fileInputStream = new FileInputStream(new File(apiLifeCycleXMLPath))) {
            apiLifeCycle = IOUtils.toString(fileInputStream);

            executorlessApiLifeCycle = ResourceModifier.removeExecutorsFromAPILifeCycle(apiLifeCycle);
        } catch (FileNotFoundException e) {
            ResourceUtil.handleException("File " + apiLifeCycleXMLPath + " not found", e);
        } catch (IOException e) {
            ResourceUtil.handleException("Error reading file " + apiLifeCycleXMLPath, e);
        }

        final String apiLifeCycleRegistryPath = RegistryConstants.LIFECYCLE_CONFIGURATION_PATH +
                APIConstants.API_LIFE_CYCLE;

        for (Tenant tenant : getTenantsArray()) {
            log.info("Start life cycle migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            try {
                registryService.startTenantFlow(tenant);

                addExecutorlessLifeCycle(tenant, apiLifeCycleRegistryPath, executorlessApiLifeCycle);

                updateApiLifeCycleStatus(tenant);

                updateWithCompleteLifeCycle(tenant, apiLifeCycleRegistryPath, apiLifeCycle);
            }
            finally {
                registryService.endTenantFlow();
            }
            log.info("End life cycle migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        }
        log.info("Life Cycles executor migration for API Manager completed.");
    }

    private void addExecutorlessLifeCycle(Tenant tenant, String apiLifeCycleRegistryPath,
                                          String executorlessApiLifeCycle) throws APIMigrationException {
        try {
            log.debug("Adding executorless LC for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            if (!registryService.isConfigRegistryResourceExists(apiLifeCycleRegistryPath)) {
                registryService.addConfigRegistryResource(apiLifeCycleRegistryPath, executorlessApiLifeCycle,
                        "application/xml");
            }
            else {
                registryService.updateConfigRegistryResource(apiLifeCycleRegistryPath, executorlessApiLifeCycle);
            }
            log.debug("Completed adding executorless LC for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        } catch (UserStoreException e) {
            log.error("Error occurred while accessing the user store when adding executorless " +
                    APIConstants.API_LIFE_CYCLE + " for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        } catch (RegistryException e) {
            log.error("Error occurred while accessing the registry when adding executorless " +
                    APIConstants.API_LIFE_CYCLE + " for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        }
    }

    private void updateApiLifeCycleStatus(Tenant tenant) throws APIMigrationException {
        HashMap<String, String[]> statuses = new HashMap<>();
        statuses.put(APIStatus.PUBLISHED.toString(), new String[]{"Publish"});
        statuses.put(APIStatus.PROTOTYPED.toString(), new String[]{"Deploy as a Prototype"});
        statuses.put(APIStatus.BLOCKED.toString(), new String[]{"Publish", "Block"});
        statuses.put(APIStatus.DEPRECATED.toString(), new String[]{"Publish", "Deprecate"});
        statuses.put(APIStatus.RETIRED.toString(), new String[]{"Publish", "Deprecate", "Retire"});

        log.debug("Updating LC status for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        try {
            registryService.addDefaultLifecycles();
            GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();

            for (GenericArtifact artifact : artifacts) {
                String currentState = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);
                //Check whether API is already migrated
                if (currentState != null && !currentState.equalsIgnoreCase(artifact.getLifecycleState())) {                    
                    artifact.attachLifecycle(APIConstants.API_LIFE_CYCLE);
                    String[] actions = statuses.get(currentState);

                    if (actions != null) {
                        for (String action : actions) {
                            artifact.invokeAction(action, APIConstants.API_LIFE_CYCLE);
                            if (log.isDebugEnabled()) {
                                log.debug("Target LC Status : " + currentState + ". Performing LC Action : " + action);
                            }
                        }
                    }
                } else {
                    log.info("API is already in target LC state: " + currentState + ". Skipping migration for API " +
                            artifact.getAttribute(APIConstants.API_OVERVIEW_NAME));
                }                
            }
            log.debug("Completed updating LC status for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        }  catch (RegistryException e) {
            log.error("Error occurred while accessing the registry when updating " +
                    "API life cycles for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        } catch (XMLStreamException e) {
            log.error("XMLStreamException while adding default life cycles if " +
                    "not available for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        } catch (FileNotFoundException e) {
            log.error("FileNotFoundException while adding default life cycles if " +
                    "not available for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        } catch (UserStoreException e) {
            log.error("UserStoreException while adding default life cycles if " +
                    "not available for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        }
    }

    private void updateWithCompleteLifeCycle(Tenant tenant, String apiLifeCycleRegistryPath, String apiLifeCycle)
                                                                throws APIMigrationException {
        log.debug("Update with complete LC for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        try {
            registryService.updateConfigRegistryResource(apiLifeCycleRegistryPath, apiLifeCycle);
            log.debug("Completed update with complete LC for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        } catch (UserStoreException e) {
            log.error("Error occurred while accessing the user store when adding complete " +
                    APIConstants.API_LIFE_CYCLE + " for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        } catch (RegistryException e) {
            log.error("Error occurred while accessing the registry when adding complete " +
                    APIConstants.API_LIFE_CYCLE + " for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
        }
    }

    /**
     * This method is used to migrate swagger v1.2 resources to swagger v2.0 resource
     * This reads the swagger v1.2 doc from the registry and creates swagger v2.0 doc
     *
     * @throws APIMigrationException
     */
    void swaggerResourceMigration() throws APIMigrationException {
        log.info("Swagger migration for API Manager " + Constants.VERSION_1_10 + " started.");

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
        for (GenericArtifact artifact : artifacts) {
            API api = registryService.getAPI(artifact);

            if (api != null) {
                APIIdentifier apiIdentifier = api.getId();
                String apiName = apiIdentifier.getApiName();
                String apiVersion = apiIdentifier.getVersion();
                String apiProviderName = apiIdentifier.getProviderName();
                try {
                    String swagger2location = ResourceUtil
                            .getSwagger2ResourceLocation(apiName, apiVersion, apiProviderName);
                    log.debug("Creating swagger v2.0 resource using v1.2 for : " + apiName + '-' + apiVersion + '-'
                            + apiProviderName);
                    String swagger2Document = getSwagger2docUsingSwagger12RegistryResources(tenant, swagger2location, api);

                    registryService
                            .addGovernanceRegistryResource(swagger2location, swagger2Document, "application/json");

                    registryService
                            .setGovernanceRegistryResourcePermissions(apiProviderName, null, null, swagger2location);
                } catch (RegistryException e) {
                    log.error("Registry error encountered for api " + apiName + '-' + apiVersion + '-' + apiProviderName
                            + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
                } catch (ParseException e) {
                    log.error("Error occurred while parsing swagger v1.2 document for api " + apiName + '-' + apiVersion
                            + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')',
                            e);
                } catch (UserStoreException e) {
                    log.error(
                            "Error occurred while setting permissions of swagger v2.0 document for api " + apiName + '-'
                                    + apiVersion + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant
                                    .getDomain() + ')', e);
                } catch (MalformedURLException e) {
                    log.error(
                            "Error occurred while creating swagger v2.0 document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain()
                                    + ')', e);
                } catch (APIManagementException e) {
                    log.error(
                            "Error occurred while creating swagger v2.0 document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain()
                                    + ')', e);
                }
            }
        }
    }

    /**
     * This method generates swagger v2 doc using swagger 1.2 doc
     *
     * @param tenant            Tenant
     * @param swagger12location the location of swagger 1.2 doc
     * @return JSON string of swagger v2 doc
     * @throws java.net.MalformedURLException
     * @throws org.json.simple.parser.ParseException
     * @throws org.wso2.carbon.registry.core.exceptions.RegistryException
     */

    private String getSwagger2docUsingSwagger12RegistryResources(Tenant tenant, String swagger12location, API api)
            throws MalformedURLException, ParseException, RegistryException, UserStoreException {
        log.debug("Calling getSwagger2docUsingSwagger12RegistryResources");
        JSONParser parser = new JSONParser();

        Object rawResource = registryService.getGovernanceRegistryResource(swagger12location);
        if (rawResource == null) {
            return "";
        }
        String swaggerRes = ResourceUtil.getResourceContent(rawResource);
        JSONObject swagger12doc = (JSONObject) parser.parse(swaggerRes);
        Object existSecDef = swagger12doc.get(Constants.SECURITY_DEFINITION__KEY);
        if (existSecDef == null) {
            JSONObject existScopes=new JSONObject();
            JSONObject xWso2Security = (JSONObject) swagger12doc.get(Constants.SWAGGER_X_WSO2_SECURITY);
            if (xWso2Security == null) {
                log.info("Security definition 'x-wso2-security' exist in API " + swagger12location
                        + " using default Security definitions");
            } else {
                JSONArray scopes = (JSONArray) ((JSONObject) xWso2Security.get(Constants.SWAGGER_OBJECT_NAME_APIM))
                        .get(Constants.SWAGGER_X_WSO2_SCOPES);
                for (int i = 0; i < scopes.size(); i++) {
                    JSONObject scope = (JSONObject) scopes.get(i);
                    existScopes.put(scope.get(Constants.SECURITY_DEFINITION_SCOPE_NAME),
                            scope.get(Constants.SECURITY_DEFINITION_SCOPE_KEY));
                }
            }
            JSONObject sec = generateSecurityDefinitionsObject(api.getId().getApiName(), existScopes);
            swagger12doc.put(Constants.SECURITY_DEFINITION__KEY, sec);
        } else {
            log.info("Security definition already exist in API " + swagger12location);
        }
        JSONObject paths = (JSONObject) swagger12doc.get(Constants.SWAGGER_PATHS);
        Set<Map.Entry> res = paths.entrySet();
        for (Map.Entry e : res) {
            JSONObject methods = (JSONObject) e.getValue();
            Set<Map.Entry> mes = methods.entrySet();
            for (Map.Entry m : mes) {
                if (!(m.getValue() instanceof JSONObject)) {
                    log.warn("path is expected to be json but string found on " + swagger12location);
                    continue;
                }
                JSONObject re = (JSONObject) m.getValue();
                JSONObject xWso2Security = (JSONObject) swagger12doc.get(Constants.SWAGGER_X_WSO2_SECURITY);
                JSONArray scopes = new JSONArray();
                if (xWso2Security != null) {
                    scopes = (JSONArray) ((JSONObject) xWso2Security.get(Constants.SWAGGER_OBJECT_NAME_APIM))
                            .get(Constants.SWAGGER_X_WSO2_SCOPES);
                }
                JSONArray scopeList = new JSONArray();
                for (int i = 0; i < scopes.size(); i++) {
                    JSONObject scope = (JSONObject) scopes.get(i);
                    scopeList.add(scope.get(Constants.SECURITY_DEFINITION_SCOPE_NAME));
                }
                JSONArray authScopeArray = new JSONArray();
                JSONObject authScopeObj = new JSONObject();
//                authScopeObj.put(api.getId().getApiName() + Constants.SECURITY_DEFINITION_NAME_KEY_SUFFIX, scopeList);
                authScopeObj.put("default", scopeList);
                authScopeArray.add(authScopeObj);
                re.put(Constants.SWAGGER_PATH_SECURITY_KEY, authScopeArray);

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

                //for resources response object
                JSONObject responses = (JSONObject) re.get(Constants.SWAGGER_RESPONSES);
                if (responses == null) {
                    log.warn("responses attribute not present in swagger " + swagger12location);
                    continue;
                }
                JSONObject response;
                Iterator itr = responses.keySet().iterator();
                while (itr.hasNext()) {
                    String key = (String) itr.next();
                    response = (JSONObject) responses.get(key);
                    boolean isExist = response.containsKey(Constants.SWAGGER_DESCRIPTION);
                    if (!isExist) {
                        response.put(Constants.SWAGGER_DESCRIPTION, "");
                    }
                }
            }
        }
        return swagger12doc.toJSONString();
    }

    /**
     * Generate swagger v2 security definition object
     * See <a href="https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#securityDefinitionsObject">
     * Swagger v2 definition object</a>
     *
     * @return security definition object
     * @throws ParseException
     */
    private static JSONObject generateSecurityDefinitionsObject(String apiName, JSONObject scopes)
            throws ParseException {
        log.debug("Calling generateSecurityDefinitionsObject");
        JSONObject sec = new JSONObject();
        sec.put(Constants.SECURITY_DEFINITION_TYPE_KEY, Constants.SECURITY_DEFINITION_TYPE_AUTH2);
        String url = ServiceHolder.getAPIManagerConfigurationService().getAPIManagerConfiguration()
                .getFirstProperty(Constants.REVOKE_URL_CONFIG_PATH);
        sec.put(Constants.SECURITY_DEFINITION_AUTHORIZATION_URL_KEY, url.replace("revoke", "token"));
        sec.put(Constants.SECURITY_DEFINITION_FLOW_KEY, Constants.SECURITY_DEFINITION_DEFAULT_GRANT_TYPES);
        sec.put(Constants.SECURITY_DEFINITION_SCOPES_KEY, scopes);
        JSONObject securityDefinitionObject = new JSONObject();
//        securityDefinitionObject.put(apiName+Constants.SECURITY_DEFINITION_NAME_KEY_SUFFIX, sec);
        securityDefinitionObject.put("default", sec);
        return securityDefinitionObject;
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
