package com.bracehealth.billing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.RemittanceResponse;
import java.util.stream.Collectors;

/**
 * DI configuration for the ClaimStore. Maybe we could but this inside the
 * ClaimStore class, but I'm not going to expreiment with that in case that's
 * "fighting the framework".
 */
@Configuration
public class ClaimStoreConfig {
    private static final Logger logger = LoggerFactory.getLogger(ClaimStoreConfig.class);
    private static final String CLAIMS_FILE = "claims.json";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule());

    @Bean
    @Primary
    public ClaimStore claimStore() {
        try {
            Path claimsPath = Paths.get(CLAIMS_FILE);
            if (Files.exists(claimsPath)) {
                Map<String, JsonClaim> jsonClaims = objectMapper.readValue(claimsPath.toFile(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, JsonClaim.class));

                Map<String, ClaimStore.Claim> claims = jsonClaims.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new ClaimStore.Claim(
                                        parsePayerClaim(e.getValue().paymentClaim()),
                                        ClaimStore.ClaimStatus.valueOf(e.getValue().status()),
                                        Optional.ofNullable(e.getValue().clearingHouseResponse())
                                                .map(r -> new ClaimStore.ClearingHouseResponse(
                                                        parseRemittanceResponse(r.remittanceResponse()),
                                                        Instant.parse(r.receivedAt()))))));

                return new ClaimStore(claims);
            }
        } catch (IOException e) {
            logger.warn("Failed to load claims from disk, starting with empty store", e);
        }
        return new ClaimStore();
    }

    @Bean
    public ClaimStorePersistenceListener claimStorePersistenceListener(ClaimStore claimStore) {
        return new ClaimStorePersistenceListener(claimStore);
    }

    private static PayerClaim parsePayerClaim(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return PayerClaim.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PayerClaim", e);
        }
    }

    private static RemittanceResponse parseRemittanceResponse(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return RemittanceResponse.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse RemittanceResponse", e);
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