package com.example.billing.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB client settings.
 *
 * The driver normally waits 30 seconds before giving up when MongoDB is not running.
 * For local development we wait 5 seconds, so a stopped database is reported quickly.
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer fastFailWhenMongoIsDown() {
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS));
    }
}
