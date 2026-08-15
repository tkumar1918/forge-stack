package dev.tushar.forgestack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ForgeStackApplication {

    static void main(String[] args) {
        SpringApplication.run(ForgeStackApplication.class, args);
    }

}
