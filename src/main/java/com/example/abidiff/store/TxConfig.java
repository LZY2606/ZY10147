package com.example.abidiff.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
public class TxConfig {

    @Bean
    public PlatformTransactionManager snapshotTransactionManager(DataSource snapshotDataSource) {
        return new DataSourceTransactionManager(snapshotDataSource);
    }

    @Bean
    public PlatformTransactionManager decisionTransactionManager(DataSource decisionDataSource) {
        return new DataSourceTransactionManager(decisionDataSource);
    }
}
