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
// Description:
//  JavaMail support.
//  The use of this module requires that the JavaMail api in downloaed and installed.
//  Support for the JavaMail api can be downloaded from the following location:
//   http://java.sun.com/products/javamail/index.jsp
// References:
//   - http://www.programmers-corner.com/sourcecode/278?PHPSESSID=93f30447970535d4c2a068a22ddb6e23
//   - http://forum.java.sun.com/thread.jspa?threadID=5146702&tstart=314
//   - http://forum.java.sun.com/thread.jspa?threadID=706550&tstart=135
// ----------------------------------------------------------------------------
// Change History:
//  2006/03/26  Martin D. Flynn
//     -Initial release
//  2006/04/03  Martin D. Flynn
//     -Renamed source file to "SendMail.java.save" to temporarily remove 
//      the requirement of having the JavaMail api installed in order to compile
//      the server.
//  2006/06/30  Martin D. Flynn
//     -Repackaged.
//  2006/07/13  Martin D. Flynn
//     -Added support for specifying user/password.
//     -Added support SSL connections.
//  2006/09/16  Martin D. Flynn
//     -Added 'main' to allow testing this module
//  2008/02/27  Martin D. Flynn
//     -Added "image/png" mime type
//  2009/01/01  Martin D. Flynn
//     -Added thread-model THREAD_NONE for debug purposes.  
//      Similar to THREAD_DEBUG but skips sending email quietly.
//  2012/02/03  Martin D. Flynn
//     -Fixed displayed error when "SendMailArgs" class cannot be found/returned.
// ----------------------------------------------------------------------------
package org.opengts.util;

import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;

public class SendMail
{

    // ------------------------------------------------------------------------

    public  static final String SendMailArgs_className  = "org.opengts.util.SendMailArgs";

    private static Class _SendMailArgs_class            = null;

    /**
    *** Gets the "SendMailArgs" class
    *** @return The "SendMailArgs" class, if "SendMail" enabled, otherwise null.
    **/
    private static Class GetSendMailArgs_class() 
    {
        if (_SendMailArgs_class == null) {
            try {
                _SendMailArgs_class = Class.forName(SendMailArgs_className);
            } catch (Throwable th) { // ClassNotFoundException
                // this class retrieval will fail if JavaMail "mail.jar" is not installed.
                Print.logError("Class '"+SendMailArgs_className+"': " + th);
                return null;
            }
        }
        return _SendMailArgs_class;
    }

    /**
    *** Returns true if "SendMail" is enabled
    *** @return True if "SendMail" is enabled, otherwise false
    **/
    public  static boolean IsSendMailEnabled()
    {
        return (GetSendMailArgs_class() != null);
    }
    
    // ------------------------------------------------------------------------

    public  static final String EXAMPLE_DOT_COM         = "example.com";
    
    public static boolean IsBlankEmailAddress(String email)
    {
        email = StringTools.trim(email); // trim
        if (email.equals("")) {
            return true;
        } else 
        if (StringTools.endsWithIgnoreCase(email,EXAMPLE_DOT_COM)) {
            return true;
        } else {
            return false;
        }
    }
    
    // ------------------------------------------------------------------------
    // Custom "X" headers

    public  static final String X_OwnerId               = "X-OwnerId";
    public  static final String X_AssetId               = "X-AssetId";
    public  static final String X_PageType              = "X-PageType";
    public  static final String X_Requestor             = "X-Requestor";
    public  static final String X_OriginatingIP         = "X-OriginatingIP";
    public  static final String X_EventTime             = "X-EventTime";
    public  static final String X_StatusCode            = "X-StatusCode";
    public  static final String X_AlarmRule             = "X-AlarmRule";
    public  static final String X_GPSLocation           = "X-GPSLocation";
 
    // ------------------------------------------------------------------------

    public  static final byte   MAGIC_GIF_87a[]         = HTMLTools.MAGIC_GIF_87a;
    public  static final byte   MAGIC_GIF_89a[]         = HTMLTools.MAGIC_GIF_89a;
    public  static final byte   MAGIC_JPEG[]            = HTMLTools.MAGIC_JPEG;
    public  static final byte   MAGIC_PNG[]             = HTMLTools.MAGIC_PNG; 

    // ------------------------------------------------------------------------
    
    public  static final String THREAD_NONE             = "none";
    public  static final int    _THREAD_NONE            = -1;
    public  static final String THREAD_CURRENT          = "current";
    public  static final int    _THREAD_CURRENT         = 0;
    public  static final String THREAD_POOL             = "pool"; // preferred
    public  static final int    _THREAD_POOL            = 1;
    public  static final String THREAD_NEW              = "new";
    public  static final int    _THREAD_NEW             = 2;
    public  static final String THREAD_DEBUG            = "debug";
    public  static final int    _THREAD_DEBUG           = 3;

    /**
    *** Returns true if the SendMail thread-model should be displayed when in debug mode.
    **/
    public static void SetShowThreadModel(boolean show)
    {
        RTConfig.setBoolean(RTKey.SMTP_THREAD_MODEL_SHOW, show);
    }

    /**
    *** Sets the SendMail thread-model 'show' state (during debug mode)
    **/
    public static boolean GetShowThreadModel()
    {
        return RTConfig.getBoolean(RTKey.SMTP_THREAD_MODEL_SHOW);
    }

    /**
    *** Sets the 'thread model' for email sent by this class.<br>
    *** The valid values are THREAD_CURRENT, THREAD_POOL, THREAD_NEW, or THREAD_DEBUG.
    *** @param model  The specified thread model.
    **/
    public static void SetThreadModel(String model)
    {
        RTConfig.setString(RTKey.SMTP_THREAD_MODEL, model);
    }

    /**
    *** Sets the 'thread model' for email sent by this class.<br>
    *** The valid values are THREAD_CURRENT, THREAD_POOL, THREAD_NEW, or THREAD_DEBUG.
    *** @param model  The specified thread model.
    *** @param show   True to display the thread model when sending an email (debug purposes only).
    **/
    public static void SetThreadModel(String model, boolean show)
    {
        SendMail.SetThreadModel(model);
        SendMail.SetShowThreadModel(false); // show);
    }

    /** 
    *** Returns the thread model in effect for this class
    *** @return  The thread model in effect.
    **/
    private static int GetThreadModel() 
    {
        return GetThreadModel(RTConfig.getString(RTKey.SMTP_THREAD_MODEL));
    }
    
    /**
    *** Returns the int representation of the specified thread model.
    *** @param model The thread model String representation
    *** @return The thread model 'int' representation
    **/
    private static int GetThreadModel(String model) 
    {
        if (model == null) {
            // If this is a server, then we want a thread pool
            // If this is a one-shot 'main' program, we want the current thread
            return RTConfig.isTestMode()? _THREAD_CURRENT : _THREAD_POOL;
            // If a thread pool is needed while in test mode, then the thread model
            // will have to be set explicitly.
        } else
        if (model.equalsIgnoreCase(THREAD_NONE)) {
            return _THREAD_NONE;
        } else
        if (model.equalsIgnoreCase(THREAD_CURRENT)) {
            return _THREAD_CURRENT;
        } else
        if (model.equalsIgnoreCase(THREAD_NEW)) {
            return _THREAD_NEW;
        } else
        if (model.equalsIgnoreCase(THREAD_DEBUG)) {
            return _THREAD_DEBUG;
        } else {
            return _THREAD_POOL;
        }
    }
        
    private static ThreadPool sendMailThreadPool = new ThreadPool("SendMail");
        
    // ------------------------------------------------------------------------
    // Convenience method for sending notification regarding internal errors
    
    //public static void sendError(String subject, String msgBody)
    //{
    //    Properties headers = null;
    //    String emailFrom   = RTConfig.getString(RTKey.ERROR_EMAIL_FROM);
    //    String emailTo     = RTConfig.getString(RTKey.ERROR_EMAIL_TO);
    //    if ((emailFrom != null) && (emailTo != null)) {
    //        SendMail.Attachment attach = null;
    //        SendMail.send(headers,emailFrom,emailTo,subject,msgBody,attach,null/*SmtpProps*/);
    //    }
    //}

    // ------------------------------------------------------------------------

    /**
    *** Returns the specified override "From" email address, if non-blank, otherwise
    *** the default configured "From" email address will be returned.
    *** @param mainEmail  The overriding email address to return if non-blank
    *** @return The SMTP user "From" email address.
    **/
    public static String getDefaultUserEmail(String mainEmail)
    {
        if (!SendMail.IsBlankEmailAddress(mainEmail)) {
            return StringTools.trim(mainEmail);
        } else {
            String email = RTConfig.getString(RTKey.SMTP_SERVER_USER_EMAIL, null);
            return !SendMail.IsBlankEmailAddress(email)? email : null;
        }
    }

    // ------------------------------------------------------------------------

    /**
    *** Sends an email
    *** @param from     The sender of the email
    *** @param to       A comma-separated list of email recipients.
    *** @param subject  The email subject.
    *** @param msgBody  The email message body.
    *** @return True
    **/
    public static boolean send(
        String from, String to, 
        String subject, String msgBody,
        SmtpProperties smtpProps)
    {
        String ato[]  = (to  != null)? StringTools.parseString(to ,',') : null;
        String acc[]  = null;
        String abcc[] = null;
        return send(null,from,ato,acc,abcc,subject,msgBody,null/*attach*/,smtpProps);
    }

    /**
    *** Sends an email
    *** @param from     The sender of the email
    *** @param to       A comma-separated list of email recipients.
    *** @param subject  The email subject.
    *** @param msgBody  The email message body.
    *** @param attach   An email attachment
    *** @return True
    **/
    public static boolean send(
        String from, String to, 
        String subject, String msgBody, 
        Attachment attach,
        SmtpProperties smtpProps)
    {
        String ato[]  = (to  != null)? StringTools.parseString(to ,',') : null;
        String acc[]  = null;
        String abcc[] = null;
        return send(null,from,ato,acc,abcc,subject,msgBody,attach,smtpProps);
    }

    /**
    *** Sends an email
    *** @param from     The sender of the email
    *** @param to       A comma-separated list of 'To:' email recipients.
    *** @param cc       A comma-separated list of 'Cc:' email recipients.
    *** @param bcc      A comma-separated list of 'Bcc:' email recipients.
    *** @param subject  The email subject.
    *** @param msgBody  The email message body.
    *** @param attach   An email attachment
    *** @return True
    **/
    public static boolean send(
        String from, String to, String cc, String bcc,
        String subject, String msgBody, 
        Attachment attach,
        SmtpProperties smtpProps)
    {
        String ato[]  = (to  != null)? StringTools.parseString(to ,',') : null;
        String acc[]  = (cc  != null)? StringTools.parseString(cc ,',') : null;
        String abcc[] = (bcc != null)? StringTools.parseString(bcc,',') : null;
        return send(null,from,ato,acc,abcc,subject,msgBody,attach,smtpProps);
    }

    /**
    *** Sends an email
    *** @param from     The sender of the email
    *** @param to       An array of 'To:' email recipients.
    *** @param cc       An array of 'Cc:' email recipients.
    *** @param bcc      An array of 'Bcc:' email recipients.
    *** @param subject  The email subject.
    *** @param msgBody  The email message body.
    *** @param attach   An email attachment
    *** @return True
    **/
    public static boolean send(
        Properties headers, 
        String from, String to[], String cc[], String bcc[], 
        String subject, String msgBody, 
        Attachment attach,
        SmtpProperties smtpProps)
    {
        Args args = new Args(headers,from,to,cc,bcc,subject,msgBody,attach,smtpProps);
        SendMailRunnable smr = new SendMailRunnable(args);
        boolean showThreadModel = SendMail.GetShowThreadModel();
        switch (SendMail.GetThreadModel()) {
            case _THREAD_NONE     :
                //if (showThreadModel) {
                    Print.logDebug("Skipping SendMail ...");
                //}
                return false;
            case _THREAD_CURRENT  :
                if (showThreadModel) {
                    Print.logDebug("Running SendMail in current thread");
                }
                smr.run();
                return smr.emailSent();
            case _THREAD_NEW   :
                if (showThreadModel) {
                    Print.logDebug("Starting new SendMail thread");
                }
                (new Thread(smr)).start();
                break;
            case _THREAD_DEBUG :
                Print.logDebug("Debug SendMail (email not sent)");
                Print.logDebug(smr.getArgs().toString());
                return false;
            case _THREAD_POOL  :
            default :
                if (showThreadModel) {
                    Print.logDebug("Running SendMail in thread pool");
                }
                sendMailThreadPool.run(smr);
                break;
        }
        return true; // send(args);
    }

    /**
    *** SendMailRunnable class.
    **/
    private static class SendMailRunnable
        implements Runnable
    {
        private Args    args = null;
        private boolean emailSent = false;
        public SendMailRunnable(Args args) {
            this.args = args;
            this.emailSent = false;
        }
        public Args getArgs() {
            return this.args;
        }
        public void run() {
            if (this.args != null) {
                //this.emailSent = SendMailArgs.send(this.args);
                try {
                    Class sendMailArgs = GetSendMailArgs_class();
                    MethodAction ma = new MethodAction(sendMailArgs, "send", Args.class);
                    this.emailSent = ((Boolean)ma.invoke(this.args)).booleanValue();
                } catch (Throwable th) {
                    Print.logDebug("Email 'send' failed: " + th);
                    this.emailSent = false;
                }
            }
        }
        public boolean emailSent() {
            return this.emailSent;
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
    *** Returns the MIME contents type for the specified file contents
    *** @param data  The file contents
    *** @return The MIME content type
    **/
    public static String DefaultContentType(byte data[])
    {
        String code = null;

        /* GIF */
        if (StringTools.compareEquals(data,MAGIC_GIF_87a,-1)) {
            return HTMLTools.MIME_GIF();
        } else
        if (StringTools.compareEquals(data,MAGIC_GIF_89a,-1)) {
            return HTMLTools.MIME_GIF();
        }

        /* JPEG */
        if (StringTools.compareEquals(data,MAGIC_JPEG,-1)) {
            return HTMLTools.MIME_JPEG();
        }

        /* PNG */
        if (StringTools.compareEquals(data,MAGIC_PNG,-1)) {
            return HTMLTools.MIME_PNG();
        }

        /* default */
        return HTMLTools.CONTENT_TYPE_OCTET;
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    private static final String DFT_ATTACHMENT_NAME = "attachment.att";
    private static final String DFT_ATTACHMENT_TYPE = HTMLTools.CONTENT_TYPE_OCTET;

    /**
    *** A container for an email attachment
    **/
    public static class Attachment
    {
        private byte       data[] = null;
        private String     name   = DFT_ATTACHMENT_NAME;
        private String     type   = DFT_ATTACHMENT_TYPE;
        public Attachment(byte data[]) {
            this(data, null, null);
        }
        public Attachment(byte data[], String name, String type) {
            this.data = data;
            this.name = ((name != null) && !name.equals(""))? name : DFT_ATTACHMENT_NAME;
            this.type = ((type != null) && !type.equals(""))? type : DFT_ATTACHMENT_TYPE;
        }
        public byte[] getBytes() {
            return this.data;
        }
        public int getSize() {
            return (this.data != null)? this.data.length : 0;
        }
        public String getName() {
            return this.name;
        }
        public String getType() {
            return this.type;
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
    *** A container for the arguments of an email
    **/
    public static class Args
    {
        private Properties     headers      = null;
        private String         from         = null;
        private String         to[]         = null;
        private String         cc[]         = null;
        private String         bcc[]        = null;
        private String         subject      = null;
        private String         msgBody      = null;
        private Attachment     attachment   = null;
        private SmtpProperties smtpProps    = null;
        public Args(Properties headers, 
            String from, String to[], String cc[], String bcc[], 
            String subject, String msgBody, 
            Attachment attach,
            SmtpProperties smtpProps) {
            this.headers    = (headers != null)? headers : new Properties();
            this.from       = from;
            this.to         = to;
            this.cc         = cc;
            this.bcc        = bcc;
            this.subject    = subject;
            this.msgBody    = msgBody;
            this.attachment = attach;
            this.smtpProps  = (smtpProps != null)? smtpProps : new SmtpProperties();
        }
        public Properties getHeaders() {
            return this.headers;
        }
        public String getFrom() {
            return (this.from != null)? this.from : "";
        }
        public String[] getTo() {
            return (this.to != null)? this.to : new String[0];
        }
        public String[] getCc() {
            return (this.cc != null)? this.cc : new String[0];
        }
        public String[] getBcc() {
            return (this.bcc != null)? this.bcc : new String[0];
        }
        public String getSubject() {
            return (this.subject != null)? this.subject : "";
        }
        public String getBody() {
            return (this.msgBody != null)? this.msgBody : "";
        }
        public Attachment getAttachment() {
            return this.attachment;
        }
        public SmtpProperties getSmtpProperties() {
            return this.smtpProps;
        }
        public String toString() {
            StringBuffer sb = new StringBuffer().append("\n");
            Properties headers = this.getHeaders();
            if ((headers != null) && !headers.isEmpty()) {
                for (Iterator i = headers.keySet().iterator(); i.hasNext();) {
                    String k = (String)i.next();
                    String v = headers.getProperty(k);
                    if (v != null) {
                        sb.append(k).append(": ");
                        sb.append(v).append("\n");
                    }
                }
            }
            sb.append("From: ").append(this.getFrom()).append("\n");
            sb.append("To: ").append(StringTools.encodeArray(this.getTo())).append("\n");
            sb.append("Subject: ").append(this.getSubject()).append("\n");
            sb.append(this.getBody()).append("\n");
            Attachment attach = this.getAttachment();
            if ((attach != null) && (attach.getSize() > 0)) {
                sb.append("---- attachment ----\n");
                sb.append(StringTools.toHexString(attach.getBytes())).append("\n");
            }
            sb.append("\n");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    public static class SmtpProperties
    {
        private RTProperties smtpProps = null;
        public SmtpProperties() {
            this(null);
        }
        public SmtpProperties(RTProperties rtp) {
            super();
            this.smtpProps = (rtp != null)? rtp : new RTProperties();
        }
        // ----
        public void setDebug(boolean V) {
            String K = RTKey.SMTP_DEBUG;
            this.smtpProps.setBoolean(K, V);
        }
        public boolean getDebug() {
            String K = RTKey.SMTP_DEBUG;
            boolean V = this.smtpProps.hasProperty(K)? this.smtpProps.getBoolean(K,false) : RTConfig.getBoolean(K);
            return V;
        }
        // ----
        public void setHost(String V) {
            String K = RTKey.SMTP_SERVER_HOST;
            this.smtpProps.setString(K, StringTools.trim(V));
        }
        public String getHost() {
            String K = RTKey.SMTP_SERVER_HOST;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (StringTools.isBlank(V) || StringTools.endsWithIgnoreCase(V,EXAMPLE_DOT_COM)) { 
                V = RTConfig.getString(K); 
                if (StringTools.endsWithIgnoreCase(V,EXAMPLE_DOT_COM)) { V = null; }
            }
            return StringTools.trim(V);
        }
        // ----
        public void setPort(int V) {
            String K = RTKey.SMTP_SERVER_PORT;
            this.smtpProps.setInt(K, ((V > 0)? V : 0));
        }
        public int getPort() {
            String K = RTKey.SMTP_SERVER_PORT;
            int V = this.smtpProps.hasProperty(K)? this.smtpProps.getInt(K,0) : 0;
            if (V <= 0) { V = RTConfig.getInt(K,25); }
            return V;
        }
        // ----
        public void setUser(String V) {
            String K = RTKey.SMTP_SERVER_USER;
            this.smtpProps.setString(K, StringTools.trim(V));
        }
        public String getUser() {
            String K = RTKey.SMTP_SERVER_USER;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (StringTools.isBlank(V)) { V = RTConfig.getString(K); }
            return StringTools.trim(V);
        }
        // ----
        public void setUserEmail(String V) {
            String K = RTKey.SMTP_SERVER_USER_EMAIL;
            this.smtpProps.setString(K, StringTools.trim(V));
        }
        public String getUserEmail() {
            String K = RTKey.SMTP_SERVER_USER_EMAIL;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (StringTools.isBlank(V) || StringTools.endsWithIgnoreCase(V,EXAMPLE_DOT_COM)) { 
                V = RTConfig.getString(K); 
                if (StringTools.endsWithIgnoreCase(V,EXAMPLE_DOT_COM)) { V = null; }
            }
            return StringTools.trim(V);
        }
        public String getUserEmail(String mainEmail) {
            return !SendMail.IsBlankEmailAddress(mainEmail)?
                StringTools.trim(mainEmail) : this.getUserEmail();
        }
        // ----
        public void setPassword(String V) {
            String K = RTKey.SMTP_SERVER_PASSWORD;
            this.smtpProps.setString(K, V); // do not trim
        }
        public String getPassword() {
            String K = RTKey.SMTP_SERVER_PASSWORD;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (V == null) { V = RTConfig.getString(K); }
            return V; // do not trim
        }
        // ----
        public void setEnableSSL(String V) {
            String K = RTKey.SMTP_ENABLE_SSL;
            this.smtpProps.setString(K, StringTools.trim(V));
        }
        public String getEnableSSL() {
            // false | true | only
            String K = RTKey.SMTP_ENABLE_SSL;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (StringTools.isBlank(V)) { V = RTConfig.getString(K); }
            return StringTools.trim(V);
        }
        // ----
        public void setEnableTLS(String V) {
            String K = RTKey.SMTP_ENABLE_TLS;
            this.smtpProps.setString(K, StringTools.trim(V));
        }
        public String getEnableTLS() {
            // false | true | only
            String K = RTKey.SMTP_ENABLE_TLS;
            String V = this.smtpProps.hasProperty(K)? this.smtpProps.getString(K,null) : null;
            if (StringTools.isBlank(V)) { V = RTConfig.getString(K); }
            return StringTools.trim(V);
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
    *** Validate the specified list of comma-separated email addresses.
    *** @param addr  A comma-separated list of email addresses
    *** @return True if all addresses in the list are valid, false otherwise
    **/
    public static boolean validateAddresses(String addr)
    {
        if (StringTools.isBlank(addr)) {
            return false;
        }
        String addrArry[] = StringTools.parseString(addr, ',');
        if (addrArry.length == 0) { return false; }
        for (int i = 0; i < addrArry.length; i++) {
            String em = addrArry[i].trim();
            if (em.equals("")) { return false; }
            if (!validateAddress(em)) { return false; }
        }
        return true;
    }
    
    /**
    *** Validate the specified email address.
    *** @param addr  The email address to validate.
    *** @return True if the specified email address is valid, false otherwise
    **/
    public static boolean validateAddress(String addr)
    {
        return !StringTools.isBlank(SendMail.getEMailAddress(addr));
    }

    /** 
    *** Filters and returns the base email address from the specified String.<br>
    *** For example, if the String "Jones&lt;jones@example.com&gt;" is passed to this
    *** method, then the value "jones@example.com" will be returned.
    *** @param addr The email address to filter.
    *** @return  The filtered email address, or null if the specified email address is invalid.
    **/
    public static String getEMailAddress(String addr)
    {
        //return SendMailArgs.parseEMailAddress(addr);
        try {
            Class sendMailArgs = GetSendMailArgs_class();
            MethodAction ma = new MethodAction(sendMailArgs, "parseEMailAddress", String.class);
            return (String)ma.invoke(addr);
        } catch (Throwable th) {
            Print.logWarn("Unable to invoke 'parseEMailAddress': " + th);
            return null;
        }
    }
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------
    
    private static String ARG_ADDR[]    = new String[] { "addr"     , "a" };

    private static String ARG_FROM[]    = new String[] { "from"     , "f" };
    private static String ARG_TO[]      = new String[] { "to"       , "t" };
    private static String ARG_SUBJECT[] = new String[] { "subject"  , "subj", "s" };
    private static String ARG_BODY[]    = new String[] { "body"     , "b" };
    private static String ARG_ATTACH[]  = new String[] { "attach"   , "att" };

    /**
    *** Command-line debug/testing entry point
    *** @param argv  The command-line arguments
    **/
    public static void main(String argv[])
    {
        RTConfig.setCommandLineArgs(argv);
        SendMail.SetThreadModel(SendMail.THREAD_CURRENT);
        SmtpProperties smtpDft = new SmtpProperties(); // default properties

        /* validate email address */
        if (RTConfig.hasProperty(ARG_ADDR)) {
            String addr = RTConfig.getString(ARG_ADDR, "");
            Print.sysPrintln("Checking: " + addr);
            Print.sysPrintln("Address : " + SendMail.getEMailAddress(addr));
            Print.sysPrintln("Valid   ? " + SendMail.validateAddress(addr));
            Print.sysPrintln("");
            System.exit(0);
        }

        /* "From" */
        String fromAddr = RTConfig.getString(ARG_FROM,null);
        fromAddr = RTConfig.insertKeyValues(fromAddr);
        if (StringTools.isBlank(fromAddr) || fromAddr.endsWith(EXAMPLE_DOT_COM)) {
            fromAddr = smtpDft.getUserEmail();
            if (StringTools.isBlank(fromAddr)) {
                Print.sysPrintln("ERROR: Missing 'From' address.");
                Print.sysPrintln("(define \"smtp.user.emailAddress\" in 'default.conf')");
                Print.sysPrintln("");
                System.exit(1);
            }
        }

        /* "To" */
        String toAddr = RTConfig.getString(ARG_TO,null);
        toAddr = RTConfig.insertKeyValues(toAddr);
        if (StringTools.isBlank(toAddr) || toAddr.endsWith(EXAMPLE_DOT_COM)) {
            Print.sysPrintln("Missing 'To' address");
            Print.sysPrintln("(specify '-to=<emailAddress>' option on command-line)");
            Print.sysPrintln("");
            System.exit(1);
        }

        /* "Subject" */
        String subject = RTConfig.getString(ARG_SUBJECT,"EMail");
        subject = RTConfig.insertKeyValues(subject);

        /* Body */
        String body = RTConfig.getString(ARG_BODY,"");
        if (body.startsWith("file:")) {
            InputStream uis = null;
            try {
                URL url = new URL(body);
                uis = url.openStream(); // may throw MalformedURLException
                byte b[] = FileTools.readStream(uis); // IOException
                body = StringTools.toStringValue(b);
            } catch (Throwable th) {
                Print.sysPrintln("ERROR: Error reading body file URL - " + th);
                System.exit(99);
            } finally {
                try { uis.close(); } catch (Throwable th) {/*ignore*/}
            }
        }
        body = RTConfig.insertKeyValues(body);
        body = StringTools.replace(body,"\\n","\n");

        /* Attachment */
        Attachment attach = null;
        if (RTConfig.hasProperty(ARG_ATTACH)) {
            File attachFile = RTConfig.getFile(ARG_ATTACH,null);
            if ((attachFile != null) && attachFile.isFile()) {
                InputStream fis = null;
                try {
                    fis = new FileInputStream(attachFile);
                    byte b[] = FileTools.readStream(fis); // IOException
                    String name = attachFile.getName();
                    String ext  = FileTools.getExtension(attachFile);
                    String type = HTMLTools.getMimeTypeFromExtension(ext,null); // CONTENT_TYPE_OCTET;
                    if (type == null) {
                        type = DefaultContentType(b);
                    }
                    attach = new Attachment(b, name, type);
                } catch (Throwable th) {
                    Print.sysPrintln("ERROR: Unable to load attachment - " + th);
                } finally {
                    try { fis.close(); } catch (Throwable th) {/*ignore*/}
                }
            } else {
                Print.sysPrintln("ERROR: Specified Attachment does not exist - " + attachFile);
                System.exit(99);
            }
        }

        /* send email */
        if (SendMail.send(fromAddr,toAddr,subject,body,attach,smtpDft)) {
            Print.sysPrintln("EMail sent:");
            Print.sysPrintln("   From   : " + fromAddr);
            Print.sysPrintln("   To     : " + toAddr);
            Print.sysPrintln("   Subject: " + subject);
        } else {
            Print.sysPrintln("Unable to send EMail");
        }
        Print.sysPrintln("");
        System.exit(0);

    }

}
