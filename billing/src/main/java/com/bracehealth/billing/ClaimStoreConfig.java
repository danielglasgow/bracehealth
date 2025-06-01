package com.bracehealth.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.nio.file.Paths;
import java.util.HashMap;


/**
 * Creates a ClaimStore instance.
 *
 * If the claimstore.mode is set to "load", the ClaimStore will be initialized with existing claims,
 * loaded from disk.
 * 
 * If the claimstore.mode is set to "new", the ClaimStore will be initialized with no claim history.
 */
@Configuration
public class ClaimStoreConfig {
    @Bean
    @Primary
    @ConditionalOnProperty(name = "claimstore.mode", havingValue = "load")
    public ClaimStore claimStoreFromDisk() {
        return ClaimStore.newInstanceFromDisk(Paths.get("claims.json"));
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "claimstore.mode", havingValue = "new", matchIfMissing = true)
    public ClaimStore claimStoreNew() {
        return new ClaimStore(Paths.get("claims.json"), new HashMap<>());
    }
}
