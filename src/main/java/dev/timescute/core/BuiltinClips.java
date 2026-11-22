package dev.timescute.core;

import java.util.*;

/** Animações que já vêm no mod. Escritas no mesmo formato do Blockbench (usa o mesmo leitor). */
public final class BuiltinClips {
    public static final String WAVE = "builtin.acenar", POINT = "builtin.apontar", CHEER = "builtin.comemorar", CALIBRATE = "builtin.calibrar";

    private static final String JSON = """
        {"format_version":"1.8.0","animations":{
          "builtin.acenar":{"loop":true,"animation_length":1.0,"bones":{"rightarm":{"rotation":[-165,0,"math.sin(query.anim_time*520)*25"]}}},
          "builtin.apontar":{"loop":"hold_on_last_frame","animation_length":0.5,"bones":{"rightarm":{"rotation":{"0.0":[0,0,0],"0.3":[-85,-3,0]}}}},
          "builtin.comemorar":{"loop":true,"animation_length":1.0,"bones":{
              "rightarm":{"rotation":[-165,0,"20+math.sin(query.anim_time*600)*10"]},
              "leftarm":{"rotation":[-165,0,"-(20+math.sin(query.anim_time*600+180)*10)"]}}},
          "builtin.calibrar":{"loop":false,"animation_length":3.0,"bones":{
              "rightarm":{"rotation":{"0.0":[0,0,0],"0.5":[90,0,0],"1.0":[0,0,0],"2.0":[0,0,0],"2.5":[0,0,90],"3.0":[0,0,0]}},
              "head":{"rotation":{"0.0":[0,0,0],"1.0":[0,0,0],"1.5":[0,45,0],"2.0":[0,0,0]}}}}
        }}""";

    private BuiltinClips() { }

    public static List<AnimClip> all() {
        List<AnimClip> out = new ArrayList<>();
        for (AnimClip c : BedrockAnimations.parse(JSON).clips()) out.add(new AnimClip(c.name(), c.length(), c.loop(), c.bones(), true));
        return out;
    }

    /** O clipe de calibração foi escrito com os eixos do Bedrock; os outros já nos eixos do Java. */
    public static AnimLibrary.Config defaultConfig(String name) {
        return CALIBRATE.equals(name) ? AnimLibrary.Config.BEDROCK : AnimLibrary.Config.JAVA;
    }

    /** Texto do que o usuário deve ver no clipe de calibração. */
    public static final String CALIBRATE_HELP =
            "0-1 s: o braço direito deve subir PARA A FRENTE. 1-2 s: a cabeça deve virar para o lado ESQUERDO do ator. "
            + "2-3 s: o braço direito abre para o lado. Se algum sair para o lado errado, use \"Inverter\" naquele eixo.";
}
