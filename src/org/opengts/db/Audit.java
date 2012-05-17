// ----------------------------------------------------------------------------
// Copyright 2007-2012, GeoTelematic Solutions, Inc.
// All rights reserved
// ----------------------------------------------------------------------------
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
// http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// ----------------------------------------------------------------------------
// Change History:
//  2010/04/11  Martin D. Flynn
//     -Initial release
// ----------------------------------------------------------------------------
package org.opengts.db;

import java.util.*;
import java.io.*;

import org.opengts.util.*;

public class Audit
{

    // ------------------------------------------------------------------------
    
    public static final int    GROUP_UNKNOWN            = 0x0000;
    public static final int    GROUP_LOGIN              = 0x0100;
    public static final int    GROUP_EMAIL              = 0x0200;
    public static final int    GROUP_DB                 = 0x0300;

    public static final int    AUDIT_UNKNOWN            = GROUP_UNKNOWN | 0x00;
    public static final int    AUDIT_LOGIN_OK           = GROUP_LOGIN   | 0x00;
    public static final int    AUDIT_LOGOUT             = GROUP_LOGIN   | 0x10;
    public static final int    AUDIT_EMAIL_NOTIFY       = GROUP_EMAIL   | 0x01;
  //public static final int    AUDIT_DB_NEW_ACCOUNT     = GROUP_DB      | 0x01;
  //public static final int    AUDIT_DB_DEL_ACCOUNT     = GROUP_DB      | 0x02;

    public static String GetAuditName(int auditCode)
    {
        switch (auditCode) {
            case AUDIT_UNKNOWN        : return "Unknown";
            case AUDIT_LOGIN_OK       : return "User Login OK";
            case AUDIT_LOGOUT         : return "User Logout";
            case AUDIT_EMAIL_NOTIFY   : return "Email Notification";
          //case AUDIT_DB_NEW_ACCOUNT : return "New Account";
          //case AUDIT_DB_DEL_ACCOUNT : return "Delete Account";
            default                   : return "Undefined";
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /* audit handler */
    public interface AuditHandler
    {
        public void addAuditEntry(
            String accountID, long auditTime, int auditCode,
            String userID, String deviceID, 
            String ipAddress,
            String privateLabelName);
    }

    /* set the audit handler */
    private static AuditHandler auditHandler = null;
    public static void SetAuditHandler(AuditHandler sah)
    {
        Audit.auditHandler = sah;
    }

    /* add an audit entry */
    public static void AddAudit(
        String accountID, long auditTime, int auditCode,
        String userID, String deviceID, 
        String ipAddress,
        String privateLabelName)
    {
        if (Audit.auditHandler != null) {
            Audit.auditHandler.addAuditEntry(
                accountID, auditTime, auditCode, 
                userID, deviceID, 
                ipAddress, 
                privateLabelName);
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    // SystemAudit.userLogin(accountID, userID, nowTimeSec, ipAddr, bplName);
    public static void userLoginOK(String acctID, String userID, String ipAddr, String bplName)
    {
        long nowTimeSec = DateTime.getCurrentTimeSec();
        Print.logInfo("Login: Time="+nowTimeSec + " Domain="+bplName + " Account="+acctID + " User="+userID + " IP="+ipAddr);
        Audit.AddAudit(acctID, nowTimeSec, AUDIT_LOGIN_OK, userID, null, ipAddr, bplName);
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    public static void ruleNotification(String acctID, String devID, String toEMail, String subject, String body)
    {
        long nowTimeSec = DateTime.getCurrentTimeSec();
        Print.logInfo("Rule EMail: Time="+nowTimeSec + " Account="+acctID);
        Audit.AddAudit(acctID, nowTimeSec, AUDIT_EMAIL_NOTIFY, null, devID, null, null);
    }

    // ------------------------------------------------------------------------

}
