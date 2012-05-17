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
//  2009/11/10  Martin D. Flynn
//     -Initial release
//  2011/12/06  Martin D. Flynn
//     -Added method "countWorkHours"
// ----------------------------------------------------------------------------
package org.opengts.db;

import java.util.*;
import java.io.*;

import org.opengts.util.*;

public class WorkHours
{

    // ------------------------------------------------------------------------

    private static final int MINUTES_PER_DAY    = 1440;
    private static final int MINUTE_INTERVAL    = 15; // divisor of 60
    private static final int INTERVAL_SEGMENTS  = MINUTES_PER_DAY / MINUTE_INTERVAL;

    /**
    *** Day
    **/
    public static class Day
    {
        private byte todSeg[] = new byte[INTERVAL_SEGMENTS];
        // default constructor
        public Day() {
            for (int i = 0; i < this.todSeg.length; i++) {
                this.todSeg[i] = (byte)0; // not a workday
            }
        }
        // time range constructor
        public Day(int todStartMin, int todEndMin) {
            int tsNdx = todStartMin / MINUTE_INTERVAL;
            if (tsNdx < 0) { tsNdx = 0; }
            int teNdx = (todEndMin - 1) / MINUTE_INTERVAL;
            if (teNdx >= this.todSeg.length) { teNdx = this.todSeg.length - 1; }
            for (int i = 0; i < this.todSeg.length; i++) {
                this.todSeg[i] = ((i >= tsNdx) && (i <= teNdx))? (byte)1 : (byte)0;
            }
        }
        // copy constructor
        public Day(Day day) {
            for (int i = 0; i < this.todSeg.length; i++) {
                this.todSeg[i] = (day != null)? day.todSeg[i] : (byte)1;
            }
        }
        // check for work-hour match
        public boolean isMatch(int todMin) {
            int todNdx = todMin / MINUTE_INTERVAL;
            if (todNdx < 0) { todNdx = 0; }
            if (todNdx >= this.todSeg.length) { todNdx = this.todSeg.length - 1; }
            return (this.todSeg[todNdx] != (byte)0);
        }
        // find the next TOD index that matches 'working'
        public int getNextMatch(int todMin, boolean working)
        {
            int todNdx = todMin / MINUTE_INTERVAL;
            if (todNdx < 0) { todNdx = 0; }
            if (todNdx >= this.todSeg.length) { todNdx = this.todSeg.length - 1; }
            for (int i = todNdx; i < this.todSeg.length; i++) {
                boolean match = (this.todSeg[todNdx] != (byte)0);
                if (working == match) {
                    int newTodNdx = i * MINUTE_INTERVAL;
                    return (newTodNdx > todNdx)? newTodNdx : todNdx;
                }
            }
            return -1;
        }
        // count work minutes for current day
        public int countWorkMinutes(int todStartMin, int todEndMin) {
            // validate values
            if (todStartMin < 0) { todStartMin = 0; }
            if (todEndMin > MINUTES_PER_DAY) { todEndMin = MINUTES_PER_DAY; }
            if (todEndMin < todStartMin) {
                return 0;
            }
            // starting interval index
            int accumMin = 0;
            int startNdx = todStartMin / MINUTE_INTERVAL; // todStartMin < MINUTES_PER_DAY guaranteed
            Print.logInfo("Starting at index: " + startNdx);
            for (int N = startNdx; N < this.todSeg.length; N++) {
                if (this.todSeg[N] != (byte)0) { // work-hour segment
                    int todS = N * MINUTE_INTERVAL;
                    int todE = todS + MINUTE_INTERVAL;
                    if (todE > MINUTES_PER_DAY) { todE = MINUTES_PER_DAY; } // beyond end of day
                    if (todS < todStartMin) { 
                        todS = todStartMin; // move segment start min up to start time
                    }
                    if (todE >= todEndMin) { 
                        accumMin += (todEndMin - todS); // we are at the end of the time period
                        break;
                    }
                    accumMin += (todE - todS); // count full interval
                }
            }
            return accumMin;
        }
        // string representation
        public String toString() {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < this.todSeg.length; i++) {
                sb.append((this.todSeg[i] != (byte)0)? "1" : "-");
            }
            return sb.toString();
        }
    }
   
    // ------------------------------------------------------------------------

    private Day dowDay[] = new Day[7];

    /**
    *** Constructor
    **/
    public WorkHours(Day day[])
    {
        Day last = !ListTools.isEmpty(day)? day[day.length - 1] : null;
        int dlen = !ListTools.isEmpty(day)? day.length : 0;
        for (int i = 0; i < this.dowDay.length; i++) {
            this.dowDay[i] = new Day((i < dlen)? day[i] : last);
        }
    }

    /**
    *** Constructor
    **/
    public WorkHours(String keyPrefix)
    {
        this(null, keyPrefix);
    }

    /**
    *** Constructor
    **/
    public WorkHours(RTConfig.PropertyGetter dayRTP)
    {
        this(dayRTP, null);
    }

    /**
    *** Constructor
    **/
    public WorkHours(RTConfig.PropertyGetter dayRTP, String keyPrefix)
    {

        /* init */
        keyPrefix = StringTools.trim(keyPrefix);
        if (dayRTP == null) { dayRTP = RTConfig.getPropertyGetter(); }

        /* init days */
        for (int i = 0; i < this.dowDay.length; i++) {
            // sun=08:00-17:00
            String dowKey = keyPrefix + DateTime.getDayName(i,1).toLowerCase();
            String timeRange = StringTools.trim(dayRTP.getProperty(dowKey,""));
            if (!StringTools.isBlank(timeRange)) {
                String tm[] = StringTools.split(timeRange,'-');
                if (tm.length == 2) {
                    //Print.logInfo("Range: " + tm[0] + "," + tm[1]);
                    String frTm[] = StringTools.split(tm[0],':');
                    int frTod = (frTm.length >= 2)? ((StringTools.parseInt(frTm[0],0)*60)+StringTools.parseInt(frTm[1],0)) : 0;
                    //Print.logInfo("From : " + frTod);
                    String toTm[] = StringTools.split(tm[1],':');
                    int toTod = (toTm.length >= 2)? ((StringTools.parseInt(toTm[0],0)*60)+StringTools.parseInt(toTm[1],0)) : MINUTES_PER_DAY;
                    //Print.logInfo("To   : " + toTod);
                    this.dowDay[i] = new Day(frTod, toTod);
                } else {
                    // invalid time specification
                    this.dowDay[i] = new Day();
                }
            } else {
                this.dowDay[i] = new Day();
            }
        }

    }

    // ------------------------------------------------------------------------

    /**
    *** Return the requested Day
    **/
    public Day getDay(int dow)
    {
        if ((dow < 0) || (dow >= this.dowDay.length)) {
            return null;
        } else {
            return this.dowDay[dow];
        }
    }

    /**
    *** Return the requested Day
    **/
    public Day getDay(DateTime dateTime)
    {
        return this.getDay(dateTime, null);
    }

    /**
    *** Return the requested Day
    **/
    public Day getDay(DateTime dateTime, TimeZone tz)
    {
        if (dateTime == null) {
            return null;
        } else {
            int dow = dateTime.getDayOfWeek(tz);
            return this.getDay(dow);
        }
    }

    /**
    *** Return the requested Day
    **/
    public Day getDay(long timestamp, TimeZone tz)
    {
        if (timestamp <= 0L) {
            return null;
        } else {
            int dow = (new DateTime(timestamp,tz)).getDayOfWeek(tz);
            return this.getDay(dow);
        }
    }

    // ------------------------------------------------------------------------

    /**
    *** True if the specified DateTime is within the current 'WorkingHours'
    **/
    public boolean isMatch(DateTime dateTime)
    {
        return this.isMatch(dateTime, null);
    }

    /**
    *** True if the specified DateTime is within the current 'WorkingHours'
    **/
    public boolean isMatch(DateTime dateTime, TimeZone tz)
    {
        if (dateTime != null) {
            int dow = dateTime.getDayOfWeek(tz);
            Day day = this.dowDay[dow];
            int tod = (dateTime.getHour24(tz) * 60) + dateTime.getMinute(tz);
            return day.isMatch(tod);
        } else {
            return false;
        }
    }

    /**
    *** True if the specified DateTime is within the current 'WorkingHours'
    **/
    public boolean isMatch(long timestamp, TimeZone tz)
    {
        if (timestamp > 0L) {
            return this.isMatch(new DateTime(timestamp,tz), tz);
        } else {
            return false;
        }
    }

    // ------------------------------------------------------------------------

    /**
    *** Accumulate all work-hour time between the specified dates
    **/
    public double countWorkHours(DateTime startDT, DateTime stopDT, TimeZone tz)
    {

        /* invalid time specification? */
        if (startDT == null) {
            Print.logWarn("Start date is null");
            return 0.0;
        } else
        if (stopDT == null) {
            Print.logWarn("Stop date is null");
            return 0.0;
        } else
        if (startDT.isAfter(stopDT,true)) {
            Print.logWarn("Start date is after stop date");
            Print.logInfo("Start date: " + startDT);
            Print.logInfo("Stop date : " + stopDT);
            return 0.0;
        }

        /* timezone */
        if (tz == null) {
            tz = startDT.getTimeZone();
        }

        /* start */
        long startSec   = startDT.getTimeSec();
        long startDS    = startDT.getDayStart(tz);
        long startDE    = startDT.getDayEnd(tz);
        int  startMOD   = startDT.getSecondOfDay(tz) / 60; // round down
        int  startDOW   = startDT.getDayOfWeek(tz);
        Day  startDay   = this.getDay(startDOW);

        /* stop */
        long stopSec    = stopDT.getTimeSec();
        long stopDS     = stopDT.getDayStart(tz);
        long stopDE     = stopDT.getDayEnd(tz);
        int  stopMOD    = (stopDT.getSecondOfDay(tz) + 59) / 60; // round up

        /* simple case: same day? */
        if (startDE >= stopSec) {
            Print.logInfo("Same Day ...");
            Print.logInfo("Start MOD: " + startMOD);
            Print.logInfo("Stop MOD : " + stopMOD);
            return (double)startDay.countWorkMinutes(startMOD, stopMOD) / 60.0;
        }

        /* count work minutes */
        long accumMin = 0L;
        long timeSec = startDS + DateTime.DaySeconds(1); // next day
        for (;;) {
            Day day = this.getDay(timeSec,tz);
            long DE = timeSec + DateTime.DaySeconds(1) - 1; // end of day
            if (DE >= stopSec) {
                // last day
                accumMin += day.countWorkMinutes(0,stopMOD);
                break;
            }
            accumMin += day.countWorkMinutes(0,MINUTES_PER_DAY);
            timeSec += DateTime.DaySeconds(1);
        }
        return (double)accumMin / 60.0;

    }
    
    // ------------------------------------------------------------------------

    /**
    *** Returns a String repreentation of this instance
    **/
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append("0   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15  16  17  18  19  20  21  22  23  \n");
        sb.append("---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+\n");
        for (int i = 0; i < this.dowDay.length; i++) {
            sb.append(this.dowDay[i].toString());
            sb.append("\n");
        }
        return sb.toString();
    }
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------
    
    private static final String ARG_LIST[]          = new String[] { "list" };
    private static final String ARG_TIME[]          = new String[] { "time" };
    private static final String ARG_START_TIME[]    = new String[] { "startTime", "beginTime", "st" };
    private static final String ARG_STOP_TIME[]     = new String[] { "stopTime" , "endTime"  , "et" };

    public static void main(String argv[])
    {
        RTConfig.setCommandLineArgs(argv);
        WorkHours wh = new WorkHours(RuleFactory.PROP_rule_workHours_);

        /* list */
        if (RTConfig.hasProperty(ARG_LIST)) {
            Print.sysPrintln(wh.toString());
            System.exit(0);
        }

        /* time check */
        if (RTConfig.hasProperty(ARG_TIME)) {
            String time = RTConfig.getString(ARG_TIME,"");
            try {
                DateTime dt = !StringTools.isBlank(time)? DateTime.parseArgumentDate(RTConfig.getString(ARG_TIME,""),null,false) : new DateTime();
                Print.sysPrintln("Time     : " + dt.toString());
                Print.sysPrintln("IsMatch  : " + wh.isMatch(dt));
                Print.sysPrintln("WorkHours:");
                Print.sysPrintln(wh.toString());
            } catch (DateTime.DateParseException dpe) {
                Print.sysPrintln("Error: Unable to parse time - " + time);
            }
            System.exit(0);
        }

        /* time range */
        if (RTConfig.hasProperty(ARG_START_TIME)) {
            try{
                DateTime startDT = DateTime.parseArgumentDate(RTConfig.getString(ARG_START_TIME,""), null, false);
                DateTime stopDT  = DateTime.parseArgumentDate(RTConfig.getString(ARG_STOP_TIME ,""), null, false);
                double hrs = wh.countWorkHours(startDT, stopDT, null);
                Print.sysPrintln("Start Date/Time  : " + startDT);
                Print.sysPrintln("Stop Date/Time   : " + stopDT);
                Print.sysPrintln("Counted WorkHours: " + hrs);
                System.exit(0);
            } catch (DateTime.DateParseException dpe) {
                Print.logException("Invalid Date format",dpe);
                System.exit(99);
            }
        }

    }
    
}
