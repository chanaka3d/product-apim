/*
*Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*WSO2 Inc. licenses this file to you under the Apache License,
*Version 2.0 (the "License"); you may not use this file except
*in compliance with the License.
*You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
*Unless required by applicable law or agreed to in writing,
*software distributed under the License is distributed on an
*"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*KIND, either express or implied.  See the License for the
*specific language governing permissions and limitations
*under the License.
*/

package org.wso2.am.integration.tests.other;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.testng.annotations.*;
import org.wso2.am.integration.test.utils.APIManagerIntegrationTestException;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.test.utils.http.client.HttpRequestUtil;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;

/**
 * This is the test case to check the inaccessibility of unauthorized users to access swagger import API
 */
@SetEnvironment(executionEnvironments = {ExecutionEnvironment.STANDALONE})
public class APIMUnauthorizedUserSwaggerImport
        extends APIMIntegrationBaseTest {

    private String backendURL;

    private static final Log log = LogFactory.getLog(APIMUnauthorizedUserSwaggerImport.class);
    private Map<String, String> requestHeaders = new HashMap<String, String>();


    @Factory(dataProvider = "userModeDataProvider")
    public APIMUnauthorizedUserSwaggerImport(TestUserMode userMode) {
        this.userMode = userMode;
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][]{new Object[]{TestUserMode.SUPER_TENANT_ADMIN},};
    }

    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        super.init(userMode);
        backendURL = getPublisherURLHttp();
    }

    @Test(groups = {"wso2.am"}, description = "Test inaccessibility of swagger definition import API by unauthorized users")
    public void unauthorizedUserSwaggerImportTest()
            throws Exception {

        String swaggerEndpoint = "http://petstore/petstore.json";
        try {
            HttpResponse httpResponse = HttpRequestUtil.doGet(backendURL +
                    "/publisher/site/blocks/item-design/ajax/import.jag?swagger_url=" + swaggerEndpoint, requestHeaders);
            JSONObject response = new JSONObject(httpResponse.getData());
            assertEquals(response.getBoolean("error"), true, "Response error mismatch");
            assertEquals(response.get("message"), "timeout", "Response error message mismatch");
        } catch (Exception e) {
            throw new APIManagerIntegrationTestException("Exception when invoking swagger definition import API "
                    + ". Error: " + e.getMessage(), e);
        }
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        super.cleanUp();
    }
}
