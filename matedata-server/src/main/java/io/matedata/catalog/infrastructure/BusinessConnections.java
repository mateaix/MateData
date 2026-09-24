package io.matedata.catalog.infrastructure;
import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.shared.infrastructure.SecretVault;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Properties;

@Component
public class BusinessConnections {
    private final SecretVault vault;
    public BusinessConnections(SecretVault vault)throws SQLException {this.vault=vault;seedDemo();}
    public Connection open(DataSourceDefinition source)throws SQLException {
        var p=new Properties();p.setProperty("user",source.username());p.setProperty("password",source.type().equals("DEMO")?"":vault.decrypt(source.encryptedPassword()));
        if(source.type().equals("POSTGRESQL")){p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","15");p.setProperty("readOnly","true");}
        if(source.type().equals("MYSQL")){p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","15000");p.setProperty("allowLoadLocalInfile","false");p.setProperty("allowMultiQueries","false");}
        var c=DriverManager.getConnection(source.jdbcUrl(),p);c.setReadOnly(true);return c;
    }
    private void seedDemo()throws SQLException {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:matedata_sales;DB_CLOSE_DELAY=-1","sa","");var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS sales(id INT PRIMARY KEY, sales_month VARCHAR(7),region VARCHAR(20),category VARCHAR(20),channel VARCHAR(20),amount DECIMAL(16,2),profit DECIMAL(16,2))");
            try(var r=s.executeQuery("SELECT COUNT(*) FROM sales")){r.next();if(r.getInt(1)>0)return;}
            String[] regions={"华东","华南","华北","西部"},categories={"软件服务","智能硬件","专业咨询"},channels={"直销","合作伙伴"};
            try(var p=c.prepareStatement("INSERT INTO sales VALUES(?,?,?,?,?,?,?)")) {
                int id=1;
                for(int month=1;month<=6;month++)for(int region=0;region<4;region++)for(int category=0;category<3;category++)for(int channel=0;channel<2;channel++) {
                    long amount=18000L+month*2200L+(4-region)*3100L+category*1700L+channel*900L;
                    p.setInt(1,id++);p.setString(2,"2026-0"+month);p.setString(3,regions[region]);p.setString(4,categories[category]);p.setString(5,channels[channel]);p.setLong(6,amount);p.setBigDecimal(7,java.math.BigDecimal.valueOf(amount).multiply(new java.math.BigDecimal("0.28")));p.addBatch();
                } p.executeBatch();
            }
        }
    }
}
