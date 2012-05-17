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
// TK102/TK103 device communication server:
//  -Includes support to overriding default status code
//  -Includes support for simulated geozone arrive/depart events
// ----------------------------------------------------------------------------
// Change History:
//  2011/07/15  Martin D. Flynn
//     -Initial release.
//  2011/08/21  Martin D. Flynn
//     -TK103: The Date column appears to be specified in a timezone local to
//      where the device was configured.  Made some changes to attempt to 
//      determine the actual GMT day, based on the time difference between the 
//      local time, and the GMT time specified elsewhere in the record.
//     -TK103: It appears that most tk103 data packets do not contain a heading
//      value.  If no heading value is present, then an approximate heading will
//      be calcualted based on the previous valid location.
//  2012/02/03  Martin D. Flynn
//     -TK103-ALT: Added support for alternate TK102/TK103 format
//     -Extract battery voltage and altitude from TK102 packet
//  2012/04/03  Martin D. Flynn
//     -Added TK103 simulated digital input change events
// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
package org.opengts.servers.tk10x;

import java.lang.*;
import java.util.*;
import java.io.*;
import java.net.*;
import java.sql.*;

import org.opengts.util.*;
import org.opengts.dbtools.*;
import org.opengts.dbtypes.*;
import org.opengts.db.*;
import org.opengts.db.tables.*;

public class TrackClientPacketHandler
    extends AbstractClientPacketHandler
{

    public static       boolean DEBUG_MODE                  = false;
    
    // ------------------------------------------------------------------------

    public static       String  UNIQUEID_PREFIX[]           = null;
    public static       double  MINIMUM_SPEED_KPH           = Constants.MINIMUM_SPEED_KPH;
    public static       boolean ESTIMATE_ODOMETER           = true;
    public static       boolean SIMEVENT_GEOZONES           = false;
    public static       long    SIMEVENT_DIGITAL_INPUTS     = 0xFFL;
    public static       boolean XLATE_LOCATON_INMOTION      = true;
    public static       double  MINIMUM_MOVED_METERS        = 0.0;
    public static       boolean PACKET_LEN_END_OF_STREAM    = false;

    // ------------------------------------------------------------------------

    /* convenience for converting knots to kilometers */
    public static final double  KILOMETERS_PER_KNOT         = 1.85200000;

    // ------------------------------------------------------------------------

    /* GTS status codes for Input-On events */
    private static final int InputStatusCodes_ON[] = new int[] {
        StatusCodes.STATUS_INPUT_ON_00,
        StatusCodes.STATUS_INPUT_ON_01,
        StatusCodes.STATUS_INPUT_ON_02,
        StatusCodes.STATUS_INPUT_ON_03,
        StatusCodes.STATUS_INPUT_ON_04,
        StatusCodes.STATUS_INPUT_ON_05,
        StatusCodes.STATUS_INPUT_ON_06,
        StatusCodes.STATUS_INPUT_ON_07,
        StatusCodes.STATUS_INPUT_ON_08,
        StatusCodes.STATUS_INPUT_ON_09,
        StatusCodes.STATUS_INPUT_ON_10,
        StatusCodes.STATUS_INPUT_ON_11,
        StatusCodes.STATUS_INPUT_ON_12,
        StatusCodes.STATUS_INPUT_ON_13,
        StatusCodes.STATUS_INPUT_ON_14,
        StatusCodes.STATUS_INPUT_ON_15
    };

    /* GTS status codes for Input-Off events */
    private static final int InputStatusCodes_OFF[] = new int[] {
        StatusCodes.STATUS_INPUT_OFF_00,
        StatusCodes.STATUS_INPUT_OFF_01,
        StatusCodes.STATUS_INPUT_OFF_02,
        StatusCodes.STATUS_INPUT_OFF_03,
        StatusCodes.STATUS_INPUT_OFF_04,
        StatusCodes.STATUS_INPUT_OFF_05,
        StatusCodes.STATUS_INPUT_OFF_06,
        StatusCodes.STATUS_INPUT_OFF_07,
        StatusCodes.STATUS_INPUT_OFF_08,
        StatusCodes.STATUS_INPUT_OFF_09,
        StatusCodes.STATUS_INPUT_OFF_10,
        StatusCodes.STATUS_INPUT_OFF_11,
        StatusCodes.STATUS_INPUT_OFF_12,
        StatusCodes.STATUS_INPUT_OFF_13,
        StatusCodes.STATUS_INPUT_OFF_14,
        StatusCodes.STATUS_INPUT_OFF_15
    };

    // ------------------------------------------------------------------------

    /* GMT/UTC timezone */
    private static final TimeZone gmtTimezone               = DateTime.getGMTTimeZone();

    // ------------------------------------------------------------------------

    /* packet handler constructor */
    public TrackClientPacketHandler() 
    {
        super();
    }

    // ------------------------------------------------------------------------

    /* callback when session is starting */
    public void sessionStarted(InetAddress inetAddr, boolean isTCP, boolean isText)
    {
        super.sessionStarted(inetAddr, isTCP, isText);
        super.clearTerminateSession();
    }
    
    /* callback when session is terminating */
    public void sessionTerminated(Throwable err, long readCount, long writeCount)
    {
        super.sessionTerminated(err, readCount, writeCount);
    }

    // ------------------------------------------------------------------------

    /* based on the supplied packet data, return the remaining bytes to read in the packet */
    public int getActualPacketLength(byte packet[], int packetLen)
    {
        if (PACKET_LEN_END_OF_STREAM) {
            return ServerSocketThread.PACKET_LEN_END_OF_STREAM;
        } else {
            return ServerSocketThread.PACKET_LEN_LINE_TERMINATOR;
        }
    }

    // ------------------------------------------------------------------------

    /* workhorse of the packet handler */
    public byte[] getHandlePacket(byte pktBytes[]) 
    {
        if (ListTools.isEmpty(pktBytes)) {
            Print.logWarn("Ignoring empty/null packet");
        } else
        if (pktBytes.length < 11) {
            Print.logError("Unexpected packet length: " + pktBytes.length);
        } else {
            Print.logInfo("Receive: " + StringTools.toStringValue(pktBytes,'.')); // debug message
            String s = StringTools.toStringValue(pktBytes).trim();
            if (s.startsWith("##")) {
                // Likely a TK103 derivitive:
                //   ##,imei:123451042191239,A;
                Print.logInfo("TK103 Header: " + s); // debug message
                //Print.logInfo("Ignoring superfluous initial IMEI TK103 packet ...");
                return null;
            } else
            if (s.startsWith("imei:")) {
                // Could be a TK103 record
                //   imei:123451042191239,tracker ,1107090553,9735551234,F,215314.000,A,4103.7641,N,14244.9450,W,0.08,;
                return this.parseInsertRecord_TK103(s); // TK103-2
            } else
            if (s.startsWith("(")) {
                return this.parseInsertRecord_TK103_alt(s); // TK103-3
            } else {
                // default to TK102 or TK103-1
                return this.parseInsertRecord_TK102(s);
            }
        }
        return null; // no return packets are expected
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /* TK103: parse and insert data record */
    private byte[] parseInsertRecord_TK103(String s)
    {
        /* pre-validate */
        if (s == null) {
            Print.logError("String is null");
            return null;
        }
        Print.logInfo("Parsing(TK103): " + s);

        /* parse to fields */
        String fld[] = StringTools.parseString(s, ',');
        if (fld == null) {
            // will not occur
            Print.logWarn("Fields are null");
            return null;
        } else
        if (fld.length < 12) {
            Print.logWarn("Invalid number of fields");
            return null;
        }

        /* get "imei:" */
        String mobileID = null;
        if (fld[0].startsWith("imei:")) {
            mobileID = fld[0].substring("imei:".length()).trim();
        }
        if (StringTools.isBlank(mobileID)) {
            Print.logError("'imei:' value is missing");
            return null;
        }

        /* get time */
        long    locYMDhm    = (fld[2].length() >= 10)? StringTools.parseLong(fld[2].substring(0,10),0L) : 0L;
        long    gmtHMS      = StringTools.parseLong(fld[5],0L);
        long    fixtime     = this._getUTCSeconds_YMDhms_HMS(locYMDhm*100L, gmtHMS);
        if (fixtime <= 0L) {
            Print.logWarn("Invalid date: " + fld[2] + "/" + fld[5]);
            fixtime = DateTime.getCurrentTimeSec(); // default to now
        }

        /* GPS */
        boolean validGPS    = fld[6].equalsIgnoreCase("A");
        double  latitude    = validGPS? this._parseLatitude( fld[7], fld[ 8])  : 0.0;
        double  longitude   = validGPS? this._parseLongitude(fld[9], fld[10])  : 0.0;
        double  knots       = (validGPS && (fld.length > 11))? StringTools.parseDouble(fld[11], -1.0) : -1.0;
        double  headingDeg  = (validGPS && (fld.length > 12))? StringTools.parseDouble(fld[12], -1.0) : -1.0;
        double  speedKPH    = (knots >= 0.0)? (knots * KILOMETERS_PER_KNOT)   : -1.0;
        double  altitudeM   = 0.0;
        double  odomKM      = 0.0;
        double  batteryV    = 0.0;

        /* GPIO input */
        long    gpioInput   = -1L;

        /* get status code */
        String  eventCode   = fld[1];
        int     statusCode  = StatusCodes.STATUS_LOCATION;
        DCServerConfig  dcs = Main.getServerConfig();
        if ((dcs != null) && !StringTools.isBlank(eventCode)) {
            int code = dcs.translateStatusCode(eventCode, -9999);
            if (code == -9999) {
                // default 'statusCode' is StatusCodes.STATUS_LOCATION
            } else {
                statusCode = code;
            }
        }
        if (statusCode == StatusCodes.STATUS_IGNORE) {
            Print.logError("Ignoring EventCode: " + eventCode);
            return null;
        } else
        if (statusCode == StatusCodes.STATUS_NONE) {
            statusCode = (speedKPH > 0.0)? StatusCodes.STATUS_MOTION_IN_MOTION : StatusCodes.STATUS_LOCATION;
        } else
        if (XLATE_LOCATON_INMOTION && (statusCode == StatusCodes.STATUS_LOCATION) && (speedKPH > 0.0)) {
            statusCode = StatusCodes.STATUS_MOTION_IN_MOTION;
        }

        /* valid lat/lon? */
        if (validGPS && !GeoPoint.isValid(latitude,longitude)) {
            Print.logWarn("Invalid GPRMC lat/lon: " + latitude + "/" + longitude);
            latitude  = 0.0;
            longitude = 0.0;
            validGPS  = false;
        }
        GeoPoint geoPoint = new GeoPoint(latitude, longitude);

        /* parsed data */
        Print.logInfo("IMEI     : " + mobileID);
        Print.logInfo("Timestamp: " + fixtime + " [" + new DateTime(fixtime) + "]");
        Print.logInfo("GPS      : " + geoPoint);

        /* find Device */
        Device device = DCServerFactory.loadDeviceByPrefixedModemID(UNIQUEID_PREFIX, mobileID);
        if (device == null) {
            return null; // errors already displayed
        }
        String accountID = device.getAccountID();
        String deviceID  = device.getDeviceID();
        String uniqueID  = device.getUniqueID();
        Print.logInfo("UniqueID  : " + uniqueID);
        Print.logInfo("DeviceID  : " + accountID + "/" + deviceID);

        /* check IP address */
        DataTransport dataXPort = device.getDataTransport();
        if (this.hasIPAddress() && !dataXPort.isValidIPAddress(this.getIPAddress())) {
            DTIPAddrList validIPAddr = dataXPort.getIpAddressValid(); // may be null
            Print.logError("Invalid IP Address from device: " + this.getIPAddress() + " [expecting " + validIPAddr + "]");
            return null;
        }
        dataXPort.setIpAddressCurrent(this.getIPAddress());    // FLD_ipAddressCurrent
        dataXPort.setRemotePortCurrent(this.getRemotePort());  // FLD_remotePortCurrent
        dataXPort.setLastTotalConnectTime(DateTime.getCurrentTimeSec()); // FLD_lastTotalConnectTime
        if (!dataXPort.getDeviceCode().equalsIgnoreCase(Main.getServerName())) {
            dataXPort.setDeviceCode(Main.getServerName()); // FLD_deviceCode
        }

        /* adjust speed, calculate approximate heading if not available in packet */
        if (speedKPH < MINIMUM_SPEED_KPH) {
            speedKPH   = 0.0;
            headingDeg = 0.0;
        } else
        if (headingDeg < 0.0) {
            headingDeg = 0.0;
            if (validGPS && (device != null)) {
                GeoPoint lastGP = device.getLastValidLocation();
                if (GeoPoint.isValid(lastGP)) {
                    // calculate heading from last point to this point
                    headingDeg = lastGP.headingToPoint(geoPoint);
                }
            }
        }
        Print.logInfo("Speed    : " + StringTools.format(speedKPH,"0.0") + " km/h " + headingDeg);

        /* estimate GPS-based odometer */
        if (odomKM <= 0.0) {
            // calculate odometer
            odomKM = (ESTIMATE_ODOMETER && validGPS)? 
                device.getNextOdometerKM(geoPoint) : 
                device.getLastOdometerKM();
        } else {
            // bounds-check odometer
            odomKM = device.adjustOdometerKM(odomKM);
        }
        Print.logInfo("OdometerKM: " + odomKM);

        /* simulate Geozone arrival/departure */
        if (SIMEVENT_GEOZONES && validGPS) {
            java.util.List<Device.GeozoneTransition> zone = device.checkGeozoneTransitions(fixtime, geoPoint);
            if (zone != null) {
                for (Device.GeozoneTransition z : zone) {
                    this.insertEventRecord(device, 
                        z.getTimestamp(), z.getStatusCode(), z.getGeozone(),
                        geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                        speedKPH, headingDeg, altitudeM, odomKM,
                        gpioInput, batteryV);
                    Print.logInfo("Geozone    : " + z);
                    if (z.getStatusCode() == statusCode) {
                        // suppress 'statusCode' event if we just added it here
                        Print.logDebug("StatusCode already inserted: 0x" + StatusCodes.GetHex(statusCode));
                        statusCode = StatusCodes.STATUS_IGNORE;
                    }
                }
            }
        }

        /* digital input change events */
        if (gpioInput >= 0L) {
            if (SIMEVENT_DIGITAL_INPUTS > 0L) {
                // The current input state is compared to the last value stored in the Device record.
                // Changes in the input state will generate a synthesized event.
                long chgMask = (device.getLastInputState() ^ gpioInput) & SIMEVENT_DIGITAL_INPUTS;
                if (chgMask != 0L) {
                    // an input state has changed
                    for (int b = 0; b <= 15; b++) {
                        long m = 1L << b;
                        if ((chgMask & m) != 0L) {
                            // this bit changed
                            long inpTime = fixtime;
                            int  inpCode = ((gpioInput & m) != 0L)? InputStatusCodes_ON[b] : InputStatusCodes_OFF[b];
                            Print.logInfo("GPIO input : " + StatusCodes.GetDescription(inpCode,null));
                            this.insertEventRecord(device, 
                                inpTime, inpCode, null/*geozone*/,
                                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                                speedKPH, headingDeg, altitudeM, odomKM,
                                gpioInput, batteryV);
                        }
                    }
                }
            }
            device.setLastInputState(gpioInput & 0xFFFFL); // FLD_lastInputState
        }

        /* status code checks */
        if (statusCode < 0) { // StatusCodes.STATUS_IGNORE
            // skip (event ignored)
        } else
        if (statusCode == StatusCodes.STATUS_IGNORE) {
            // skip (event ignored)
        } else
        if ((statusCode == StatusCodes.STATUS_LOCATION) && this.hasSavedEvents()) {
            // skip (already inserted an event)
        } else
        if (statusCode != StatusCodes.STATUS_LOCATION) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        } else
        if (validGPS && !device.isNearLastValidLocation(geoPoint,MINIMUM_MOVED_METERS)) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        }

        /* save device changes */
        if (!DEBUG_MODE && (device != null)) {
            try {
                //DBConnection.pushShowExecutedSQL();
                device.updateChangedEventFields();
            } catch (DBException dbe) {
                Print.logException("Unable to update Device: " + accountID + "/" + deviceID, dbe);
            } finally {
                //DBConnection.popShowExecutedSQL();
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /* TK103: parse and insert data record */
    private byte[] parseInsertRecord_TK103_alt(String s)
    {
        /* pre-validate */
        if (s == null) {
            Print.logError("String is null");
            return null;
        } else
        if (!s.startsWith("(")) {
            Print.logError("Does not start with '('.");
            return null;
        }
        Print.logInfo("Parsing(TK103-Alt): " + s);

        /* parse header */
        String runNumStr = s.substring( 1,13);
        String msgType   = s.substring(13,17);
        
        /* parse packet type */
        int G;
        String modemID = "";
        if (msgType.equals("BP05")) {
            modemID = s.substring(17,32);
            G = 32; // BP05
        } else 
        if (msgType.equals("BR00") || 
            msgType.equals("BR02") || 
            msgType.equals("BP04")) {
            // modem-id should have been specified in a previous packet
            G = 17;
        } else
        if (msgType.equals("BP00")) {
            String ack = "(" + runNumStr + "AP01HSO)";
            Print.logWarn("Returning ACK: " + ack);
            return ack.getBytes();
        } else {
            Print.logWarn("Unsupported message type: " + msgType);
            return null;
        }

        // recheck packet length
        if (s.length() < (G+63)) {
            Print.logError("Unexpected packet length: " + s.length());
            return null;
        }

        /* GPS Data */
        String   dateStr    = s.substring(G+ 0,G+ 6);                                              //  0, 6  [YYMMDD]
        boolean  validGPS   = s.substring(G+ 6,G+ 7).equalsIgnoreCase("A");                        //  6, 7  [A/V]
        double   latitude   = this._parseLatitude( s.substring(G+ 7,G+16),s.substring(G+16,G+17)); //  7,16, 16:17
        double   longitude  = this._parseLongitude(s.substring(G+17,G+27),s.substring(G+27,G+28)); // 17,27, 27:28
        double   speedKPH   = StringTools.parseDouble(s.substring(G+28,G+33),0.0);                 // 28,33
        String   timeStr    = s.substring(G+33,G+39);                                              // 33,39
        long     fixtime    = this._getUTCSeconds_YMD_HMS(dateStr, timeStr); // UTC
        double   headingDeg = StringTools.parseDouble(s.substring(G+39,G+45),0.0);                 // 39,45
        String   gpio       = s.substring(G+45,G+53);                                              // 45,53
        boolean  isMiles    = s.substring(G+53,G+54).equalsIgnoreCase("L");                        // 53,54
        long     odom       = StringTools.parseLong("0x"+s.substring(G+54,G+62),0L);               // 54,62
        int      statCode   = StatusCodes.STATUS_LOCATION;
        double   altitudeM  = 0.0;
        GeoPoint geoPoint   = new GeoPoint(latitude,longitude);
        double   batteryV   = 0.0;

        /* status code */
        int      statusCode = StatusCodes.STATUS_LOCATION;

        /* validate GPS */
        if (validGPS && !geoPoint.isValid()) {
            validGPS = false;
        }

        /* GPIO input */
        long gpioInput = -1L;
        if (gpio.length() >= 8) {
            gpioInput = 0L;
            for (int i = 0; i <= 7; i++) {
                char B = gpio.charAt(7 - i);
                if (B != '0') { // should be '1' or '0'
                    gpioInput |= (1L << i);
                }
            }
            // Power on/off : either 0x01 or 0x80 (doc is not clear)
            // Acc on/off   : either 0x02 or 0x40 (doc is not clear)
            // need sample data to confirm
        }

        /* odometer */
        double odomKM = 0.0;
        if (isMiles) {
            odomKM = (double)odom * GeoPoint.KILOMETERS_PER_MILE;
        } else {
            odomKM = (double)odom;
        }

        /* adjustments to speed/heading */
        if (speedKPH < MINIMUM_SPEED_KPH) {
            speedKPH   = 0.0;
            headingDeg = 0.0;
        } else
        if (headingDeg < 0.0) {
            headingDeg = 0.0;
        }

        /* debug */
        Print.logInfo("Timestamp: " + new DateTime(fixtime));
        Print.logInfo("GeoPoint : " + geoPoint);
        Print.logInfo("Speed    : " + speedKPH + " km/h [heading " + headingDeg + "]");

        /* lookup mobile-id */
        Device device = DCServerFactory.loadDeviceByPrefixedModemID(UNIQUEID_PREFIX, modemID);
        if (device == null) {
            return null; // errors already displayed
        }
        String accountID = device.getAccountID();
        String deviceID  = device.getDeviceID();
        String uniqueID  = device.getUniqueID();
        Print.logInfo("UniqueID  : " + uniqueID);
        Print.logInfo("DeviceID  : " + accountID + "/" + deviceID);
        DataTransport dataXPort = device.getDataTransport();
        if (this.hasIPAddress() && !dataXPort.isValidIPAddress(this.getIPAddress())) {
            Print.logError("Invalid IP Address from device: " + this.getIPAddress() + 
                " [expecting " + dataXPort.getIpAddressValid() + "]");
            return null;
        }
        dataXPort.setIpAddressCurrent(this.getIPAddress());    // FLD_ipAddressCurrent
        dataXPort.setRemotePortCurrent(this.getRemotePort());  // FLD_remotePortCurrent
        dataXPort.setLastTotalConnectTime(DateTime.getCurrentTimeSec()); // FLD_lastTotalConnectTime
        if (!dataXPort.getDeviceCode().equalsIgnoreCase(Constants.DEVICE_CODE)) {
            dataXPort.setDeviceCode(Constants.DEVICE_CODE); // FLD_deviceCode
        }

        /* estimate GPS-based odometer */
        if (odomKM <= 0.0) {
            // calculate odometer
            odomKM = (ESTIMATE_ODOMETER && validGPS)? 
                device.getNextOdometerKM(geoPoint) : 
                device.getLastOdometerKM();
        } else {
            // bounds-check odometer
            odomKM = device.adjustOdometerKM(odomKM);
        }
        Print.logInfo("OdometerKM: " + odomKM);

        /* simulate Geozone arrival/departure */
        if (SIMEVENT_GEOZONES && validGPS) {
            java.util.List<Device.GeozoneTransition> zone = device.checkGeozoneTransitions(fixtime, geoPoint);
            if (zone != null) {
                for (Device.GeozoneTransition z : zone) {
                    this.insertEventRecord(device, 
                        z.getTimestamp(), z.getStatusCode(), z.getGeozone(),
                        geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                        speedKPH, headingDeg, altitudeM, odomKM,
                        gpioInput, batteryV);
                    Print.logInfo("Geozone    : " + z);
                    if (z.getStatusCode() == statusCode) {
                        // suppress 'statusCode' event if we just added it here
                        Print.logDebug("StatusCode already inserted: 0x" + StatusCodes.GetHex(statusCode));
                        statusCode = StatusCodes.STATUS_IGNORE;
                    }
                }
            }
        }

        /* status code checks */
        if (statusCode < 0) { // StatusCodes.STATUS_IGNORE
            // skip (event ignored)
        } else
        if (statusCode == StatusCodes.STATUS_IGNORE) {
            // skip (event ignored)
        } else
        if ((statusCode == StatusCodes.STATUS_LOCATION) && this.hasSavedEvents()) {
            // skip (already inserted an event)
        } else
        if (statusCode != StatusCodes.STATUS_LOCATION) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        } else
        if (validGPS && !device.isNearLastValidLocation(geoPoint,MINIMUM_MOVED_METERS)) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        }

        /* save device changes */
        try {
            //DBConnection.pushShowExecutedSQL();
            device.updateChangedEventFields();
        } catch (DBException dbe) {
            Print.logException("Unable to update Device: " + accountID + "/" + deviceID, dbe);
        } finally {
            //DBConnection.popShowExecutedSQL();
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /* TK102: parse and insert data record */
    private byte[] parseInsertRecord_TK102(String s)
    {
        /* pre-validate */
        if (s == null) {
            Print.logError("String is null");
            return null;
        }
        Print.logInfo("Parsing(TK102): " + s);

        /* parse to fields */
        String fld[] = StringTools.parseString(s, ',');
        if (fld == null) {
            // will not occur
            Print.logWarn("Fields are null");
            return null;
        } else
        if (fld.length < 15) {
            Print.logWarn("Invalid number of fields");
            return null;
        }

        /* find "imei:" */
        String mobileID = null;
        int imeiNdx = -1;
        for (int f = 0; f < fld.length; f++) {
            if (fld[f].startsWith("imei:")) {
                mobileID = fld[f].substring("imei:".length()).trim();
                imeiNdx = f;
                break;
            }
        }
        if (StringTools.isBlank(mobileID)) {
            Print.logError("'imei:' value is missing");
            return null;
        }

        /* find "GPRMC" */
        int gpx = 0;
        for (; (gpx < fld.length) && !fld[gpx].equalsIgnoreCase("GPRMC"); gpx++);
        if (gpx >= fld.length) {
            Print.logError("'GPRMC' not found");
            return null;
        } else
        if ((gpx + 12) >= fld.length) {
            Print.logError("Insufficient 'GPRMC' fields");
            return null;
        }

        /* parse data following GPRMC */
        long    hms         = StringTools.parseLong(fld[gpx + 1], 0L);
        long    dmy         = StringTools.parseLong(fld[gpx + 9], 0L);
        long    fixtime     = this._getUTCSeconds_DMY_HMS(dmy, hms);
        boolean validGPS    = fld[gpx + 2].equalsIgnoreCase("A");
        double  latitude    = validGPS? this._parseLatitude( fld[gpx + 3], fld[gpx + 4])  : 0.0;
        double  longitude   = validGPS? this._parseLongitude(fld[gpx + 5], fld[gpx + 6])  : 0.0;
        double  knots       = validGPS? StringTools.parseDouble(fld[gpx + 7], -1.0) : 0.0;
        double  headingDeg  = validGPS? StringTools.parseDouble(fld[gpx + 8], -1.0) : 0.0;
        double  speedKPH    = (knots >= 0.0)? (knots * KILOMETERS_PER_KNOT)   : -1.0;
        double  odomKM      = 0.0;

        /* altitude */
        String  altMStr     = (fld.length > (imeiNdx + 2))? fld[imeiNdx + 2] : "";
        double  altitudeM   = StringTools.parseDouble(altMStr,0.0); // meters?

        /* GPIO input */
        long    gpioInput   = -1L;

        /* battery voltage */
        // imei:353451042083525, 04, 259.7, F:4.06V, 0, 138,64306,310,26,99E8,343B
        String  battVStr    = (fld.length > (imeiNdx + 3))? fld[imeiNdx + 3] : "";
        double  batteryV    = 0.0;
        if (battVStr.startsWith("F:") || battVStr.startsWith("L:")) {
            batteryV = StringTools.parseDouble(battVStr.substring(2), 0.0);
        }

        /* invalid date? */
        if (fixtime <= 0L) {
            Print.logWarn("Invalid date: " + fld[gpx + 9] + "/" + fld[gpx + 1]);
            fixtime = DateTime.getCurrentTimeSec(); // default to now
        }

        /* status code */
        String eventCode   = ((gpx + 14) < fld.length)? StringTools.trim(fld[gpx + 14]) : "";
        int    statusCode  = StatusCodes.STATUS_LOCATION;
        DCServerConfig dcs = Main.getServerConfig();
        if ((dcs != null) && !StringTools.isBlank(eventCode)) {
            int code = dcs.translateStatusCode(eventCode, -9999);
            if (code == -9999) {
                // default 'statusCode' is StatusCodes.STATUS_LOCATION
            } else {
                statusCode = code;
            }
        }
        if (statusCode == StatusCodes.STATUS_IGNORE) {
            Print.logError("Ignoring EventCode: " + eventCode);
            return null;
        } else
        if (statusCode == StatusCodes.STATUS_NONE) {
            statusCode = (speedKPH > 0.0)? StatusCodes.STATUS_MOTION_IN_MOTION : StatusCodes.STATUS_LOCATION;
        } else
        if (XLATE_LOCATON_INMOTION && (statusCode == StatusCodes.STATUS_LOCATION) && (speedKPH > 0.0)) {
            statusCode = StatusCodes.STATUS_MOTION_IN_MOTION;
        }

        /* valid lat/lon? */
        if (validGPS && !GeoPoint.isValid(latitude,longitude)) {
            Print.logWarn("Invalid GPRMC lat/lon: " + latitude + "/" + longitude);
            latitude  = 0.0;
            longitude = 0.0;
            validGPS  = false;
        }
        GeoPoint geoPoint = new GeoPoint(latitude, longitude);

        /* adjustments to received values */
        if (speedKPH < MINIMUM_SPEED_KPH) {
            speedKPH   = 0.0;
            headingDeg = 0.0;
        } else
        if (headingDeg < 0.0) {
            headingDeg = 0.0;
        }

        /* parsed data */
        Print.logInfo("IMEI     : " + mobileID);
        Print.logInfo("Timestamp: " + fixtime + " [" + new DateTime(fixtime) + "]");
        Print.logInfo("GPS      : " + geoPoint);
        Print.logInfo("Speed    : " + StringTools.format(speedKPH ,"#0.0") + " kph " + headingDeg);
        Print.logInfo("Altitude : " + StringTools.format(altitudeM,"#0.0") + " meters");
        Print.logInfo("Battery  : " + StringTools.format(batteryV ,"#0.0") + " Volts");

        /* find Device */
        Device device = DCServerFactory.loadDeviceByPrefixedModemID(UNIQUEID_PREFIX, mobileID);
        if (device == null) {
            return null; // errors already displayed
        }
        String accountID = device.getAccountID();
        String deviceID  = device.getDeviceID();
        String uniqueID  = device.getUniqueID();
        Print.logInfo("UniqueID  : " + uniqueID);
        Print.logInfo("DeviceID  : " + accountID + "/" + deviceID);

        /* check IP address */
        DataTransport dataXPort = device.getDataTransport();
        if (this.hasIPAddress() && !dataXPort.isValidIPAddress(this.getIPAddress())) {
            DTIPAddrList validIPAddr = dataXPort.getIpAddressValid(); // may be null
            Print.logError("Invalid IP Address from device: " + this.getIPAddress() + " [expecting " + validIPAddr + "]");
            return null;
        }
        dataXPort.setIpAddressCurrent(this.getIPAddress());    // FLD_ipAddressCurrent
        dataXPort.setRemotePortCurrent(this.getRemotePort());  // FLD_remotePortCurrent
        dataXPort.setLastTotalConnectTime(DateTime.getCurrentTimeSec()); // FLD_lastTotalConnectTime
        if (!dataXPort.getDeviceCode().equalsIgnoreCase(Main.getServerName())) {
            dataXPort.setDeviceCode(Main.getServerName()); // FLD_deviceCode
        }

        /* estimate GPS-based odometer */
        if (odomKM <= 0.0) {
            // calculate odometer
            odomKM = (ESTIMATE_ODOMETER && validGPS)? 
                device.getNextOdometerKM(geoPoint) : 
                device.getLastOdometerKM();
        } else {
            // bounds-check odometer
            odomKM = device.adjustOdometerKM(odomKM);
        }
        Print.logInfo("OdometerKM: " + odomKM);

        /* simulate Geozone arrival/departure */
        if (SIMEVENT_GEOZONES && validGPS) {
            java.util.List<Device.GeozoneTransition> zone = device.checkGeozoneTransitions(fixtime, geoPoint);
            if (zone != null) {
                for (Device.GeozoneTransition z : zone) {
                    this.insertEventRecord(device, 
                        z.getTimestamp(), z.getStatusCode(), z.getGeozone(),
                        geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                        speedKPH, headingDeg, altitudeM, odomKM,
                        gpioInput, batteryV);
                    Print.logInfo("Geozone    : " + z);
                    if (z.getStatusCode() == statusCode) {
                        // suppress 'statusCode' event if we just added it here
                        Print.logDebug("StatusCode already inserted: 0x" + StatusCodes.GetHex(statusCode));
                        statusCode = StatusCodes.STATUS_IGNORE;
                    }
                }
            }
        }

        /* status code checks */
        if (statusCode < 0) { // StatusCodes.STATUS_IGNORE
            // skip (event ignored)
        } else
        if (statusCode == StatusCodes.STATUS_IGNORE) {
            // skip (event ignored)
        } else
        if ((statusCode == StatusCodes.STATUS_LOCATION) && this.hasSavedEvents()) {
            // skip (already inserted an event)
        } else
        if (statusCode != StatusCodes.STATUS_LOCATION) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        } else
        if (validGPS && !device.isNearLastValidLocation(geoPoint,MINIMUM_MOVED_METERS)) {
            this.insertEventRecord(device, 
                fixtime, statusCode, null/*geozone*/,
                geoPoint, 0/*gpsAge*/, 0.0/*HDOP*/, 0/*numSats*/,
                speedKPH, headingDeg, altitudeM, odomKM,
                gpioInput, batteryV);
        }

        /* save device changes */
        try {
            //DBConnection.pushShowExecutedSQL();
            device.updateChangedEventFields();
        } catch (DBException dbe) {
            Print.logException("Unable to update Device: " + accountID + "/" + deviceID, dbe);
        } finally {
            //DBConnection.popShowExecutedSQL();
        }

        return null;

    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
    *** Computes seconds in UTC time given values from GPS device.
    *** @param yymmdd Date received from GPS in DDMMYY format, where DD is day, MM is month,
    ***               YY is year.
    *** @param hhmmss Time received from GPS in HHMMSS format, where HH is hour, MM is minute,
    ***               and SS is second.
    *** @return Time in UTC seconds.
    ***/
    private long _getUTCSeconds_YMD_HMS(String yymmdd, String hhmmss)
    {
        // 100406  021359
        if ((yymmdd.length() < 6) || (hhmmss.length() < 6)) {
            return 0L;
        } else {
            int  YY = StringTools.parseInt(yymmdd.substring(0,2),-1);
            int  MM = StringTools.parseInt(yymmdd.substring(2,4),-1);
            int  DD = StringTools.parseInt(yymmdd.substring(4,6),-1);
            int  hh = StringTools.parseInt(hhmmss.substring(0,2),-1);
            int  mm = StringTools.parseInt(hhmmss.substring(2,4),-1);
            int  ss = StringTools.parseInt(hhmmss.substring(4,6),-1);
            if ((YY < 0) || (MM < 1) || (MM > 12) || (DD < 1) || (DD > 31) || 
                (hh < 0) || (mm < 0) || (ss < 0)) {
                return 0L;
            } else {
                long fixtime  = (new DateTime(gmtTimezone,YY+2000,MM,DD,hh,mm,ss)).getTimeSec();
                return fixtime;
            }
        }
    }

    /**
    *** Computes seconds in UTC time given values from GPS device.
    *** @param dmy    Date received from GPS in DDMMYY format, where DD is day, MM is month,
    ***               YY is year.
    *** @param hms    Time received from GPS in HHMMSS format, where HH is hour, MM is minute,
    ***               and SS is second.
    *** @return Time in UTC seconds.
    ***/
    private long _getUTCSeconds_DMY_HMS(long dmy, long hms)
    {
    
        /* time of day [TOD] */
        int    HH  = (int)((hms / 10000L) % 100L);
        int    MM  = (int)((hms / 100L) % 100L);
        int    SS  = (int)(hms % 100L);
        long   TOD = (HH * 3600L) + (MM * 60L) + SS;
    
        /* current UTC day */
        long DAY;
        if (dmy > 0L) {
            int    yy  = (int)(dmy % 100L) + 2000;
            int    mm  = (int)((dmy / 100L) % 100L);
            int    dd  = (int)((dmy / 10000L) % 100L);
            long   yr  = ((long)yy * 1000L) + (long)(((mm - 3) * 1000) / 12);
            DAY        = ((367L * yr + 625L) / 1000L) - (2L * (yr / 1000L))
                         + (yr / 4000L) - (yr / 100000L) + (yr / 400000L)
                         + (long)dd - 719469L;
        } else {
            // we don't have the day, so we need to figure out as close as we can what it should be.
            long   utc = DateTime.getCurrentTimeSec();
            long   tod = utc % DateTime.DaySeconds(1);
            DAY        = utc / DateTime.DaySeconds(1);
            long   dif = (tod >= TOD)? (tod - TOD) : (TOD - tod); // difference should be small (ie. < 1 hour)
            if (dif > DateTime.HourSeconds(12)) { // 12 to 18 hours
                // > 12 hour difference, assume we've crossed a day boundary
                if (tod > TOD) {
                    // tod > TOD likely represents the next day
                    DAY++;
                } else {
                    // tod < TOD likely represents the previous day
                    DAY--;
                }
            }
        }
        
        /* return UTC seconds */
        long sec = DateTime.DaySeconds(DAY) + TOD;
        return sec;
        
    }

    /**
    *** Computes seconds in UTC time given values from GPS device.
    *** @param locYMDhms Date received from packet in YYMMDDhhmmss format, where DD is day, MM is month,
    ***                  YY is year, hh is the hour, mm is the minutes, and ss is the seconds.  
    ***                  Unfortunately, the device is allowed to be configured to specify this time 
    ***                  relative to the local timezone, rather than GMT.  This makes determining the
    ***                  actual time of the event more difficult.
    *** @param gmtHMS    Time received from GPS in HHMMSS format, where HH is hour, MM is minute,
    ***                  and SS is second.  This time is assumed to be relative to GMT.
    *** @return Time in UTC seconds.
    ***/
    private long _getUTCSeconds_YMDhms_HMS(long locYMDhms, long gmtHMS)
    {

        /* GMT time of day */
        int    gmtHH  = (int)((gmtHMS / 10000L) % 100L);
        int    gmtMM  = (int)((gmtHMS /   100L) % 100L);
        int    gmtSS  = (int)((gmtHMS         ) % 100L);
        long   gmtTOD = (gmtHH * 3600L) + (gmtMM * 60L) + gmtSS; // seconds of day
        //Print.logInfo("GMT HHMMSS: " + gmtHMS);
        //Print.logInfo("GMT HH="+gmtHH + " MM="+gmtMM + " SS="+gmtSS +" TOD="+gmtTOD);

        /* local time of day */
        int    locHH  = (int)((locYMDhms / 10000L) % 100L);
        int    locMM  = (int)((locYMDhms /   100L) % 100L);
        int    locSS  = (int)((locYMDhms         ) % 100L);
        long   locTOD = (locHH * 3600L) + (locMM * 60L) + locSS; // seconds of day
        //Print.logInfo("Loc HHMMSS: " + locYMDhms);
        //Print.logInfo("Loc HH="+locHH + " MM="+locMM + " SS="+locSS +" TOD="+locTOD);

        /* current day */
        long ymd = locYMDhms / 1000000L; // remove hhmmss
        long DAY;
        if (ymd > 0L) {
            int    dd = (int)( ymd           % 100L);
            int    mm = (int)((ymd /   100L) % 100L);
            int    yy = (int)((ymd / 10000L) % 100L) + 2000;
            long   yr = ((long)yy * 1000L) + (long)(((mm - 3) * 1000) / 12);
            DAY       = ((367L * yr + 625L) / 1000L) - (2L * (yr / 1000L))
                         + (yr / 4000L) - (yr / 100000L) + (yr / 400000L)
                         + (long)dd - 719469L;
            long  dif = (locTOD >= gmtTOD)? (locTOD - gmtTOD) : (gmtTOD - locTOD); // difference should be small (ie. < 1 hour)
            if (dif > DateTime.HourSeconds(12)) { // 12 to 18 hours
                // > 12 hour difference, assume we've crossed a day boundary
                if (locTOD > gmtTOD) {
                    // locTOD > gmtTOD likely represents the next day
                    // ie 2011/07/10 23:00 ==> 01:00 (2011/07/11)
                    DAY++;
                } else {
                    // locTOD < gmtTOD likely represents the previous day
                    // ie 2011/07/10 01:00 ==> 23:00 (2011/07/09)
                    DAY--;
                }
            }
        } else {
            // we don't have the day, so we need to figure out as close as we can what it should be.
            long   utc = DateTime.getCurrentTimeSec();
            long   tod = utc % DateTime.DaySeconds(1);
            DAY        = utc / DateTime.DaySeconds(1);
            long   dif = (tod >= gmtTOD)? (tod - gmtTOD) : (gmtTOD - tod); // difference should be small (ie. < 1 hour)
            if (dif > DateTime.HourSeconds(12)) { // 12 to 18 hours
                // > 12 hour difference, assume we've crossed a day boundary
                if (tod > gmtTOD) {
                    // tod > TOD likely represents the next day
                    DAY++;
                } else {
                    // tod < TOD likely represents the previous day
                    DAY--;
                }
            }
        }

        /* return UTC seconds */
        long sec = DateTime.DaySeconds(DAY) + gmtTOD;
        return sec;

    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
    *** Parses latitude given values from GPS device.
    *** @param  s  Latitude String from GPS device in DDmm.mmmm format.
    *** @param  d  Latitude hemisphere, "N" for northern, "S" for southern.
    *** @return Latitude parsed from GPS data, with appropriate sign based on hemisphere or
    ***         90.0 if invalid latitude provided.
    **/
    private double _parseLatitude(String s, String d)
    {
        double _lat = StringTools.parseDouble(s, 99999.0);
        if (_lat < 99999.0) {
            double lat = (double)((long)_lat / 100L); // _lat is always positive here
            lat += (_lat - (lat * 100.0)) / 60.0;
            return d.equals("S")? -lat : lat;
        } else {
            return 90.0; // invalid latitude
        }
    }

    /**
    *** Parses longitude given values from GPS device.
    *** @param s Longitude String from GPS device in DDDmm.mmmm format.
    *** @param d Longitude hemisphere, "E" for eastern, "W" for western.
    *** @return Longitude parsed from GPS data, with appropriate sign based on hemisphere or
    *** 180.0 if invalid longitude provided.
    **/
    private double _parseLongitude(String s, String d)
    {
        double _lon = StringTools.parseDouble(s, 99999.0);
        if (_lon < 99999.0) {
            double lon = (double)((long)_lon / 100L); // _lon is always positive here
            lon += (_lon - (lon * 100.0)) / 60.0;
            return d.equals("W")? -lon : lon;
        } else {
            return 180.0; // invalid longitude
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    private EventData createEventRecord(Device device, 
        long     gpsTime, int statusCode, Geozone geozone,
        GeoPoint geoPoint, long gpsAge, double HDOP, int numSats,
        double   speedKPH, double heading, double altitudeM, double odomKM,
        long     gpioInput, double batteryV)
    {
        String accountID    = device.getAccountID();
        String deviceID     = device.getDeviceID();
        EventData.Key evKey = new EventData.Key(accountID, deviceID, gpsTime, statusCode);
        EventData evdb      = evKey.getDBRecord();
        evdb.setGeozone(geozone);
        evdb.setGeoPoint(geoPoint);
        evdb.setGpsAge(gpsAge);
        evdb.setHDOP(HDOP);
        evdb.setSatelliteCount(numSats);
        evdb.setSpeedKPH(speedKPH);
        evdb.setHeading(heading);
        evdb.setAltitude(altitudeM);
        evdb.setOdometerKM(odomKM);
        evdb.setInputMask(gpioInput);
        evdb.setBatteryVolts(batteryV);
        return evdb;
    }

    /* create and insert an event record */
    private void insertEventRecord(Device device, 
        long     gpsTime, int statusCode, Geozone geozone,
        GeoPoint geoPoint, long gpsAge, double HDOP, int numSats,
        double   speedKPH, double heading, double altitudeM, double odomKM,
        long     gpioInput, double batteryV)
    {

        /* create event */
        EventData evdb = createEventRecord(device, 
            gpsTime, statusCode, geozone,
            geoPoint, gpsAge, HDOP, numSats,
            speedKPH, heading, altitudeM, odomKM,
            gpioInput, batteryV);

        /* insert event */
        // this will display an error if it was unable to store the event
        Print.logInfo("Event: [0x" + StringTools.toHexString(statusCode,16) + "] " + 
            StatusCodes.GetDescription(statusCode,null));
        if (!DEBUG_MODE) {
            device.insertEventData(evdb);
            this.incrementSavedEventCount();
        }

    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    public static void configInit() 
    {
        DCServerConfig dcsc = Main.getServerConfig();
        if (dcsc != null) {
    
            /* common */
            UNIQUEID_PREFIX          = dcsc.getUniquePrefix();
            MINIMUM_SPEED_KPH        = dcsc.getMinimumSpeedKPH(MINIMUM_SPEED_KPH);
            ESTIMATE_ODOMETER        = dcsc.getEstimateOdometer(ESTIMATE_ODOMETER);
            SIMEVENT_GEOZONES        = dcsc.getSimulateGeozones(SIMEVENT_GEOZONES);
            SIMEVENT_DIGITAL_INPUTS  = dcsc.getSimulateDigitalInputs(SIMEVENT_DIGITAL_INPUTS) & 0xFFL;
            XLATE_LOCATON_INMOTION   = dcsc.getStatusLocationInMotion(XLATE_LOCATON_INMOTION);
            MINIMUM_MOVED_METERS     = dcsc.getMinimumMovedMeters(MINIMUM_MOVED_METERS);

            /* custom */
            PACKET_LEN_END_OF_STREAM = dcsc.getBooleanProperty(Constants.CFG_packetLenEndOfStream, PACKET_LEN_END_OF_STREAM);

        }
        
    }

}
