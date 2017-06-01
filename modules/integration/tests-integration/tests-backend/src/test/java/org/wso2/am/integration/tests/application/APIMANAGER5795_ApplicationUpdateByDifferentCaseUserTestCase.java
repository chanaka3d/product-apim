/*
 *
 *   Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 * /
 */

package org.wso2.am.integration.tests.application;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.am.integration.test.utils.APIManagerIntegrationTestException;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.test.utils.bean.APIMURLBean;
import org.wso2.am.integration.test.utils.bean.APPKeyRequestGenerator;
import org.wso2.am.integration.test.utils.clients.APIStoreRestClient;
import org.wso2.carbon.automation.engine.context.AutomationContext;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import static org.testng.Assert.assertFalse;

/**
 * Related to Patch Automation  https://wso2.org/jira/browse/APIMANAGER-5795
 * This test checks if a user named "SUBSCRIBER" who logs in to the store as "SUBSCRIBER" to create an Application, is
 * able to login to the store as "subcriber" and be able to update the same Application.
 */

public class APIMANAGER5795_ApplicationUpdateByDifferentCaseUserTestCase extends APIMIntegrationBaseTest {

    private static final Log log = LogFactory.getLog(APIMANAGER5795_ApplicationUpdateByDifferentCaseUserTestCase.class);

    private static final String UPPERCASE_USER_NAME = "SUBSCRIBER";
    private static final String LOWERCASE_USER_NAME = "subscriber";
    private static final String PASSWORD = "test@123";
    private static final String FIRST_NAME = "John";
    private static final String LAST_NAME = "Doe";
    private static final String EMAIL = "john.doe@test.com";
    private static final String APP_NAME = "NewApplication";
    private static final String APP_CALLBACK_URL = "http://werwrw/wewe";
    private static final String APP_DESCRIPTION = "AnewApp";

    @BeforeClass(alwaysRun = true)
    public void init() throws APIManagerIntegrationTestException {
        super.init();
    }

    @Test(description = "Update Application after login with a different case user name")
    public void testApplicationUpdate() throws Exception {
        AutomationContext storeContext = new AutomationContext(APIMIntegrationConstants.AM_PRODUCT_GROUP_NAME,
                APIMIntegrationConstants.AM_STORE_INSTANCE, TestUserMode.SUPER_TENANT_ADMIN);
        APIMURLBean storeUrls = new APIMURLBean(storeContext.getContextUrls());
        String storeURLHttp = storeUrls.getWebAppURLHttp();
        APIStoreRestClient apiStore = new APIStoreRestClient(storeURLHttp);

        // Signup user "SUBSCRIBER"
        HttpResponse storeSignUpResponse = apiStore.signUp(UPPERCASE_USER_NAME, PASSWORD, FIRST_NAME, LAST_NAME, EMAIL);
        JSONObject signUpJsonObject = new JSONObject(storeSignUpResponse.getData());
        assertFalse(signUpJsonObject.getBoolean("error"), "Error in user sign up Response");
        assertFalse(signUpJsonObject.getBoolean("showWorkflowTip"), "Error in sign up Response");

        log.info("Signed Up User: " + UPPERCASE_USER_NAME);

        //login as user "SUBSCRIBER"
        HttpResponse loginResponse = apiStore.login(UPPERCASE_USER_NAME, PASSWORD);
        JSONObject loginJsonObject = new JSONObject(loginResponse.getData());
        assertFalse(loginJsonObject.getBoolean("error"), "Error in Login Request: User Name : " + UPPERCASE_USER_NAME);

        log.info("Logged in as User: " + UPPERCASE_USER_NAME);

        // Create Application
        apiStore.addApplication(APP_NAME,
                APIMIntegrationConstants.APPLICATION_TIER.DEFAULT_APP_POLICY_FIFTY_REQ_PER_MIN, APP_CALLBACK_URL,
                APP_DESCRIPTION);

        //generate the key for the subscription
        APPKeyRequestGenerator generateAppKeyRequest = new APPKeyRequestGenerator(APP_NAME);
        HttpResponse response = apiStore.generateApplicationKey(generateAppKeyRequest);
        verifyResponse(response);

        // Logout
        apiStore.logout();

        //login as user "subscriber"
        loginResponse = apiStore.login(LOWERCASE_USER_NAME, PASSWORD);
        loginJsonObject = new JSONObject(loginResponse.getData());
        assertFalse(loginJsonObject.getBoolean("error"), "Error in Login Request: User Name : " + LOWERCASE_USER_NAME);

        log.info("Logged in as User: " + LOWERCASE_USER_NAME);

        // Update the previously created application
        String keyType = "PRODUCTION";
        String authorizedDomains = "ALL";
        String retryAfterFailure = String.valueOf(false);
        String jsonParams = "{\"grant_types\":\"urn:ietf:params:oauth:grant-type:saml2-bearer,iwa:ntlm\"}";

        String callbackUrl = "http://sdsafd/sada";
        response = apiStore
                .updateClientApplication(APP_NAME, keyType, authorizedDomains, retryAfterFailure, jsonParams,
                        callbackUrl);
        verifyResponse(response);
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        apiStore.login(UPPERCASE_USER_NAME, PASSWORD);
        apiStore.removeApplication(APP_NAME);
        userManagementClient.deleteUser(UPPERCASE_USER_NAME);
        super.cleanUp();
    }
}
