package in.code.gatekeeper.management.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealthService {

    public final JdbcTemplate jdbcTemplate;

    public String getDBStatus() {
     String databaseStatus = "DOWN";

     try{
         Integer result = jdbcTemplate.queryForObject("Select 1", Integer.class);
         if(Integer.valueOf(1).equals(result)){
             databaseStatus = "UP";
         }
     }catch(Exception e){
         System.out.print("DB is not up");
        }
     return databaseStatus;
    }
}
