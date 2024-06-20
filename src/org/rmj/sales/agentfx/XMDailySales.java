package org.rmj.sales.agentfx;

import java.util.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.CommonUtils;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.printer.EPSONPrint;
import org.rmj.appdriver.constants.CRMEvent;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.appdriver.constants.UserRight;
import org.rmj.appdriver.iface.GEntity;
import org.rmj.payment.agent.XMCashDrawer;
import org.rmj.sales.base.printer.EndShift;
import org.rmj.sales.base.printer.XReading;
import org.rmj.sales.base.printer.ZReading;
import org.rmj.sales.pojo.UnitDailySummary;

public class XMDailySales {
    private final String pxeModuleName = this.getClass().getSimpleName();
    
    
    public XMDailySales(GRider foApp, boolean fbWithParent){
        p_oApp = foApp;
        p_bWithParent = fbWithParent;
        
        p_oTrans = null;
        
        p_sPOSNo = System.getProperty("pos.clt.crm.no");
        p_sVATReg = System.getProperty("pos.clt.tin");
        p_sCompny = System.getProperty("pos.clt.nm");
        
        p_sAccrdt = System.getProperty("pos.clt.accrd.no");
        p_sPermit = System.getProperty("pos.clt.prmit.no");
        p_sSerial = System.getProperty("pos.clt.srial.no");
        p_sTermnl = System.getProperty("pos.clt.trmnl.no");
        p_nZRdCtr = Integer.valueOf(System.getProperty("pos.clt.zcounter"));

        p_sWebSvr = System.getProperty("pos.clt.web.svrx");
        p_sPrintr = System.getProperty("pos.clt.prntr.01");
    }
    
    public boolean NewTransaction(){
        try {
            //mac 2020.07.04
            //  check start date
            String lsSQL = "SELECT dPOSStart FROM Cash_Reg_Machine WHERE sIDNumber = " + SQLUtil.toSQL(p_sPOSNo);
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            
            if (!loRS.next()){
                p_nSaleStat = 7;
                return false;
            } else{
                if (p_oApp.getServerDate().before(loRS.getDate("dPOSStart"))){
                    p_nSaleStat = 6;
                    return false;
                }
            }
            
            //don't allow sys admin to login on pos
            if (p_oApp.getUserLevel() == UserRight.SYSADMIN){
                p_nSaleStat = 4;
                return false;
            }
            
            //Set default as Sales for today is okey...
            p_nSaleStat = 2;
            
            p_sTranDate = SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATE);
            if (!validSummary(false)){                
                setMessage("There are open SALES ORDER!  Please close them first");
                p_nSaleStat = 0;
                return false;
            }
            
            String lsCashierx = p_oApp.getUserID();
            boolean lbSupervisor = p_oApp.getUserLevel() >= UserRight.SUPERVISOR;
            
            //check unclosed previous day summary
            lsSQL = "SELECT sTranDate, sCRMNumbr, sCashierx" +
                            " FROM Daily_Summary" +
                            " WHERE sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                                " AND sTranDate < " + SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX) +
                                " AND cTranStat = '0'";
            loRS = p_oApp.executeQuery(lsSQL);
            
            if (loRS.next()){
                if (System.getProperty("pos.clt.date") == null || 
                        System.getProperty("pos.clt.date").isEmpty()){
                    System.setProperty("pos.clt.date", loRS.getString("sTranDate"));
                }
                
                System.out.println(System.getProperty("pos.clt.date"));
                setMessage("Unclosed previous day transaction decteted.");
                
                lsCashierx = loRS.getString("sCashierx");
                p_sCashier = lsCashierx;
                
                if (lsCashierx.equals(p_oApp.getUserID())){ //own account
                    if (PrintTXReading(loRS.getString("sTranDate"), p_sPOSNo)){
                        setMessage("X-Reading for " + loRS.getString("sTranDate") + " printed successfuly.\n" + 
                                    "Please re-login to continue use of POS.");
                    }
                    
                    //declare end-of-day
                    if (PrintTZReading(loRS.getString("sTranDate"), loRS.getString("sTranDate"), p_sPOSNo)){
                        setMessage("End of Day Transaction Summary successfully printed.\n" +
                                    "Please re-login to continue use of POS.");
                    }
                } else {
                    if (lbSupervisor){
                        if (ShowMessageFX.YesNo(getCashier(lsCashierx) + " is the Cashier-In-Charge for the date " + loRS.getString("sTranDate") + ".\n" +
                                                "Do you want to continue using your account to close her transaction?", "Confirm", null)){
                            if (PrintTXReading(loRS.getString("sTranDate"), p_sPOSNo)){
                                setMessage("X-Reading for " + loRS.getString("sTranDate") + " printed successfuly.\n" + 
                                            "Please re-login to continue use of POS.");
                            }
                        }
                    }else{
                        ShowMessageFX.Information(getCashier(lsCashierx) + " is the Cashier-In-Charge for the date " + loRS.getString("sTranDate") + ".\n" +
                                                    "Please ask to login her account to close the transaction.", "Notice", null);
                    }
                }
                
                p_nSaleStat = -1;
                return false;
            } else {
                lsSQL = "SELECT sTranDate, sCRMNumbr, sCashierx" +
                            " FROM Daily_Summary" +
                            " WHERE sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                            " AND sTranDate < " + SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX) +
                            " AND cTranStat < '2'";
                
                loRS = p_oApp.executeQuery(lsSQL);
                
                if (loRS.next()){
                    if (System.getProperty("pos.clt.date") == null || 
                        System.getProperty("pos.clt.date").isEmpty()){
                        System.setProperty("pos.clt.date", loRS.getString("sTranDate"));
                    }

                    System.out.println(System.getProperty("pos.clt.date"));
                    
                    if (PrintTZReading(loRS.getString("sTranDate"), loRS.getString("sTranDate"), p_sPOSNo)){
                        setMessage("End of Day Transaction Summary successfully printed.\n" +
                                    "Please re-login to continue use of POS.");
                        
                        p_nSaleStat = 4;
                        return false;
                    }
                }
            }
            
            lsSQL = "SELECT sTranDate, sCRMNumbr, sCashierx" +
                    " FROM Daily_Summary" +
                    " WHERE sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                        " AND sTranDate = " + SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX) +
                        " AND cTranStat = '0'";
            
            loRS = p_oApp.executeQuery(lsSQL);
            
            //Is there an unprocess Terminal X Reading
            if (loRS.next()){
                lsCashierx = loRS.getString("sCashierx");
                
                if (lsCashierx.equals(p_oApp.getUserID())){
                    p_nSaleStat = 0;
                } else {
                    if (lbSupervisor){
                        if (ShowMessageFX.YesNo(getCashier(lsCashierx) + " was the cashier in-charge. Do you continue with the account?", "Confirm", null)){
                            p_sCashier = lsCashierx;
                            p_nSaleStat = 0;
                        } else {
                            p_nSaleStat = 4;
                            return false;
                        }
                    } else {
                        setMessage("Someone is still logged as cashier. Kinldy end the shift first.");
                        p_nSaleStat = 4;
                        return false;
                    }
                }
            }
            
            //Prepare query that will check for the existence of CASHIER DAILY SALES
            lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTranDate = " + SQLUtil.toSQL(SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX)) +
                                                            " AND sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo));
            
            loRS = p_oApp.executeQuery(lsSQL);
            
            String lsCondition = "0=1";
            
            //Is there an existing CASHIER DAILY SALES    
            while (loRS.next()){
                if (loRS.getString("cTranStat").equals(TransactionStatus.STATE_OPEN)){
                    lsCondition = "sTranDate = " + SQLUtil.toSQL(SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX)) +
                                    " AND sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                                    " AND sCashierx = " + SQLUtil.toSQL(lsCashierx) +
                                    " AND cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_OPEN);
                    break;
                }else if (loRS.getString("cTranStat").equals(TransactionStatus.STATE_CLOSED)){
                    lsCondition = "sTranDate = " + SQLUtil.toSQL(SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX)) +
                                    " AND sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                                    " AND sCashierx = " + SQLUtil.toSQL(lsCashierx) +
                                    " AND cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED);
                    lsSQL = MiscUtil.addCondition(lsSQL, lsCondition);
                    p_oTrans = loadSummary(lsSQL);
                    
                    setMessage("Your shift for this day was already closed.");
                    p_nSaleStat = 5;
                    return false; 
                }else if (loRS.getString("cTranStat").equals(TransactionStatus.STATE_POSTED)){
                    setMessage("Sales for the day was already closed.");
                    p_nSaleStat = 1;
                    return false; 
                }
            }
            
            //check the latest date of the pos transaction.
            //we should print x and z reading for days the pos are not used
            lsSQL = "SELECT sTranDate, sCRMNumbr, sCashierx" +
                            " FROM Daily_Summary" +
                            " WHERE sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo) +
                                " AND cTranStat = '2'" +
                            " ORDER BY sTranDate DESC LIMIT 1";
            loRS = p_oApp.executeQuery(lsSQL);
            
            String lsOldDate;
            
            if (loRS.next()){
                lsOldDate = loRS.getString("sTranDate");
                lsOldDate = lsOldDate.substring(0, 4) + "-" + lsOldDate.substring(4, 6) + "-" + lsOldDate.substring(6, 8);
                
                Date ldOldDate = SQLUtil.toDate(lsOldDate, SQLUtil.FORMAT_SHORT_DATE);
                
                lsOldDate = SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATE);
                Date ldNewDate = SQLUtil.toDate(lsOldDate, SQLUtil.FORMAT_SHORT_DATE);
                
                int lnDays = Integer.parseInt(String.valueOf(CommonUtils.dateDiff(ldNewDate, ldOldDate)));
                if (lnDays > 0){ //x and z reading is not issued for number of lnDays
                    for (int lnCtr = 1; lnCtr <= lnDays-1; lnCtr++){
                        p_oTrans = new UnitDailySummary();
                    
                        ldNewDate = CommonUtils.dateAdd(ldOldDate, lnCtr);
                        p_sTranDate = SQLUtil.dateFormat(ldNewDate, SQLUtil.FORMAT_SHORT_DATE);
                        
                        p_oTrans.setTransactionDate(SQLUtil.dateFormat(ldNewDate, SQLUtil.FORMAT_SHORT_DATEX));
                        p_oTrans.setMachineNo(p_sPOSNo);
                        p_oTrans.setCashier(p_oApp.getUserID());
                        p_sCashier = p_oTrans.getCashier();
                        p_nEditMode = EditMode.ADDNEW;

                        p_oTrans.setOpeningBalance(0.00);
                        p_oTrans.setDateOpened(ldNewDate);
                        SaveTransaction();
                        
                        System.setProperty("pos.clt.date", p_oTrans.getTransactionDate());
                        System.out.println(System.getProperty("pos.clt.date"));
                        
                        //end cashier shift
                        if (PrintTXReading(p_oTrans.getTransactionDate(), p_sPOSNo)){
                            setMessage("X-Reading for " + loRS.getString("sTranDate") + " printed successfuly.\n" + 
                                        "Please re-login to continue use of POS.");
                        }
                        //declare end-of-day
                        if (PrintTZReading(p_oTrans.getTransactionDate(), p_oTrans.getTransactionDate(), p_sPOSNo)){
                            setMessage("End of Day Transaction Summary successfully printed.\n" +
                                        "Please re-login to continue use of POS.");
                        }
                    }
                }
            }
            //END : check the latest date of the pos transaction.

            lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTranDate = " + SQLUtil.toSQL(SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX)) +
                                                            " AND sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo));
            
            lsSQL = MiscUtil.addCondition(lsSQL, lsCondition);
            p_oTrans = loadSummary(lsSQL);
            
            if (p_oTrans == null) {
                p_oTrans = new UnitDailySummary();
                
                p_sTranDate = SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATE);
                p_oTrans.setTransactionDate(SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX));
                p_oTrans.setMachineNo(p_sPOSNo);
                p_oTrans.setCashier(p_oApp.getUserID());
                
                p_sCashier = p_oTrans.getCashier();
                p_nEditMode = EditMode.ADDNEW;
                
                CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                        , CRMEvent.SHIFT_OPENING, "Date: " + p_oTrans.getTransactionDate() + "; " + "Cashier: " + p_oTrans.getCashier()
                        , System.getProperty("pos.clt.crm.no"));
            } else {
                p_nEditMode = EditMode.READY;
                p_sCashier = lsCashierx;
                p_nSaleStat = 2;
                
                CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                        CRMEvent.LOAD_SHIFT, "Date: " + p_oTrans.getTransactionDate() + "; " + "Cashier: " + p_oTrans.getCashier()
                        , System.getProperty("pos.clt.crm.no"));
            }
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
            p_nSaleStat = -1;
            return false;
        }
        
        return true;
    }
    
    public boolean SaveTransaction(){
        if (!(p_nEditMode == EditMode.ADDNEW ||
            p_nEditMode == EditMode.READY ||
            p_nEditMode == EditMode.UPDATE)){
            
            ShowMessageFX.Warning("Invalid Edit Mode Detected.", "Warning", null);
            return false;
        }
        
        String lsSQL;
        String lsCondition;
        
        if (p_bWithParent) p_oApp.beginTrans();
        
        if (p_nEditMode == EditMode.ADDNEW){            
            lsSQL = MiscUtil.makeSQL(p_oTrans);
        } else {
            lsCondition = "sTranDate = " + SQLUtil.toSQL(p_oTrans.getValue("sTranDate")) +
                            " AND sCRMNumbr = " + SQLUtil.toSQL(p_oTrans.getValue("sCRMNumbr")) +
                            " AND sCashierx = " + SQLUtil.toSQL(p_oTrans.getValue("sCashierx"));
            
            lsSQL = MiscUtil.addCondition(getSQ_Master(), lsCondition);
            
            UnitDailySummary loOldEnt = loadSummary(lsSQL);
            lsSQL = MiscUtil.makeSQL((GEntity) p_oTrans, (GEntity) loOldEnt, lsCondition);
        }       
        
        if (!lsSQL.equals("")) {
            if (p_oApp.executeQuery(lsSQL, pxeMasTable, p_oApp.getBranchCode(), "") == 0){
                setMessage(p_oApp.getErrMsg() + "\n" + p_oApp.getMessage());
                if (p_bWithParent) p_oApp.rollbackTrans();
                return false;
            }
        }
        
        //create cash drawer record
        if (p_nEditMode == EditMode.ADDNEW){
            XMCashDrawer loDrawer = new XMCashDrawer(p_oApp, p_oApp.getBranchCode(), true, false);
            if (loDrawer.NewTransaction()){
                loDrawer.setMaster("nOpenAmtx", p_oTrans.getOpeningBalance());
                loDrawer.setMaster("sTranDate", p_oTrans.getTransactionDate());
                loDrawer.setMaster("sCashierx", p_oTrans.getCashier());

                if (!loDrawer.SaveTransaction()){
                    setMessage(loDrawer.getMessage());
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    return false;
                }
                
                CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.CASH_DEPOSIT, String.valueOf(p_oTrans.getOpeningBalance()), System.getProperty("pos.clt.crm.no"));
            } else {
                setMessage(loDrawer.getMessage());
                if (p_bWithParent) p_oApp.rollbackTrans();
                return false;
            }
        }
        
        if (p_bWithParent) p_oApp.commitTrans();
        
        p_nEditMode = EditMode.READY;
        
        return true;
    }
    
    public boolean OpenTransaction(String fsTranDate, String fsCRMNumbr, String fsCashierx){
        String lsSQL = "sTranDate = " + SQLUtil.toSQL(fsTranDate) +
                        " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                        " AND sCashierx = " + SQLUtil.toSQL(fsCashierx);
        
        lsSQL = MiscUtil.addCondition(getSQ_Master(), lsSQL);
        
        p_oTrans = loadSummary(lsSQL);
        
        if (p_oTrans == null){
            p_nEditMode = EditMode.UNKNOWN;
            return false;
        }
        
        String lsTranDate = p_oTrans.getTransactionDate();
        p_sTranDate = lsTranDate.substring(0, 4) + "-" + lsTranDate.substring(4, 6) + "-" + lsTranDate.substring(6, 8);
        
        p_nEditMode = EditMode.READY;
        return true;
    }
    
    public boolean ComputeCashDrawer(String fsTranDate, String fsCRMNumbr, String fsCashierx){
        if (!OpenTransaction(fsTranDate, fsCRMNumbr, fsCashierx)){
            ShowMessageFX.Warning("Cannot open the daily transaction", "Warning", null);
            return false;
        }
        
        String lsTranDate = p_oTrans.getTransactionDate();
        lsTranDate = lsTranDate.substring(0, 4) + "-" + lsTranDate.substring(4, 6) + "-" + lsTranDate.substring(6, 8);
        
        if (p_oTrans.getTranStat().equals(TransactionStatus.STATE_OPEN)) computeTotalCashierSales();
        
        String lsSQL = "UPDATE Cash_Drawer SET" + 
                            "  nCashAmtx = " + SQLUtil.toSQL(p_oTrans.getCashAmount()) + 
                            ", nCheckAmt = " + SQLUtil.toSQL(p_oTrans.getCheckAmount()) + 
                            ", nChargexx = " + SQLUtil.toSQL(p_oTrans.getChargeInvoiceAmount()) + 
                            ", nCredtCrd = " + SQLUtil.toSQL(p_oTrans.getCreditCardAmount()) + 
                            ", nGiftCert = " + SQLUtil.toSQL(p_oTrans.getGiftAmount()) + 
                            ", nFinAmntx = " + SQLUtil.toSQL(p_oTrans.getFinanceAmount()) + 
                            ", dModified = " + SQLUtil.toSQL(p_oApp.getServerDate()) + 
                        " WHERE sTransNox LIKE " + SQLUtil.toSQL(p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no") + "%") + 
                            " AND sTranDate = " + SQLUtil.toSQL(fsTranDate) + 
                            " AND sCashierx = " + SQLUtil.toSQL(fsCashierx);
        
        p_oApp.executeQuery(lsSQL, "Cash_Drawer", p_oApp.getBranchCode(), "");
        return true;
    }
    
    public boolean PrintCashierSales(String fsTranDate, String fsCRMNumbr, String fsCashierx){
        if (!OpenTransaction(fsTranDate, fsCRMNumbr, fsCashierx)){
            ShowMessageFX.Warning("Cannot open the daily transaction", "Warning", null);
            return false;
        }
        
        String lsTranDate = p_oTrans.getTransactionDate();
        lsTranDate = lsTranDate.substring(0, 4) + "-" + lsTranDate.substring(4, 6) + "-" + lsTranDate.substring(6, 8);
        
        if (p_oTrans.getTranStat().equals(TransactionStatus.STATE_OPEN)) {
            try {
                computeTotalCashierSales();
                
                String lsSQL = "SELECT dOpenedxx, dClosedxx, nAccuSale FROM Daily_Summary" +
                        " WHERE dClosedxx < " + SQLUtil.toSQL(p_oTrans.getDateOpened()) +
                        " ORDER BY dOpenedxx DESC LIMIT 1";
                ResultSet loRS = p_oApp.executeQuery(lsSQL);
                
                double lnNetAmnt = (double) p_oTrans.getSalesAmount() - ((double) p_oTrans.getDiscount() + (double) p_oTrans.getPWDDisc() + (double) p_oTrans.getVATDisc());
                if (!loRS.next())
                    p_oTrans.setAccumulatedSale(lnNetAmnt);
                else
                    p_oTrans.setAccumulatedSale(lnNetAmnt + loRS.getDouble("nAccuSale"));
                
            } catch (SQLException ex) {
                Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
                return false;
            }
        }
        
        return printYAPI();
    }
    
    public boolean PrintTXReading(String fsTrandate, String fsCRMNumbr){
        try {
            boolean bCurrent = SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX).equals(fsTrandate);
            
            if (!validSummary(bCurrent)){
                setMessage("There are open SALES ORDER!  Please save them first.");
                return false;
            }
            
            String lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTranDate = " + SQLUtil.toSQL(fsTrandate) +
                                                                    " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                                                                    " AND sCashierx = " + SQLUtil.toSQL(p_sCashier) +
                                                                    " AND cTranStat = '0'");
            
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            long lnRow = MiscUtil.RecordCount(loRS);
            
            if (lnRow == 0){
                ShowMessageFX.Information("There are no transaction for this date(" + fsTrandate + ").", "Notice", null);
                return false;
            }else if (lnRow > 1){
                ShowMessageFX.Information("Unclosed cashier shifts detected for the date(" + fsTrandate + ").", "Notice", null);
                return false;
            }
            
            if (!OpenTransaction(fsTrandate, fsCRMNumbr, p_sCashier)){
                ShowMessageFX.Warning("Can't open transaction of " + getCashier(p_sCashier), "Warning", null);
                return false;
            }
            
            if (p_bWithParent) p_oApp.beginTrans();
            Timestamp loClosed = null;
            
            loRS.first();
            if (loRS.getString("cTranStat").equals("0")){
                computeTotalCashierSales();
                
                lsSQL = "SELECT dOpenedxx, dClosedxx, nAccuSale FROM Daily_Summary" +
                        " WHERE dClosedxx < " + SQLUtil.toSQL(p_oTrans.getDateOpened()) +
                        " ORDER BY dOpenedxx DESC LIMIT 1";
                loRS = p_oApp.executeQuery(lsSQL);
                
                double lnNetAmnt = (double) p_oTrans.getSalesAmount() - ((double) p_oTrans.getDiscount() + (double) p_oTrans.getPWDDisc() + (double) p_oTrans.getVATDisc());
                if (!loRS.next())
                    p_oTrans.setAccumulatedSale(lnNetAmnt);
                else 
                    p_oTrans.setAccumulatedSale(lnNetAmnt + loRS.getDouble("nAccuSale"));
                
                loClosed = p_oApp.getServerDate();
                
                lsSQL = "UPDATE Daily_Summary" +
                        " SET  nSalesAmt = " + p_oTrans.getSalesAmount() +
                            ", nVATSales = " + p_oTrans.getVATableSales() +
                            ", nVATAmtxx = " + p_oTrans.getVATAmount() +
                            ", nNonVATxx = " + p_oTrans.getNonVATAmount() +
                            ", nZeroRatd = " + p_oTrans.getZeroRatedSales() +
                            ", nDiscount = " + p_oTrans.getDiscount() +
                            ", nVatDiscx = " + p_oTrans.getVATDisc() +
                            ", nPWDDiscx = " + p_oTrans.getPWDDisc() +
                            ", nReturnsx = " + p_oTrans.getReturnAmount() +
                            ", nVoidAmnt = " + p_oTrans.getVoidAmount() +
                            ", nAccuSale = " + p_oTrans.getAccumulatedSale() +
                            ", nCashAmnt = " + p_oTrans.getCashAmount() +
                            ", nChckAmnt = " + p_oTrans.getCheckAmount() +
                            ", nCrdtAmnt = " + p_oTrans.getCreditCardAmount() +
                            ", nChrgAmnt = " + p_oTrans.getChargeInvoiceAmount() +
                            ", nGiftAmnt = " + p_oTrans.getGiftAmount() +
                            ", nFinAmntx = " + p_oTrans.getFinanceAmount() +
                            ", sORNoFrom = " + SQLUtil.toSQL(p_oTrans.getORFrom()) +
                            ", sORNoThru = " + SQLUtil.toSQL(p_oTrans.getORThru()) +
                            ", nZReadCtr = " + p_nZRdCtr +
                            ", dClosedxx = " + SQLUtil.toSQL(loClosed) +
                        " WHERE sTranDate = " + SQLUtil.toSQL(p_oTrans.getTransactionDate()) +
                            " AND sCRMNumbr = " + SQLUtil.toSQL(p_oTrans.getMachineNo()) +
                            " AND sCashierx = " + SQLUtil.toSQL(p_oTrans.getCashier());
                
                if (p_oApp.executeQuery(lsSQL, pxeMasTable, p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    return false;
                }
            }
            
            //set the closing date to class
            p_oTrans.setDateClosed(loClosed);
            
            if (!printXAPI()){
                if (p_bWithParent) p_oApp.rollbackTrans();
                return false;
            }
            
            //create query to post the daily sales
            if (p_oTrans.getTranStat().equals(TransactionStatus.STATE_OPEN)){
                lsSQL = "UPDATE " + pxeMasTable +
                        " SET cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED) +
                        " WHERE sTranDate = " + SQLUtil.toSQL(fsTrandate) +
                            " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                            " AND cTranStat = '0'";
                p_oApp.executeQuery(lsSQL, pxeMasTable, p_oApp.getBranchCode(), "");
            }
            
            if (p_bWithParent) p_oApp.commitTrans();
            
            setMessage("X-Reading was perform successfully!!");
            return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
            return false;
        }
    }
    
    public boolean RePrintTXReading(String fsTrandate, String fsCRMNumbr){
        try {
            //if (!InitMachine()) return false;
            
            boolean bCurrent = SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_SHORT_DATEX).equals(fsTrandate);
            
            if (!validSummary(bCurrent)){
                setMessage("There are open SALES ORDER!  Please save them first.");
                return false;
            }
            
            String lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTranDate = " + SQLUtil.toSQL(fsTrandate) +
                                                                    " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                                                                    " AND sCashierx = " + SQLUtil.toSQL(p_sCashier) +
                                                                    " AND cTranStat IN ('1', '2')");
            
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            long lnRow = MiscUtil.RecordCount(loRS);
            
            if (lnRow == 0){
                ShowMessageFX.Information("There are no transaction for this date(" + fsTrandate + ").", "Notice", null);
                return false;
            }else if (lnRow > 1){
                ShowMessageFX.Information("Unclosed cashier shifts detected for the date(" + fsTrandate + ").", "Notice", null);
                return false;
            }
            
            if (!OpenTransaction(fsTrandate, fsCRMNumbr, p_sCashier)){
                ShowMessageFX.Warning("Can't open transaction of " + getCashier(p_sCashier), "Warning", null);
                return false;
            }
            
            if (p_bWithParent) p_oApp.beginTrans();
            Timestamp loClosed = null;
            
            loRS.first();
            if (loRS.getString("cTranStat").equals("0")){
                computeTotalCashierSales();
                
                lsSQL = "SELECT dOpenedxx, dClosedxx, nAccuSale FROM Daily_Summary" +
                        " WHERE dClosedxx < " + SQLUtil.toSQL(p_oTrans.getDateOpened()) +
                        " ORDER BY dOpenedxx DESC LIMIT 1";
                loRS = p_oApp.executeQuery(lsSQL);
                
                double lnNetAmnt = (double) p_oTrans.getSalesAmount() - ((double) p_oTrans.getDiscount() + (double) p_oTrans.getPWDDisc() + (double) p_oTrans.getVATDisc());
                if (!loRS.next())
                    p_oTrans.setAccumulatedSale(lnNetAmnt);
                else 
                    p_oTrans.setAccumulatedSale(lnNetAmnt + loRS.getDouble("nAccuSale"));
                
                loClosed = p_oApp.getServerDate();
                
                lsSQL = "UPDATE Daily_Summary" +
                        " SET  nSalesAmt = " + p_oTrans.getSalesAmount() +
                            ", nVATSales = " + p_oTrans.getVATableSales() +
                            ", nVATAmtxx = " + p_oTrans.getVATAmount() +
                            ", nNonVATxx = " + p_oTrans.getNonVATAmount() +
                            ", nZeroRatd = " + p_oTrans.getZeroRatedSales() +
                            ", nDiscount = " + p_oTrans.getDiscount() +
                            ", nVatDiscx = " + p_oTrans.getVATDisc() +
                            ", nPWDDiscx = " + p_oTrans.getPWDDisc() +
                            ", nReturnsx = " + p_oTrans.getReturnAmount() +
                            ", nVoidAmnt = " + p_oTrans.getVoidAmount() +
                            ", nAccuSale = " + p_oTrans.getAccumulatedSale() +
                            ", nCashAmnt = " + p_oTrans.getCashAmount() +
                            ", nChckAmnt = " + p_oTrans.getCheckAmount() +
                            ", nCrdtAmnt = " + p_oTrans.getCreditCardAmount() +
                            ", nChrgAmnt = " + p_oTrans.getChargeInvoiceAmount() +
                            ", nGiftAmnt = " + p_oTrans.getGiftAmount() +
                            ", nFinAmntx = " + p_oTrans.getFinanceAmount() +
                            ", sORNoFrom = " + SQLUtil.toSQL(p_oTrans.getORFrom()) +
                            ", sORNoThru = " + SQLUtil.toSQL(p_oTrans.getORThru()) +
                            ", nZReadCtr = " + p_nZRdCtr +
                            ", dClosedxx = " + SQLUtil.toSQL(loClosed) +
                        " WHERE sTranDate = " + SQLUtil.toSQL(p_oTrans.getTransactionDate()) +
                            " AND sCRMNumbr = " + SQLUtil.toSQL(p_oTrans.getMachineNo()) +
                            " AND sCashierx = " + SQLUtil.toSQL(p_oTrans.getCashier());
                
                if (p_oApp.executeQuery(lsSQL, pxeMasTable, p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    return false;
                }
            }
            
            if (!printXAPI()){
                if (p_bWithParent) p_oApp.rollbackTrans();
                return false;
            }
            
            //create query to post the daily sales
            if (p_oTrans.getTranStat().equals(TransactionStatus.STATE_OPEN)){
                lsSQL = "UPDATE " + pxeMasTable +
                        " SET cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED) +
                        " WHERE sTranDate = " + SQLUtil.toSQL(fsTrandate) +
                            " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                            " AND cTranStat = '0'";
                p_oApp.executeQuery(lsSQL, pxeMasTable, p_oApp.getBranchCode(), "");
            }
            
            if (p_bWithParent) p_oApp.commitTrans();
            
            setMessage("X-Reading was perform successfully!!");
            return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
            return false;
        }
    }
    
    public boolean RePrintTZReading(String fsPrdFromx, String fsPrdThrux, String fsCRMNumbr){
        try {
            //if (!InitMachine()) return false;
            
            String lsSQL = MiscUtil.addCondition(getSQ_Master(), "(sTranDate BETWEEN " + SQLUtil.toSQL(fsPrdFromx) +
                                                                    " AND " + SQLUtil.toSQL(fsPrdThrux) + ")"+
                                                                " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                                                                " AND cTranStat IN ('1', '2')");
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            
            long lnRow = MiscUtil.RecordCount(loRS);
            if (lnRow == 0){
                ShowMessageFX.Warning("There are no transaction for the given date.", "", null);
                return false;
            }
            loRS.beforeFirst();
            
            lsSQL = "SELECT nAccuSale FROM Daily_Summary" +
                    " WHERE sTranDate < " + SQLUtil.toSQL(fsPrdFromx) +
                        " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                        " AND cTranStat IN ('1', '2')" +
                    " ORDER BY dClosedxx DESC LIMIT 1";
            ResultSet loRSx = p_oApp.executeQuery(lsSQL);
            
            double lnPrevSale = 0.00;
            if (loRSx.next()) lnPrevSale = loRSx.getDouble("nAccuSale");
            
            if (printZAPI(fsPrdFromx, fsPrdThrux, loRS, lnPrevSale, p_nZRdCtr)){
                /*if (p_bWithParent) p_oApp.beginTrans();
                
                lsSQL = "UPDATE Cash_Reg_Machine" +
                    " SET nZReadCtr = " + (p_nZRdCtr + 1) +
                    " WHERE sIDNumber = " + SQLUtil.toSQL(fsCRMNumbr);
                
                if (p_oApp.executeQuery(lsSQL, "Cash_Reg_Machine", p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    setMessage(p_oApp.getErrMsg() + "; " + p_oApp.getMessage());
                    return false;
                }
                
                lsSQL = "UPDATE Daily_Summary" +
                        " SET cTranStat = '2'" +
                            ", nZReadCtr = nZReadCtr + 1" +
                        " WHERE sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                            " AND sTranDate BETWEEN " + SQLUtil.toSQL(fsPrdFromx) + " AND " + SQLUtil.toSQL(fsPrdThrux);
                
                if (p_oApp.executeQuery(lsSQL, "Daily_Summary", p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    setMessage(p_oApp.getErrMsg() + "; " + p_oApp.getMessage());
                    return false;
                }
                
                if (p_bWithParent) p_oApp.commitTrans();*/
            } else {
                ShowMessageFX.Warning("Unable to print Z-Reading", "Warning", null);
                return false;
            }
            
            setMessage("Z-Reading Printed Successfully.");
            return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
            return false;
        }
    }
    
    public boolean PrintTZReading(String fsPrdFromx, String fsPrdThrux, String fsCRMNumbr){
        try {
            //if (!InitMachine()) return false;
            
            String lsSQL = MiscUtil.addCondition(getSQ_Master(), "(sTranDate BETWEEN " + SQLUtil.toSQL(fsPrdFromx) +
                                                                    " AND " + SQLUtil.toSQL(fsPrdThrux) + ")"+
                                                                " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                                                                " AND cTranStat IN ('1', '2')");
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            
            long lnRow = MiscUtil.RecordCount(loRS);
            if (lnRow == 0){
                ShowMessageFX.Warning("There are no transaction for the given date.", "", null);
                return false;
            }
            loRS.beforeFirst();
            
            lsSQL = "SELECT nAccuSale FROM Daily_Summary" +
                    " WHERE sTranDate < " + SQLUtil.toSQL(fsPrdFromx) +
                        " AND sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                        " AND cTranStat IN ('1', '2')" +
                    " ORDER BY dClosedxx DESC LIMIT 1";
            ResultSet loRSx = p_oApp.executeQuery(lsSQL);
            
            double lnPrevSale = 0.00;
            if (loRSx.next()) lnPrevSale = loRSx.getDouble("nAccuSale");
            
            if (printZAPI(fsPrdFromx, fsPrdThrux, loRS, lnPrevSale, p_nZRdCtr + 1)){
                if (p_bWithParent) p_oApp.beginTrans();
                
                lsSQL = "UPDATE Cash_Reg_Machine" +
                    " SET nZReadCtr = " + (p_nZRdCtr + 1) +
                    " WHERE sIDNumber = " + SQLUtil.toSQL(fsCRMNumbr);
                
                if (p_oApp.executeQuery(lsSQL, "Cash_Reg_Machine", p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    setMessage(p_oApp.getErrMsg() + "; " + p_oApp.getMessage());
                    return false;
                }
                
                lsSQL = "UPDATE Daily_Summary" +
                        " SET cTranStat = '2'" +
                            ", nZReadCtr = nZReadCtr + 1" +
                        " WHERE sCRMNumbr = " + SQLUtil.toSQL(fsCRMNumbr) +
                            " AND sTranDate BETWEEN " + SQLUtil.toSQL(fsPrdFromx) + " AND " + SQLUtil.toSQL(fsPrdThrux);
                
                if (p_oApp.executeQuery(lsSQL, "Daily_Summary", p_oApp.getBranchCode(), "") == 0){
                    if (p_bWithParent) p_oApp.rollbackTrans();
                    setMessage(p_oApp.getErrMsg() + "; " + p_oApp.getMessage());
                    return false;
                }
                
                if (p_bWithParent) p_oApp.commitTrans();
            } else {
                ShowMessageFX.Warning("Unable to print Z-Reading", "Warning", null);
                return false;
            }
            
            setMessage("Z-Reading Printed Successfully.");
            return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
            return false;
        }
    }
    
    private boolean computeTotalCashierSales(){
        try {
            double lnSalesAmt = 0.00;
            double lnVATSales = 0.00;
            double lnVATAmtxx = 0.00;
            double lnZeroRatd = 0.00;
            double lnNonVATxx = 0.00;
            double lnDiscount = 0.00;
            double lnPWDDiscx = 0.00;
            double lnVatDiscx = 0.00;
            
            //Add payment form computation
            double lnCashAmtx = 0.00;
            double lnChckAmnt = 0.00;
            double lnCrdtAmnt = 0.00;
            double lnGiftAmnt = 0.00;
            double lnFinAmntx = 0.00;
            double lnChrgAmnt = 0.00;
            double lnVoidAmnt = 0.00;
            
            String lsTranDate = p_oTrans.getTransactionDate();
            lsTranDate = lsTranDate.substring(0, 4) + "-" + lsTranDate.substring(4, 6) + "-" + lsTranDate.substring(6, 8);
            
            String lsSQL = "SELECT" + 
                                "  (a.nVATSales + a.nVATAmtxx) nSalesAmt" + 
                                ", a.nVATSales" + 
                                ", a.nVATAmtxx" +
                                ", a.nNonVATSl" +
                                ", a.nZroVATSl" +
                                ", (b.nTranTotl * b.nDiscount) + b.nAddDiscx nDiscount" +  
                                ", a.nCWTAmtxx" +
                                ", a.nAdvPaymx" +
                                ", a.nCashAmtx" +	
                                ", a.sSourceCd" +
                                ", a.sSourceNo" +
                                ", a.sORNumber" +
                                ", a.cTranStat" +
                                ", b.nTranTotl" +
                            " FROM  Receipt_Master a" +  
                                ", Sales_Master b" + 
                            " WHERE a.sTransNox LIKE " + SQLUtil.toSQL(p_oApp.getBranchCode() + p_sTermnl + "%") +
                                " AND a.sSourceNo = b.sTransNox" + 
                                " AND a.sSourceCd = 'SL'" + 
                                " AND a.sCashierx = " + SQLUtil.toSQL(p_oTrans.getCashier()) +
                                " AND b.dTransact LIKE " + SQLUtil.toSQL(lsTranDate + "%") +
                                " AND a.cTranStat IN ('1', '3')" +  
                            " ORDER BY a.sORNumber ASC" ;
            
            ResultSet loRS = p_oApp.executeQuery(lsSQL);
            ResultSet loRSx;
            
            while(loRS.next()){
                switch (loRS.getString("cTranStat")){
                    case "1":
                        lnSalesAmt = lnSalesAmt + loRS.getDouble("nSalesAmt");
                        lnVATSales = lnVATSales + loRS.getDouble("nVATSales");
                        lnVATAmtxx = lnVATAmtxx + loRS.getDouble("nVATAmtxx");
                        lnZeroRatd = lnZeroRatd + loRS.getDouble("nZroVATSl");

                        lnPWDDiscx = 0.00; //lnPWDDiscx + loRS.getDouble("nPWDDiscx");
                        lnVatDiscx = 0.00; //lnVatDiscx + loRS.getDouble("nVatDiscx");
                        lnDiscount = lnDiscount + loRS.getDouble("nDiscount");

                        lsSQL = "SELECT a.cPaymForm, a.nAmountxx" +
                                " FROM Sales_Payment a" +
                                    ", Receipt_Master b" +
                                " WHERE a.sSourceCd = 'ORec'" +
                                    " AND a.sSourceNo = b.sTransNox" +
                                    " AND a.sSourceNo = " + SQLUtil.toSQL(loRS.getString("sSourceNo")) +
                                    " AND b.cTranStat <> '3'";

                        loRSx = p_oApp.executeQuery(lsSQL);

                        double lnOtherPaym = 0.00;
                        while (loRSx.next()){
                            switch (loRSx.getString("cPaymForm")){
                                case "1":
                                    lnChckAmnt = lnChckAmnt + loRSx.getDouble("nAmountxx");
                                    break;
                                case "2":
                                    lnCrdtAmnt = lnCrdtAmnt + loRSx.getDouble("nAmountxx");
                                    break;
                                case "3":
                                    lnGiftAmnt = lnGiftAmnt + loRSx.getDouble("nAmountxx");
                                case "4":
                                    lnFinAmntx = lnFinAmntx + loRSx.getDouble("nAmountxx");
                            }
                            lnOtherPaym += loRSx.getDouble("nAmountxx");
                        }

                        //Add payment form computation
                        lnCashAmtx += loRS.getDouble("nSalesAmt") - lnOtherPaym;
                        break;
                    case "3":
                        lnVoidAmnt = lnVoidAmnt + (loRS.getDouble("nTranTotl") - loRS.getDouble("nDiscount"));
                }

            }
            
            //this are the cancelled/returned tranasctions
            lnCashAmtx += lnVoidAmnt;
            lnSalesAmt += lnVoidAmnt;
            p_oTrans.setReturns(lnVoidAmnt);
            
            if (MiscUtil.RecordCount(loRS) > 0){
                loRS.first();
                p_oTrans.setORFrom(loRS.getString("sORNumber"));
                loRS.last();
                p_oTrans.setORThru(loRS.getString("sORNumber"));
            } else{
                p_oTrans.setORFrom("");
                p_oTrans.setORThru("");
            }
            
            /*
            //Compute for Reversed Orders as VOID Sales
            lsSQL = "SELECT b.dTransact, a.nQuantity, a.nUnitPrce, a.nDiscount, a.nAddDiscx" +
                    " FROM Sales_Detail a" +
                        ", Sales_Master b" +
                    " WHERE b.sTransNox LIKE " + SQLUtil.toSQL(p_oApp.getBranchCode() + p_sTermnl + "%") +
                        " AND a.sTransNox = b.sTransNox" +
                        " AND b.dTransact LIKE " + SQLUtil.toSQL(lsTranDate + "%") +
                        " AND b.cTranStat = '4'" +
                        " AND b.sModified = " + SQLUtil.toSQL(p_oTrans.getCashier());
            loRS = p_oApp.executeQuery(lsSQL);
            
            lnVoidAmnt = 0.00;
            
            double lnSlPrc;
            while(loRS.next()){
                lnSlPrc = loRS.getDouble("nUnitPrce") * loRS.getInt("nQuantity");
                lnSlPrc = lnSlPrc - (lnSlPrc * loRS.getDouble("nDiscount")) - loRS.getDouble("nAddDiscx");
                
                lnVoidAmnt += lnSlPrc;
            }
            
            */
            p_oTrans.setVoidAmount(0.00);

            lnNonVATxx = lnSalesAmt - (lnVATSales + lnZeroRatd + lnVATAmtxx + lnVoidAmnt);
            
            p_oTrans.setSalesAmount((lnSalesAmt + lnDiscount + lnVatDiscx + lnPWDDiscx) - p_oTrans.getReturnAmount().doubleValue());
            
            p_oTrans.setVATableSales(lnVATSales);
            p_oTrans.setVATAmount(lnVATAmtxx);
            p_oTrans.setNonVATAmount(lnNonVATxx);
            p_oTrans.setZeroRatedSales(lnZeroRatd);
            p_oTrans.setDiscount(lnDiscount);
            p_oTrans.setPWDDisc(lnPWDDiscx);
            p_oTrans.setVATDisc(lnVatDiscx);
            
            p_oTrans.setCashAmount(lnCashAmtx);
            p_oTrans.setCheckAmount(lnChckAmnt);
            p_oTrans.setCreditCardAmount(lnCrdtAmnt);
            p_oTrans.setGiftAmount(lnGiftAmnt);
            p_oTrans.setFinanceAmount(lnFinAmntx);
            p_oTrans.setChargeInvoiceAmount(lnChrgAmnt);
                        
            return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            setMessage(ex.getMessage());
        }
        
        return false;
    }
        
    private boolean validSummary(boolean fbValue){
        String lsCondition;
        
        if (fbValue)
            lsCondition = "dTransact  = " + SQLUtil.toSQL(p_sTranDate);
        else 
            lsCondition = "dTransact  < " + SQLUtil.toSQL(p_sTranDate);
        
        String lsSQL = "SELECT *" +
                        " FROM Sales_Master" +
                        " WHERE sTransNox LIKE " + SQLUtil.toSQL(p_oApp.getBranchCode() + p_sTermnl + "%") +
                            " AND cTranStat = '0'";
        
        lsSQL = MiscUtil.addCondition(lsSQL, lsCondition);
        
        ResultSet loRS = p_oApp.executeQuery(lsSQL);
        
        try {
            if (loRS.next()){
                lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTranDate = " + SQLUtil.toSQL(SQLUtil.dateFormat(loRS.getDate("dTransact"), SQLUtil.FORMAT_SHORT_DATEX)) +
                        " AND sCRMNumbr = " + SQLUtil.toSQL(p_sPOSNo));
                
                p_oTrans = loadSummary(lsSQL);
                
                return false;
            } else return true;
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }
    
    private UnitDailySummary loadSummary(String fsValue){
        UnitDailySummary loObject = null;
        
        ResultSet loRS = p_oApp.executeQuery(fsValue);
        
        try {
            if (!loRS.next()){
                setMessage("No Record Found");
            }else{
                loObject = new UnitDailySummary();
                //load each column to the entity
                for(int lnCol=1; lnCol<=loRS.getMetaData().getColumnCount(); lnCol++){
                    loObject.setValue(lnCol, loRS.getObject(lnCol));
                }
            }              
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
        } finally{
            MiscUtil.close(loRS);
        }
        
        return loObject;
    }
    
    private boolean printXAPI(){
        if (p_oTrans == null) return false;
        
        JSONObject loMasx = new JSONObject();
        JSONObject loJSON = new JSONObject();
        
        //START - HEADER
        loJSON.put("sCompnyNm", p_sCompny);
        loJSON.put("sBranchNm", p_oApp.getBranchName());
        loJSON.put("sAddress1", p_oApp.getAddress());
        loJSON.put("sAddress2", p_oApp.getTownName() + ", " + p_oApp.getProvince());
        loJSON.put("sVATREGTN", p_sVATReg);
        loJSON.put("sMINumber", p_sPOSNo);
        loJSON.put("sSerialNo", p_sSerial);
        
        String lsDate = p_oTrans.getTransactionDate().substring(0, 4) + "-" + 
                        p_oTrans.getTransactionDate().substring(4, 6) + "-" + 
                        p_oTrans.getTransactionDate().substring(6, 8);
        
        loJSON.put("dTransact", lsDate);
        loJSON.put("sCashierx", getCashier(p_oTrans.getCashier()));
        loJSON.put("sTerminal", p_sTermnl);
        loMasx.put("Header", loJSON);
        //END - HEADER
        
        //BODY
        loJSON = new JSONObject();        
        loJSON.put("dOpenedxx", SQLUtil.dateFormat(p_oTrans.getDateOpened(), SQLUtil.FORMAT_TIMESTAMP));
        loJSON.put("dClosedxx", SQLUtil.dateFormat(p_oTrans.getDateClosed(), SQLUtil.FORMAT_TIMESTAMP));
        
        loJSON.put("sORNoFrom", p_oTrans.getORFrom());
        loJSON.put("sORNoThru", p_oTrans.getORThru());
        
        loJSON.put("nSalesAmt", p_oTrans.getSalesAmount());
        loJSON.put("nAccuSale", p_oTrans.getAccumulatedSale());
        
        loJSON.put("nVoidAmnt", p_oTrans.getVoidAmount());
        loJSON.put("nReturnsx", p_oTrans.getReturnAmount());
        loJSON.put("nDiscount", p_oTrans.getDiscount());
        loJSON.put("nVATDiscx", p_oTrans.getVATDisc());
        loJSON.put("nPWDDiscx", p_oTrans.getPWDDisc());
        loJSON.put("nVoidAmnt", p_oTrans.getVoidAmount());
        
        loJSON.put("nVATSales", p_oTrans.getVATableSales());
        loJSON.put("nVATAmtxx", p_oTrans.getVATAmount());
        loJSON.put("nNonVATxx", p_oTrans.getNonVATAmount());
        loJSON.put("nZeroRatd", p_oTrans.getZeroRatedSales());
        
        loJSON.put("nOpenBalx", p_oTrans.getOpeningBalance());
        loJSON.put("nCPullOut", p_oTrans.getCashPullout());
        
        loJSON.put("nCashAmnt", p_oTrans.getCashAmount());
        loJSON.put("nChckAmnt", p_oTrans.getCheckAmount());
        loJSON.put("nCrdtAmnt", p_oTrans.getCreditCardAmount());
        loJSON.put("nGiftAmnt", p_oTrans.getGiftAmount());
        loJSON.put("nFinAmntx", p_oTrans.getFinanceAmount());
        loJSON.put("nChrgAmnt", p_oTrans.getChargeInvoiceAmount());
        
        loJSON.put("dSysDatex", SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_TIMESTAMP));
        
        loMasx.put("Detail", loJSON);
        loMasx.put("webserver", p_sWebSvr);
        loMasx.put("printer", p_sPrintr);
        //END - BODY
        
        loJSON = EPSONPrint.XReading(loMasx);
        
        if ("success".equals(((String) loJSON.get("result")).toLowerCase())){
            XReading loPrinter = new XReading(loMasx, EndShift.XREADING);
            if (!loPrinter.Print()) ShowMessageFX.Warning(null, pxeModuleName, loPrinter.getMessage());
            
            CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                    CRMEvent.PRINT_X_READING, "Transaction date: " + lsDate + "; " + 
                        "Cashier: " + getCashier(p_oTrans.getCashier())
                    , System.getProperty("pos.clt.crm.no"));
            
            CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                    CRMEvent.SHIFT_CLOSING, "Transaction date: " + lsDate + "; " + 
                        "Cashier: " + getCashier(p_oTrans.getCashier())
                    , System.getProperty("pos.clt.crm.no"));
            return true;
        } 
        
        JSONParser loParser = new JSONParser();
        try {
            loJSON = (JSONObject) loParser.parse(loJSON.get("error").toString());
            setMessage((String) loJSON.get("message"));
            System.err.print(getMessage());
        } catch (ParseException e) {
            setMessage((String) loJSON.get(e.getMessage()));
            System.err.print(getMessage());
        }
        return false;
    }
    
    private boolean printYAPI(){
        if (p_oTrans == null) return false;
        
        JSONObject loMasx = new JSONObject();
        JSONObject loJSON = new JSONObject();
        
        //START - HEADER
        loJSON.put("sCompnyNm", p_sCompny);
        loJSON.put("sBranchNm", p_oApp.getBranchName());
        loJSON.put("sAddress1", p_oApp.getAddress());
        loJSON.put("sAddress2", p_oApp.getTownName() + ", " + p_oApp.getProvince());
        loJSON.put("sVATREGTN", p_sVATReg);
        loJSON.put("sMINumber", p_sPOSNo);
        loJSON.put("sSerialNo", p_sSerial);
        
        String lsDate = p_oTrans.getTransactionDate().substring(0, 4) + "-" + 
                        p_oTrans.getTransactionDate().substring(4, 6) + "-" + 
                        p_oTrans.getTransactionDate().substring(6, 8);
        
        loJSON.put("dTransact", lsDate);
        loJSON.put("sCashierx", getCashier(p_oTrans.getCashier()));
        loJSON.put("sTerminal", p_sTermnl);
        loMasx.put("Header", loJSON);
        //END - HEADER
        
        //BODY
        loJSON = new JSONObject();        
        loJSON.put("dOpenedxx", SQLUtil.dateFormat(p_oTrans.getDateOpened(), SQLUtil.FORMAT_TIMESTAMP));
        loJSON.put("dClosedxx", SQLUtil.dateFormat(p_oTrans.getDateClosed(), SQLUtil.FORMAT_TIMESTAMP));
        
        loJSON.put("sORNoFrom", p_oTrans.getORFrom());
        loJSON.put("sORNoThru", p_oTrans.getORThru());
        
        loJSON.put("nSalesAmt", p_oTrans.getSalesAmount());
        loJSON.put("nAccuSale", p_oTrans.getAccumulatedSale());
        
        loJSON.put("nVoidAmnt", p_oTrans.getVoidAmount());
        loJSON.put("nReturnsx", p_oTrans.getReturnAmount());
        loJSON.put("nDiscount", p_oTrans.getDiscount());
        loJSON.put("nVATDiscx", p_oTrans.getVATDisc());
        loJSON.put("nPWDDiscx", p_oTrans.getPWDDisc());
        loJSON.put("nVoidAmnt", p_oTrans.getVoidAmount());
        
        loJSON.put("nVATSales", p_oTrans.getVATableSales());
        loJSON.put("nVATAmtxx", p_oTrans.getVATAmount());
        loJSON.put("nNonVATxx", p_oTrans.getNonVATAmount());
        loJSON.put("nZeroRatd", p_oTrans.getZeroRatedSales());
        
        loJSON.put("nOpenBalx", p_oTrans.getOpeningBalance());
        loJSON.put("nCPullOut", p_oTrans.getCashPullout());
        
        loJSON.put("nCashAmnt", p_oTrans.getCashAmount());
        loJSON.put("nChckAmnt", p_oTrans.getCheckAmount());
        loJSON.put("nCrdtAmnt", p_oTrans.getCreditCardAmount());
        loJSON.put("nGiftAmnt", p_oTrans.getGiftAmount());
        loJSON.put("nFinAmntx", p_oTrans.getFinanceAmount());
        loJSON.put("nChrgAmnt", p_oTrans.getChargeInvoiceAmount());
        
        loJSON.put("dSysDatex", SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_TIMESTAMP));
        
        loMasx.put("Detail", loJSON);
        loMasx.put("webserver", p_sWebSvr);
        loMasx.put("printer", p_sPrintr);
        //END - BODY
        
        loJSON = EPSONPrint.YReading(loMasx);
        
        if ("success".equals(((String) loJSON.get("result")).toLowerCase())){
            XReading loPrinter = new XReading(loMasx, EndShift.YREADING);
            if (!loPrinter.Print()) ShowMessageFX.Warning(null, pxeModuleName, loPrinter.getMessage());
            
            
            CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                    CRMEvent.PRINT_Y_READING, "Transaction date: " + SQLUtil.dateFormat(p_oTrans.getDateOpened(), SQLUtil.FORMAT_SHORT_DATE) + "; " + 
                        "Cashier: " + getCashier(p_oTrans.getCashier())
                    , System.getProperty("pos.clt.crm.no"));
            return true;
        } 
        
        JSONParser loParser = new JSONParser();
        try {
            loJSON = (JSONObject) loParser.parse(loJSON.get("error").toString());
            setMessage((String) loJSON.get("message"));
            System.err.print(getMessage());
        } catch (ParseException e) {
            setMessage((String) loJSON.get(e.getMessage()));
            System.err.print(getMessage());
        }
        return false;
    }
    
    private boolean printZAPI(String fsDateFrom, String fsDateThru, ResultSet foRS, double fnPrevSale, int fnZReading){
        JSONObject loMasx = new JSONObject();
        JSONObject loJSON = new JSONObject();
        
        try {                  
            //START - HEADER
            loJSON.put("sCompnyNm", p_sCompny);
            loJSON.put("sBranchNm", p_oApp.getBranchName());
            loJSON.put("sAddress1", p_oApp.getAddress());
            loJSON.put("sAddress2", p_oApp.getTownName() + ", " + p_oApp.getProvince());
            loJSON.put("sVATREGTN", p_sVATReg);
            loJSON.put("sMINumber", p_sPOSNo);
            loJSON.put("sSerialNo", p_sSerial);
            
            String lsDateFrom = fsDateFrom.substring(0, 4) + "-" +
                    fsDateFrom.substring(4, 6) + "-" +
                    fsDateFrom.substring(6, 8);
            
            String lsDateThru = fsDateThru.substring(0, 4) + "-" +
                    fsDateThru.substring(4, 6) + "-" +
                    fsDateThru.substring(6, 8);
            
            loJSON.put("dTransact", lsDateFrom + " to " + lsDateThru);
            loJSON.put("sTerminal", p_sTermnl);
            loMasx.put("Header", loJSON);
            //END - HEADER
            
            foRS.first();
            String lsORNoFrom = foRS.getString("sORNoFrom");
            String lsORNoThru = foRS.getString("sORNoThru");
            
            double lnOpenBalx = 0.00;
            double lnCPullOut = 0.00;

            double lnCashAmnt = 0.00;
            double lnChckAmnt = 0.00;
            double lnCrdtAmnt = 0.00;
            double lnChrgAmnt = 0.00;
            double lnGiftAmnt = 0.00;
            double lnFinAmntx = 0.00;

            double lnSalesAmt = 0.00;
            double lnVATSales = 0.00;
            double lnVATAmtxx = 0.00;
            double lnZeroRatd = 0.00;
            double lnNonVATxx = 0.00;   //Non-Vat means Vat Exempt
            double lnDiscount = 0.00;   //Regular Discount
            double lnVatDiscx = 0.00;   //12% VAT Discount
            double lnPWDDiscx = 0.00;   //Senior/PWD Discount

            double lnReturnsx = 0.00;   //Returns
            double lnVoidAmnt = 0.00;   //Void Transactions
            
            foRS.beforeFirst();
            while (foRS.next()){
                if (!foRS.getString("sORNoFrom").equals("") &&
                        Integer.parseInt(foRS.getString("sORNoFrom")) < Integer.parseInt(lsORNoFrom)) {
                    lsORNoFrom = foRS.getString("sORNoFrom");
                }
                
                if (!foRS.getString("sORNoThru").equals("") &&
                        Integer.parseInt(foRS.getString("sORNoThru")) > Integer.parseInt(lsORNoThru)) {
                    lsORNoFrom = foRS.getString("sORNoThru");
                }
                
                //compute gross sales
                lnSalesAmt = lnSalesAmt + foRS.getDouble("nSalesAmt");
                
                lnDiscount = lnDiscount + foRS.getDouble("nDiscount");
                //compute vat related sales
                lnVATSales = lnVATSales + foRS.getDouble("nVATSales");
                lnVATAmtxx = lnVATAmtxx + foRS.getDouble("nVATAmtxx");
                lnZeroRatd = lnZeroRatd + foRS.getDouble("nZeroRatd");
                //compute returns/refund/void transactions
                lnReturnsx = lnReturnsx + foRS.getDouble("nReturnsx");
                lnVoidAmnt = lnVoidAmnt + foRS.getDouble("nVoidAmnt");
                //compute cashier collection info
                lnOpenBalx = lnOpenBalx + foRS.getDouble("nOpenBalx");
                lnCPullOut = lnCPullOut + foRS.getDouble("nCPullOut");
                lnCashAmnt = lnCashAmnt + foRS.getDouble("nCashAmnt");
                lnChckAmnt = lnChckAmnt + foRS.getDouble("nChckAmnt");
                lnCrdtAmnt = lnCrdtAmnt + foRS.getDouble("nCrdtAmnt");
                lnChrgAmnt = lnChrgAmnt + foRS.getDouble("nChrgAmnt");
                lnGiftAmnt = lnGiftAmnt + foRS.getDouble("nGiftAmnt");
                lnFinAmntx = lnFinAmntx + foRS.getDouble("nFinAmntx");
            }
            
            //DETAIL
            loJSON = new JSONObject();
            
            loJSON.put("sORNoFrom", lsORNoFrom);
            loJSON.put("sORNoThru", lsORNoThru);

            loJSON.put("nSalesAmt", lnSalesAmt);

            loJSON.put("nVoidAmnt", lnVoidAmnt);
            loJSON.put("nReturnsx", lnReturnsx);
            loJSON.put("nDiscount", lnDiscount);
            loJSON.put("nVATDiscx", lnVatDiscx);
            loJSON.put("nPWDDiscx", lnPWDDiscx);
            loJSON.put("nVoidAmnt", lnVoidAmnt);

            loJSON.put("nVATSales", lnVATSales);
            loJSON.put("nVATAmtxx", lnVATAmtxx);
            loJSON.put("nNonVATxx", lnNonVATxx);
            loJSON.put("nZeroRatd", lnZeroRatd);

            loJSON.put("nOpenBalx", lnOpenBalx);
            loJSON.put("nCPullOut", lnCPullOut);

            loJSON.put("nCashAmnt", lnCashAmnt);
            loJSON.put("nChckAmnt", lnChckAmnt);
            loJSON.put("nCrdtAmnt", lnCrdtAmnt);
            loJSON.put("nGiftAmnt", lnGiftAmnt);
            loJSON.put("nFinAmntx", lnFinAmntx);
            loJSON.put("nChrgAmnt", lnChrgAmnt);
            
            loJSON.put("nZCounter", fnZReading);
            loJSON.put("nPrevSale", fnPrevSale);
            
            loJSON.put("dSysDatex", SQLUtil.dateFormat(p_oApp.getServerDate(), SQLUtil.FORMAT_TIMESTAMP));

            loMasx.put("Detail", loJSON);
            loMasx.put("webserver", p_sWebSvr);
            loMasx.put("printer", p_sPrintr);
            //END DETAIL
            
            loJSON = EPSONPrint.ZReading(loMasx);
            
            if ("success".equals(((String) loJSON.get("result")).toLowerCase())){
                ZReading loPrinter = new ZReading(loMasx);
                if (!loPrinter.Print()) ShowMessageFX.Warning(null, pxeModuleName, loPrinter.getMessage());
                
                CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                    CRMEvent.PRINT_Z_READING, "Transaction date: " + lsDateFrom + " to " + lsDateThru + "; " + 
                        "Z-Counter: " +fnZReading 
                    , System.getProperty("pos.clt.crm.no"));
            
                CommonUtils.createEventLog(p_oApp, p_oApp.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), 
                    CRMEvent.DECLARE_NEW_DAY, "Transaction date: " + lsDateFrom + " to " + lsDateThru + "; " + 
                        "Z-Counter: " +fnZReading 
                    , System.getProperty("pos.clt.crm.no"));
                
                return true;
            } 
            
            JSONParser loParser = new JSONParser();
            loJSON = (JSONObject) loParser.parse(loJSON.get("error").toString());
            setMessage((String) loJSON.get("message"));
            System.err.print(getMessage());
            return false;
        } catch (SQLException | ParseException e) {
            setMessage((String) loJSON.get(e.getMessage()));
            System.err.print(getMessage());
            return false;
        }
    }
    
    private String getCashier(String fsValue){        
        String lsSQL = "SELECT" +
                            " a.sLogNamex" +
                            ", IFNULL(b.sClientNm, 'UNSET ID') xCashierx" + 
                        " FROM xxxSysUser a" + 
                            " LEFT JOIN Client_Master b" +
                                " ON a.sEmployNo = b.sClientID" + 
                        " WHERE a.sUserIDxx = " + SQLUtil.toSQL(fsValue);
        
        ResultSet loRS = p_oApp.executeQuery(lsSQL);
        try {
            if (loRS.next())
                return p_oApp.Decrypt(loRS.getString("sLogNamex")) + "/" + loRS.getString("xCashierx").toUpperCase();
        } catch (SQLException ex) {
            Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);            
        }
        return "UNKNOWN";
    }
    
    private String getSQ_Master(){
        return MiscUtil.makeSelect(new UnitDailySummary());
    }
    
    public int getEditMode(){
        return p_nEditMode;
    }
    
    public int getSalesStatus(){
        return p_nSaleStat;
    }
    
    public void setCashier(String fsValue){
        p_sCashier = fsValue;
    }
    public String getCashier(){
        return p_sCashier;
    }
    
    public void setMessage(String fsValue){
        p_sMessage  = fsValue;
    }
    public String getMessage(){
        return p_sMessage;
    }
    
    public UnitDailySummary DailySummary(){
        return p_oTrans;
    }
    
    public String getCompanyName(){
        return p_sCompny;
    }
    public String getVATRegTIN(){
        return p_sVATReg;
    }
    public String getMachineNo(){
        return p_sPOSNo;
    }
    public String getSerialNo(){
        return p_sSerial;
    }
    public String getTerminalNo(){
        return p_sTermnl;
    }
    public String getCashierName(){
        return getCashier(p_sCashier);
    }
    
    public String getWebServer(){
        return p_sWebSvr;
    }
    public String getPrinter(){
        return p_sPrintr;
    }

    private GRider p_oApp;
    private int p_nEditMode;
    
    //0->With Open Sales Order From Previous Sale;
    //1->Sales for the Day was already closed;
    //2->Sales for the Day is Ok;
    //3->Error Printing TXReading 
    //4->User is not allowed to enter Sales Transaction
    private int p_nSaleStat;
    
    private UnitDailySummary p_oTrans;
    
    private String p_sPOSNo;
    private String p_sVATReg;
    private String p_sCompny;
    
    private String p_sWebSvr;
    private String p_sPrintr;
    private String p_sTranDate;
    
    private String p_sPermit;
    private String p_sSerial;
    private String p_sAccrdt;
    private String p_sTermnl;
    private int p_nZRdCtr;   
    
    private String p_sCashier;
    private boolean p_bWithParent;
    private String p_sMessage;
    
    private final String pxeMasTable = "Daily_Summary";
}

/*public boolean InitMachine(){
    try {
        if (p_sPOSNo.equals("")){
            setMessage("Invalid Machine Identification Info Detected...");
            return false;
        }

        String lsSQL = "SELECT" +
                            "  a.sAccredtn" +
                            ", a.sPermitNo" +
                            ", a.sSerialNo" +
                            ", a.nPOSNumbr" +
                            ", a.nZReadCtr" +
                            ", IFNULL(b.sWebSrver, '') sWebSrver" +
                            ", IFNULL(b.sPrinter1, '') sPrinter1" +
                        " FROM Cash_Reg_Machine a" +
                            " LEFT JOIN Cash_Reg_Machine_Printer b" +
                                " ON a.sIDNumber = b.sIDNumber" +
                        " WHERE a.sIDNumber = " + SQLUtil.toSQL(p_sPOSNo);

        ResultSet loRS = p_oApp.executeQuery(lsSQL);
        long lnRow = MiscUtil.RecordCount(loRS);

        if (lnRow != 1){
            setMessage("Invalid Config for MIN Detected...");
            return false;
        }

        loRS.first();
        p_sAccrdt = loRS.getString("sAccredtn");
        p_sPermit = loRS.getString("sPermitNo");
        p_sSerial = loRS.getString("sSerialNo");
        p_sTermnl = loRS.getString("nPOSNumbr");
        p_nZRdCtr = loRS.getInt("nZReadCtr");

        p_sWebSvr = loRS.getString("sWebSrver");
        p_sPrintr = loRS.getString("sPrinter1");

        return true;
    } catch (SQLException ex) {
        Logger.getLogger(XMDailySales.class.getName()).log(Level.SEVERE, null, ex);
        setMessage(ex.getMessage());
    }
    return false;
}*/
