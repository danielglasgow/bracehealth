package com.bracehealth.billing;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Persists the claims to disk on application shutdown.
 * 
 * Maybe I could put this in the ClaimStore class, but I'm not going to expreiment with that in case
 * that's "fighting the framework".
 * 
 * TODO: Also persist every 10 seconds or something...
 */
@Component
public class ClaimStorePersistenceListener implements ApplicationListener<ContextClosedEvent> {
    private final ClaimStore claimStore;

    public ClaimStorePersistenceListener(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // Skipping persistence for now
        claimStore.writeToDisk();
    }
}
