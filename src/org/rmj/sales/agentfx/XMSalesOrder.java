/**
 * @author  Michael Cuison
 * @date    2018-04-19
 */
package org.rmj.sales.agentfx;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.CommonUtils;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.cas.client.application.ClientFX;
import org.rmj.cas.client.base.XMClient;
import org.rmj.cas.inventory.base.InvMaster;
import org.rmj.cas.parameter.agent.XMTerm;
import org.rmj.sales.base.SalesOrder;
import org.rmj.sales.pojo.UnitSalesOrderDetail;
import org.rmj.sales.pojo.UnitSalesOrderMaster;

public class XMSalesOrder{    
    public XMSalesOrder(GRider foGRider, String fsBranchCD, boolean fbWithParent){
        this.poGRider = foGRider;
        if (foGRider != null){
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;
            
            poControl = new SalesOrder();
            poControl.setGRider(foGRider);
            poControl.setBranch(fsBranchCD);
            poControl.setWithParent(fbWithParent);
            
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
        poData = poControl.newTransaction();              
        
        if (poData == null){
            return false;
        }else{
            poData.setValue("dTransact", poGRider.getServerDate());
            poData.setValue("dDueDatex", Date.valueOf("1900-01-01"));
            poData.setValue("dExpected", poGRider.getServerDate());
            
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
            pnEditMode = EditMode.READY;
            return true;
        }
    }

    public boolean updateTransaction() {
        if(pnEditMode != EditMode.READY) {
            return false;
        } else{
            if (!poData.getTranStatus().equals(TransactionStatus.STATE_OPEN)){
                psWarnMsg = "Trasaction may be CLOSED/CANCELLED/POSTED.";
                psErrMsgx = "Can't update processed transactions!!!";
                return false;
            }
            
            pnEditMode = EditMode.UPDATE;
            addDetail();
            return true;
        }
    }

    public boolean saveTransaction() {
        if(pnEditMode == EditMode.UNKNOWN){
            return false;
        }else{
            // Perform testing on values that needs approval here...
            UnitSalesOrderMaster loResult;
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
            if(!poData.getTranStatus().equals(TransactionStatus.STATE_OPEN)){  
                psErrMsgx = "Can't cancel this transaction!!!";
                psWarnMsg = "Trasaction was PAID already.";
                return false;
            }
            
            boolean lbResult = poControl.cancelTransaction(fsTransNox);
            if(lbResult) 
                pnEditMode = EditMode.UNKNOWN;
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
            
            boolean lbResult = poControl.closeTransaction(fsTransNox);
            if(lbResult) pnEditMode = EditMode.UNKNOWN;

            return lbResult;
        }
    }
    
    public boolean postTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.postTransaction(fsTransNox);
            if(lbResult) 
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

            return lbResult;
        }
    }
    
    public boolean voidTransaction(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.voidTransaction(fsTransNox);
            if(lbResult) 
                pnEditMode = EditMode.UNKNOWN;
            else ShowMessageFX();

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
    
    public boolean SearchTransaction(String fsValue, boolean fbByCode){
        String lsSQL = getSQ_Sales();
        
        if (fbByCode){ //based on refer nox
            lsSQL = MiscUtil.addCondition(lsSQL, "a.sReferNox = " + SQLUtil.toSQL(fsValue));
        } else { //based on customer name
            lsSQL = MiscUtil.addCondition(lsSQL, "d.sClientNm LIKE " + SQLUtil.toSQL(fsValue + "%"));
        }
        
        ResultSet loRS = poGRider.executeQuery(lsSQL);
        
        try {
            if (MiscUtil.RecordCount(loRS) == 1){
                loRS.first();
                return openTransaction(loRS.getString("sTransNox"));
            } else{
                String lsHeader = "Trans No»Client Name»Date»Total»Inv. Type»Branch";
                String lsColName = "a.sTransNox»d.sClientNm»a.dTransact»a.nTranTotl»c.sDescript»b.sBranchNm";
                String lsColCrit = "a.sTransNox»d.sClientNm»a.dTransact»a.nTranTotl»c.sDescript»b.sBranchNm";
                
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
        lnTranTotal += Double.parseDouble(poData.getFreightCharge().toString());
        //less the discounts
        lnTranTotal = lnTranTotal - (lnTranTotal * Double.parseDouble(poData.getDiscount().toString())) - Double.parseDouble(poData.getAddtlDiscount().toString());
        return lnTranTotal;
    }
    
    public boolean addDetail(){return poControl.addDetail();}
    public boolean deleteDetail(int fnIndex){
        if (!poControl.deleteDetail(fnIndex)) return false;
        
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
                        " WHEN a.cTranStat = '1' THEN 'CLOSED'" +
                        " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                        " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                        " WHEN a.cTranStat = '4' THEN 'VOID'" +
                        " END AS xTranStat" +
                " FROM Sales_Order_Master a" + 
                        " LEFT JOIN Branch b ON a.sBranchCd = b.sBranchCd" +
                        " LEFT JOIN Inv_Type c ON a.sInvTypCd = c.sInvTypCd" +
                    ", Client_Master d" + 
                " WHERE a.sClientID = d.sClientID";
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
            case 5: //sClientID
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
            case 17: //sTermCode
                XMTerm loTerm = new XMTerm(poGRider, psBranchCd, true);
                return loTerm.searchTerm((String) foValue, false);
            default:
                return null;
        }
    }
    
    private boolean searchItem(int fnRow, int fnIndex, String fsValue){
        boolean lbSearch = false;
        
        InvMaster loInv = new InvMaster(poGRider, psBranchCd, true);
        switch(fnIndex){
            case 3:
                lbSearch = loInv.SearchStock(psCategCd1, fsValue, true, true);
                break;
            case 80:
                lbSearch = loInv.SearchStock(psCategCd1,fsValue, true, false);
                break;
            case 81:
                lbSearch = loInv.SearchStock(psCategCd1,fsValue, false, false);
        }
        
        if (lbSearch){
            if ((int) loInv.getMaster("nQtyOnHnd") <= 0){
                psWarnMsg = "No stocks available for the item selected.";
                psErrMsgx = "";
                return false;
            }
            
            poControl.setDetail(fnRow, "sStockIDx", loInv.getInventory("sStockIDx"));
            poControl.setDetail(fnRow, "nInvCostx", loInv.getInventory("nUnitPrce"));
            poControl.setDetail(fnRow, "nUnitPrce", loInv.getInventory("nSelPrice"));
            poControl.setDetail(fnRow, "nQuantity", 0);
            poControl.setDetail(fnRow, "nDiscount", 0.00);
            poControl.setDetail(fnRow, "nAddDiscx", 0.00);
            poControl.setDetail(fnRow, "sSerialID", "");
            poControl.setDetail(fnRow, "cNewStock", "1");
            poControl.setDetail(fnRow, "sInsTypID", "");
            poControl.setDetail(fnRow, "nInsAmtxx", 0.00);
            poControl.setDetail(fnRow, "sWarrntNo", "");
            poControl.setDetail(fnRow, "cUnitForm", "1");
            poControl.setDetail(fnRow, "sNotesxxx", "");
            poControl.setDetail(fnRow, "cDetailxx", "0");
            poControl.setDetail(fnRow, "cPromoItm", "0");
            poControl.setDetail(fnRow, "cComboItm", "0");
        } else {
            psWarnMsg = loInv.getMessage();
            psErrMsgx = loInv.getErrMsg();
        }
        
        return lbSearch;
    }
    
    private boolean searchOrder(int fnRow, String fsValue){
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
    
    public String getErrMsg(){return psErrMsgx;}
    public String getWarnMsg(){return psWarnMsg;}
    public void setCategory(String fsValue){psCategCd1 = fsValue;}
    
    //Member Variables
    private GRider poGRider;
    private SalesOrder poControl;
    private UnitSalesOrderMaster poData;
    private final UnitSalesOrderDetail poDetail = new UnitSalesOrderDetail();
    
    private String psBranchCd;
    private int pnEditMode;
    private String psUserIDxx;
    private boolean pbWithParent;
    
    private String psWarnMsg = "";
    private String psErrMsgx = "";
    private String psCategCd1 = "";
    
    private final String pxeModuleName = this.getClass().getSimpleName();
}
