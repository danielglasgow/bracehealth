package com.bracehealth.billing;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Base64;
import java.util.Map;

/**
 * Persists the claims to disk on application shutdown.
 * 
 * Maybe I could put this in the ClaimStore class, but I'm not going to
 * expreiment with that in case that's "fighting the framework".
 */
@Component
public class ClaimStorePersistenceListener implements ApplicationListener<ContextClosedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ClaimStorePersistenceListener.class);
    private static final String CLAIMS_FILE = "claims.json";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ClaimStore claimStore;

    public ClaimStorePersistenceListener(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        writeClaimsToDisk(claimStore.getData(), Paths.get(CLAIMS_FILE));
    }

    @VisibleForTesting
    static void writeClaimsToDisk(Map<String, ClaimStore.Claim> data, Path claimsPath) {
        try {
            Map<String, JsonClaim> jsonClaims = data.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new JsonClaim(
                                    Base64.getEncoder().encodeToString(e.getValue().claim().toByteArray()),
                                    e.getValue().status().name(),
                                    e.getValue().clearingHouseResponse().map(r -> new JsonResponse(
                                            Base64.getEncoder().encodeToString(r.remittanceResponse().toByteArray()),
                                            r.receivedAt().toString()))
                                            .orElse(null))));

            objectMapper.writeValue(claimsPath.toFile(), jsonClaims);
            logger.info("Successfully persisted claims to disk");
        } catch (IOException e) {
            logger.error("Failed to write claims to disk", e);
        }
    }

    private record JsonClaim(
            String paymentClaim,
            String status,
            JsonResponse clearingHouseResponse) {
    }

    private record JsonResponse(
            String remittanceResponse,
            String receivedAt) {
    }
}