package io.auctionhouse.auction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="auctionhouse.closure.enabled",havingValue="true",matchIfMissing=true)
public class DeadlineCloser {
    private static final Logger LOG = LoggerFactory.getLogger(DeadlineCloser.class);
    private final AuctionService auctions;
    public DeadlineCloser(AuctionService auctions) { this.auctions = auctions; }
    @Scheduled(fixedDelayString="${auctionhouse.closure.interval-ms:1000}")
    public void closeDue() {
        try { auctions.closeDue(); }
        catch (RuntimeException failure) { LOG.warn("Scheduled auction closure will retry: {}", failure.getClass().getSimpleName()); }
    }
}
