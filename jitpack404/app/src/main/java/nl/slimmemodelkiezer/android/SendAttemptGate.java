package nl.slimmemodelkiezer.android;

/** A single accessibility-send attempt can dispatch at most once. */
final class SendAttemptGate {
    private boolean active;
    private boolean dispatched;
    synchronized boolean begin() {
        if (active) return false;
        active = true;
        dispatched = false;
        return true;
    }
    synchronized boolean markDispatched() {
        if (!active || dispatched) return false;
        dispatched = true;
        return true;
    }
    synchronized void reset() {
        active = false;
        dispatched = false;
    }
}
