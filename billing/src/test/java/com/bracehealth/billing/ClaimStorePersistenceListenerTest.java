package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.RemittanceResponse;
import com.bracehealth.shared.Insurance;
import com.bracehealth.shared.Patient;
import com.bracehealth.shared.Organization;
import com.bracehealth.shared.RenderingProvider;
import com.bracehealth.shared.ServiceLine;
import com.google.common.collect.ImmutableMap;
import com.bracehealth.shared.Address;
import com.bracehealth.shared.Contact;
import com.bracehealth.shared.PayerId;
import com.bracehealth.shared.Gender;
import java.util.stream.Stream;
import static com.bracehealth.billing.ClaimStorePersistenceListener.writeClaimsToDisk;

class ClaimStorePersistenceListenerTest {
        private static final Path TEST_DIR = Paths.get("test_data");

        @BeforeAll
        static void setUp() throws IOException {
                if (!Files.exists(TEST_DIR)) {
                        Files.createDirectory(TEST_DIR);
                }
                try (Stream<Path> files = Files.list(TEST_DIR)) {
                        files.forEach(file -> {
                                try {
                                        Files.delete(file);
                                } catch (IOException e) {
                                        throw new RuntimeException(e);
                                }
                        });
                }
        }

        @Test
        void pendingClaim_writesToDisk() throws IOException {
                PayerClaim payerClaim = createPayerClaim();

                ClaimStore.Claim claim = new ClaimStore.Claim(
                                payerClaim,
                                ClaimStore.ClaimStatus.RESPONSE_RECEIVED,
                                Optional.empty());

                ImmutableMap<String, ClaimStore.Claim> data = ImmutableMap.of("CLM1234567", claim);

                Path claimsFile = TEST_DIR.resolve("pending_claims.json");
                writeClaimsToDisk(data, claimsFile);

                assertTrue(Files.exists(claimsFile), "Claims file should exist");
        }

        @Test
        void completedClaim_writesToDisk() throws IOException {
                PayerClaim payerClaim = createPayerClaim();
                RemittanceResponse remittanceResponse = createRemittanceResponse();
                ClaimStore.ClearingHouseResponse clearingHouseResponse = new ClaimStore.ClearingHouseResponse(
                                remittanceResponse,
                                Instant.ofEpochMilli(0));

                ClaimStore.Claim claim = new ClaimStore.Claim(
                                payerClaim,
                                ClaimStore.ClaimStatus.RESPONSE_RECEIVED,
                                Optional.of(clearingHouseResponse));

                ImmutableMap<String, ClaimStore.Claim> data = ImmutableMap.of("CLM1234567", claim);
                Path claimsFile = TEST_DIR.resolve("completed_claims.json");
                writeClaimsToDisk(data, claimsFile);

                assertTrue(Files.exists(claimsFile), "Claims file should exist");
        }

        private static PayerClaim createPayerClaim() {
                return PayerClaim.newBuilder()
                                .setClaimId("CLM1234567")
                                .setPlaceOfServiceCode(11)
                                .setInsurance(Insurance.newBuilder()
                                                .setPayerId(PayerId.MEDICARE)
                                                .setPatientMemberId("PM123456789")
                                                .build())
                                .setPatient(Patient.newBuilder()
                                                .setFirstName("Jane")
                                                .setLastName("Doe")
                                                .setEmail("jane.doe@example.com")
                                                .setGender(Gender.F)
                                                .setDob("1980-05-15")
                                                .setAddress(Address.newBuilder()
                                                                .setStreet("123 Main St")
                                                                .setCity("Boston")
                                                                .setState("MA")
                                                                .setZip("02115")
                                                                .setCountry("USA")
                                                                .build())
                                                .build())
                                .setOrganization(Organization.newBuilder()
                                                .setName("Brace Health Clinic")
                                                .setBillingNpi("1234567890")
                                                .setEin("12-3456789")
                                                .setContact(Contact.newBuilder()
                                                                .setFirstName("Alice")
                                                                .setLastName("Smith")
                                                                .setPhoneNumber("555-123-4567")
                                                                .build())
                                                .setAddress(Address.newBuilder()
                                                                .setStreet("456 Health Ave")
                                                                .setCity("Boston")
                                                                .setState("MA")
                                                                .setZip("02116")
                                                                .setCountry("USA")
                                                                .build())
                                                .build())
                                .setRenderingProvider(RenderingProvider.newBuilder()
                                                .setFirstName("John")
                                                .setLastName("Smith")
                                                .setNpi("9876543210")
                                                .build())
                                .addServiceLines(ServiceLine.newBuilder()
                                                .setServiceLineId("SL001")
                                                .setProcedureCode("99213")
                                                .addModifiers("25")
                                                .setUnits(1)
                                                .setDetails("Office/outpatient visit")
                                                .setUnitChargeCurrency("USD")
                                                .setUnitChargeAmount(75.0)
                                                .setDoNotBill(false)
                                                .build())
                                .addServiceLines(ServiceLine.newBuilder()
                                                .setServiceLineId("SL002")
                                                .setProcedureCode("87070")
                                                .setUnits(1)
                                                .setDetails("Bacterial culture, any source except urine/blood/stool")
                                                .setUnitChargeCurrency("USD")
                                                .setUnitChargeAmount(50.0)
                                                .setDoNotBill(false)
                                                .build())
                                .build();
        }

        private static RemittanceResponse createRemittanceResponse() {
                return RemittanceResponse.newBuilder()
                                .setClaimId("CLM1234567")
                                .setPayerPaidAmount(125.0)
                                .setCoinsuranceAmount(25.0)
                                .setCopayAmount(0.0)
                                .setDeductibleAmount(0.0)
                                .setNotAllowedAmount(0.0)
                                .build();

        }
}