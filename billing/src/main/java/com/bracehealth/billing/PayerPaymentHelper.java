package com.bracehealth.billing;

import com.bracehealth.billing.ClaimStore.ClaimProcessingInfo;
import com.bracehealth.shared.AccountsReceivableBucket;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableBucketValue;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableRow;
import com.bracehealth.shared.ServiceLine;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.PayerId;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.List;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.function.Function.identity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Helper for calculating payer accounts receivable.
 */
@Component
public class PayerPaymentHelper {

    private final ClaimStore claimStore;

    @Autowired
    public PayerPaymentHelper(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    public GetPayerAccountsReceivableResponse getPayerAccountsReceivable(
            ImmutableList<PayerId> payerIds, ImmutableList<AccountsReceivableBucket> buckets) {
        GetPayerAccountsReceivableResponse.Builder responseBuilder =
                GetPayerAccountsReceivableResponse.newBuilder();
        ImmutableMap<PayerId, ImmutableList<PayerClaim>> claimsByPayer =
                ImmutableMap.copyOf(claimStore.getPendingClaims().values().stream()
                        .collect(groupingBy(claim -> claim.getInsurance().getPayerId(),
                                mapping(identity(), toImmutableList()))));
        ImmutableSet<String> allPendingClaims =
                ImmutableSet.copyOf(claimStore.getPendingClaims().keySet());
        for (PayerId payerId : payerIds) {
            ImmutableSet<String> payerClaimIds =
                    claimsByPayer.getOrDefault(payerId, ImmutableList.of()).stream()
                            .map(PayerClaim::getClaimId).collect(toImmutableSet());
            ImmutableList<String> pendingClaimIds = allPendingClaims.stream()
                    .filter(payerClaimIds::contains).collect(toImmutableList());
            responseBuilder.addRow(createRow(payerId, pendingClaimIds, buckets));
        }
        return responseBuilder.build();
    }

    private AccountsReceivableRow createRow(PayerId payerId, ImmutableList<String> pendingClaimIds,
            List<AccountsReceivableBucket> buckets) {
        AccountsReceivableRow.Builder rowBuilder = AccountsReceivableRow.newBuilder()
                .setPayerId(payerId.name()).setPayerName(payerId.name());
        ImmutableList<ClaimProcessingInfo> claimProcessingInfo = pendingClaimIds.stream()
                .map(claimStore::getProcessingInfo).collect(toImmutableList());
        for (AccountsReceivableBucket bucket : buckets) {
            Instant start = bucket.getStartSecondsAgo() == 0 ? Instant.EPOCH
                    : Instant.now().minusSeconds(bucket.getStartSecondsAgo());
            Instant end = Instant.now().minusSeconds(bucket.getEndSecondsAgo());
            ImmutableList<PayerClaim> claimsInBucket = claimProcessingInfo.stream()
                    .filter(info -> info.responseReceivedAt().isEmpty())
                    .filter(info -> info.submittedAt().isAfter(start))
                    .filter(info -> info.submittedAt().isBefore(end))
                    .map(info -> claimStore.getClaim(info.claimId())).collect(toImmutableList());
            ImmutableList<CurrencyAmount> charges = claimsInBucket.stream().flatMap(
                    claim -> claim.getServiceLinesList().stream().map(ServiceLine::getCharge))
                    .map(CurrencyAmount::fromProto).collect(toImmutableList());
            CurrencyAmount amount =
                    charges.stream().reduce(CurrencyAmount.ZERO, CurrencyAmount::add);
            rowBuilder.addBucketValue(AccountsReceivableBucketValue.newBuilder().setBucket(bucket)
                    .setAmount(amount.toProto()).build());
        }
        return rowBuilder.build();
    }
}
