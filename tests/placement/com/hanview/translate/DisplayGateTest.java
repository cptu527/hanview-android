package com.hanview.translate;

public class DisplayGateTest {
    public static void main(String[] args) {
        TranslationDisplayGate gate = new TranslationDisplayGate();
        int firstPage = gate.current();
        require(gate.canDisplay(firstPage), "current visible page");
        gate.setVisible(false);
        require(!gate.canDisplay(firstPage), "response arriving in another app");
        require(!gate.canDisplay(gate.current()), "no results while capture is hidden");
        gate.setVisible(true);
        require(!gate.canDisplay(firstPage), "old response after returning to captured app");
        int secondPage = gate.current();
        require(gate.canDisplay(secondPage), "fresh request after return");
        gate.invalidate();
        require(!gate.canDisplay(secondPage), "old response after scrolling or pausing");
        for (int i = 0; i < 1000; i++) {
            int pending = gate.current();
            gate.invalidate();
            require(!gate.canDisplay(pending), "out-of-order network result");
        }
        System.out.println("PASS: hidden, resumed, scrolled and out-of-order results are rejected");
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
