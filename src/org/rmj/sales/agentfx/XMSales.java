/**
 * @author  Michael Cuison
 * @date    2018-04-19
 */
package org.rmj.sales.agentfx;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.StringUtil;
import org.rmj.appdriver.agentfx.CommonUtils;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.printer.EPSONPrint;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.appdriver.constants.CRMEvent;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.appdriver.constants.UserRight;
import org.rmj.cas.client.application.ClientFX;
import org.rmj.cas.client.base.XMClient;
import org.rmj.cas.inventory.base.InvMaster;
import org.rmj.cas.inventory.base.Inventory;
import org.rmj.cas.parameter.agent.XMBrand;
import org.rmj.cas.parameter.agent.XMColor;
import org.rmj.cas.parameter.agent.XMInventory;
import org.rmj.cas.parameter.agent.XMModel;
import org.rmj.cas.parameter.agent.XMTerm;
import org.rmj.payment.agent.XMCashDrawer;
import org.rmj.payment.agent.XMORMaster;
import org.rmj.payment.agent.XMSalesPayment;
import org.rmj.sales.base.Sales;
import org.rmj.sales.base.printer.Invoice;
import org.rmj.sales.base.printer.Invoice_Cancel;
import org.rmj.sales.pojo.UnitSalesDetail;
import org.rmj.sales.pojo.UnitSalesMaster;

public class XMSales{
    public XMSales(GRider foGRider, String fsBranchCD, boolean fbWithParent){
        this.poGRider = foGRider;
        if (foGRider != null){
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;
            
            poSales = new XMDailySales(poGRider, false);
            
            int lnStat = processDailySales();
            
            switch (lnStat){
                case 0:
                    ShowMessageFX.Warning("Open SALES ORDER EXISTS from the PREVIOUS SALE.", "Warning", null);
                    break;
                case 1:
                    ShowMessageFX.Warning("Sales for the day was already closed.", "Warning", null);
                    System.exit(1);
                case 2: break;
                case 3:
                    ShowMessageFX.Warning("Error printing X-Reading.", "Warning", null);
                    System.exit(1);
                case 4:
                    ShowMessageFX.Warning("User is not allowed to use this system.", "Warning", null);
                    System.exit(1);
                case 5:
                    ShowMessageFX.Warning("Your shift for this day was already closed.", "Warning", null);
                    System.exit(1);    
                case 6:
                    ShowMessageFX.Warning("POS was locked until the CURRENT DATE is equal to the DATE START set when it was reset.", "Warning", null);
                    System.exit(1);    
                case 7:
                    ShowMessageFX.Warning("Machine is not registered to use this system.", "Warning", null);
                    System.exit(1);    
                default:
                    System.exit(1);
            }
            
            poControl = new Sales();
            poControl.setGRider(foGRider);
            poControl.setBranch(fsBranchCD);
            poControl.setWithParent(fbWithParent);
            poControl.setUserID(poSales.DailySummary().getCashier());
            
            pnEditMode = EditMode.UNKNOWN;
        }
    }
    
    public void setMaster(int fnCol, Object foData) {
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poData.getColumn("sTransNox") ||
                fnCol == poData.getColumn("cTranStat") ||
                fnCol == poData.getColumn("sModified") ||
                fnCol == poData.getColumn("dModified"))){
                
                poData.setValue(fnCol, foData);
                
                if (fnCol == poData.getColumn("nDiscount") ||
                        fnCol == poData.getColumn("nAddDiscx")){
                    if (fnCol == poDetail.getColumn("nDiscount"))
                        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                            , CRMEvent.MODIFY_DISCOUNT, "Order No.: " + poData.getTransNo() + "; " + "Discount Rate: " + foData
                            , System.getProperty("pos.clt.crm.no"));
                    else 
                        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                            , CRMEvent.MODIFY_DISCOUNT, "Order No.: " + poData.getTransNo() + "; " + "Peso Discount: " + foData
                            , System.getProperty("pos.clt.crm.no"));
                }
            }
        }
    }

    public void setMaster(String fsCol, Object foData) {
        setMaster(poData.getColumn(fsCol), foData);
    }

    public Object getMaster(int fnCol) {
        if(pnEditMode == EditMode.UNKNOWN || poControl == null)
         return null;
      else{
         return poData.getValue(fnCol);
      }
    }

    public Object getMaster(String fsCol) {
        return getMaster(poData.getColumn(fsCol));
    }

    public boolean newTransaction() {
        //don't allow entry/update of sales when we are working on previous day transaction.
        if (poSales.getSalesStatus() != 2) {
            psErrMsgx = "Unable to encode/modify transaction.";
            psWarnMsg = "Unable to encode/modify transaction since we are working on previous day transaction.";
            System.err.println("Unable to encode/modify transaction since we are working on previous day transaction.");
            return false;
        }
        
        poData = poControl.newTransaction();              
        
        if (poData == null){
            return false;
        }else{
            poData.setValue("dTransact", poGRider.getServerDate());
            poData.setValue("dDueDatex", Date.valueOf("1900-01-01"));
            poData.setValue("dExpected", poGRider.getServerDate());
            
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.NEW_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
            
            addDetail();
            pnEditMode = EditMode.ADDNEW;
            return true;
        }
    }

    public boolean openTransaction(String fstransNox) {
        poData = poControl.loadTransaction(fstransNox);
        
        if (poData.getTransNo()== null){
            ShowMessageFX();
            return false;
        } else{
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.LOAD_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
            
            pnEditMode = EditMode.READY;
            return true;
        }
    }

    public boolean updateTransaction() {
        //don't allow entry/update of sales when we are working on previous day transaction.
        if (poSales.getSalesStatus() != 2) {
            psErrMsgx = "Unable to encode/modify transaction.";
            psWarnMsg = "Unable to encode/modify transaction since we are working on previous day transaction.";
            System.err.println("Unable to encode/modify transaction since we are working on previous day transaction.");
            return false;
        }
        
        if(pnEditMode != EditMode.READY) {
            return false;
        } else{
            if (!poData.getTranStatus().equals(TransactionStatus.STATE_OPEN)){
                psWarnMsg = "Trasaction may be CLOSED/CANCELLED/POSTED.";
                psErrMsgx = "Can't update processed transactions!!!";
                return false;
            }
            
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.MODIFY_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
            
            pnEditMode = EditMode.UPDATE;
            addDetail();
            return true;
        }
    }

    public boolean saveTransaction() {
        //don't allow entry/update of sales when we are working on previous day transaction.
        if (poSales.getSalesStatus() != 2) {
            psErrMsgx = "Unable to encode/modify transaction.";
            psWarnMsg = "Unable to encode/modify transaction since we are working on previous day transaction.";
            System.err.println("Unable to encode/modify transaction since we are working on previous day transaction.");
            return false;
        }
        
        if(pnEditMode == EditMode.UNKNOWN){
            return false;
        }else{
            // Perform testing on values that needs approval here...
            UnitSalesMaster loResult;
            if(pnEditMode == EditMode.ADDNEW)
                loResult = poControl.saveUpdate(poData, "");
            else loResult = poControl.saveUpdate(poData, (String) poData.getValue(1));

            if(loResult == null){
                ShowMessageFX();
                return false;
            }else{
                pnEditMode = EditMode.READY;
                poData = loResult;
                return true;
            }
        }
    }
    
    public boolean cancelTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{               
            if (poGRider.getUserLevel() < UserRight.SUPERVISOR || poGRider.getUserLevel() < UserRight.AUDIT ||
                poGRider.getUserLevel() < UserRight.SYSADMIN){
                
                if (Double.valueOf(String.valueOf(poData.getDiscount())) > 0.00 || Double.valueOf(String.valueOf(poData.getAddtlDiscount())) > 0.00){
                    ShowMessageFX.Information("Please ask your supervosor for his account credential to continue.", "Information", "Supervisor account needed");

                    JSONObject loJSON = showFXDialog.getApproval(poGRider);
                    
                    if ((int) loJSON.get("nUserLevl") < UserRight.SUPERVISOR || (int) loJSON.get("nUserLevl") < UserRight.AUDIT ||
                        (int) loJSON.get("nUserLevl") < UserRight.SYSADMIN){
                        ShowMessageFX.Warning("User account is not valid for approving discounts.", "Information", "Invalid User Rights");
                        return false;
                    }
                }
            }
            
            boolean lbResult = poControl.cancelTransaction(fsTransNox);
            
            if(lbResult){
                PrintCancelledInvoice(fsTransNox);
                
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.CANCEL_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
                
                String lsSQL;
                //update inventory location
                for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr++){
                    if (!poControl.getDetail(lnCtr, "sSerialID").equals("")){
                        lsSQL = "UPDATE Inv_Serial SET cLocation = '1', cSoldStat = '0' WHERE sSerialID = " + SQLUtil.toSQL(poControl.getDetail(lnCtr, "sSerialID"));
                        poGRider.executeQuery(lsSQL, "Inv_Serial", psBranchCd, "");
                    }
                }
                
                pnEditMode = EditMode.UNKNOWN;
            }
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean closeTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            if(!poData.getTranStatus().equals(TransactionStatus.STATE_OPEN)){
                psErrMsgx = "Can't update processed transactions!!!";
                psWarnMsg = "Trasaction may be PAYED/CANCELLED/POSTED.";
                return false;
            }  
            
            if (poGRider.getUserLevel() < UserRight.SUPERVISOR || poGRider.getUserLevel() < UserRight.AUDIT ||
                poGRider.getUserLevel() < UserRight.SYSADMIN){
                
                if (Double.valueOf(String.valueOf(poData.getDiscount())) > 0.00 || Double.valueOf(String.valueOf(poData.getAddtlDiscount())) > 0.00){
                    ShowMessageFX.Information("Please ask your supervisor for his account credential to continue.", "Information", "A discount was detected");

                    JSONObject loJSON = showFXDialog.getApproval(poGRider);
                    
                    if ((int) loJSON.get("nUserLevl") < UserRight.SUPERVISOR || (int) loJSON.get("nUserLevl") < UserRight.AUDIT ||
                        (int) loJSON.get("nUserLevl") < UserRight.SYSADMIN){
                        ShowMessageFX.Warning("User account is not valid for approving discounts.", "Information", "Invalid User Rights");
                        return false;
                    }
                    poData.setApprovedBy((String) loJSON.get("sUserIDxx"));
                    poData.setApprovedDate(poGRider.getServerDate());
                }
            }
            
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                        , CRMEvent.PAY_BILL, "Order No.: " + poData.getTransNo() + "; " + "Order Amount: " + poData.getTranTotal() + "; " +
                                "Discount Rate: " + poData.getDiscount() + "; " + "Peso Discount: " + poData.getAddtlDiscount() + "; "
                        , System.getProperty("pos.clt.crm.no"));
            
            boolean lbResult = poControl.closeTransaction(fsTransNox);
            if(lbResult) {
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.ACTION_ALLOWED, "", System.getProperty("pos.clt.crm.no"));
                
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.CLOSE_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
                
                //update cash drawer
                poSales.ComputeCashDrawer(poSales.DailySummary().getTransactionDate(), 
                                            poSales.DailySummary().getMachineNo(), 
                                            poSales.DailySummary().getCashier());
                
                String lsSQL = "";
                
                PrintInvoice(fsTransNox);
 
                //update inventory location
                for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr++){
                    if (!poControl.getDetail(lnCtr, "sSerialID").equals("")){
                        lsSQL = "UPDATE Inv_Serial SET cLocation = '3', cSoldStat = '1' WHERE sSerialID = " + SQLUtil.toSQL(poControl.getDetail(lnCtr, "sSerialID"));
                        poGRider.executeQuery(lsSQL, "Inv_Serial", psBranchCd, "");
                    }
                }

                pnEditMode = EditMode.UNKNOWN;
            } else 
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.ACTION_DENIED, "", System.getProperty("pos.clt.crm.no"));
            
            return lbResult;
        }
    }
    
    public boolean postTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.postTransaction(fsTransNox);
            if(lbResult){
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.POST_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
                pnEditMode = EditMode.UNKNOWN;
            } 
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean voidTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            if (poGRider.getUserLevel() < UserRight.SUPERVISOR || poGRider.getUserLevel() < UserRight.AUDIT ||
                poGRider.getUserLevel() < UserRight.SYSADMIN){
                
                if (Double.valueOf(String.valueOf(poData.getDiscount())) > 0.00 || Double.valueOf(String.valueOf(poData.getAddtlDiscount())) > 0.00){
                    ShowMessageFX.Information("Please ask your supervosor for his account credential to continue.", "Information", "Supervisor account needed");

                    JSONObject loJSON = showFXDialog.getApproval(poGRider);
                    
                    if ((int) loJSON.get("nUserLevl") < UserRight.SUPERVISOR || (int) loJSON.get("nUserLevl") < UserRight.AUDIT ||
                        (int) loJSON.get("nUserLevl") < UserRight.SYSADMIN){
                        ShowMessageFX.Warning("User account is not valid for approving discounts.", "Information", "Invalid User Rights");
                        return false;
                    }
                }
            }
            
            
            boolean lbResult = poControl.voidTransaction(fsTransNox);
            if(lbResult) {
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.VOID_ORDER, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
                
                //update cash drawer
                poSales.ComputeCashDrawer(poSales.DailySummary().getTransactionDate(), 
                                            poSales.DailySummary().getMachineNo(), 
                                            poSales.DailySummary().getCashier());
                
                pnEditMode = EditMode.UNKNOWN;
            } else ShowMessageFX();

            return lbResult;
        }
    }

    public boolean deleteTransaction(String fsTransNox) {
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.deleteTransaction(fsTransNox);
            if(lbResult) 
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public void setBranch(String foBranchCD) {
        psBranchCd = foBranchCD;
    }

    public int getEditMode() {
        return pnEditMode;
    }
    
    //Added methods
    private void ShowMessageFX(){
        psErrMsgx = poControl.getErrMsg();
        psWarnMsg = poControl.getMessage();
    }
    
    public JSONArray BrowseTransaction(String fsValue, boolean fbByCode){
        String lsSQL = MiscUtil.addCondition(getSQ_Sales(), "a.cTranStat IN ('0', '1', '3', '4')"); 
        
        String lsTranDate = poSales.DailySummary().getTransactionDate();
        lsTranDate = lsTranDate.substring(0, 4) + "-" + lsTranDate.substring(4, 6) + "-" + lsTranDate.substring(6, 8);
        
        lsSQL = MiscUtil.addCondition(lsSQL, "a.dTransact >= " + SQLUtil.toSQL(lsTranDate));
        lsSQL = lsSQL + " ORDER BY a.cTranStat ASC, a.sTransNox ASC";
         
        if (!fbByCode)
            lsSQL = MiscUtil.addCondition(lsSQL, "d.sClientNm LIKE " + SQLUtil.toSQL(fsValue + "%"));
        else
            lsSQL = MiscUtil.addCondition(lsSQL, "a.sTransNox LIKE " + SQLUtil.toSQL("%" + fsValue));
         
        ResultSet loRS = poGRider.executeQuery(lsSQL);
         
        JSONObject loJSON;
        JSONArray loArray = new JSONArray();

        try {
            while (loRS.next()){
                loJSON = new JSONObject();
                for (int lnCtr = 1; lnCtr <= loRS.getMetaData().getColumnCount(); lnCtr++){
                    loJSON.put(loRS.getMetaData().getColumnLabel(lnCtr), loRS.getString(lnCtr));
                }
                loArray.add(loJSON);
            }
        } catch (SQLException ex) {
            Logger.getLogger(XMSales.class.getName()).log(Level.SEVERE, null, ex);
            psErrMsgx = ex.getMessage();
            return null;
        }
        
        return loArray;
    }
    
    public boolean SearchTransaction(String fsValue, boolean fbByCode){
        String lsSQL = getSQ_Sales();
        String lsCondition = "";
        
        if (fbByCode){ //based on refer nox
            lsCondition = "a.sTransNox = " + SQLUtil.toSQL(fsValue);
        } else { //based on customer name
            lsCondition = "d.sClientNm LIKE " + SQLUtil.toSQL(fsValue + "%");
        }        
        
        ResultSet loRS = poGRider.executeQuery(MiscUtil.addCondition(lsSQL, lsCondition));
        
        try {
            if (MiscUtil.RecordCount(loRS) == 1){
                loRS.first();
                return openTransaction(loRS.getString("sTransNox"));
            } else{
                String lsHeader = "Trans No»Client Name»Date»Total»Status";
                String lsColName = "a.sTransNox»d.sClientNm»a.dTransact»a.nTranTotl»xTranStat";
                String lsColCrit = "a.sTransNox»d.sClientNm»a.dTransact»a.nTranTotl»xTranStat";
                
                JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                                            lsSQL, 
                                                            fsValue, 
                                                            lsHeader, 
                                                            lsColName, 
                                                            lsColCrit, 
                                                            1);

                if (loJSON != null){
                    return openTransaction((String) loJSON.get("sTransNox"));
                } else {
                    psWarnMsg = "No transaction to load.";
                    return false;
                }
            }
        } catch (SQLException e) {
            psErrMsgx = e.getMessage();
            return false;
        }
    }
    
    public void setGRider(GRider foGrider){
        this.poGRider = foGrider;
        this.psUserIDxx = foGrider.getUserID();
        
        if (psBranchCd.isEmpty()) psBranchCd = poGRider.getBranchCode();
    }
    
    private double computeTotal(){
        double lnTranTotal = 0;
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr ++){
            lnTranTotal += ((int) poControl.getDetail(lnCtr, "nQuantity") * Double.parseDouble(poControl.getDetail(lnCtr, "nUnitPrce").toString()))
                                - (((int) poControl.getDetail(lnCtr, "nQuantity") 
                                    * Double.parseDouble(poControl.getDetail(lnCtr, "nUnitPrce").toString())) 
                                    * Double.parseDouble(poControl.getDetail(lnCtr, "nDiscount").toString()))
                                - Double.parseDouble(poControl.getDetail(lnCtr, "nAddDiscx").toString());
        }
        
        //add the freight charge to total order
        //lnTranTotal += Double.parseDouble(poData.getFreightCharge().toString());
        //less the discounts
        //lnTranTotal = lnTranTotal - (lnTranTotal * Double.parseDouble(poData.getDiscount().toString())) - Double.parseDouble(poData.getAddtlDiscount().toString());
        return lnTranTotal;
    }
    
    public boolean addDetail(){
        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.ADD_ITEM, "Order No.: " + poData.getTransNo(), System.getProperty("pos.clt.crm.no"));
        return poControl.addDetail();
    }
    public boolean deleteDetail(int fnIndex){
        String lsBarcodex = "";
        String lsDescript = "";
        int lnQuantity = 0;
        
        XMInventory loInv = new XMInventory(poGRider, psBranchCd, true);
        if (loInv.browseRecord((String) poControl.getDetail(fnIndex, "sStockIDx"), true, true)){
            lsBarcodex = (String) loInv.getMaster("sBarCodex");
            lsDescript = (String) loInv.getMaster("sDescript");
            lnQuantity = (int) poControl.getDetail(fnIndex, "nQuantity");
        }
        
        if (!poControl.deleteDetail(fnIndex)) {
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                    , CRMEvent.DELETE_ITEM, "Order No.: " + poData.getTransNo() + "; " + "Barcode: " + lsBarcodex + "; " + "Description: " + lsDescript + "; " + "Quantity: " + lnQuantity
                    , System.getProperty("pos.clt.crm.no"));
            return false;
        }
        
        poData.setTranTotal(computeTotal());
        return true;
    }
    public int getDetailCount(){return poControl.ItemCount();}
    
    public void setDetail(int fnRow, int fnCol, Object foData) throws SQLException {
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poDetail.getColumn("sTransNox") ||
                fnCol == poDetail.getColumn("nEntryNox") ||
                fnCol == poDetail.getColumn("dModified"))){
                
                poControl.setDetail(fnRow, fnCol, foData);

                if (fnCol == poDetail.getColumn("nQuantity") ||
                    fnCol == poDetail.getColumn("nUnitPrce") ||
                    fnCol == poDetail.getColumn("nDiscount") ||
                    fnCol == poDetail.getColumn("nAddDiscx")) {
                    poData.setTranTotal(computeTotal());
                }
                
                if (fnCol == poDetail.getColumn("nDiscount") ||
                    fnCol == poDetail.getColumn("nAddDiscx")){
                    
                    String lsBarcodex = "";
                    String lsDescript = "";

                    XMInventory loInv = new XMInventory(poGRider, psBranchCd, true);
                    if (loInv.browseRecord((String) poControl.getDetail(fnRow, "sStockIDx"), true, true)){
                        lsBarcodex = (String) loInv.getMaster("sBarCodex");
                        lsDescript = (String) loInv.getMaster("sDescript");
                    }
                    
                    if (fnCol == poDetail.getColumn("nDiscount"))
                        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                            , CRMEvent.MODIFY_ITEM_DISCOUNT, "Order No.: " + poData.getTransNo() + "; " + "BC: " + lsBarcodex + "; " + "Desc: " + lsDescript + "; " + "Disc. Rate: " + foData
                            , System.getProperty("pos.clt.crm.no"));
                    else 
                        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                            , CRMEvent.MODIFY_ITEM_DISCOUNT, "Order No.: " + poData.getTransNo() + "; " + "BC: " + lsBarcodex + "; " + "Desc: " + lsDescript + "; " + "Peso Disc.: " + foData
                            , System.getProperty("pos.clt.crm.no"));
                }
            }
        }
    }

    public void setDetail(int fnRow, String fsCol, Object foData) throws SQLException {        
        setDetail(fnRow, poDetail.getColumn(fsCol), foData);           
    }
    
    public Object getDetail(int fnRow, String fsCol){return poControl.getDetail(fnRow, fsCol);}
    public Object getDetail(int fnRow, int fnCol){return poControl.getDetail(fnRow, fnCol);}
    
    public String getDetailInfo(String fsTableNme, 
                                    String fsPrimKey, 
                                    String fsValuexx,
                                    String fsColumnx) throws SQLException{
        String lsSQL;
        
        switch (fsTableNme){
            case "Inventory": 
                lsSQL = MiscUtil.addCondition(getSQ_Spareparts(), fsPrimKey + " = " + SQLUtil.toSQL(fsValuexx));
                break;
            default: lsSQL = "";
        }
        
        if (!lsSQL.equals("")){
            ResultSet loRS;
            
            loRS = poGRider.executeQuery(lsSQL);
            
            if (MiscUtil.RecordCount(loRS) == 1){
                return loRS.getString(fsColumnx);
            } else return "";
        } else return "";
    }
    
    private String getSQ_Spareparts(){
        return "SELECT" + 
                    "  a.sStockIDx" + 
                    ", a.sBarCodex" + 
                    ", a.sDescript" + 
                    ", a.sBriefDsc" + 
                    ", a.sAltBarCd" + 
                    ", a.sBrandCde" + 
                    ", a.sModelIDx" + 
                    ", a.sColorCde" + 
                    ", a.sInvTypCd" + 
                    ", a.nUnitPrce" + 
                    ", a.nSelPrice" + 
                    ", a.nDiscLev1" + 
                    ", a.nDiscLev2" + 
                    ", a.nDiscLev3" + 
                    ", a.nDealrDsc" + 
                    ", b.cClassify" + 
                    ", a.dModified" + 
                " FROM Inventory a" + 
                    " LEFT JOIN Inv_Master b" + 
                        " ON a.sStockIDx = b.sStockIDx" + 
                            " AND b.sBranchCd = " + SQLUtil.toSQL(psBranchCd);
    }   
    
    private String getSQ_Sales(){
        return "SELECT " +
                    "  a.sTransNox" +
                    ", a.sBranchCd" + 
                    ", a.dTransact" +
                    ", a.sClientID" +
                    ", a.sReferNox" +
                    ", a.nTranTotl" + 
                    ", a.sInvTypCd" + 
                    ", b.sBranchNm" + 
                    ", c.sDescript" + 
                    ", d.sClientNm" + 
                    ", a.cTranStat" + 
                    ", CASE " +
                        " WHEN a.cTranStat = '0' THEN 'OPEN'" +
                        " WHEN a.cTranStat = '1' THEN 'PAID'" +
                        " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                        " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                        " WHEN a.cTranStat = '4' THEN 'VOID'" +
                        " END AS xTranStat" +
                " FROM Sales_Master a" + 
                        " LEFT JOIN Branch b ON a.sBranchCd = b.sBranchCd" +
                        " LEFT JOIN Inv_Type c ON a.sInvTypCd = c.sInvTypCd" +
                    ", Client_Master d" + 
                " WHERE a.sClientID = d.sClientID" +
                    " AND a.sTransNox LIKE " + SQLUtil.toSQL(psBranchCd + System.getProperty("pos.clt.trmnl.no") + "%");
    }
    
    public boolean SearchTransaction(String fsValue){
        String lsSQL = getSQ_Sales();
        ResultSet loRS = poGRider.executeQuery(lsSQL);
        
        try {
            if (MiscUtil.RecordCount(loRS) == 1){
                loRS.first();
                return openTransaction(loRS.getString("sTransNox"));
            } else{
                String lsHeader = "Date»Name»TranTotal»ClientID";
                String lsColName = "dTransact»sClientNm»nTranTotl»sClientID";
                String lsColCrit = "a.dTransact»d.sClientNm»a.nTranTotl»a.sClientID";
                
                JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                                            lsSQL, 
                                                            fsValue, 
                                                            lsHeader, 
                                                            lsColName, 
                                                            lsColCrit, 
                                                            3);

                if (loJSON != null){
                    return openTransaction((String) loJSON.get("sTransNox"));
                } else {
                    psWarnMsg = "No transaction to load.";
                    return false;
                }
            }
        } catch (SQLException e) {
            psErrMsgx = e.getMessage();
            return false;
        }
    }
    
    public boolean SearchDetail(int fnRow, String fsIndex, Object foValue){
        switch(fsIndex){
            case "sOrderNox":
                return SearchDetail(fnRow, 2, foValue);
            case "sStockIDx":
                return SearchDetail(fnRow, 3, foValue);
            case "sBarCodex":
                return SearchDetail(fnRow, 80, foValue);
            case "sDescript":
                return SearchDetail(fnRow, 81, foValue);
            default:
                return false;
        }
    }
    
    public boolean SearchDetail(int fnRow, int fnIndex, Object foValue){
        switch(fnIndex){
            case 2: //sOrderNox
                return searchOrder(fnRow, (String) foValue);
            case 3: //sStockIDx
            case 80: //sBarCodex
            case 81: //sDescript
                return searchItem(fnRow, fnIndex, (String) foValue);
            default:
                return false;
        }
    }
    
    public JSONObject SearchMaster(String fsIndex, Object foValue){
        return SearchMaster(poData.getColumn(fsIndex), foValue);
    }
    
    public JSONObject SearchMaster(int fnIndex, Object foValue){
        switch (fnIndex){
            case 4: //sClientID
                XMClient loClient = new XMClient(poGRider, psBranchCd, true);
                JSONObject loJSON = loClient.BrowseClient((String) foValue, false);
                if (loJSON == null){
                    ClientFX oClient = new ClientFX();
                    ClientFX.poGRider = this.poGRider;
                    ClientFX.pnClientTp = 1;
                    
                    try {
                        CommonUtils.showModal(oClient);
                    } catch (Exception ex) {
                        System.err.println(ex.getMessage());
                    }
                    
                    if (oClient.getClientID().equals(""))
                        return null;
                    else return loClient.SearchClient(oClient.getClientID(), true);
                } else return loJSON;
            case 7: //sSalesman
                String lsSQL = "SELECT a.sEmployID, b.sClientNm" + 
                                " FROM Employee_Master001 a" +
                                    ", Client_Master b" +
                                " WHERE a.sEmployID = b.sClientID" +
                                    " AND a.sBranchCd = " + SQLUtil.toSQL(psBranchCd);
                
                return showFXDialog.jsonSearch(poGRider, 
                                        lsSQL, 
                                        (String) foValue, 
                                        "Employee ID»Name", 
                                        "sEmployID»sClientNm", 
                                        "a.sEmployID»b.sClientNm", 
                                        1);
            case 15: //sTermCode
                XMTerm loTerm = new XMTerm(poGRider, psBranchCd, true);
                return loTerm.searchTerm((String) foValue, false);
            default:
                return null;
        }
    }
    
    public boolean searchDiscount(boolean fbRateDisc){
        String lsHeader = "Code»Description»Rate»Additional»From»Thru";
        String lsColName = "sDiscIDxx»sDescript»nDiscRate»nAddDiscx»dDateFrom»dDateThru";
        String lsColCrit = "sDiscIDxx»sDescript»nDiscRate»nAddDiscx»dDateFrom»dDateThru";
        String lsSQL = "SELECT" +
                            "  sDiscIDxx" +
                            ", sDescript" +
                            ", nDiscRate" +
                            ", nAddDiscx" +
                            ", dDateFrom" +
                            ", dDateThru" + 
                        " FROM Promo_Discount" +
                        " WHERE cRecdStat = '1'" +
                            " AND " + SQLUtil.toSQL(poGRider.getServerDate()) + " BETWEEN dDateFrom AND dDateThru";
        
        String lsCondition = "";
        
        if (fbRateDisc)
            lsCondition  = "nDiscRate > 0.00 AND nDiscRate <= 1.00";
        else
            lsCondition  = "nAddDiscx > 0.00";
        
        
        lsSQL = MiscUtil.addCondition(lsSQL, lsCondition);
        System.out.println(lsSQL);
        
        JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                            lsSQL, 
                                            "", 
                                            lsHeader, 
                                            lsColName, 
                                            lsColCrit, 
                                            1);
        
        if (loJSON != null){
            poData.setDiscount(Double.parseDouble(String.valueOf(loJSON.get("nDiscRate"))));
            poData.setAddtlDiscount(Double.parseDouble(String.valueOf(loJSON.get("nAddDiscx"))));
            computeTotal();
            
            return true;
        }
        
        //reset the discounts
        poData.setDiscount(0.00);
        poData.setAddtlDiscount(0.00);
        
        computeTotal();
        
        return false;
    }
    
    private boolean searchItem(int fnRow, int fnIndex, String fsValue){
        boolean lbSearch = false;
        
        InvMaster loInv = new InvMaster(poGRider, psBranchCd, true);
        switch(fnIndex){
            case 3:
                if  (poData.getTranStatus().equals(TransactionStatus.STATE_OPEN))                
                    lbSearch = loInv.SearchStock(fsValue, "", true, true);
                else
                    lbSearch = loInv.SearchSoldStock(fsValue, "", true, true);
                
                break;
            case 80:
                if  (poData.getTranStatus().equals(TransactionStatus.STATE_OPEN))                
                    lbSearch = loInv.SearchStock(fsValue, "", true, false);
                else
                    lbSearch = loInv.SearchSoldStock(fsValue, "", false, false);
                break;
            case 81:
                if  (poData.getTranStatus().equals(TransactionStatus.STATE_OPEN))                
                    lbSearch = loInv.SearchStock(fsValue, "", false, false);
                else 
                    lbSearch = loInv.SearchSoldStock(fsValue, "", false, false); 
        }
        
        if (lbSearch){
            /*if ((int) loInv.getMaster("nQtyOnHnd") <= 0){
                psWarnMsg = "No stocks available for the item selected.";
                psErrMsgx = "";
                return false;
            }*/
            
            for (int lnCtr = 0; lnCtr <= poControl.ItemCount() -1; lnCtr ++){
                //check if the detail is existing
                if (poControl.getDetail(lnCtr, "sStockIDx").equals(loInv.getInventory("sStockIDx"))){
                    //serialized
                    if (loInv.getInventory("cSerialze").equals("1") && !loInv.getSerial("sSerialID").equals("")){
                        if (loInv.getSerial("sSerialID").equals((String) poControl.getDetail(lnCtr, "sSerialID")))
                            return true;
                    }
                    //not serialized
                    else {
                        /*if ((int) loInv.getMaster("nQtyOnHnd") < (int) poControl.getDetail(lnCtr, "nQuantity") + 1){
                            psWarnMsg = "No stocks available for the item selected.";
                            psErrMsgx = "";
                            return false;
                        }*/
                        
                        poControl.setDetail(lnCtr, "nQuantity", (int) poControl.getDetail(lnCtr, "nQuantity") + 1);
                        poData.setTranTotal(computeTotal());
                        return true;
                    }
                }
            }
            
            poControl.setDetail(fnRow, "sStockIDx", loInv.getInventory("sStockIDx"));
            poControl.setDetail(fnRow, "nInvCostx", loInv.getInventory("nUnitPrce"));
            poControl.setDetail(fnRow, "nUnitPrce", loInv.getInventory("nSelPrice"));
            poControl.setDetail(fnRow, "nQuantity", 1);
            poControl.setDetail(fnRow, "nDiscount", 0.00);
            poControl.setDetail(fnRow, "nAddDiscx", 0.00);
            poControl.setDetail(fnRow, "cNewStock", "1");
            poControl.setDetail(fnRow, "sInsTypID", "");
            poControl.setDetail(fnRow, "nInsAmtxx", 0.00);
            poControl.setDetail(fnRow, "sWarrntNo", "");
            poControl.setDetail(fnRow, "cUnitForm", "1");
            poControl.setDetail(fnRow, "sNotesxxx", "");
            poControl.setDetail(fnRow, "cDetailxx", "0");
            poControl.setDetail(fnRow, "cPromoItm", "0");
            poControl.setDetail(fnRow, "cComboItm", "0");
            
            String lsSerialNo = "";
            if (loInv.getInventory("cSerialze").equals("1")){
                poControl.setDetail(fnRow, "sSerialID", loInv.getSerial("sSerialID"));
                lsSerialNo = (String) loInv.getSerial("sSerial01");
            }
            else
                poControl.setDetail(fnRow, "sSerialID", "");
            
            
            String lsBarcodex = "";
            String lsDescript = "";
            
            int lnQuantity = 0;

            XMInventory loDetail = new XMInventory(poGRider, psBranchCd, true);
            if (loDetail.browseRecord((String) loInv.getInventory("sStockIDx"), true, true)){
                lsBarcodex = (String) loInv.getInventory("sBarCodex");
                lsDescript = (String) loInv.getInventory("sDescript");
                lnQuantity = 1;
            }

            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                        , CRMEvent.MODIFY_ITEM, "Order No.: " + poData.getTransNo() + "; " + "BC: " + lsBarcodex + "; " + "Desc: " + lsDescript + "; " + "Qty: " + lnQuantity + "; "
                        , System.getProperty("pos.clt.crm.no"));
        } else {
            psWarnMsg = loInv.getMessage();
            psErrMsgx = loInv.getErrMsg();
        }
        
        poData.setTranTotal(computeTotal());
        return lbSearch;
    }
    
    private boolean searchOrder(int fnRow, String fsValue){
        return false;
    }
    
    public boolean OpenCashDrawer(){
        if (poSales.getSalesStatus() != 2) {
            psWarnMsg = "Invalid sales status. Unable to get cash drawer values.";
            return false;
        }
        //recompute cash drawer values
        poSales.ComputeCashDrawer(poSales.DailySummary().getTransactionDate(), 
                                    poSales.DailySummary().getMachineNo(), 
                                    poSales.DailySummary().getCashier());
        
        
        XMCashDrawer loDrawer = new XMCashDrawer(poGRider, poGRider.getBranchCode(), false, true);
        if (loDrawer.LoadCashierTransaction(poSales.DailySummary().getTransactionDate(), 
                                            poSales.DailySummary().getCashier())){
            if (loDrawer.showGUI()){
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.CASH_DRAWER_CHECK, "", System.getProperty("pos.clt.crm.no"));
            }
        }
        
        return true;
    }
    
    public boolean PrintInvoice(String fsTransNox){
        if(pnEditMode != EditMode.READY) return false;        
        
        XMORMaster loRec = new XMORMaster(poGRider, psBranchCd, true);
        if (!loRec.loadTransaction(fsTransNox, pxeSourceCode)) return false;
        
        JSONObject loMasx = new JSONObject();
        JSONObject loJSON = new JSONObject();
        String lsDescript;
        ResultSet loRS;
        
        //START - HEADER
        loJSON.put("sCompnyNm", poSales.getCompanyName());
        loJSON.put("sBranchNm", poGRider.getBranchName());
        loJSON.put("sAddress1", poGRider.getAddress());
        loJSON.put("sAddress2", poGRider.getTownName() + ", " + poGRider.getProvince());
        loJSON.put("sVATREGTN", poSales.getVATRegTIN());
        loJSON.put("sMINumber", poSales.getMachineNo());
        loJSON.put("sSerialNo", poSales.getSerialNo());
        loJSON.put("sSlipType", pxeInvoiceTyp);
        loJSON.put("cReprintx", loRec.getMaster("cPrintedx").equals("1") ? "1" : "0");
        loJSON.put("cTranMode", System.getProperty("pos.clt.tran.mode"));
        loMasx.put("Header", loJSON);
        //END - HEADER
            
        //START - MASTER
        loJSON = new JSONObject();
        loJSON.put("nTranTotl", poData.getTranTotal());
        loJSON.put("nFreightx", poData.getFreightCharge());
        loJSON.put("nVATRatex", poData.getVATRate());
        loJSON.put("nDiscount", poData.getDiscount());
        loJSON.put("nAddDiscx", poData.getAddtlDiscount());
        
        //mac 2020.08.08
        //get discount description
        if (Double.parseDouble(String.valueOf(poData.getDiscount())) > 0 || 
            Double.parseDouble(String.valueOf(poData.getAddtlDiscount())) > 0){
            lsDescript = "SELECT sDiscIDxx, sDescript, nDiscRate, nAddDiscx, dDateFrom, dDateThru" +
                            " FROM Promo_Discount" +
                            " WHERE cRecdStat = '1'" +
                                " AND " + SQLUtil.toSQL(poGRider.getServerDate()) + " BETWEEN dDateFrom AND dDateThru" +
                                " AND nDiscRate = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poData.getDiscount()))) +
                                " AND nAddDiscx = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poData.getAddtlDiscount())));

            loRS = poGRider.executeQuery(lsDescript);
            try {
                if (loRS.next())
                    loJSON.put("sPromoDsc", loRS.getString("sDescript"));
                else
                    loJSON.put("sPromoDsc", "Regular Disc.");
            } catch (SQLException ex) {
                Logger.getLogger(XMSales.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        XMClient loClient = new XMClient(poGRider, psBranchCd, true);
        JSONObject jsonClient = loClient.SearchClient(poData.getClientID(), true);
        if (jsonClient != null){
            loJSON.put("sClientNm", (String) jsonClient.get("sClientNm"));
            loJSON.put("sAddressx", "");
            loJSON.put("sTINumber", "");
            loJSON.put("sBusStyle", "");
        } else{
            loJSON.put("sClientNm", "");
            loJSON.put("sAddressx", "");
            loJSON.put("sTINumber", "");
            loJSON.put("sBusStyle", "");
        }
        
        //mac 2020.07-29
        //create tranasaction number per print of invoice
        String lsTransNox = CommonUtils.createSalesTransaction(poGRider, 
                                                                (String) loRec.getMaster("sORNumber"), 
                                                                pxeInvoiceCde, 
                                                                (String) loRec.getMaster("sTransNox"), 
                                                                loRec.getMaster("cPrintedx").equals("1"), 
                                                                false);
        
        loJSON.put("sCashierx", poSales.getCashierName());
        loJSON.put("sTerminal", poSales.getTerminalNo());
        loJSON.put("sTransNox", lsTransNox.substring(8)); //(String) loRec.getMaster("sSourceNo")
        loJSON.put("sInvoicex", (String) loRec.getMaster("sORNumber"));
        loJSON.put("sDateTime", SQLUtil.dateFormat(loRec.getMaster("dModified"), SQLUtil.FORMAT_TIMESTAMP));        
        loMasx.put("Master", loJSON);
        
        loMasx.put("webserver", poSales.getWebServer());
        loMasx.put("printer", poSales.getPrinter());
        
        //END - MASTER

        //START - DETAIL
        int lnCtr;
        JSONArray loDetail = new JSONArray();

        Inventory loInv = new Inventory(poGRider, psBranchCd, true);
        XMBrand loBrand = new XMBrand(poGRider, psBranchCd, true);
        XMModel loModel = new XMModel(poGRider, psBranchCd, true);
        XMColor loColor = new XMColor(poGRider, psBranchCd, true);
        InvMaster loInvMaster = new InvMaster(poGRider, psBranchCd, true);
        JSONObject loParam;
        
        for (lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr++){
            if (loInv.BrowseRecord((String) poControl.getDetail(lnCtr, "sStockIDx"), true, true)){
                loJSON = new JSONObject();
                loJSON.put("sBarCodex", loInv.getMaster("sBarCodex"));
                loJSON.put("sDescript", loInv.getMaster("sDescript"));
                loJSON.put("cSerialze", loInv.getMaster("cSerialze"));
                loJSON.put("nQuantity", poControl.getDetail(lnCtr, "nQuantity"));
                loJSON.put("nAmountxx", poControl.getDetail(lnCtr, "nUnitPrce"));
                loJSON.put("nDiscount", poControl.getDetail(lnCtr, "nDiscount"));
                loJSON.put("nAddDiscx", poControl.getDetail(lnCtr, "nAddDiscx"));
                loJSON.put("cVatablex", "1");
                
                //mac 2020.07.31
                //get discount description
                if (Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nDiscount"))) > 0 || 
                    Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nAddDiscx"))) > 0){
                    lsDescript = "SELECT sDiscIDxx, sDescript, nDiscRate, nAddDiscx, dDateFrom, dDateThru" +
                                    " FROM Promo_Discount" +
                                    " WHERE cRecdStat = '1'" +
                                        " AND " + SQLUtil.toSQL(poGRider.getServerDate()) + " BETWEEN dDateFrom AND dDateThru" +
                                        " AND nDiscRate = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nDiscount")))) +
                                        " AND nAddDiscx = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nAddDiscx"))));

                    loRS = poGRider.executeQuery(lsDescript);
                    try {
                        if (loRS.next())
                            loJSON.put("sPromoDsc", loRS.getString("sDescript"));
                        else
                            loJSON.put("sPromoDsc", "Regular Disc.");
                    } catch (SQLException ex) {
                        Logger.getLogger(XMSales.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                
                if (loInv.getMaster("cSerialze").equals("1")){
                    lsDescript = "";
                    if (!loInv.getMaster("sBrandCde").equals("")){
                        loParam = loBrand.searchBrand((String) loInv.getMaster("sBrandCde"), true);
                        if (loParam != null) lsDescript = (String) loParam.get("sDescript") + "/";
                    }
                    
                    if (!loInv.getMaster("sModelCde").equals("")){
                        loParam = loModel.searchModel((String) loInv.getMaster("sModelCde"), true);
                        if (loParam != null) lsDescript = lsDescript + (String) loParam.get("sDescript") + "/";
                    }
                    
                    if (!loInv.getMaster("sColorCde").equals("")){
                        loParam = loColor.searchColor((String) loInv.getMaster("sColorCde"), true);
                        if (loParam != null) lsDescript = lsDescript + (String) loParam.get("sDescript");
                    }
                    
                    loJSON.put("xDescript", lsDescript);
                    
                    if (loInvMaster.SearchSoldStock((String) poControl.getDetail(lnCtr, "sStockIDx"),
                                                    (String) poControl.getDetail(lnCtr, "sSerialID"), true, true)){
                        loJSON.put("sSerial01", (String) loInvMaster.getSerial("sSerial01"));
                        loJSON.put("sSerial02", (String) loInvMaster.getSerial("sSerial02"));
                    }
                }
                
                loDetail.add(loJSON);
            }
        }
        loMasx.put("Detail", loDetail);            
        //END - DETAIL

        //START - PAYMENT
        JSONObject loPayment = new JSONObject();
        loPayment.put("nCashAmtx", loRec.getMaster("nCashAmtx"));       
        
        XMSalesPayment loPaym = new XMSalesPayment(poGRider, psBranchCd, true);        
        
        JSONArray laPayment = new JSONArray();
        if (loPaym.loadCreditCard((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sBankCode", (String) loPaym.getPayInfo("sBankCode"));
            loJSON.put("sCardNoxx", (String) loPaym.getPayInfo("sCardNoxx"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sCredtCrd", laPayment);
        
        laPayment = new JSONArray();
        if (loPaym.loadCheck((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sBankCode", (String) loPaym.getPayInfo("sBankCode"));
            loJSON.put("sCheckNox", (String) loPaym.getPayInfo("sCheckNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sCheckPay", laPayment);  
        
        laPayment = new JSONArray();
        if (loPaym.loadGC((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sCompnyCd", (String) loPaym.getPayInfo("sCompnyCd"));
            loJSON.put("sReferNox", (String) loPaym.getPayInfo("sReferNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sGiftCert", laPayment);
        
        laPayment = new JSONArray();
        if (loPaym.loadFinance((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sCompnyCd", (String) loPaym.getPayInfo("sClientID"));
            loJSON.put("sReferNox", (String) loPaym.getPayInfo("sReferNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmtPaidx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sFinancer", laPayment); 
        
        loMasx.put("Payment", loPayment);

        //START - FOOTER
        loJSON = new JSONObject();
        loJSON.put("sDevelopr", System.getProperty("pos.footer.sDevelopr"));
        loJSON.put("sAddress1", System.getProperty("pos.footer.sAddress1"));
        loJSON.put("sAddress2", System.getProperty("pos.footer.sAddress2"));
        loJSON.put("sVATREGTN", System.getProperty("pos.footer.sVATREGTN"));
        loJSON.put("sAccrNmbr", System.getProperty("pos.footer.sAccrNmbr"));
        loJSON.put("sAccrIssd", System.getProperty("pos.footer.sAccrIssd"));
        loJSON.put("sAccdExpr", System.getProperty("pos.footer.sAccdExpr"));
        loJSON.put("sPTUNmber", System.getProperty("pos.footer.sPTUNmber"));
//        loJSON.put("sPTUIssdx", System.getProperty("pos.footer.sPTUIssdx"));
//        loJSON.put("sPTUExpry", System.getProperty("pos.footer.sPTUExpry"));
        loMasx.put("Footer", loJSON);
        
        /*
        loJSON.put("sDevelopr", "RMJ Business Solutions");
        loJSON.put("sAddress1", "32 Pogo grande");
        loJSON.put("sAddress2", "Dagupan City, Pangasinan 2400");
        loJSON.put("sVATREGTN", "XXX-XXX-XXX-XXXXX");
        loJSON.put("sAccrNmbr", "XXXXXXXXXXXXXXXXXXXXXX");
        loJSON.put("sAccrIssd", "XXXX-XX-XX");
        loJSON.put("sAccdExpr", "XXXX-XX-XX");
        loJSON.put("sPTUNmber", "XXXXXXXXXXXXXXXXXXXXXX");
        loJSON.put("sPTUIssdx", "XXXX-XX-XX");
        loJSON.put("sPTUExpry", "XXXX-XX-XX");
        */
        
        //END - FOOTER
        
        loJSON = EPSONPrint.Invoice(loMasx);
        
        if ("success".equals(((String) loJSON.get("result")).toLowerCase())){
            String lsSQL = "UPDATE Receipt_Master" + 
                            " SET  cPrintedx = '1'" +
                                ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            " WHERE sTransNox = " + SQLUtil.toSQL(loRec.getMaster("sTransNox"));
            poGRider.executeQuery(lsSQL, "Receipt_Master", psBranchCd, "");
            
            
            Invoice loPrinter = new Invoice(loMasx);
            if (!loPrinter.Print()) ShowMessageFX.Warning(null, pxeModuleName, loPrinter.getMessage());
            
            if (loRec.getMaster("cPrintedx").equals("1")){                
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                    , CRMEvent.REPRINT_INVOICE, pxeInvoiceTyp + (String) loRec.getMaster("sORNumber")
                    , System.getProperty("pos.clt.crm.no"));
            } else {
                CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                    , CRMEvent.PRINT_INVOICE, pxeInvoiceTyp + (String) loRec.getMaster("sORNumber")
                    , System.getProperty("pos.clt.crm.no"));
            }
            
            return true;
        } 
       
        JSONParser loParser = new JSONParser();
        try {
            loJSON = (JSONObject) loParser.parse(loJSON.get("error").toString());
            psErrMsgx = (String) loJSON.get("message");
            System.err.print(psErrMsgx);
        } catch (ParseException e) {
            psErrMsgx = e.getMessage();
            System.err.print(psErrMsgx);
        }
        return false;
    }
    
    public boolean PrintCancelledInvoice(String fsTransNox){
        if(pnEditMode != EditMode.READY) return false;        
        
        XMORMaster loRec = new XMORMaster(poGRider, psBranchCd, true);
        if (!loRec.loadTransaction(fsTransNox, pxeSourceCode)) return false;
        
        JSONObject loMasx = new JSONObject();
        JSONObject loJSON = new JSONObject();
        String lsDescript;
        ResultSet loRS;
        
        //START - HEADER
        loJSON.put("sCompnyNm", poSales.getCompanyName());
        loJSON.put("sBranchNm", poGRider.getBranchName());
        loJSON.put("sAddress1", poGRider.getAddress());
        loJSON.put("sAddress2", poGRider.getTownName() + ", " + poGRider.getProvince());
        loJSON.put("sVATREGTN", poSales.getVATRegTIN());
        loJSON.put("sMINumber", poSales.getMachineNo());
        loJSON.put("sSerialNo", poSales.getSerialNo());
        loJSON.put("sSlipType", pxeInvoiceTyp);
        loJSON.put("cReprintx", loRec.getMaster("cPrintedx").equals("1") ? "1" : "0");
        loJSON.put("cTranMode", System.getProperty("pos.clt.tran.mode"));
        loMasx.put("Header", loJSON);
        //END - HEADER
            
        //START - MASTER
        loJSON = new JSONObject();
        loJSON.put("nTranTotl", Double.parseDouble(String.valueOf(poData.getTranTotal())) * -1);
        loJSON.put("nFreightx", Double.parseDouble(String.valueOf(poData.getFreightCharge())) * -1);
        loJSON.put("nVATRatex", poData.getVATRate());
        loJSON.put("nDiscount", Double.parseDouble(String.valueOf(poData.getDiscount())));
        loJSON.put("nAddDiscx", Double.parseDouble(String.valueOf(poData.getAddtlDiscount())));
        
        //mac 2020.08.08
        //get discount description
        if (Double.parseDouble(String.valueOf(poData.getDiscount())) > 0 || 
            Double.parseDouble(String.valueOf(poData.getAddtlDiscount())) > 0){
            lsDescript = "SELECT sDiscIDxx, sDescript, nDiscRate, nAddDiscx, dDateFrom, dDateThru" +
                            " FROM Promo_Discount" +
                            " WHERE cRecdStat = '1'" +
                                " AND " + SQLUtil.toSQL(poGRider.getServerDate()) + " BETWEEN dDateFrom AND dDateThru" +
                                " AND nDiscRate = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poData.getDiscount()))) +
                                " AND nAddDiscx = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poData.getAddtlDiscount())));

            loRS = poGRider.executeQuery(lsDescript);
            try {
                if (loRS.next())
                    loJSON.put("sPromoDsc", loRS.getString("sDescript"));
                else
                    loJSON.put("sPromoDsc", "Regular Disc.");
            } catch (SQLException ex) {
                Logger.getLogger(XMSales.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        XMClient loClient = new XMClient(poGRider, psBranchCd, true);
        JSONObject jsonClient = loClient.SearchClient(poData.getClientID(), true);
        if (jsonClient != null){
            loJSON.put("sClientNm", (String) jsonClient.get("sClientNm"));
            loJSON.put("sAddressx", "");
            loJSON.put("sTINumber", "");
            loJSON.put("sBusStyle", "");
        } else{
            loJSON.put("sClientNm", "");
            loJSON.put("sAddressx", "");
            loJSON.put("sTINumber", "");
            loJSON.put("sBusStyle", "");
        }
        
        //mac 2020.07-29
        //create tranasaction number per print of invoice
        String lsTransNox = CommonUtils.createSalesTransaction(poGRider, 
                                                                (String) loRec.getMaster("sORNumber"), 
                                                                pxeInvoiceCde, 
                                                                (String) loRec.getMaster("sTransNox"), 
                                                                loRec.getMaster("cPrintedx").equals("1"), 
                                                                true);
        
        loJSON.put("sCashierx", poSales.getCashierName());
        loJSON.put("sTerminal", poSales.getTerminalNo());
        loJSON.put("sTransNox", lsTransNox.substring(8));
        loJSON.put("sInvoicex", (String) loRec.getMaster("sORNumber"));
        loJSON.put("sDateTime", SQLUtil.dateFormat(loRec.getMaster("dModified"), SQLUtil.FORMAT_TIMESTAMP));
        loMasx.put("Master", loJSON);
        
        loMasx.put("webserver", poSales.getWebServer());
        loMasx.put("printer", poSales.getPrinter());
        //END - MASTER

        //START - DETAIL
        int lnCtr;
        JSONArray loDetail = new JSONArray();

        Inventory loInv = new Inventory(poGRider, psBranchCd, true);
        XMBrand loBrand = new XMBrand(poGRider, psBranchCd, true);
        XMModel loModel = new XMModel(poGRider, psBranchCd, true);
        XMColor loColor = new XMColor(poGRider, psBranchCd, true);
        InvMaster loInvMaster = new InvMaster(poGRider, psBranchCd, true);
        JSONObject loParam;
        
        for (lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr++){
            if (loInv.BrowseRecord((String) poControl.getDetail(lnCtr, "sStockIDx"), true, true)){
                loJSON = new JSONObject();
                loJSON.put("sBarCodex", loInv.getMaster("sBarCodex"));
                loJSON.put("sDescript", loInv.getMaster("sDescript"));
                loJSON.put("cSerialze", loInv.getMaster("cSerialze"));
                loJSON.put("nQuantity", (int) poControl.getDetail(lnCtr, "nQuantity") * -1);
                loJSON.put("nAmountxx", poControl.getDetail(lnCtr, "nUnitPrce"));
                loJSON.put("nDiscount", poControl.getDetail(lnCtr, "nDiscount"));
                loJSON.put("nAddDiscx", poControl.getDetail(lnCtr, "nAddDiscx"));
                loJSON.put("cVatablex", "1");
                
                //mac 2020.07.31
                //get discount description
                if (Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nDiscount"))) > 0 || 
                    Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nAddDiscx"))) > 0){
                    lsDescript = "SELECT sDiscIDxx, sDescript, nDiscRate, nAddDiscx, dDateFrom, dDateThru" +
                                    " FROM Promo_Discount" +
                                    " WHERE cRecdStat = '1'" +
                                        " AND " + SQLUtil.toSQL(poGRider.getServerDate()) + " BETWEEN dDateFrom AND dDateThru" +
                                        " AND nDiscRate = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nDiscount")))) +
                                        " AND nAddDiscx = " + SQLUtil.toSQL(Double.parseDouble(String.valueOf(poControl.getDetail(lnCtr, "nAddDiscx"))));

                    loRS = poGRider.executeQuery(lsDescript);
                    try {
                        if (loRS.next())
                            loJSON.put("sPromoDsc", loRS.getString("sDescript"));
                        else
                            loJSON.put("sPromoDsc", "Regular Disc.");
                    } catch (SQLException ex) {
                        Logger.getLogger(XMSales.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                
                if (loInv.getMaster("cSerialze").equals("1")){
                    lsDescript = "";
                    if (!loInv.getMaster("sBrandCde").equals("")){
                        loParam = loBrand.searchBrand((String) loInv.getMaster("sBrandCde"), true);
                        if (loParam != null) lsDescript = (String) loParam.get("sDescript") + "/";
                    }
                    
                    if (!loInv.getMaster("sModelCde").equals("")){
                        loParam = loModel.searchModel((String) loInv.getMaster("sModelCde"), true);
                        if (loParam != null) lsDescript = lsDescript + (String) loParam.get("sDescript") + "/";
                    }
                    
                    if (!loInv.getMaster("sColorCde").equals("")){
                        loParam = loColor.searchColor((String) loInv.getMaster("sColorCde"), true);
                        if (loParam != null) lsDescript = lsDescript + (String) loParam.get("sDescript");
                    }
                    
                    loJSON.put("xDescript", lsDescript);
                    
                    if (loInvMaster.SearchSoldStock((String) poControl.getDetail(lnCtr, "sStockIDx"),
                                                    (String) poControl.getDetail(lnCtr, "sSerialID"), true, true)){
                        loJSON.put("sSerial01", (String) loInvMaster.getSerial("sSerial01"));
                        loJSON.put("sSerial02", (String) loInvMaster.getSerial("sSerial02"));
                    }
                }
                
                loDetail.add(loJSON);
            }
        }
        loMasx.put("Detail", loDetail);            
        //END - DETAIL

        //START - PAYMENT
        JSONObject loPayment = new JSONObject();
        loPayment.put("nCashAmtx", loRec.getMaster("nCashAmtx"));       
        
        XMSalesPayment loPaym = new XMSalesPayment(poGRider, psBranchCd, true);        
        
        JSONArray laPayment = new JSONArray();
        if (loPaym.loadCreditCard((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sBankCode", (String) loPaym.getPayInfo("sBankCode"));
            loJSON.put("sCardNoxx", (String) loPaym.getPayInfo("sCardNoxx"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sCredtCrd", laPayment);
        
        laPayment = new JSONArray();
        if (loPaym.loadCheck((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sBankCode", (String) loPaym.getPayInfo("sBankCode"));
            loJSON.put("sCheckNox", (String) loPaym.getPayInfo("sCheckNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sCheckPay", laPayment);  
        
        laPayment = new JSONArray();
        if (loPaym.loadGC((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sCompnyCd", (String) loPaym.getPayInfo("sCompnyCd"));
            loJSON.put("sReferNox", (String) loPaym.getPayInfo("sReferNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmountxx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sGiftCert", laPayment); 
        
        laPayment = new JSONArray();
        if (loPaym.loadFinance((String) loRec.getMaster("sTransNox"), pxeInvoiceCde)){
            loJSON = new JSONObject();
            loJSON.put("sClientID", (String) loPaym.getPayInfo("sClientID"));
            loJSON.put("sReferNox", (String) loPaym.getPayInfo("sReferNox"));
            loJSON.put("nAmountxx", Double.valueOf(String.valueOf(loPaym.getPayInfo("nAmtPaidx"))));
            laPayment.add(loJSON);
        }
        loPayment.put("sFinancer", laPayment); 

        loMasx.put("Payment", loPayment);

        //START - FOOTER
        loJSON = new JSONObject();
        loJSON.put("sDevelopr", "RMJ Business Solutions");
        loJSON.put("sAddress1", "32 Pogo grande");
        loJSON.put("sAddress2", "Dagupan City, Pangasinan 2400");
        loJSON.put("sVATREGTN", "XXX-XXX-XXX-XXXXX");
        loJSON.put("sAccrNmbr", "XXXXXXXXXXXXXXXXXXXXXX");
        loJSON.put("sAccrIssd", "XXXX-XX-XX");
        loJSON.put("sAccdExpr", "XXXX-XX-XX");
        loJSON.put("sPTUNmber", "XXXXXXXXXXXXXXXXXXXXXX");
//        loJSON.put("sPTUIssdx", "XXXX-XX-XX");
//        loJSON.put("sPTUExpry", "XXXX-XX-XX");
        loMasx.put("Footer", loJSON);
        //END - FOOTER
       
        loJSON = EPSONPrint.CancelInvoice(loMasx);
        if ("success".equals(((String) loJSON.get("result")).toLowerCase())){
            String lsSQL = "UPDATE Receipt_Master" + 
                            " SET  cPrintedx = '1'" +
                                ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            " WHERE sTransNox = " + SQLUtil.toSQL(loRec.getMaster("sTransNox"));
            poGRider.executeQuery(lsSQL, "Receipt_Master", psBranchCd, "");
            
            Invoice_Cancel loPrinter = new Invoice_Cancel(loMasx);
            if (!loPrinter.Print()) ShowMessageFX.Warning(null, pxeModuleName, loPrinter.getMessage());
            
            CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no")
                , CRMEvent.CANCELLED_INVOICE, pxeInvoiceTyp + (String) loRec.getMaster("sORNumber")
                , System.getProperty("pos.clt.crm.no"));
            
            return true;
        } 
        
        
        JSONParser loParser = new JSONParser();
        try {
            loJSON = (JSONObject) loParser.parse(loJSON.get("error").toString());
            psErrMsgx = (String) loJSON.get("message");
            System.err.print(psErrMsgx);
        } catch (ParseException e) {
            psErrMsgx = e.getMessage();
            System.err.print(psErrMsgx);
        }
        
        return false;
    }
    
    public String getSalesman(String fsEmployID){
        String lsSQL = "SELECT a.sEmployID, b.sClientNm" + 
                        " FROM Employee_Master001 a" +
                            ", Client_Master b" +
                        " WHERE a.sEmployID = b.sClientID" +
                            " AND a.sEmployID = " + SQLUtil.toSQL(fsEmployID);
        
        ResultSet loRS = poGRider.executeQuery(lsSQL);
        
        try {
            if (loRS.next())
                return loRS.getString("sClientNm");
        } catch (SQLException ex) {
            System.err.println(ex.getMessage());
        }
        
        return "";
    }
    
    private int processDailySales(){        
        poSales.setCashier(poGRider.getUserID());
        
        if (!poSales.NewTransaction()) return poSales.getSalesStatus();
        
        if (poSales.getEditMode() == EditMode.READY) return poSales.getSalesStatus();
        
        //TODO: create interface for opening shift balance
        String lsValue = "";
        int lnRetry = 0;
        double lnValue = 0.00;
        boolean lbContinue = false;
        
        lsValue = ShowMessageFX.InputText("Please input your petty cash amount.", "Petty Cash", "Input Required");

        while (!lbContinue && lnRetry <= 3){
            if (StringUtil.isNumeric(lsValue)){
                lnValue = Double.parseDouble(lsValue);
                lbContinue = true;
            } else{
                lnValue = 0.00;
            }

            if (lnValue == 0.00){
                lbContinue = ShowMessageFX.YesNo("Do you want to continue with zero petty cash?", "Cofirm", "Please confirm...");
            }        
            
            lnRetry += lnRetry;
        }
        
        poSales.DailySummary().setOpeningBalance(lnValue);
        poSales.DailySummary().setDateOpened(poGRider.getServerDate());
        poSales.SaveTransaction();
        
        CommonUtils.createEventLog(poGRider, poGRider.getBranchCode() + System.getProperty("pos.clt.trmnl.no"), CRMEvent.CASH_DEPOSIT, String.valueOf(poSales.DailySummary().getOpeningBalance()), System.getProperty("pos.clt.crm.no"));
            
        return poSales.getSalesStatus();
    }
    
    public boolean PrintXReading(){        
        return poSales.PrintTXReading(poSales.DailySummary().getTransactionDate(), poSales.DailySummary().getMachineNo());
    }
    
    public boolean PrintZReading(){       
        return poSales.PrintTZReading(poSales.DailySummary().getTransactionDate(), 
                                        poSales.DailySummary().getTransactionDate(), 
                                        poSales.DailySummary().getMachineNo());
    }
    
    public boolean PrintXReading(String lsPeriod){        
        return poSales.RePrintTXReading(lsPeriod, poSales.DailySummary().getMachineNo());
    }
    
    public boolean PrintZReading(String lsPeriodFrom, String lsPeriodThru){       
        return poSales.RePrintTZReading(lsPeriodFrom, 
                                        lsPeriodThru, 
                                        poSales.DailySummary().getMachineNo());
    }
    
    public String getErrMsg(){return psErrMsgx;}
    public String getWarnMsg(){return psWarnMsg;}
    
    public XMDailySales getDailySales(){return poSales;}
    
    //Member Variables
    private GRider poGRider;
    private Sales poControl;
    private XMDailySales poSales;
    private UnitSalesMaster poData;
    private final UnitSalesDetail poDetail = new UnitSalesDetail();
    
    private String psBranchCd;
    private int pnEditMode;
    private String psUserIDxx;
    private boolean pbWithParent;
    
    private String psWarnMsg = "";
    private String psErrMsgx = "";
    
    private final String pxeModuleName = "Sales";
    private final String pxeSourceCode = "SL"; //sales
    private final String pxeInvoiceTyp = "SI"; //official receipt
    private final String pxeInvoiceCde = "ORec"; //official receipt source code
    
}
