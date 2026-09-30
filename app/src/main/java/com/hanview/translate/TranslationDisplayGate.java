package com.hanview.translate;

/** Rejects asynchronous results after navigation, hiding, pause, or resume. */
final class TranslationDisplayGate {
    private int generation;
    private boolean visible = true;
    synchronized int current() { return generation; }
    synchronized void invalidate() { generation++; }
    synchronized void setVisible(boolean value) { visible = value; generation++; }
    synchronized boolean isVisible() { return visible; }
    synchronized boolean canDisplay(int token) { return visible && generation == token; }
}
