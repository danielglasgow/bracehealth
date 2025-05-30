package com.bracehealth.billing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.nio.file.Paths;


/**
 * DI configuration for the ClaimStore. Maybe we could but this inside the ClaimStore class, but I'm
 * not going to expreiment with that in case that's "fighting the framework".
 */
@Configuration
public class ClaimStoreConfig {
    @Bean
    @Primary
    public ClaimStore claimStore() {
        // return new ClaimStore(Paths.get("claims.json"), ImmutableMap.of());
        // Skipping persistence for now
        return ClaimStore.newInstanceFromDisk(Paths.get("claims.json"));
    }

    @Bean
    public ClaimStorePersistenceListener claimStorePersistenceListener(ClaimStore claimStore) {
        return new ClaimStorePersistenceListener(claimStore);
    }
}
