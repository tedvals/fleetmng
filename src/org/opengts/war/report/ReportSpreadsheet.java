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
//  2011/01/28  Martin D. Flynn
//     -Initial release
// ----------------------------------------------------------------------------
package org.opengts.war.report;

import java.util.*;
import java.io.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.opengts.util.*;
import org.opengts.db.*;

import org.opengts.war.tools.*;
import org.opengts.war.report.*;

public class ReportSpreadsheet // FORMAT_XLS
{
    
    // ------------------------------------------------------------------------
    
    private static boolean  initExcelSpreadsheetClass   = false;
    private static Class    ExcelSpreadsheetClass       = null;

    public static Class GetExcelSpreadsheetClass()
    {
        if (!initExcelSpreadsheetClass) {
            initExcelSpreadsheetClass = true;
            try {
                ExcelSpreadsheetClass = Class.forName("org.opengts.util.ExcelTools$Spreadsheet");
            } catch (NoClassDefFoundError ncdfe) {
                Print.logWarn("Excel interface not supported: " + ncdfe);
            } catch (ClassNotFoundException cnfe) {
                Print.logWarn("Excel interface not supported: " + cnfe);
            } catch (Throwable th) {
                Print.logException("Excel interface not supported", th);
            }
        }
        return ExcelSpreadsheetClass; // may be null
    }
    
    public static boolean IsExcelSpreadsheetSupported()
    {
        return (GetExcelSpreadsheetClass() != null);
    }
    
    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    private ReportData  rptData = null;
    private ExcelAPI    excel = null;
    private boolean     xlsx = false;
    
    private int         currentRow = 0;
    private int         currentCol = 0;

    public ReportSpreadsheet(boolean xlsx, ReportData rd)
    {
        this.xlsx = xlsx;
        this.rptData = rd;

        /* create interface instance */
        Class ssClass = GetExcelSpreadsheetClass();
        if (ssClass == null) {
            return;
        }

        /* create Excel Spreadsheet instance */
        try {
            Print.logInfo("Creating Excel spreadsheet report instance ...");
            this.excel = (ExcelAPI)ssClass.newInstance();
            this.excel.init(this.xlsx, this.rptData.getReportName());
        } catch (Throwable th) {
            Print.logException("Error creating Excel Spreadsheet instance", th);
            this.excel = null;
        }

    }
    
    public boolean isValid()
    {
        return (this.excel != null);
    }
    
    // ------------------------------------------------------------------------

    public boolean isXLS()
    {
        return !this.xlsx;
    }

    public boolean isXLSX()
    {
        return this.xlsx;
    }
    
    // ------------------------------------------------------------------------

    public int getCurrentRowIndex()
    {
        return this.currentRow;
    }

    public int getCurrentColumnIndex()
    {
        return this.currentCol;
    }

    public int incrementRowIndex()
    {
        this.currentRow++;
        this.currentCol = 0;
        return this.currentRow;
    }

    public int incrementColumnIndex()
    {
        this.currentCol++;
        return this.currentCol;
    }

    // ------------------------------------------------------------------------

    public void setHeaderTitle(String title)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colSpan = this.rptData.getColumnCount();
            //Print.logInfo("HeaderTitle ["+rowIndex+"]: " + title);
            this.excel.setTitle(rowIndex, title, colSpan);
            this.incrementRowIndex();
        } else {
            Print.logWarn("Excel spreadsheet reporting not available ...");
        }
    }

    public void setHeaderSubtitle(String title)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colSpan = this.rptData.getColumnCount();
            //Print.logInfo("HeaderSubtitle ["+rowIndex+"]: " + title);
            this.excel.setSubtitle(rowIndex, title, colSpan);
            this.incrementRowIndex();
        } else {
            //Print.logWarn("Excel spreadsheet reporting not available ...");
        }
    }

    public void setBlankRow()
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colSpan = this.rptData.getColumnCount();
            this.excel.setBlankRow(rowIndex, colSpan);
            this.incrementRowIndex();
        }
    }

    // ------------------------------------------------------------------------

    public void addHeaderColumn(String colTitle, int colWidth)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colIndex = this.getCurrentColumnIndex();
            //Print.logInfo("HeaderColumn ["+rowIndex+":"+colIndex+"]: " + colTitle);
            this.excel.addHeaderColumn(rowIndex, colIndex, colTitle, colWidth);
            this.incrementColumnIndex();
        }
    }
    
    // ------------------------------------------------------------------------

    public void addBodyColumn(Object value)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colIndex = this.getCurrentColumnIndex();
            //Print.logInfo("BodyColumn ["+rowIndex+":"+colIndex+"]: " + value);
            this.excel.addBodyColumn(rowIndex, colIndex, value);
            this.incrementColumnIndex();
        }
    }

    public void addSubtotalColumn(Object value)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colIndex = this.getCurrentColumnIndex();
            //Print.logInfo("SubtotalColumn ["+rowIndex+":"+colIndex+"]: " + value);
            this.excel.addSubtotalColumn(rowIndex, colIndex, value);
            this.incrementColumnIndex();
        }
    }

    public void addTotalColumn(Object value)
    {
        if (this.excel != null) {
            int rowIndex = this.getCurrentRowIndex();
            int colIndex = this.getCurrentColumnIndex();
            //Print.logInfo("TotalColumn ["+rowIndex+":"+colIndex+"]: " + value);
            this.excel.addTotalColumn(rowIndex, colIndex, value);
            this.incrementColumnIndex();
        }
    }

    // ------------------------------------------------------------------------

    public boolean write(OutputStream out)
    {
        if (this.excel != null) {
            Print.logInfo("Writing Excel spreadsheet to output stream ...");
            return this.excel.write(out);
        } else {
            Print.logWarn("Excel spreadsheet reporting not available ...");
            return false;
        }
    }

}
