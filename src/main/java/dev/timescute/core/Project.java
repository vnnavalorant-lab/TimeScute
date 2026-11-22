package dev.timescute.core;

/** Tudo que o usuário edita: trajeto da câmera, trilha de velocidade (legado) e atores. */
public final class Project {
    public final CameraPath path = new CameraPath();
    public final SpeedTrack speed = new SpeedTrack();
    public final ActorSet actors = new ActorSet();
}
