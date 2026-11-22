package dev.timescute.core;

import java.nio.file.*;
import java.util.*;

/** Cortes de câmera, atores roteirizados (sem gravar) e modelos do Blockbench. */
public final class Phase6Tests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b, double e) { return Math.abs(a - b) < e; }

    static boolean bad(String json) {
        try { BedrockGeometry.parse(json); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static final String HUMANOID = """
        {"format_version":"1.12.0","minecraft:geometry":[{
          "description":{"identifier":"geometry.robo","texture_width":64,"texture_height":32},
          "bones":[
            {"name":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[16,16]}]},
            {"name":"head","parent":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0],"inflate":0.5}]},
            {"name":"rightArm","parent":"body","pivot":[-5,22,0],"rotation":[10,20,30],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[40,16]}]},
            {"name":"leftArm","parent":"body","pivot":[5,22,0],"mirror":true,"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[40,16]}]},
            {"name":"rightLeg","parent":"body","pivot":[-2,12,0],"cubes":[{"origin":[-4,0,-2],"size":[4,12,4],"uv":[0,16]}]},
            {"name":"leftLeg","parent":"body","pivot":[2,12,0],"cubes":[{"origin":[0,0,-2],"size":[4,12,4],"uv":{
                "north":{"uv":[4,20],"uv_size":[4,12]},"up":{"uv":[4,16],"uv_size":[4,4]}}}]}
          ]}]}
        """;

    static Actor scripted(Reaction... rs) {
        return Actor.createScripted(1, "Robô", new Actor.Origin(0, 64, 0, 0f)).withReactions(List.of(rs));
    }

    static Reaction walk(long id, long start, long dur, double x, double y, double z) {
        return Reaction.of(id, start, dur, ReactionType.WALK).withTarget(x, y, z, "", "");
    }

    public static void main(String[] args) throws Exception {
        // ---------- modelo do Blockbench ----------
        BedrockGeometry.Result r = BedrockGeometry.parse(HUMANOID);
        GeoModel m = r.models().get(0);
        check(r.models().size() == 1 && m.name().equals("geometry.robo") && m.texW() == 64 && m.texH() == 32, "lê a geometria Bedrock 1.12 (nome e tamanho da textura)");
        check(m.bones().size() == 6 && m.cubeCount() == 6, "lê os 6 ossos e os 6 cubos");
        check(m.roots().size() == 1 && m.roots().get(0).name().equals("body") && m.childrenOf("body").size() == 5, "monta a árvore: body na raiz, 5 filhos");
        check(m.bone("rightArm").get().rotation()[2] == 30 && m.bone("leftArm").get().mirror(), "guarda rotação e mirror do osso");
        check(m.bone("head").get().cubes().get(0).inflate() == 0.5, "guarda o inflate do cubo");
        check(m.bone("leftLeg").get().cubes().get(0).boxUv() == null && m.bone("leftLeg").get().cubes().get(0).faceUv().size() == 2, "UV por face: só as faces dadas");
        check(m.looksHumanoid(), "reconhece ossos de jogador (anda sem clipe)");

        GeoModel legacy = BedrockGeometry.parse("{\"format_version\":\"1.8.0\",\"geometry.pig:geometry.base\":{\"texturewidth\":64,\"textureheight\":32,\"bones\":[{\"name\":\"snout\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}}").models().get(0);
        check(legacy.name().equals("geometry.pig") && legacy.bones().size() == 1 && !legacy.looksHumanoid(), "lê o formato antigo (geometry.nome) e ignora a herança");

        check(bad("{}") && bad("[1,2]") && bad("não é json"), "arquivos que não são modelo dão erro claro");
        BedrockGeometry.Result cyc = BedrockGeometry.parse("{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"g\"},\"bones\":["
                + "{\"name\":\"a\",\"parent\":\"b\"},{\"name\":\"b\",\"parent\":\"a\"},{\"name\":\"c\",\"parent\":\"nada\"},{\"name\":\"a\"}]}]}");
        GeoModel cm = cyc.models().get(0);
        check(cm.bones().size() == 3 && cm.roots().size() >= 2 && !cyc.warnings().isEmpty(), "ciclo, pai inexistente e osso repetido não travam: viram raiz com aviso");
        check(cm.bones().stream().allMatch(b -> { int d = 0; String p = b.parent(); while (p != null && d++ < 10) p = cm.bone(p).map(GeoModel.Bone::parent).orElse(null); return d < 10; }), "nenhum ciclo sobra na árvore");
        StringBuilder many = new StringBuilder("{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"g\"},\"bones\":[{\"name\":\"a\",\"cubes\":[");
        for (int i = 0; i < BedrockGeometry.MAX_CUBES + 1; i++) many.append(i == 0 ? "" : ",").append("{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}");
        many.append("]}]}]}");
        check(bad(many.toString()), "cubos demais são recusados");
        check(BedrockGeometry.parse("{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"g\"},\"bones\":[{\"name\":\"a\",\"cubes\":[{\"origin\":[0,0],\"size\":[1,1,1]},{\"origin\":[0,0,0],\"size\":[1,1,1]}]}]}]}")
                .models().get(0).cubeCount() == 1, "cubo sem origin válido é ignorado");

        // ---------- pasta de modelos ----------
        Path dir = Files.createTempDirectory("tsmodels");
        Files.writeString(dir.resolve("robo.geo.json"), HUMANOID);
        Files.writeString(dir.resolve("robo.png"), "x");
        Files.writeString(dir.resolve("sem_textura.geo.json"), HUMANOID);
        Files.writeString(dir.resolve("animacao.json"), "{\"format_version\":\"1.8.0\",\"animations\":{\"animation.a\":{\"animation_length\":1}}}");
        Files.writeString(dir.resolve("quebrado.geo.json"), "{ isso nao e json");
        ModelFiles.Scan sc = ModelFiles.scan(dir);
        check(sc.models().keySet().equals(new TreeSet<>(String.CASE_INSENSITIVE_ORDER) {{ add("robo"); add("sem_textura"); }}), "a pasta lista só os modelos (ignora animação e arquivo quebrado)");
        check(sc.messages().stream().anyMatch(s -> s.contains("sem_textura") && s.contains("textura")), "avisa quando falta o .png");
        check(sc.messages().stream().anyMatch(s -> s.contains("quebrado")), "avisa do arquivo quebrado");
        check(sc.messages().stream().noneMatch(s -> s.contains("animacao.json")), "arquivo de animação na pasta não gera erro");
        check(ModelFiles.keyOf("robo.geo.json").equals("robo") && ModelFiles.keyOf("Robo.JSON").equals("Robo"), "nome do modelo = nome do arquivo sem extensão");
        check(ModelFiles.scan(dir.resolve("nao_existe")).models().isEmpty(), "pasta inexistente = nenhum modelo");

        // ---------- ator roteirizado ----------
        Actor a = scripted(walk(10, 1000, 2000, 8, 64, 0));
        check(a.isScripted() && !a.hasPerformance() && a.endMs() == 3000, "ator roteirizado: sem gravação, termina no fim do último bloco");
        ActorFrame f0 = ScriptedMotion.frameAt(a, 0);
        check(near(f0.x(), 0, 1e-9) && near(f0.y(), 64, 1e-9) && f0.has(ActorFrame.ON_GROUND), "antes de andar: na posição inicial, no chão");
        ActorFrame fmid = ScriptedMotion.frameAt(a, 2000);
        check(near(fmid.x(), 4, 1e-9) && near(fmid.z(), 0, 1e-9), "na metade do bloco está na metade do caminho");
        check(near(fmid.yaw(), -90, 0.01) && near(fmid.headYaw(), fmid.yaw(), 1e-6), "andando para +X ele olha para +X (yaw -90), cabeça junto");
        check(ScriptedMotion.frameAt(a, 3000).x() == 8 && ScriptedMotion.frameAt(a, 99_000).x() == 8, "depois do bloco fica no destino");
        check(near(ScriptedMotion.frameAt(a, 1000).yaw(), 0, 0.5) && near(ScriptedMotion.frameAt(a, 1125).yaw(), -45, 5), "vira suave para a direção do passo (não de uma vez)");
        check(!fmid.has(ActorFrame.SPRINTING) && !fmid.has(ActorFrame.SNEAKING), "4 blocos/s é andar normal");
        check(ScriptedMotion.frameAt(scripted(walk(10, 0, 1000, 8, 64, 0)), 500).has(ActorFrame.SPRINTING), "8 blocos/s vira corrida");
        check(ScriptedMotion.frameAt(scripted(walk(10, 0, 4000, 4, 64, 0).withOffHand(true)), 1000).has(ActorFrame.SNEAKING), "andar 'esgueirando' marca agachado");
        check(!ScriptedMotion.frameAt(a, 3500).has(ActorFrame.SPRINTING), "parado no fim não fica correndo");

        Actor two = scripted(walk(10, 0, 1000, 4, 64, 0), walk(11, 2000, 1000, 4, 64, 6), Reaction.of(12, 1500, 400, ReactionType.SWING));
        check(near(ScriptedMotion.frameAt(two, 1500).x(), 4, 1e-9) && near(ScriptedMotion.frameAt(two, 1500).z(), 0, 1e-9), "entre dois blocos fica parado onde parou");
        check(near(ScriptedMotion.frameAt(two, 2500).z(), 3, 1e-9) && near(ScriptedMotion.frameAt(two, 2500).x(), 4, 1e-9), "o segundo andar parte de onde o primeiro terminou");
        check(near(ScriptedMotion.frameAt(two, 3000).yaw(), 0, 1e-3) && near(ScriptedMotion.frameAt(two, 3000).z(), 6, 1e-9), "andando para +Z o yaw é 0");
        check(Arrays.equals(ScriptedMotion.endPosition(two), new double[]{4, 64, 6}), "endPosition devolve o destino do último Andar");

        Actor turn = scripted(Reaction.of(10, 500, 1000, ReactionType.TURN).withTarget(10, 64, 0, "", ""));
        check(near(ScriptedMotion.frameAt(turn, 2000).yaw(), -90, 0.01) && near(ScriptedMotion.frameAt(turn, 1000).yaw(), -45, 1) && ScriptedMotion.frameAt(turn, 0).yaw() == 0, "Virar para gira suave até olhar para o ponto");
        check(ScriptedMotion.frameAt(turn, 2000).x() == 0, "virar não move o ator");

        ClipLibrary lib = new ClipLibrary();
        ActorPose hidden = ActorPlayer.evaluate(scripted(walk(10, 0, 1000, 4, 64, 0)).withStart(5000), 1000, lib);
        check(!hidden.visible && ActorPlayer.evaluate(scripted(), 1000, lib).visible, "roteirizado aparece desde o começo (ou desde startMs) até o fim");
        check(!ActorPlayer.evaluate(Actor.create(2, "vazio"), 0, lib).visible && !ActorPlayer.evaluate(scripted().withMuted(true), 0, lib).visible, "sem gravação e sem posição inicial, ou mudo: escondido");
        check(ActorPlayer.evaluate(a, 2000, lib).frame.x() == 4, "ActorPlayer usa o movimento roteirizado");
        Actor rec = ActorTests.actorWith(3, 0, 2).withOrigin(new Actor.Origin(100, 100, 100, 0f));
        check(near(ActorPlayer.evaluate(rec, 1000, lib).frame.x(), 10, 1e-6), "com gravação, a gravação manda (a posição inicial é ignorada)");

        // animação aplicada por nome de osso (modelos do Blockbench)
        Actor anim = scripted(Reaction.of(20, 0, 2000, ReactionType.ANIMATION).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE)));
        ActorPose ap = ActorPlayer.evaluate(anim, 1000, lib);
        check(!ap.boneLayers.isEmpty() && !ap.layers.isEmpty(), "uma animação gera camadas por osso (modelo) e por parte (jogador)");
        check(ap.boneLayers.keySet().stream().anyMatch(k -> Part.fromBone(k) == Part.RIGHT_ARM), "as camadas por osso usam o nome do osso do arquivo");

        // ---------- tipos de reação ----------
        check(ReactionType.WALK.scriptedOnly() && ReactionType.TURN.scriptedOnly() && !ReactionType.SWING.scriptedOnly(), "Andar e Virar são só para roteirizados");
        ReactionType t = ReactionType.LOOK_AT;
        check(t.next(false) == ReactionType.ANIMATION && t.next(true) == ReactionType.WALK, "o seletor de tipo pula Andar/Virar em atores gravados");
        check(Reaction.of(1, 0, 1000, ReactionType.WALK).shortLabel().equals("Andar") && Reaction.of(1, 0, 1000, ReactionType.TURN).shortLabel().equals("Virar"), "rótulos dos blocos novos");

        // ---------- arquivo ----------
        Timeline tl = new Timeline();
        Actor ta = Actor.createScripted(tl.newId(), "Robô", new Actor.Origin(1.5, 70, -3.25, 90f)).withModel("robo").withReaction(walk(tl.newId(), 1000, 2000, 5, 70, 5));
        tl.updateActor(tl.addActor("x").withName("tmp"), "x"); // ator qualquer só para mexer no contador
        Timeline t2 = new Timeline();
        t2.replaceAll(List.of(), List.of(), List.of(ta), "x");
        Path file = Files.createTempFile("path", ".json");
        Files.writeString(file, PathJson.write(t2));
        PathJson.Data d = PathJson.read(Files.readString(file));
        Actor back = d.actors().get(0);
        check(back.model().equals("robo") && back.origin() != null && near(back.origin().x(), 1.5, 1e-9) && near(back.origin().z(), -3.25, 1e-9) && back.origin().yaw() == 90f, "salvar/abrir guarda o modelo e a posição inicial");
        check(back.reactions().size() == 1 && back.reactions().get(0).type() == ReactionType.WALK && back.reactions().get(0).tx() == 5, "salvar/abrir guarda o bloco Andar até");
        Timeline old = new Timeline();
        Actor plain = ActorTests.actorWith(old.newId(), 0, 1);
        old.replaceAll(List.of(), List.of(), List.of(plain), "x");
        Actor plainBack = PathJson.read(PathJson.write(old)).actors().get(0);
        check(plainBack.model().isEmpty() && plainBack.origin() == null && !plainBack.isScripted(), "ator gravado continua sem modelo nem posição inicial");

        Timeline sc2 = new Timeline();
        Actor made = sc2.addScriptedActor("Roteiro", new Actor.Origin(3, 65, 4, 45f));
        check(sc2.actors().size() == 1 && made.isScripted() && sc2.actors().byId(made.id()).get().origin().yaw() == 45f, "addScriptedActor cria o ator na posição dada");
        sc2.undo();
        check(sc2.actors().isEmpty(), "desfazer remove o ator roteirizado");
        sc2.redo();
        check(sc2.actors().size() == 1, "refazer traz de volta");

        // ---------- corte de câmera ----------
        Timeline c = new Timeline();
        CameraState s0 = new CameraState(0, 70, 0, 0, 0, 0, 70), s1 = new CameraState(10, 70, 0, 90, 0, 0, 70), s2 = new CameraState(20, 70, 0, 180, 0, 0, 70);
        c.capture(0, s0);
        Keyframe k1 = c.captureCut(2000, s1);
        check(k1.easing() == Easing.HOLD && c.path().all().get(0).easing() == Easing.HOLD, "o corte marca o novo keyframe e o anterior como 'Parado'");
        c.captureCut(4000, s2);
        check(c.path().all().stream().allMatch(k -> k.easing() == Easing.HOLD), "vários cortes em sequência: tudo 'Parado'");
        check(c.path().sample(1999).get().x() == 0 && c.path().sample(2000).get().x() == 10 && c.path().sample(3999).get().x() == 10 && c.path().sample(4000).get().x() == 20, "a câmera fica parada e salta exatamente no instante do corte");
        c.undo();
        check(c.path().size() == 2 && c.path().all().get(1).easing() == Easing.HOLD, "desfazer remove só o último corte");
        c.undo();
        check(c.path().size() == 1 && c.path().all().get(0).easing() == Easing.SMOOTH, "desfazer o primeiro corte também devolve a interpolação do keyframe anterior (um passo só)");
        c.redo();
        check(c.path().size() == 2 && c.path().all().get(0).easing() == Easing.HOLD, "refazer reaplica o corte");
        Timeline rc = new Timeline();
        rc.captureCut(1000, s0);
        Keyframe re = rc.captureCut(1010, s1);
        check(rc.path().size() == 1 && re.easing() == Easing.HOLD && re.state().x() == 10, "cortar em cima de um keyframe recaptura em vez de duplicar");

        // ---------- tamanhos da interface ----------
        Settings ui = new Settings();
        check(ui.camLaneHeight == 56 && ui.panelOpacity == 93, "interface: altura da faixa e opacidade padrão");
        ui.camLaneHeight = 5; ui.panelOpacity = 500;
        Path uf = Files.createTempFile("settings", ".json");
        ui.save(uf);
        Settings ul = Settings.load(uf);
        check(ul.camLaneHeight == Settings.CAM_LANE_MIN && ul.panelOpacity == Settings.OPACITY_MAX, "interface: altura da faixa e opacidade são limitadas ao salvar");
        ui.camLaneHeight = 80; ui.panelOpacity = 50; ui.save(uf);
        check(Settings.load(uf).camLaneHeight == 80 && Settings.load(uf).panelOpacity == 50, "interface: altura da faixa e opacidade válidas são mantidas");
        Settings st = new Settings();
        check(st.dockWidth == 310 && st.lanesVisible == 3 && !st.dockLeft && !st.showGrid && !st.collapsed, "interface: padrões");
        st.dockWidth = 9999; st.lanesVisible = 0; st.dockLeft = true; st.showGrid = true; st.collapsed = true;
        Path sf = Files.createTempFile("settings", ".json");
        st.save(sf);
        Settings sl = Settings.load(sf);
        check(sl.dockWidth == Settings.DOCK_MAX && sl.lanesVisible == Settings.LANES_MIN && sl.dockLeft && sl.showGrid && sl.collapsed, "interface: salva, limita os valores e relê");
        st.dockWidth = 400; st.lanesVisible = 6; st.save(sf);
        check(Settings.load(sf).dockWidth == 400 && Settings.load(sf).lanesVisible == 6, "interface: tamanhos válidos são mantidos");
        Files.writeString(sf, "{\"dockWidth\": \"largo\", \"lanesVisible\": -5, \"ffmpegPath\": \"x\"}");
        Settings bad = Settings.load(sf);
        check(bad.dockWidth == 310 && bad.lanesVisible == Settings.LANES_MIN && bad.ffmpegPath.equals("x"), "interface: valor inválido volta ao padrão e valor absurdo é limitado");
        Path oldSettings = Files.createTempFile("settings", ".json");
        Files.writeString(oldSettings, "{\"ffmpegPath\": \"\", \"renderPreset\": \"padrao\", \"renderFps\": 30, \"renderHeight\": 0, \"freezeWorld\": true}");
        check(Settings.load(oldSettings).dockWidth == 310 && Settings.load(oldSettings).lanesVisible == 3, "interface: settings.json antigo (sem os campos novos) continua funcionando");

        System.out.println("\n" + passed + " verificações da fase 6 passaram.");
    }
}
