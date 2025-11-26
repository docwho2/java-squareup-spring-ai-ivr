package cloud.cleo.squareup;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;

/**
 *
 * @author sjensen
 */
@SpringBootApplication(
  exclude = {
    DataSourceAutoConfiguration.class,
    JooqAutoConfiguration.class
  }
)
public class SpringApp {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringApp.class, args);
    }
}
