package com.example.abidiff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * ABI impact comparison service.
 *
 * <p>Two independent SQLite databases are wired manually ({@code store.DataSourceConfig}):
 * one for immutable extracted snapshots and derived comparisons, and a separate one for
 * reviewer decisions, so resolutions never share storage with raw snapshots.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AbiDiffApplication {
    public static void main(String[] args) {
        SpringApplication.run(AbiDiffApplication.class, args);
    }
}
