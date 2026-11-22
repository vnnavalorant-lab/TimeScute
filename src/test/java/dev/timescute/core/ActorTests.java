package dev.timescute.core;

import java.nio.file.*;
import java.util.*;

public final class ActorTests {
    static int passed = 0;

    static void check(boolean c, String name) {
        if (!c) throw new AssertionError("FALHOU: " + name);
        passed++;
        System.out.println("  ok  " + name);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }
    static boolean near(double a, double b, double e) { return Math.abs(a - b) < e; }

    static double ev(String expr, double t) { return Molang.parse(expr).eval(t); }

    static boolean bad(String expr) {
        try { Molang.parse(expr); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static Actor actorWith(long id, long start, int seconds) {
        List<ActorFrame> fr = new ArrayList<>();
        for (int t = 0; t <= seconds * 1000; t += 50)
            fr.add(new ActorFrame(t, t / 100.0, 64, 0, 0f, 0f, 0f, 0f, ActorFrame.ON_GROUND, 1, "minecraft:stick"));
        return new Actor(id, "Ator " + id, "", start, Performance.of(fr), List.of(), false);
    }

    static ActorPose eval(Actor a, long t, AnimLibrary lib) { return ActorPlayer.evaluate(a, t, lib); }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("tsr7");
        ClipLibrary lib = new ClipLibrary();

        // ================= Molang =================
        check(ev("1+2*3", 0) == 7 && ev("(1+2)*3", 0) == 9 && ev("-2+5", 0) == 3 && ev("10/4", 0) == 2.5, "Molang: aritmética e precedência");
        check(ev("1/0", 0) == 0 && ev("5 / (2-2)", 0) == 0, "Molang: divisão por zero vale 0 (não quebra)");
        check(near(ev("math.sin(90)", 0), 1) && near(ev("math.cos(0)", 0), 1) && near(ev("math.sin(180)", 0), 0), "Molang: seno e cosseno em GRAUS");
        check(ev("query.anim_time*2", 1.5) == 3 && ev("q.anim_time", 2.5) == 2.5 && ev("query.life_time", 4) == 4, "Molang: tempo da animação (query.anim_time, q., life_time)");
        check(ev("query.anim_time<1 ? 10 : 20", 0.5) == 10 && ev("query.anim_time<1 ? 10 : 20", 2) == 20, "Molang: condicional a ? b : c");
        check(ev("1&&0", 0) == 0 && ev("1||0", 0) == 1 && ev("!0", 0) == 1 && ev("2==2", 0) == 1 && ev("2!=2", 0) == 0 && ev("3>=3", 0) == 1, "Molang: lógica e comparações");
        check(ev("math.clamp(15,0,10)", 0) == 10 && ev("math.lerp(0,10,0.25)", 0) == 2.5 && ev("math.min(3,4)", 0) == 3 && ev("math.max(3,4)", 0) == 4
                && ev("math.abs(-7)", 0) == 7 && ev("math.floor(2.9)", 0) == 2 && ev("math.sqrt(9)", 0) == 3 && ev("math.pow(2,10)", 0) == 1024 && ev("math.mod(7,3)", 0) == 1, "Molang: funções math.*");
        check(near(ev("math.pi", 0), Math.PI) && ev("variable.qualquer + 5", 0) == 5 && ev("v.x", 0) == 0 && ev("funcao.desconhecida(1,2)", 0) == 0, "Molang: pi; variáveis e funções desconhecidas valem 0");
        check(ev("MATH.SIN(90)", 0) == ev("math.sin(90)", 0) && ev("return 5;", 0) == 5 && ev("1e2", 0) == 100 && ev("math.random(0,10)", 0) == 5, "Molang: maiúsculas, 'return', notação científica, random determinístico");
        check(bad("1+") && bad("(1") && bad("2 $ 3") && bad("math.sin()") && bad("1 ? 2") && bad("1 2"), "Molang: expressões inválidas são recusadas com erro claro");
        String msg = "";
        try { Molang.parse("1 +* 2"); } catch (IllegalArgumentException e) { msg = e.getMessage(); }
        check(msg.contains("1 +* 2") && msg.contains("posição"), "Molang: a mensagem mostra a expressão e a posição");

        // ================= leitor do Blockbench =================
        String json = """
            {"format_version":"1.8.0","animations":{
              "animation.hero.wave":{"loop":true,"animation_length":2.0,"bones":{
                 "rightArm":{"rotation":{"0.0":[0,0,0],"1.0":[90,0,0],"2.0":[0,0,0]},"position":[0,5,0],"scale":2},
                 "head":{"rotation":[0,"math.sin(query.anim_time*180)*10",0]},
                 "tail":{"rotation":[10,0,0]}}},
              "animation.hero.once":{"loop":false,"animation_length":1.0,"bones":{"body":{"rotation":{"0":[0,0,0],"1":[0,60,0]}}}},
              "animation.hero.hold":{"loop":"hold_on_last_frame","animation_length":1.0,"bones":{"body":{"rotation":{"0":[0,0,0],"1":[0,60,0]}}}},
              "animation.hero.nolen":{"loop":true,"bones":{"body":{"rotation":{"0":[0,0,0],"3":[0,30,0]}}}},
              "animation.hero.jump":{"loop":false,"animation_length":2,"bones":{"body":{"position":{
                   "0":[0,0,0],"1":{"pre":[0,0,0],"post":[0,10,0]},"2":[0,10,0]}}}},
              "animation.hero.step":{"loop":false,"animation_length":2,"bones":{"body":{"rotation":{
                   "0":{"post":[0,0,0],"lerp_mode":"step"},"1":[0,90,0],"2":[0,90,0]}}}},
              "animation.hero.smooth":{"loop":false,"animation_length":3,"bones":{"body":{"rotation":{
                   "0":[0,0,0],"1":{"post":[0,90,0],"lerp_mode":"catmullrom"},"2":[0,90,0],"3":[0,0,0]}}}},
              "animation.hero.broken":{"loop":true,"bones":{"body":{"rotation":[1,2]}}}
            }}""";
        BedrockAnimations.Result res = BedrockAnimations.parse(json);
        Map<String, AnimClip> byName = new HashMap<>();
        for (AnimClip c : res.clips()) byName.put(c.name(), c);
        check(res.clips().size() == 7 && !byName.containsKey("animation.hero.broken"), "leitor: lê 7 animações e descarta só a quebrada");
        check(res.warnings().stream().anyMatch(w -> w.contains("broken")), "leitor: avisa qual animação foi descartada e por quê");
        check(res.warnings().stream().anyMatch(w -> w.contains("tail")), "leitor: avisa os ossos que o jogador não tem (tail)");
        AnimClip wave = byName.get("animation.hero.wave");
        check(wave.length() == 2.0 && wave.loop() == AnimClip.Loop.LOOP && byName.get("animation.hero.once").loop() == AnimClip.Loop.ONCE
                && byName.get("animation.hero.hold").loop() == AnimClip.Loop.HOLD, "leitor: duração e modos de repetição");
        check(byName.get("animation.hero.nolen").length() == 3.0, "leitor: sem animation_length usa o último keyframe");
        check(near(wave.sample(0.5).get("rightArm").rot()[0], 45) && near(wave.sample(1.0).get("rightArm").rot()[0], 90) && near(wave.sample(2.0).get("rightArm").rot()[0], 0), "keyframes: interpolação linear (0 -> 90 -> 0)");
        check(near(wave.sample(2.5).get("rightArm").rot()[0], wave.sample(0.5).get("rightArm").rot()[0]), "loop: 2,5 s repete o instante 0,5 s");
        check(wave.sample(1).get("rightArm").pos()[1] == 5 && wave.sample(1).get("rightArm").scale()[0] == 2 && wave.sample(1).get("rightArm").scale()[2] == 2, "canal constante (posição) e número único valendo para os 3 eixos (escala)");
        check(near(wave.sample(0.5).get("head").rot()[1], 10) && near(wave.sample(1.0).get("head").rot()[1], 0), "expressão Molang no keyframe (seno do tempo)");
        check(wave.sample(0).get("head").scale()[0] == 1 && wave.sample(0).get("head").pos()[0] == 0, "canais ausentes: posição 0 e escala 1");
        check(byName.get("animation.hero.once").sample(1.5).isEmpty() && !byName.get("animation.hero.once").sample(0.5).isEmpty(), "sem repetição: depois do fim o clipe não aplica nada");
        check(near(byName.get("animation.hero.hold").sample(5).get("body").rot()[1], 60), "segurar último quadro: fica na pose final");
        AnimClip jump = byName.get("animation.hero.jump");
        check(jump.sample(0.999).get("body").pos()[1] < 0.1 && near(jump.sample(1.001).get("body").pos()[1], 10, 0.1), "pre/post: salto instantâneo no keyframe");
        AnimClip step = byName.get("animation.hero.step");
        check(step.sample(0.9).get("body").rot()[1] == 0 && near(step.sample(1.0).get("body").rot()[1], 90), "step: segura o valor até o próximo keyframe");
        AnimClip smooth = byName.get("animation.hero.smooth");
        double linearQuarter = 22.5; // a reta; no meio (0,5) a curva simétrica coincide com ela, então olhamos 0,25
        check(near(smooth.sample(0.25).get("body").rot()[1], 18.28125, 0.001) && Math.abs(smooth.sample(0.25).get("body").rot()[1] - linearQuarter) > 3
                && near(smooth.sample(0.5).get("body").rot()[1], 45) && near(smooth.sample(1.0).get("body").rot()[1], 90) && near(smooth.sample(2.0).get("body").rot()[1], 90), "catmullrom: curva suave (18,28° em 0,25 s, não os 22,5° da reta) que passa pelos keyframes");
        check(wave.affectsPlayer() && wave.unmappedBones().equals(List.of("tail")), "ossos mapeados vs. não mapeados");

        boolean e1 = false, e2 = false, e3 = false;
        try { BedrockAnimations.parse("{\"format_version\":\"1.8.0\"}"); } catch (IllegalArgumentException e) { e1 = e.getMessage().contains("animations"); }
        try { BedrockAnimations.parse("isto não é json"); } catch (IllegalArgumentException e) { e2 = true; }
        try { BedrockAnimations.parse("[1,2]"); } catch (IllegalArgumentException e) { e3 = true; }
        check(e1 && e2 && e3, "leitor: arquivo sem animações / não-JSON / não-objeto dão erro claro");
        boolean allBad = false;
        try { BedrockAnimations.parse("{\"animations\":{\"a\":{\"bones\":{\"head\":{\"rotation\":\"1 +\"}}}}}"); } catch (IllegalArgumentException e) { allBad = e.getMessage().contains("Nenhuma"); }
        check(allBad, "leitor: se nenhuma animação for legível, o erro explica");

        // ================= partes do corpo =================
        check(Part.fromBone("rightArm") == Part.RIGHT_ARM && Part.fromBone("right_arm") == Part.RIGHT_ARM && Part.fromBone("RightArm") == Part.RIGHT_ARM && Part.fromBone("leftleg") == Part.LEFT_LEG
                && Part.fromBone("Head") == Part.HEAD && Part.fromBone("torso") == Part.BODY && Part.fromBone("waist") == Part.BODY, "nomes de osso em vários estilos viram a parte certa");
        check(Part.fromBone("tail") == null && Part.fromBone("") == null && Part.fromBone("wing_left") == null, "ossos que o jogador não tem dão null");
        check(Part.RIGHT_ARM.mirror() == Part.LEFT_ARM && Part.LEFT_LEG.mirror() == Part.RIGHT_LEG && Part.HEAD.mirror() == Part.HEAD, "espelhar troca esquerda e direita");
        check(Part.ALL == 63 && Part.has(Part.ARMS, Part.RIGHT_ARM) && !Part.has(Part.ARMS, Part.HEAD) && Part.has(Part.LEGS, Part.LEFT_LEG), "máscaras de partes");

        // ================= conversão de eixos =================
        AnimClip.BoneValue bv = new AnimClip.BoneValue(new double[]{90, 90, 90}, new double[]{1, 2, 3}, new double[]{1, 1.5, 2});
        PartPose pb = AnimLibrary.Config.BEDROCK.toPose(bv);
        check(near(pb.pitch(), -Math.PI / 2) && near(pb.yaw(), -Math.PI / 2) && near(pb.roll(), Math.PI / 2) && pb.x() == -1 && pb.y() == -2 && pb.z() == 3, "Bedrock: giro X e Y invertidos, Z mantido; posição gira 180° em Z");
        PartPose pj = AnimLibrary.Config.JAVA.toPose(bv);
        check(near(pj.pitch(), Math.PI / 2) && near(pj.yaw(), Math.PI / 2) && pj.x() == 1 && pj.y() == -2 && pj.z() == 3, "Java: giros como estão; posição com Y invertido");
        check(pb.sx() == 1 && pb.sy() == 1.5 && pb.sz() == 2, "a escala passa direto");
        PartPose fl = new AnimLibrary.Config(Convention.BEDROCK, true, true, true).toPose(bv);
        check(near(fl.pitch(), Math.PI / 2) && near(fl.yaw(), Math.PI / 2) && near(fl.roll(), -Math.PI / 2), "inverter giro X/Y/Z (calibração) funciona");
        check(PartPose.REST.mirrored().equals(PartPose.REST) && near(pb.mirrored().yaw(), -pb.yaw()) && near(pb.mirrored().roll(), -pb.roll()) && pb.mirrored().x() == 1 && pb.mirrored().pitch() == pb.pitch(), "espelhar: inverte Y, Z e o deslocamento lateral; X fica");

        // ================= camadas =================
        PartPose rest = PartPose.REST;
        PartPose arm = new PartPose(-1.0, 0, 0.5, 2, 0, 0, 2, 1, 1);
        PartPose r1 = new PartLayer(arm, 1.0, false).applyOver(rest);
        check(near(r1.pitch(), -1.0) && near(r1.sx(), 2), "camada substitui com peso 1");
        check(new PartLayer(arm, 0, false).applyOver(rest).equals(rest), "peso 0 não muda nada");
        PartPose half = new PartLayer(arm, 0.5, false).applyOver(rest);
        check(near(half.pitch(), -0.5) && near(half.roll(), 0.25) && near(half.sx(), 1.5), "peso 0,5 mistura no meio (inclusive a escala)");
        PartPose base = new PartPose(0.3, 0, 0, 0, 0, 0, 1, 1, 1);
        PartPose add = new PartLayer(arm, 0.5, true).applyOver(base);
        check(near(add.pitch(), 0.3 - 0.5) && near(add.sx(), 1.5), "somar acrescenta por cima da pose do jogo (escala multiplica)");
        check(near(new PartLayer(arm, 7, false).applyOver(rest).pitch(), -1.0), "peso acima de 1 é limitado a 1");
        PartPose chain = new PartLayer(new PartPose(1, 0, 0, 0, 0, 0, 1, 1, 1), 1, false).applyOver(
                new PartLayer(new PartPose(-1, 0, 0, 0, 0, 0, 1, 1, 1), 1, false).applyOver(rest));
        check(near(chain.pitch(), 1), "camadas em ordem: a última substituta manda");

        // ================= animações embutidas =================
        check(BuiltinClips.all().size() == 4 && BuiltinClips.all().stream().allMatch(AnimClip::builtin), "4 animações embutidas, todas marcadas como embutidas");
        check(lib.clip(BuiltinClips.WAVE).isPresent() && lib.config(BuiltinClips.WAVE).convention() == Convention.JAVA && lib.config(BuiltinClips.CALIBRATE).convention() == Convention.BEDROCK, "calibração usa Bedrock; as demais já estão nos eixos do Java");
        PartPose wv = lib.config(BuiltinClips.WAVE).toPose(lib.clip(BuiltinClips.WAVE).get().sample(0.25).get("rightarm"));
        check(near(wv.pitch(), Math.toRadians(-165)) && near(wv.roll(), Math.toRadians(25 * Math.sin(Math.toRadians(130)))), "acenar: braço levantado (-165°) com balanço calculado por Molang");
        AnimClip cal = lib.clip(BuiltinClips.CALIBRATE).get();
        PartPose c1 = lib.config(BuiltinClips.CALIBRATE).toPose(cal.sample(0.5).get("rightarm"));
        PartPose c2 = lib.config(BuiltinClips.CALIBRATE).toPose(cal.sample(1.5).get("head"));
        check(near(c1.pitch(), -Math.PI / 2) && near(c2.yaw(), -Math.PI / 4), "calibração: braço para a frente (pitch -90°) e cabeça virando (yaw -45°)");
        check(cal.sample(3.5).isEmpty() && cal.loop() == AnimClip.Loop.ONCE, "calibração acontece uma vez");

        // ================= biblioteca =================
        ClipLibrary l2 = new ClipLibrary();
        check(l2.names().get(0).startsWith("builtin.") && l2.importedCount() == 0, "biblioteca: embutidas primeiro");
        List<String> names = l2.addImported(res.clips());
        check(l2.importedCount() == 7 && names.size() == 7 && l2.config("animation.hero.wave").convention() == Convention.BEDROCK, "importadas entram com a convenção Bedrock");
        List<String> again = l2.addImported(List.of(wave));
        check(again.get(0).equals("animation.hero.wave (2)") && l2.clip("animation.hero.wave (2)").isPresent(), "nome repetido ganha (2)");
        l2.setConfig("animation.hero.wave", new AnimLibrary.Config(Convention.JAVA, true, false, false));
        check(l2.config("animation.hero.wave").flipPitch() && l2.config("animation.nao.existe").convention() == Convention.BEDROCK, "configuração por clipe (e padrão para desconhecidos)");
        l2.clearImported();
        check(l2.importedCount() == 0 && l2.clip(BuiltinClips.WAVE).isPresent() && l2.configs().keySet().stream().allMatch(k -> k.startsWith("builtin.")), "reler a pasta mantém só as embutidas");
        check(ClipLibrary.label("builtin.acenar").equals("★ acenar") && ClipLibrary.label("animation.hero.wave").equals("hero.wave") && ClipLibrary.label("").equals("(nenhuma)"), "rótulos para a interface");

        // ================= arquivos de animação =================
        Path dir = Files.createDirectory(tmp.resolve("anims"));
        Files.writeString(dir.resolve("heroi.animation.json"), json);
        Files.writeString(dir.resolve("outra.json"), "{\"animations\":{\"animation.x.idle\":{\"loop\":true,\"bones\":{\"head\":{\"rotation\":[0,10,0]}}}}}");
        Files.writeString(dir.resolve("quebrada.json"), "{ isso nao e json");
        Files.writeString(dir.resolve("notas.txt"), "ignorar");
        AnimFiles.Scan sc = AnimFiles.scan(dir);
        check(sc.files() == 3 && sc.clips().size() == 8, "pasta: 3 arquivos .json lidos, 8 clipes no total");
        check(sc.messages().stream().anyMatch(m -> m.startsWith("quebrada.json")) && sc.messages().stream().anyMatch(m -> m.contains("tail")), "pasta: erros e avisos vêm com o nome do arquivo");
        check(AnimFiles.scan(tmp.resolve("nao-existe")).clips().isEmpty(), "pasta inexistente: vazio, sem erro");
        ClipLibrary l3 = new ClipLibrary();
        l3.addImported(sc.clips());
        l3.setConfig("animation.x.idle", new AnimLibrary.Config(Convention.JAVA, false, true, true));
        AnimFiles.saveConfig(dir, l3);
        check(AnimFiles.scan(dir).files() == 3, "clips.json (configuração) não é lido como animação");
        ClipLibrary l4 = new ClipLibrary();
        l4.addImported(sc.clips());
        AnimFiles.loadConfig(dir, l4);
        check(l4.config("animation.x.idle").equals(new AnimLibrary.Config(Convention.JAVA, false, true, true)), "configuração de eixos salva e restaurada");
        Files.writeString(dir.resolve(AnimFiles.CONFIG_FILE), "{ lixo");
        ClipLibrary l5 = new ClipLibrary();
        l5.addImported(sc.clips());
        AnimFiles.loadConfig(dir, l5);
        check(l5.config("animation.x.idle").convention() == Convention.BEDROCK, "configuração corrompida é ignorada (usa os padrões)");

        // ================= performance =================
        Actor base1 = actorWith(1, 0, 2);
        Performance perf = base1.performance();
        check(perf.size() == 41 && perf.durationMs() == 2000 && near(perf.sampleAt(25).x(), 0.25) && near(perf.sampleAt(-5).x(), 0) && near(perf.sampleAt(99999).x(), 20), "performance: duração, interpolação e pontas");
        List<ActorFrame> ang = List.of(new ActorFrame(0, 0, 0, 0, 350f, 0, 350f, 350f, 0, 0, ""), new ActorFrame(50, 1, 0, 0, 10f, 0, 10f, 10f, 0, 0, ""));
        float yw = Performance.of(ang).sampleAt(25).yaw();
        check(near(((yw % 360) + 360) % 360, 0) || near(((yw % 360) + 360) % 360, 360), "performance: ângulos giram pelo caminho curto");
        check(Performance.of(List.of(new ActorFrame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""), new ActorFrame(5000, 5, 0, 0, 0, 0, 0, 0, 0, 0, ""))).sampleAt(2500).x() == 0, "performance: lacuna grande não interpola");
        check(Performance.of(List.of(new ActorFrame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""), new ActorFrame(50, 500, 0, 0, 0, 0, 0, 0, 0, 0, ""))).sampleAt(25).x() == 0, "performance: teletransporte não desliza");
        ActorFrame fm = Performance.of(List.of(new ActorFrame(0, 0, 0, 0, 0, 0, 0, 0, ActorFrame.SNEAKING, 3, "a:b"), new ActorFrame(50, 1, 0, 0, 0, 0, 0, 0, 0, 5, "c:d"))).sampleAt(25);
        check(fm.has(ActorFrame.SNEAKING) && fm.slot() == 3 && fm.item().equals("a:b"), "performance: flags, slot e item vêm do quadro anterior");
        boolean thrown = false;
        try { Performance.EMPTY.sampleAt(0); } catch (IllegalStateException e) { thrown = true; }
        check(thrown && Performance.EMPTY.isEmpty() && Performance.of(List.of()).isEmpty(), "performance vazia");
        Random rnd = new Random(3);
        List<ActorFrame> many = new ArrayList<>();
        long cur = 0;
        for (int i = 0; i < 3000; i++) { cur += 1 + rnd.nextInt(60); many.add(new ActorFrame(cur, i, 0, 0, 0, 0, 0, 0, 0, 0, "")); }
        Performance mp = Performance.of(many);
        boolean okIdx = true;
        for (int q = 0; q < 3000; q++) {
            long ms = rnd.nextInt((int) cur + 200) - 50;
            int brute = -1;
            for (int i = 0; i < many.size(); i++) if (many.get(i).timeMs() <= ms) brute = i;
            okIdx &= mp.indexAt(ms) == brute;
        }
        check(okIdx, "performance: busca binária igual à busca linear (3000 consultas)");

        // ================= olhar =================
        float[] fwd = LookMath.yawPitch(0, 0, 0, 0, 0, 5), px = LookMath.yawPitch(0, 0, 0, 5, 0, 0), nx = LookMath.yawPitch(0, 0, 0, -5, 0, 0), up = LookMath.yawPitch(0, 0, 0, 0, 5, 5), dn = LookMath.yawPitch(0, 0, 0, 0, -5, 5);
        check(near(fwd[0], 0) && near(px[0], -90) && near(nx[0], 90) && near(up[1], -45) && near(dn[1], 45), "olhar: +Z yaw 0; +X yaw -90; -X yaw 90; para cima pitch negativo");
        check(near(LookMath.yawPitch(0, 0, 0, 0, 5, 0)[1], -90) && LookMath.yawPitch(1, 1, 1, 1, 1, 1)[0] == 0, "olhar: reto para cima e alvo no mesmo lugar não dão NaN");
        check(near(LookMath.blendAngle(350, 10, 0.5), 360) && near(LookMath.blendAngle(10, 350, 0.5), 0) && near(LookMath.blendAngle(0, 90, 0), 0) && near(LookMath.blendAngle(0, 90, 1), 90) && near(LookMath.blendAngle(0, 90, 5), 90), "olhar: mistura de ângulos pelo caminho curto, peso limitado");
        check(LookMath.smoothWeight(0, 0.15) == 0 && LookMath.smoothWeight(0.5, 0.15) == 1 && LookMath.smoothWeight(1, 0.15) == 0 && LookMath.smoothWeight(0.075, 0.15) > 0.4 && LookMath.smoothWeight(0.075, 0.15) < 0.6, "peso suave: 0 nas pontas, 1 no meio");

        // ================= reação / ator =================
        Reaction rr = new Reaction(1, -50, 5, ReactionType.SWING, null, null, false, 999, 0, 0, 0, null, null);
        check(rr.startMs() == 0 && rr.durationMs() == Reaction.MIN_DURATION_MS && rr.count() == 50 && rr.anim().equals(AnimParams.DEFAULT) && rr.itemId().isEmpty(), "reação: início/duração/contagem limitados e nulos viram padrão");
        check(Reaction.of(1, 0, 1000, ReactionType.SWING).shortLabel().equals("Bater") && Reaction.of(1, 0, 1000, ReactionType.SWING).withCount(3).shortLabel().equals("Bater x3")
                && Reaction.of(1, 0, 1000, ReactionType.SWAP_ITEM).withItem("minecraft:diamond_sword").shortLabel().equals("Item diamond sword")
                && Reaction.of(1, 0, 1000, ReactionType.ANIMATION).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE)).shortLabel().equals("★ acenar"), "reação: rótulos curtos dos blocos");
        AnimParams ap = new AnimParams(null, 0xFFFF, 0, 9, true, false, false);
        check(ap.clip().isEmpty() && ap.partMask() == Part.ALL && near(ap.speed(), 0.05) && ap.weight() == 1 && AnimParams.DEFAULT.toggled(Part.HEAD).partMask() == (Part.ALL ^ Part.HEAD.bit()), "parâmetros de animação: limites e alternar parte");
        Actor ac = actorWith(1, 0, 2).withReaction(Reaction.of(9, 500, 500, ReactionType.SWING)).withReaction(Reaction.of(8, 100, 500, ReactionType.SWING));
        check(ac.reactions().get(0).id() == 8 && ac.reactions().get(1).id() == 9, "ator: reações ordenadas por início");
        check(ac.withReaction(ac.reactions().get(0).withStart(900)).reactions().get(1).id() == 8 && ac.withoutReaction(8).reactions().size() == 1 && ac.reaction(9).isPresent() && ac.reaction(77).isEmpty(), "ator: trocar/remover/achar reação");
        check(ac.endMs() == 2000 && ac.withStart(1000).endMs() == 3000 && Actor.create(1, "x").endMs() == 0 && Actor.create(1, " ").name().equals("Ator"), "ator: fim, deslocamento e nome padrão");

        // ================= avaliação do ator =================
        Actor a = actorWith(1, 1000, 2); // visível de 1000 a 3000 da timeline
        check(!eval(a, 999, lib).visible && eval(a, 1000, lib).visible && eval(a, 3000, lib).visible && !eval(a, 3001, lib).visible, "ator aparece só durante a performance (início e fim inclusivos)");
        check(near(eval(a, 1025, lib).frame.x(), 0.25) && near(eval(a, 2000, lib).frame.x(), 10), "ator: posição interpolada no instante, deslocada pelo início");
        check(!eval(a.withMuted(true), 1500, lib).visible && !eval(Actor.create(2, "vazio"), 0, lib).visible, "ator mudo ou sem performance fica escondido");
        check(eval(a, 1500, lib).item.equals("minecraft:stick") && eval(a, 1500, lib).layers.isEmpty() && eval(a, 1500, lib).swingCount == 0 && eval(a, 1500, lib).look == null, "sem reações: só a performance");

        Actor sw = a.withReaction(Reaction.of(5, 1500, 500, ReactionType.SWAP_ITEM).withItem("minecraft:diamond_sword"));
        check(eval(sw, 1499, lib).item.equals("minecraft:stick") && eval(sw, 1500, lib).item.equals("minecraft:diamond_sword") && eval(sw, 1999, lib).item.equals("minecraft:diamond_sword") && eval(sw, 2000, lib).item.equals("minecraft:stick"), "trocar item: vale só durante o bloco e depois volta ao da performance");

        Actor sg = a.withReaction(Reaction.of(6, 1000, 900, ReactionType.SWING).withCount(3).withOffHand(true));
        check(eval(sg, 999, lib).swingCount == 0 && eval(sg, 1000, lib).swingCount == 1 && eval(sg, 1299, lib).swingCount == 1 && eval(sg, 1300, lib).swingCount == 2 && eval(sg, 1600, lib).swingCount == 3 && eval(sg, 2900, lib).swingCount == 3, "bater: 3 batidas igualmente espaçadas e o total para em 3");
        boolean mono = true;
        int prev = 0;
        for (long t = 1000; t <= 3000; t += 10) { int c = eval(sg, t, lib).swingCount; mono &= c >= prev; prev = c; }
        check(mono && eval(sg, 1000, lib).swingOffHand && !eval(a.withReaction(Reaction.of(6, 1000, 900, ReactionType.SWING)), 1000, lib).swingOffHand, "bater: contagem só cresce; mão secundária respeitada");
        Actor two = sg.withReaction(Reaction.of(7, 2000, 300, ReactionType.SWING));
        check(eval(two, 2000, lib).swingCount == 4, "duas reações de bater somam as batidas");
        Actor us = a.withReaction(Reaction.of(8, 1000, 600, ReactionType.USE_ITEM).withItem("minecraft:apple"));
        check(eval(us, 1100, lib).item.equals("minecraft:apple") && eval(us, 1100, lib).swingCount == 1 && eval(us, 1700, lib).item.equals("minecraft:stick"), "usar item: segura o item durante o bloco e balança o braço");

        Actor lk = a.withReaction(Reaction.of(4, 1000, 1000, ReactionType.LOOK_AT).withTarget(5, 70, 5, "", "baú"));
        check(eval(lk, 1000, lib).lookWeight == 0 && eval(lk, 1500, lib).lookWeight == 1 && eval(lk, 1500, lib).look.id() == 4 && eval(lk, 2000, lib).look == null && eval(lk, 999, lib).look == null, "olhar: peso sobe, fica em 1 e desce; fora do bloco não olha");

        // ---- animações em partes específicas ----
        Reaction wr = Reaction.of(20, 1000, 1000, ReactionType.ANIMATION).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE));
        Actor aw = a.withReaction(wr);
        ActorPose pw = eval(aw, 1250, lib);
        check(pw.layers.keySet().equals(Set.of(Part.RIGHT_ARM)) && pw.layers.get(Part.RIGHT_ARM).size() == 1, "animação de acenar mexe só no braço direito");
        PartLayer wl = pw.layers.get(Part.RIGHT_ARM).get(0);
        check(near(wl.pose().pitch(), Math.toRadians(-165)) && !wl.additive(), "a pose chega convertida para o modelo do Java");
        check(eval(aw, 1000, lib).layers.get(Part.RIGHT_ARM).get(0).weight() == 0 && eval(aw, 1500, lib).layers.get(Part.RIGHT_ARM).get(0).weight() == 1 && eval(aw, 1990, lib).layers.get(Part.RIGHT_ARM).get(0).weight() < 0.05, "a intensidade sobe e desce suave nas pontas do bloco");
        check(eval(aw, 999, lib).layers.isEmpty() && eval(aw, 2000, lib).layers.isEmpty(), "fora do bloco não há animação");
        Actor longWave = a.withReaction(wr.withDuration(2000).withStart(1000));
        check(near(eval(longWave, 1250, lib).layers.get(Part.RIGHT_ARM).get(0).pose().roll(), eval(longWave, 2250, lib).layers.get(Part.RIGHT_ARM).get(0).pose().roll()), "clipe em repetição: o bloco maior que o clipe repete o movimento");

        // máscara: só as partes escolhidas
        String multi = "{\"animations\":{\"animation.t.multi\":{\"loop\":true,\"animation_length\":1,\"bones\":{\"head\":{\"rotation\":[0,30,0]},\"rightarm\":{\"rotation\":[-90,0,0]},\"leftleg\":{\"rotation\":[20,0,0]}}}}}";
        ClipLibrary lm = new ClipLibrary();
        lm.addImported(BedrockAnimations.parse(multi).clips());
        Reaction mr = Reaction.of(30, 1000, 1000, ReactionType.ANIMATION);
        Actor am = a.withReaction(mr.withAnim(AnimParams.DEFAULT.withClip("animation.t.multi")));
        check(eval(am, 1500, lm).layers.keySet().equals(Set.of(Part.HEAD, Part.RIGHT_ARM, Part.LEFT_LEG)), "sem máscara: a animação controla todas as partes que ela tem");
        Actor am2 = a.withReaction(mr.withAnim(AnimParams.DEFAULT.withClip("animation.t.multi").withMask(Part.ARMS)));
        check(eval(am2, 1500, lm).layers.keySet().equals(Set.of(Part.RIGHT_ARM)), "máscara 'braços': só o braço direito é afetado; cabeça e perna seguem a performance");
        Actor am3 = a.withReaction(mr.withAnim(AnimParams.DEFAULT.withClip("animation.t.multi").withMask(Part.HEAD.bit() | Part.LEFT_LEG.bit())));
        check(eval(am3, 1500, lm).layers.keySet().equals(Set.of(Part.HEAD, Part.LEFT_LEG)), "máscara combinada (cabeça + pernas)");
        check(eval(a.withReaction(mr.withAnim(AnimParams.DEFAULT.withClip("animation.t.multi").withMask(0))), 1500, lm).layers.isEmpty(), "máscara vazia: nada é afetado");

        // espelhar
        Actor mir = a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withMirror(true)));
        ActorPose pm = eval(mir, 1250, lib);
        check(pm.layers.keySet().equals(Set.of(Part.LEFT_ARM)) && near(pm.layers.get(Part.LEFT_ARM).get(0).pose().roll(), -wl.pose().roll()) && near(pm.layers.get(Part.LEFT_ARM).get(0).pose().pitch(), wl.pose().pitch()), "espelhar: o aceno passa para o braço esquerdo, com o giro lateral invertido");
        Actor mirMask = a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withMirror(true).withMask(Part.RIGHT_ARM.bit())));
        check(eval(mirMask, 1250, lib).layers.isEmpty(), "a máscara vale para a parte FINAL (depois de espelhar)");

        // ajustar à duração, velocidade, somar
        Actor fit = a.withReaction(wr.withDuration(2000).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withFit(true)));
        double expectRoll = Math.toRadians(25 * Math.sin(Math.toRadians(0.5 * 520))); // 1 s de bloco em 2 s -> t do clipe = 0,5
        check(near(eval(fit, 2000, lib).layers.get(Part.RIGHT_ARM).get(0).pose().roll(), expectRoll), "ajustar à duração: o clipe de 1 s se estica para caber em 2 s");
        Actor fast = a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withSpeed(2)));
        check(near(eval(fast, 1125, lib).layers.get(Part.RIGHT_ARM).get(0).pose().roll(), eval(aw, 1250, lib).layers.get(Part.RIGHT_ARM).get(0).pose().roll()), "velocidade 2x: 0,125 s do bloco = 0,25 s do clipe");
        check(eval(a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withAdditive(true).withWeight(0.5))), 1500, lib).layers.get(Part.RIGHT_ARM).get(0).additive()
                && near(eval(a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withWeight(0.5))), 1500, lib).layers.get(Part.RIGHT_ARM).get(0).weight(), 0.5), "somar e intensidade do usuário chegam à camada");

        // clipe ausente, clipe que acaba, camadas empilhadas
        check(eval(a.withReaction(wr.withAnim(AnimParams.DEFAULT.withClip("animation.nao.existe"))), 1500, lib).layers.isEmpty(), "clipe ausente: não faz nada nem quebra");
        Reaction cr = Reaction.of(40, 1000, 5000, ReactionType.ANIMATION).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.CALIBRATE));
        Actor ca = actorWith(1, 1000, 6).withReaction(cr); // performance de 6 s: o ator continua visível depois do fim do clipe
        check(!eval(ca, 3900, lib).layers.isEmpty() && eval(ca, 4100, lib).layers.isEmpty(), "clipe sem repetição: depois que acaba, as partes voltam à performance");
        Reaction over = Reaction.of(23, 1100, 800, ReactionType.ANIMATION).withAnim(AnimParams.DEFAULT.withClip(BuiltinClips.WAVE).withAdditive(true)); // id próprio
        Actor stack = a.withReaction(wr).withReaction(over);
        List<PartLayer> st = eval(stack, 1500, lib).layers.get(Part.RIGHT_ARM);
        check(st.size() == 2 && !st.get(0).additive() && st.get(1).additive(), "animações sobrepostas empilham camadas na ordem dos blocos (a de baixo vai primeiro)");
        check(eval(a.withReaction(wr).withReaction(Reaction.of(22, 1000, 1000, ReactionType.LOOK_AT)), 1500, lib).look != null && !eval(a.withReaction(wr).withReaction(Reaction.of(22, 1000, 1000, ReactionType.LOOK_AT)), 1500, lib).layers.isEmpty(), "animação e olhar acontecem juntos");

        // ================= timeline com atores =================
        Timeline t = new Timeline();
        t.capture(0, new CameraState(0, 0, 0, 0, 0, 0, 70));
        Actor n1 = t.addActor("A"), n2 = t.addActor("B"), n3 = t.addActor("C");
        check(t.actors().size() == 3 && n1.id() != n2.id() && n2.id() != n3.id() && t.actors().all().get(1).name().equals("B"), "timeline: atores com ids únicos, em ordem");
        long rid = t.newId();
        check(rid != n1.id() && rid != n3.id() && t.newId() > rid, "newId nunca repete");
        check(t.updateActor(n2.withPerformance(perf).withStart(500), "Gravar ator") && t.actors().byId(n2.id()).get().hasPerformance() && !t.updateActor(t.actors().byId(n2.id()).get()), "timeline: atualizar (e ignorar quando nada muda)");
        check(t.undoLabel().get().equals("Gravar ator") && t.contentEndMs() == 2500, "desfazer tem o rótulo; fim do conteúdo considera os atores (500 + 2000)");
        t.undo();
        check(!t.actors().byId(n2.id()).get().hasPerformance() && t.contentEndMs() == 0, "undo da gravação");
        t.redo();
        check(t.actors().byId(n2.id()).get().hasPerformance(), "redo da gravação");
        t.removeActor(n2.id());
        check(t.actors().size() == 2 && t.actors().byId(n2.id()).isEmpty(), "remover ator");
        t.undo();
        check(t.actors().size() == 3 && t.actors().all().get(1).id() == n2.id() && t.actors().all().get(1).hasPerformance(), "undo da remoção devolve o ator NA MESMA POSIÇÃO e com a performance");
        t.updateActor(t.actors().byId(n3.id()).get().withReaction(Reaction.of(t.newId(), 9000, 1000, ReactionType.SWING)));
        check(t.contentEndMs() == 10_000 && t.durationMs() == 15_000, "a duração da timeline cresce com as reações dos atores");
        t.clear();
        check(t.actors().isEmpty() && t.path().isEmpty(), "nova cena limpa tudo, inclusive atores");
        t.undo();
        check(t.actors().size() == 3 && t.path().size() == 1, "undo da nova cena restaura câmera e atores");
        Timeline loaded = new Timeline();
        loaded.addActor("lixo");
        loaded.resetTo(List.of(), List.of(), t.actors().all());
        long maxId = 0;
        for (Actor x : t.actors().all()) { maxId = Math.max(maxId, x.id()); for (Reaction rx : x.reactions()) maxId = Math.max(maxId, rx.id()); }
        check(loaded.actors().size() == 3 && !loaded.canUndo() && loaded.newId() > maxId, "carregar substitui os atores, zera o histórico e os ids novos nunca repetem os carregados (maior id: " + maxId + ")");
        check(!t.updateActor(Actor.create(99999, "fantasma")), "atualizar ator que não existe é recusado");

        // ================= arquivo da cena =================
        Timeline src = new Timeline();
        src.capture(1000, new CameraState(1, 2, 3, 10, 20, 0, 70));
        src.capture(5000, new CameraState(4, 5, 6, 30, 40, 0, 60));
        Actor ga = src.addActor("Zé \"o ator\" ação ✓");
        List<ActorFrame> fr = new ArrayList<>();
        for (int i = 0; i <= 20; i++) fr.add(new ActorFrame(i * 50L, i * 0.25, 64.5, -i * 0.125, 12.34f, -5.5f, 12.34f, 11f, i % 2 == 0 ? ActorFrame.ON_GROUND : ActorFrame.SNEAKING, i % 9, i < 10 ? "minecraft:stick" : i < 15 ? "" : "minecraft:diamond_sword"));
        Actor full = ga.withSkin("Notch").withStart(2500).withPerformance(Performance.of(fr)).withMuted(true)
                .withReaction(Reaction.of(src.newId(), 3000, 800, ReactionType.ANIMATION).withAnim(new AnimParams("animation.hero.wave", Part.ARMS | Part.HEAD.bit(), 1.5, 0.75, true, true, true)))
                .withReaction(Reaction.of(src.newId(), 3500, 600, ReactionType.SWING).withCount(4).withOffHand(true))
                .withReaction(Reaction.of(src.newId(), 4000, 500, ReactionType.USE_ITEM).withItem("minecraft:apple"))
                .withReaction(Reaction.of(src.newId(), 4200, 500, ReactionType.SWAP_ITEM).withItem("minecraft:torch"))
                .withReaction(Reaction.of(src.newId(), 4500, 900, ReactionType.LOOK_AT).withTarget(10.5, 64, -3.25, "123e4567-e89b-12d3-a456-426614174000", "Creeper"));
        src.updateActor(full);
        src.addActor("Outro");
        PathJson.Data d = PathJson.read(PathJson.write(src));
        check(d.actors().size() == 2 && d.keys().size() == 2, "arquivo: câmera e atores voltam");
        Actor g = d.actors().get(0);
        check(g.name().equals(full.name()) && g.skin().equals("Notch") && g.startMs() == 2500 && g.muted() && g.id() == full.id(), "arquivo: nome com aspas/acentos/símbolos, skin, início, mudo");
        check(g.performance().equals(full.performance()), "arquivo: a performance volta idêntica (" + g.performance().size() + " quadros, itens e flags)");
        check(g.reactions().equals(full.reactions()), "arquivo: as 5 reações voltam idênticas (animação com máscara/velocidade/espelho/ajuste, bater, usar, trocar, olhar com entidade)");
        check(d.actors().get(1).name().equals("Outro") && !d.actors().get(1).hasPerformance(), "arquivo: ator vazio");
        Timeline t2 = new Timeline();
        t2.resetTo(d.keys(), d.speeds(), d.actors());
        check(PathJson.write(t2).equals(PathJson.write(src)), "arquivo: ler e gravar de novo dá exatamente o mesmo texto");
        check(PathJson.read("{\"format\":\"timescute-path\",\"version\":1,\"keyframes\":[]}").actors().isEmpty() && PathJson.read(PathJson.write(new Timeline())).actors().isEmpty(), "arquivo sem atores (versão 1 ou cena vazia) abre normalmente");

        String head = "{\"format\":\"timescute-path\",\"version\":2,\"keyframes\":[],\"actors\":[";
        String okFrame = "[0,0,0,0,0,0,0,0,0,0,0]";
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[[0,0,0]]}]}"), "arquivo: quadro com quantidade errada de campos é recusado");
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[[-5,0,0,0,0,0,0,0,0,0,0]]}]}"), "arquivo: quadro com tempo negativo é recusado");
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[[0,0,0,0,0,0,0,0,0,0,7]]}]}"), "arquivo: quadro que aponta para item inexistente é recusado");
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[" + okFrame + "]},{\"id\":1,\"name\":\"b\",\"items\":[\"\"],\"frames\":[]}]}"), "arquivo: ids de atores repetidos são recusados");
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[],\"reactions\":[{\"id\":1,\"t\":0,\"d\":500}]}]}"), "arquivo: id de reação igual ao do ator é recusado");
        check(badFile(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[],\"reactions\":[{\"id\":2,\"t\":-1,\"d\":500}]}]}"), "arquivo: reação com tempo negativo é recusada");
        Actor tol = PathJson.read(head + "{\"id\":1,\"name\":\"a\",\"items\":[\"\"],\"frames\":[" + okFrame + "],\"reactions\":[{\"id\":2,\"t\":10,\"d\":500,\"type\":\"INVENTADO\"}]}]}").actors().get(0);
        check(tol.reactions().get(0).type() == ReactionType.ANIMATION && tol.reactions().get(0).anim().partMask() == Part.ALL, "arquivo: tipo de reação desconhecido vira animação; campos ausentes ganham padrão");

        // ================= formatação =================
        check(Fmt.duration(3_725_000).equals("1:02:05") && Fmt.duration(65_000).equals("01:05") && Fmt.size(1536).equals("1.5 KB") && Fmt.size(12).equals("12 B") && Fmt.size(5L << 20).equals("5.0 MB"), "formatação de tempo e tamanho");
        check(!Fmt.fileName("a/b\\c:d*e").contains("/") && !Fmt.fileName("..oculto").startsWith(".") && Fmt.fileName("   ").equals("video") && Fmt.fileName("x".repeat(200)).length() == 64 && Fmt.fileName("Câmera ação-1").equals("Câmera ação-1"), "nome de arquivo seguro");

        System.out.println("\n" + passed + " verificações de atores e animações passaram.");
    }

    static boolean badFile(String text) {
        try { PathJson.read(text); return false; } catch (IllegalArgumentException e) { return true; }
    }
}
