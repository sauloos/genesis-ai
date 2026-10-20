package ai.genesisbrands.platform;

import ai.genesisbrands.model.IngestionEvent;

/**
 * Extension point: a module that wants to act on what was just ingested (review it, index it
 * elsewhere, notify someone) registers one of these. The ingestion runtime calls every listener
 * once per event after it has finished and been saved, whatever its status. A listener runs on
 * the ingestion thread, so anything slow belongs on its own executor; an exception it throws is
 * logged and never changes the event.
 */
public interface IngestionEventListener {

    void finished(IngestionEvent event);
}
