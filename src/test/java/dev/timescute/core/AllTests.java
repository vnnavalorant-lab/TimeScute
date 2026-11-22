package dev.timescute.core;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        System.out.println("== Timeline e câmera ==");
        CoreTests.main(args);
        System.out.println("\n== Cena, arquivos e velocidade ==");
        Phase2Tests.main(args);
        System.out.println("\n== Renderização ==");
        Phase5Tests.main(args);
        System.out.println("\n== Atores e animações ==");
        ActorTests.main(args);
    }
}
