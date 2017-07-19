/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/
package org.wso2.am.integration.tests.other;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.am.integration.test.utils.clients.APIStoreRestClient;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * This class is used to validate behavior of change password feature in a invalid session
 */
@SetEnvironment(executionEnvironments = { ExecutionEnvironment.STANDALONE })
public class StoreChangePasswordTestCase
        extends APIMIntegrationBaseTest {

    private APIStoreRestClient apiStoreClient;

    Log log = LogFactory.getLog(StoreChangePasswordTestCase.class);

    @BeforeClass(alwaysRun = true) public void setEnvironment() throws Exception {
        super.init(userMode);
        String storeURLHttp = storeUrls.getWebAppURLHttp();
        apiStoreClient = new APIStoreRestClient(storeURLHttp);
        apiStoreClient.login(storeContext.getContextTenant().getContextUser().getUserName(),
                storeContext.getContextTenant().getContextUser().getPassword());
    }

    @Test(groups = {
            "wso2.am" }, description = "Change password in a invalid session")
    public void changePasswordWithInvalidSession()
            throws Exception {

        //When the user is logged out session is expired.
        apiStoreClient.logout();
        HttpResponse serviceResponse = apiStore
                .changePassword(storeContext.getContextTenant().getContextUser().getUserName(),
                        storeContext.getContextTenant().getContextUser().getPassword(), "newPassword!");
        JSONObject response = new JSONObject(serviceResponse.getData());
        assertTrue(response.getBoolean("error"),
                "Should get an error when trying to change password in a invalid session.");
        assertEquals(response.get("message"), "Please login with a valid username/password",
                "Should get an error when trying to change password in a invalid session.");
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        super.cleanUp();
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][] { new Object[] { TestUserMode.SUPER_TENANT_USER } };
    }

    @Factory(dataProvider = "userModeDataProvider")
    public StoreChangePasswordTestCase(TestUserMode userMode) {
        this.userMode = userMode;
    }
}
