
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.rmj.appdriver.GProperty;
import org.rmj.appdriver.GRider;
import org.rmj.sales.agentfx.XMSalesReturn;

public class testSalesReturn {
    public static void main (String [] args){
        String lsProdctID = "gRider";
        String lsUserIDxx = "M001111122";
        GRider poGRider = new GRider(lsProdctID);
        GProperty loProp = new GProperty("GhostRiderXP");
        if (!poGRider.loadEnv(lsProdctID)) System.exit(0);
        if (!poGRider.logUser(lsProdctID, lsUserIDxx)) System.exit(0);
        
        
        XMSalesReturn instance = new XMSalesReturn(poGRider, poGRider.getBranchCode(), false);
        /*if (instance.newTransaction()){
            instance.setMaster("sClientID", "H00218000022");
            instance.setMaster("sRemarksx", "this is a test");
            instance.setMaster("sSourceCd", "SO");
            instance.setMaster("sSourceNo", "H00218000001");
            instance.setMaster("sInvTypCd", "FOOD");
            
            try {
                instance.setDetail(0, "sStockIDx", "H00218000001");
                instance.addDetail();
                instance.setDetail(1, "sStockIDx", "H00218000002");
            } catch (SQLException ex) {
                Logger.getLogger(testSalesReturn.class.getName()).log(Level.SEVERE, null, ex);
            }
        } 
        if (instance.saveTransaction()) System.out.println("this is a test.");        
        else{
            System.out.println(instance.getErrMsg());
            System.out.println(instance.getWarnMsg());
        }*/

        if (instance.openTransaction("M00118000005")) 
            System.out.println("this is a test.");        
        else{
            System.out.println(instance.getErrMsg());
            System.out.println(instance.getWarnMsg());
        }
    }
}
