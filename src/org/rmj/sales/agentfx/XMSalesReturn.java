/**
 * @author  Michael Cuison
 * 
 * @date    2018-09-21
 */
package org.rmj.sales.agentfx;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.cas.client.base.XMClient;
import org.rmj.cas.parameter.agent.XMInventoryType;
import org.rmj.sales.base.SalesReturn;
import org.rmj.sales.pojo.UnitSalesReturnDetail;
import org.rmj.sales.pojo.UnitSalesReturnMaster;

public class XMSalesReturn{
    public XMSalesReturn(GRider foGRider, String fsBranchCD, boolean fbWithParent){
        this.poGRider = foGRider;
        if (foGRider != null){
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;
            
            poControl = new SalesReturn();
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
    
    public boolean SearchDetail(int fnRow, int fnIndex, String fsValue){
        switch(fnIndex){
            case 3:
                poControl.setDetail(fnRow, 3, getSales2Return(fnRow, fsValue, 0));
                break;
            case 80:
                poControl.setDetail(fnRow, 3, getSales2Return(fnRow, fsValue, 1));
                break;
            case 81:
                poControl.setDetail(fnRow, 3, getSales2Return(fnRow, fsValue, 2));
                break;
        }
        
        return !poControl.getDetail(fnRow, 3).equals("");
    }
    
    public boolean SearchDetail(int fnRow, String fsIndex, String fsValue){
        switch (fsIndex) {
            case "sStockIDx":
                return SearchDetail(fnRow, 3, fsValue);
            case "sBarCodex":
                return SearchDetail(fnRow, 80, fsValue);
            case "sDescript":
                return SearchDetail(fnRow, 81, fsValue);
            default:
                return false;
        }
    }
    
    public boolean SearchTransaction(String fsValue){
        String lsSQL = getSQ_ReturnTrans();
        ResultSet loRS = poGRider.executeQuery(lsSQL);
        
        try {
            if (MiscUtil.RecordCount(loRS) == 1){
                loRS.first();
                return openTransaction(loRS.getString("sTransNox"));
            } else{
                String lsHeader = "Date»Name»TranTotal»ClientID";
                String lsColName = "a.dTransact»b.sClientNm»a.nTranTotl»a.sClientID";
                String lsColCrit = "a.dTransact»b.sClientNm»a.nTranTotl»a.sClientID";
                
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
    
    public boolean SearchMaster(String fsIndex, String fsValue, boolean fbByCode){
        switch(fsIndex){
            case "sClientID":
                return SearchMaster(4, fsValue, fbByCode);
            case "sInvTypCd":
                return SearchMaster(15, fsValue, fbByCode);
            default:
                return false;
        }
    }
    
    public boolean SearchMaster(int fnIndex, String fsValue, boolean fbByCode){
        if (fsValue.equals("")) return false;
        
        JSONObject loJSON;
        
        switch(fnIndex){
            case 4:
                XMClient loClient = new XMClient(poGRider, psBranchCd, true);
                loJSON = loClient.SearchClient(fsValue, fbByCode);

                if (loJSON != null){
                    psClientNm = (String) loJSON.get("sClientNm");
                    psAddressx = (String) loJSON.get("xAddressx");
                    setMaster("sClientID", (String) loJSON.get("sClientID"));
                } else {
                    psClientNm = "";
                    psAddressx = "";
                    setMaster("sClientID", "");
                }
                break;
            case 15:
                XMInventoryType loInvType = new XMInventoryType(poGRider, psBranchCd, true);
                loJSON = loInvType.searchInvType(fsValue, fbByCode);

                if (loJSON != null){
                    setMaster("sInvTypCd", (String) loJSON.get("sInvTypCd"));
                    psInvTypNm =  (String) loJSON.get("sDescript");
                } else{
                    setMaster("sInvTypCd", "");
                    psInvTypNm =  "";
                }
        }
        
        return true;
    }

    public boolean newTransaction() {
        poData = poControl.newTransaction();              
        
        if (poData == null){
            return false;
        }else{
            poData.setValue("dTransact", poGRider.getServerDate());
            
            addDetail();
            pnEditMode = EditMode.ADDNEW;
            return true;
        }
    }

    public boolean openTransaction(String fstransNox) {
        poData = poControl.loadTransaction(fstransNox);
        
        if (poData.getTransNox()== null){
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
      }
      else{
         pnEditMode = EditMode.UPDATE;
         return true;
      }
    }

    public boolean saveTransaction() {
        if(pnEditMode == EditMode.UNKNOWN){
            return false;
        }else{
            // Perform testing on values that needs approval here...
            UnitSalesReturnMaster loResult;
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
        /*if (!poControl.getErrMsg().isEmpty()){
            if (!poControl.getMessage().isEmpty())
                ShowMessageFX.Error(poControl.getErrMsg(), pxeModuleName, poControl.getMessage());
            else ShowMessageFX.Error(poControl.getErrMsg(), pxeModuleName, null);
        }else ShowMessageFX.Information(null, pxeModuleName, poControl.getMessage());*/
    }
    
    public void setGRider(GRider foGrider){
        this.poGRider = foGrider;
        this.psUserIDxx = foGrider.getUserID();
        
        if (psBranchCd.isEmpty()) psBranchCd = poGRider.getBranchCode();
    }
    
    private double computeTotal(){
        double lnTranTotal = 0;
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr ++){
            lnTranTotal += ((int) poControl.getDetail(lnCtr, "nQuantity") * (Double) poControl.getDetail(lnCtr, "nUnitPrce"));
        }
        
        //add the freight charge to total order
        lnTranTotal += (Double) poData.getFreightx();
        return lnTranTotal;
    }
    
    public boolean addDetail(){return poControl.addDetail();}
    public boolean deleteDetail(int fnIndex){return poControl.deleteDetail(fnIndex);}
    public int getDetailCount(){return poControl.ItemCount();}
    
    public void setDetail(int fnRow, int fnCol, Object foData) throws SQLException {
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poDetail.getColumn("sTransNox") ||
                fnCol == poDetail.getColumn("nEntryNox") ||
                fnCol == poDetail.getColumn("dModified"))){

                poControl.setDetail(fnRow, fnCol, foData);
                
                if (fnCol == poDetail.getColumn("nQuantity") ||
                    fnCol == poDetail.getColumn("nUnitPrce")) {
                    poData.setTranTotl(computeTotal());
                }
            }
        }
    }

    public void setDetail(int fnRow, String fsCol, Object foData) throws SQLException {        
        setDetail(fnRow, poDetail.getColumn(fsCol), foData);
    }
    
    public Object getDetail(int fnRow, String fsCol){return poControl.getDetail(fnRow, fsCol);}
    public Object getDetail(int fnRow, int fnCol){return poControl.getDetail(fnRow, fnCol);}
    
    public String getDetailInfo(String fsStockIDx, 
                                String fsColumnNm){
        String lsSQL = MiscUtil.addCondition(getSQ_Spareparts(), "a.sStockIDx = " + SQLUtil.toSQL(fsStockIDx));
        
        if (!lsSQL.equals("")){
            ResultSet loRS;
            
            loRS = poGRider.executeQuery(lsSQL);
            
            if (MiscUtil.RecordCount(loRS) == 1){
                try {
                    loRS.first();
                    return loRS.getString(fsColumnNm);
                } catch (SQLException e) {
                    ShowMessageFX.Error(e.getMessage(), pxeModuleName, "Please inform MIS Department.");
                    return "";
                }
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
    
    public String getErrMsg(){return psErrMsgx;}
    public String getWarnMsg(){return psWarnMsg;}
    
    public String getClientNm(){return psClientNm;}
    public String getAddressx(){return psAddressx;}
    public String getInvTypNm(){return psInvTypNm;}
    
    private String getSQ_ReturnTrans(){
        return "SELECT" +
                    "  a.sTransNox" + 
                    ", a.dTransact" + 
                    ", a.sClientID" + 
                    ", a.nTranTotl" + 
                    ", b.sClientNm" + 
                " FROM Sales_Return_Master a" + 
                    ", Client_Master b" + 
                " WHERE a.sClientID = b.sClientID";
    }
    
    private String getSales2Return(int fnRow, String fsValue, int fnSort){
        String lsHeader = "Barcode»Description»Inv. Type»Brand»Model»Stock ID»Quantity»Unit Price";
        String lsColName = "c.sBarCodex»c.sDescript»f.sDescript»d.sDescript»e.sDescript»b.sStockIDx»b.nQuantity»b.nUnitPrce";
        String lsColCrit = "c.sBarCodex»c.sDescript»f.sDescript»d.sDescript»e.sDescript»b.sStockIDx»b.nQuantity»b.nUnitPrce";
        
        String lsSQL =  "SELECT" +
                            "  b.sStockIDx" + 
                            ", c.sBarCodex" + 
                            ", c.sDescript" + 
                            ", d.sDescript" + 
                            ", e.sDescript" + 
                            ", f.sDescript" + 
                            ", a.sTransNox" +
                            ", b.nEntryNox" + 
                            ", 'SO' xReferNox" + 
                            ", b.nQuantity" + 
                            ", b.nUnitPrce" +
                        " FROM Sales_Master a" +
                            ", Sales_Detail b" +
                                " LEFT JOIN Inventory c" + 
                                    " ON b.sStockIDx = c.sStockIDx" + 
                                " LEFT JOIN Brand d" + 
                                    " ON c.sBrandCde = d.sBrandCde" + 
                                " LEFT JOIN Model e" + 
                                    " ON c.sModelIDx = e.sModelCde" + 
                                " LEFT JOIN Inv_Type f" + 
                                    " ON c.sInvTypCd = f.sInvTypCd" + 
                        " WHERE a.sTransNox = b.sTransNox" + 
                            " AND a.cTranStat = '1'";
        
        JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                                    lsSQL, 
                                                    fsValue, 
                                                    lsHeader, 
                                                    lsColName, 
                                                    lsColCrit, 
                                                    fnSort);
        
        if (loJSON != null){
            if (!"".equals((String) loJSON.get("sStockIDx"))){
                poControl.setDetail(fnRow, 4, Integer.parseInt((String) loJSON.get("nQuantity")));
                poControl.setDetail(fnRow, 6, Double.parseDouble((String) loJSON.get("nUnitPrce")));
                
                computeTotal();
                return (String) loJSON.get("sStockIDx");
            }
        } return "";
    }
       
    //Member Variables
    private GRider poGRider;
    private SalesReturn poControl;
    private UnitSalesReturnMaster poData;
    private final UnitSalesReturnDetail poDetail = new UnitSalesReturnDetail();
    private ArrayList<UnitSalesReturnDetail> poDetailArr;
    
    private String psBranchCd;
    private int pnEditMode;
    private String psUserIDxx;
    private boolean pbWithParent;
    
    private String psWarnMsg = "";
    private String psErrMsgx = "";
    
    private String psInvTypNm = "";
    private String psClientNm = "";
    private String psAddressx = "";
    
    private final String pxeModuleName = "org.rmj.sales.agentfx.XMSalesReturn";
}
