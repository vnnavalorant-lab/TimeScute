package dev.timescute.core;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        System.out.println("== Fase 1 ==");
        CoreTests.main(args);
        System.out.println("\n== Fase 2 ==");
        Phase2Tests.main(args);
        System.out.println("\n== Fase 3 ==");
        Phase3Tests.main(args);
        System.out.println("\n== Fase 4 ==");
        Phase4Tests.main(args);
    }
}
