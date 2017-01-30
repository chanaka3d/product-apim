/*
 *
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */


package org.wso2.am.integration.tests.other;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.test.utils.bean.APICreationRequestBean;
import org.wso2.am.integration.test.utils.bean.APIResourceBean;
import org.wso2.am.integration.test.utils.bean.SubscriptionRequest;
import org.wso2.am.integration.test.utils.clients.APIPublisherRestClient;
import org.wso2.am.integration.test.utils.clients.APIStoreRestClient;
import org.wso2.am.integration.tests.api.lifecycle.APIManagerLifecycleBaseTest;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.identity.user.registration.stub.dto.UserDTO;
import org.wso2.carbon.identity.user.registration.stub.dto.UserFieldDTO;
import org.wso2.carbon.integration.common.admin.client.UserManagementClient;
import org.wso2.carbon.integration.common.utils.mgt.ServerConfigurationManager;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;

@SetEnvironment(executionEnvironments = {ExecutionEnvironment.STANDALONE})
public class APIMANAGER5433SharingApplicationTestCase extends APIManagerLifecycleBaseTest {

    private String publisherURLHttp;
    private APIPublisherRestClient apiPublisher;

    private static final String API_NAME = "APIMANAGER5433SharingApplicationTestCase";
    private static final String APPLICATION_NAME_1 = "APIMANAGER5433SharingApplicationTestCase";
    private static final String API_CONTEXT = "APIMANAGER5433SharingApplicationTestCase";
    private static final String API_VERSION = "1.0.0";
    private static final String TAGS = "ACAC, cors, test";
    private static final String DESCRIPTION = "This is test API create by API manager integration test";
    public static final String API_SUBSCRIBE = "/permission/admin/manage/api/subscribe";

    private ServerConfigurationManager serverConfigurationManager;

    Log log = LogFactory.getLog(APIMANAGER5433SharingApplicationTestCase.class);

    @BeforeTest(alwaysRun = true)
    public void setEnvironment() throws Exception {
        super.init(userMode);
        serverConfigurationManager = new ServerConfigurationManager(superTenantKeyManagerContext);
    }

    public static Object[][] storeUserSignUpCredentialsDataProvider() throws Exception {

        return new Object[][] {
                {"user1shairingapplication", "user1pass", "firstName", "lastNmae", "applicationSharing.com",
                        "user1@wso2.com"},
                {"user2shairingapplication", "user2pass", "firstName", "lastNmae", "applicationSharing.com",
                        "user1@wso2.com"}
        };
    }

    @Test(groups = {"wso2.am"}, description = "Checking subscription shared from the user get replicate on other side")
    public void CheckSubscriptionSharedWithinOrganizationTest() throws Exception {
        if (TestUserMode.SUPER_TENANT_ADMIN == userMode) {
            serverConfigurationManager.applyConfigurationWithoutRestart(new File(getAMResourceLocation() + File
                    .separator + "configFiles" + File.separator + "shairing" + File.separator + "api-manager.xml"));
            serverConfigurationManager.restartGracefully();
            super.init();
        }
        publisherURLHttp = getPublisherURLHttp();
        apiPublisher = new APIPublisherRestClient(publisherURLHttp);
        apiPublisher.login(user.getUserName(), user.getPassword());
        userManagementClient = new UserManagementClient(keyManagerContext.getContextUrls()
                .getBackEndUrl(), user.getUserName(), user.getPassword());
        String providerName = user.getUserName();
        URL endpointUrl = new URL(getSuperTenantAPIInvocationURLHttp("response", "1.0.0"));
        ArrayList<APIResourceBean> resourceBeanList = new ArrayList<APIResourceBean>();
        resourceBeanList.add(new APIResourceBean(APIMIntegrationConstants.HTTP_VERB_GET,
                APIMIntegrationConstants.RESOURCE_AUTH_TYPE_APPLICATION_AND_APPLICATION_USER,
                APIMIntegrationConstants.RESOURCE_TIER.UNLIMITED, "/*"));
        APICreationRequestBean apiCreationRequestBean = new APICreationRequestBean(API_NAME, API_CONTEXT,
                API_VERSION, providerName,
                endpointUrl, resourceBeanList);
        apiCreationRequestBean.setTags(TAGS);
        apiCreationRequestBean.setDescription(DESCRIPTION);
        String publisherURLHttp = getPublisherURLHttp();
        String storeURLHttp = getStoreURLHttp();
        APIPublisherRestClient apiPublisherClientUser1 = new APIPublisherRestClient(publisherURLHttp);
        APIStoreRestClient apiStoreClientUser1 = new APIStoreRestClient(storeURLHttp);
        //Login to API Publisher with admin
        apiPublisherClientUser1.login(user.getUserName(), user.getPassword());
        APIIdentifier apiIdentifier = new APIIdentifier(providerName, API_NAME, API_VERSION);
        apiIdentifier.setTier(APIMIntegrationConstants.API_TIER.GOLD);
        createAndPublishAPI(apiIdentifier, apiCreationRequestBean, apiPublisherClientUser1, false);
        for (int i = 0; i < storeUserSignUpCredentialsDataProvider().length; i++) {
            String username = storeUserSignUpCredentialsDataProvider()[i][0].toString();
            String password = storeUserSignUpCredentialsDataProvider()[i][1].toString();
            String firstName = storeUserSignUpCredentialsDataProvider()[i][2].toString();
            String lastName = storeUserSignUpCredentialsDataProvider()[i][3].toString();
            String organization = storeUserSignUpCredentialsDataProvider()[i][4].toString();
            String email = storeUserSignUpCredentialsDataProvider()[i][5].toString();
            UserDTO userDTO = new UserDTO();
            if (userMode == TestUserMode.SUPER_TENANT_USER) {
                userDTO.setUserName(username);
            } else {
                userDTO.setUserName(username.concat("@").concat(user.getUserDomain()));
            }
            userDTO.setPassword(password);
            UserFieldDTO[] userFieldDTOs = userRegistrationAdminClient.getOrderedUserFieldsDto();
            for (UserFieldDTO userFieldDTO : userFieldDTOs) {
                if ("firstname".equalsIgnoreCase(userFieldDTO.getFieldName())) {
                    userFieldDTO.setFieldValue(firstName);
                } else if ("lastname".equalsIgnoreCase(userFieldDTO.getFieldName())) {
                    userFieldDTO.setFieldValue(lastName);
                } else if ("email".equalsIgnoreCase(userFieldDTO.getFieldName())) {
                    userFieldDTO.setFieldValue(email);
                } else if ("organization".equalsIgnoreCase(userFieldDTO.getFieldName())) {
                    userFieldDTO.setFieldValue(organization);
                } else {
                    userFieldDTO.setFieldValue("");
                }
            }
            log.info("Tenant domain is ===1" + user.getUserDomain());
            userDTO.setUserFields(userFieldDTOs);
            userRegistrationAdminClient.addUser(userDTO);
            String[] subscriberPermissions = new String[] {"/permission/admin/login", API_SUBSCRIBE};
            if (!userManagementClient.roleNameExists("Internal/subscriber")) {
                userManagementClient.addInternalRole("subscriber", new String[] {username}, subscriberPermissions);
            } else {
                userManagementClient.addRemoveRolesOfUser(username, new String[] {"Internal/subscriber"}, new
                        String[] {});
            }
        }
        apiStoreClientUser1.login(storeUserSignUpCredentialsDataProvider()[0][0].toString().concat("@").concat(user
                .getUserDomain()), storeUserSignUpCredentialsDataProvider()[0][1].toString());
        apiStoreClientUser1.addApplication(APPLICATION_NAME_1, APIMIntegrationConstants.APPLICATION_TIER
                .DEFAULT_APP_POLICY_FIFTY_REQ_PER_MIN, "", "");
        apiStoreClientUser1.subscribe(new SubscriptionRequest(API_NAME, API_VERSION, providerName,
                APPLICATION_NAME_1, APIMIntegrationConstants.API_TIER.GOLD));
        JSONObject subscribedApi = new JSONObject(apiStoreClientUser1.getSubscribedAPIsForApplication
                (APPLICATION_NAME_1).getData());
        log.info("Tenant domain==" + user.getUserDomain());
        log.info("subscribedApis===" + subscribedApi.toString());
        Assert.assertTrue(checkApiSubscriptionExist(apiIdentifier, subscribedApi), "API Subscribed successfully");
        apiStoreClientUser1.logout();
        apiStoreClientUser1.login(storeUserSignUpCredentialsDataProvider()[1][0].toString().concat("@").concat(user
                .getUserDomain()), storeUserSignUpCredentialsDataProvider()[1][1].toString());
        JSONObject subscribedApiInUser2 = new JSONObject(apiStoreClientUser1.getSubscribedAPIsForApplication
                (APPLICATION_NAME_1).getData());
        log.info("subscribedApis2===" + subscribedApi.toString());
        Assert.assertTrue(checkApiSubscriptionExist(apiIdentifier, subscribedApiInUser2), "API Subscribed " +
                "successfully in user2");
    }

    @AfterTest(alwaysRun = true)
    public void destroy() throws Exception {
        if (TestUserMode.SUPER_TENANT_ADMIN == userMode) {
            serverConfigurationManager.applyConfiguration(new File(getAMResourceLocation() + File
                    .separator + "configFiles" + File.separator + "shairing" + File.separator + "original" + File
                    .separator + "api-manager.xml"));
            serverConfigurationManager.restartGracefully();
        }
        super.cleanUp();
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][] {new Object[] {TestUserMode.SUPER_TENANT_ADMIN},
                new Object[] {TestUserMode.TENANT_ADMIN}};
    }

    @Factory(dataProvider = "userModeDataProvider")
    public APIMANAGER5433SharingApplicationTestCase(TestUserMode userMode) {
        this.userMode = userMode;
    }

    private boolean checkApiSubscriptionExist(APIIdentifier apiIdentifier, JSONObject jsonObject) throws JSONException {
        JSONArray apiArray = jsonObject.getJSONArray("apis");

        for (int i = 0; i < apiArray.length(); i++) {
            JSONObject apiSubscription = apiArray.getJSONObject(i);
            if (apiIdentifier.getApiName().equals(apiSubscription.getString("apiName")) &&
                    apiIdentifier.getVersion().equals(apiSubscription.getString("apiVersion")) &&
                    apiIdentifier.getProviderName().equals(apiSubscription.getString("apiProvider"))) {
                return true;
            }
        }
        return false;
    }

}
