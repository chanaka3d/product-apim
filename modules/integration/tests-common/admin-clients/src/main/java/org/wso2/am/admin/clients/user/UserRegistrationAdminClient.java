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
package org.wso2.am.admin.clients.user;

import org.apache.axis2.AxisFault;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.wso2.carbon.identity.user.registration.stub.UserRegistrationAdminServiceException;
import org.wso2.carbon.identity.user.registration.stub.UserRegistrationAdminServiceIdentityException;
import org.wso2.carbon.identity.user.registration.stub.UserRegistrationAdminServiceStub;
import org.wso2.carbon.identity.user.registration.stub.dto.UserDTO;
import org.wso2.carbon.identity.user.registration.stub.dto.UserFieldDTO;
import org.wso2.carbon.user.core.UserCoreConstants;

import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Comparator;

public class UserRegistrationAdminClient {
    private final String serviceName = "UserRegistrationAdminService";
    private UserRegistrationAdminServiceStub userRegistrationAdminServiceStub;


    /**
     * @param backEndUrl
     * @param username
     * @param password
     * @throws AxisFault
     */
    public UserRegistrationAdminClient(String backEndUrl, String username,String password) throws AxisFault {
        String endPoint = backEndUrl + serviceName;
        userRegistrationAdminServiceStub = new UserRegistrationAdminServiceStub(endPoint);
        ServiceClient client = userRegistrationAdminServiceStub._getServiceClient();
        Options option = client.getOptions();
        option.setUserName(username);
        option.setPassword(password);
        option.setManageSession(true);
    }

    public void addUser(UserDTO userDTO) throws RemoteException, UserRegistrationAdminServiceException {
        userRegistrationAdminServiceStub.addUser(userDTO);
    }

    public UserFieldDTO[] getOrderedUserFieldsDto() throws UserRegistrationAdminServiceIdentityException, RemoteException {

        UserFieldDTO[] userFields = userRegistrationAdminServiceStub.readUserFieldsForUserRegistration
                (UserCoreConstants.DEFAULT_CARBON_DIALECT);
        Arrays.sort(userFields, new RequiredUserFieldComparator());
        Arrays.sort(userFields, new UserFieldComparator());
        return userFields;
    }

    public static class RequiredUserFieldComparator implements Comparator<UserFieldDTO> {

        public int compare(UserFieldDTO filed1, UserFieldDTO filed2) {
            if (filed1.getDisplayOrder() == 0) {
                filed1.setDisplayOrder(Integer.MAX_VALUE);
            }

            if (filed2.getDisplayOrder() == 0) {
                filed2.setDisplayOrder(Integer.MAX_VALUE);
            }

            if (!filed1.getRequired() && filed2.getRequired()) {
                return 1;
            }

            if (filed1.getRequired() && filed2.getRequired()) {
                return 0;
            }

            if (filed1.getRequired() && !filed2.getRequired()) {
                return -1;
            }

            return 0;
        }

    }

    public static class UserFieldComparator implements Comparator<UserFieldDTO> {

        public int compare(UserFieldDTO filed1, UserFieldDTO filed2) {
            if (filed1.getDisplayOrder() == 0) {
                filed1.setDisplayOrder(Integer.MAX_VALUE);
            }

            if (filed2.getDisplayOrder() == 0) {
                filed2.setDisplayOrder(Integer.MAX_VALUE);
            }

            if (filed1.getDisplayOrder() < filed2.getDisplayOrder()) {
                return -1;
            }
            if (filed1.getDisplayOrder() == filed2.getDisplayOrder()) {
                return 0;
            }
            if (filed1.getDisplayOrder() > filed2.getDisplayOrder()) {
                return 1;
            }
            return 0;
        }
    }
}
