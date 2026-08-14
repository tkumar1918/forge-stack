package dev.tushar.forge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ForgeApplication {

    static void main(String[] args) {
        SpringApplication.run(ForgeApplication.class, args);
    }

}
