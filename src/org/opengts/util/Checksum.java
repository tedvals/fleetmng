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
//  Various checksum calculations
// ----------------------------------------------------------------------------
// Change History:
//  2010/01/29  Martin D. Flynn
//     -Initial release
// ----------------------------------------------------------------------------
package org.opengts.util;

/**
*** Checksum tools
**/

public class Checksum
{
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /* startup init */
    static {
        Checksum.initCrcCCITT();
    }
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------
    // CRC-CCITT? [x16 + x12 + x5 + 1]
    private static int crc_CCITT_Table[] = null;
    private static void initCrcCCITT()
    {
        if (crc_CCITT_Table == null) {
            crc_CCITT_Table = new int[256];
            for (int c = 0; c < 256; c++) {
                int fcs = 0;
                int x = (c << 8);
                for (int j = 0; j < 8; j++) {
                    if (((fcs ^ x) & 0x8000) != 0) {
                        fcs = (fcs << 1) ^ 0x1021;
                    } else { 
                        fcs = (fcs << 1);
                    }
                    x <<= 1;
                    fcs &= 0xFFFF;
                }
                crc_CCITT_Table[c] = fcs;
            }
        }
    }

    public static int calcCrcCCITT(byte b[])
    {
        return calcCrcCCITT(b,0,-1);
    }

    public static int calcCrcCCITT(byte b[], int bLen)
    {
        return calcCrcCCITT(b,0,bLen);
    }

    public static int calcCrcCCITT(byte b[], int bOfs, int bLen)
    {
        int W = 0xFFFF;
        if (b != null) {

            /* initialize CRC table */
            if (crc_CCITT_Table == null) { Checksum.initCrcCCITT(); }

            /* adjust offset/length */
            int ofs = (bOfs <= 0)? 0 : (bOfs >= b.length)? b.length : bOfs;
            int len = ((bLen >= 0) && (bLen <= (b.length-ofs)))? bLen : (b.length-ofs);

            /* calc CRC */
            for (int i = 0; i < len; i++) {
                W = (crc_CCITT_Table[(b[i+ofs] ^ (W >>> 8)) & 0xFF] ^ (W << 8)) & 0xFFFF;
            }

        }
        return W;
    }
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    public static void main(String args[])
    {
        RTConfig.setCommandLineArgs(args);
        
        String crcCCITT = RTConfig.getString("crcccitt","");
        if (!StringTools.isBlank(crcCCITT)) {
            byte b[] = StringTools.parseHex(RTConfig.getString("crcccitt",""),new byte[0]);
            int crc = calcCrcCCITT(b);
            Print.sysPrintln("CRC CCITT = 0x" + StringTools.toHexString((long)crc,16));
            System.exit(0);
        }
        
    }
    
}
