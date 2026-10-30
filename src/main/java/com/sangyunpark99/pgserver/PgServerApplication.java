package com.sangyunpark99.pgserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PgServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgServerApplication.class, args);
    }

}
