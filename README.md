# TimeScute — editor de câmera e atores (Fabric 1.20.1)

Editor de cinema para o Minecraft: uma **timeline** com keyframes de câmera e **atores** (você grava a sua performance e ela é repetida por um "dublê" no seu mundo), com reações editáveis (animações do Blockbench, golpes, itens, olhar) e **renderização para vídeo** quadro a quadro via FFmpeg. Código 100% próprio.

## Como compilar
Precisa de JDK 17 e Gradle.
1. Na pasta do projeto: `gradle wrapper --gradle-version 8.8` (gera o `gradlew`).
2. `./gradlew build` → o mod fica em `build/libs/timescute-0.6.0.jar`.
3. Coloque o jar em `mods/` junto com o **Fabric API** (1.20.1).
4. Testes do núcleo (sem Minecraft): `./gradlew coreTests`.

### Compilar pelo GitHub
O projeto já tem `.github/workflows/build.yml`. A cada push o Actions compila, roda os testes do núcleo e guarda o jar (aba **Actions** → execução → **Artifacts** → `timescute-jar`). Uma tag `v*` (ex.: `git tag v0.6.0 && git push --tags`) anexa o jar a uma release.

## Teclas
| Tecla | Ação |
|---|---|
| **K** | Abre o editor (dentro dele, K captura um keyframe) |
| **J** | Captura keyframe sem abrir o editor; o playhead avança 2 s |
| **H** | Reproduz/pausa a cena sem abrir o editor |
| **F8** | Grava a sua performance para um ator (contagem de 3 s); aperte de novo para parar e salvar |
| **F9** | Abre a tela de renderização |

Todas podem ser mudadas em Controles → TimeScute.

## Editor de timeline
- Clique num keyframe: seleciona e mostra aquela tomada. Shift+clique: soma/remove da seleção.
- Arraste um keyframe (ou vários): move no tempo. Vermelho = posição inválida.
- Arraste no vazio: seleção em caixa. Clique simples no vazio: move o playhead.
- Régua: clique/arraste faz scrub. Roda: zoom no cursor. Shift+Roda ou botão do meio: rolar.
- Botão direito num keyframe, ou os botões da direita: muda a interpolação (Suave, Linear, Acelera/Freia, Parado).
- Espaço: play/pausa. Del: apagar. Ctrl+Z / Ctrl+Y: desfazer/refazer (ilimitado). Ctrl+A: selecionar tudo. Ctrl+D: duplicar reação.
- ←/→: keyframe anterior/próximo. Shift+←/→: ±100 ms. Home/End: início/fim.
- **Guias** (botão): linha do trajeto colorida pela interpolação e uma pirâmide de câmera por keyframe. As guias nunca aparecem no vídeo renderizado.

**Salvar e abrir** — arquivos `.json` em `config/timescute/paths/` (guardam câmera e atores): Ctrl+S salva (Ctrl+Shift+S pede um nome), Ctrl+O abre, Ctrl+N começa do zero; abrir e novo são desfazíveis. O **autosave** acontece ao fechar o editor, ao capturar com J e ao fechar o jogo.

## Atores
Botão **Atores**: crie atores, renomeie, troque a pele (nome de um jogador online; vazio = a sua), oculte, exclua e grave.
1. **Gravar** (ou F8): você atua enquanto os outros atores já gravados repetem suas performances. A gravação vira a *performance* do ator, que aparece como uma barra na faixa dele; arraste a barra para mudar onde ela começa.
2. **Reações**: duplo clique na faixa do ator (ou **+ Reação**) cria um bloco. Arraste para mover, puxe a borda direita para mudar a duração, botão direito (ou Enter) edita. Tipos: **Animação**, **Bater**, **Usar item**, **Trocar item**, **Olhar** (para um ponto ou uma entidade).
3. **Animações**: embutidas (acenar, apontar, comemorar, calibrar) ou importadas do Blockbench (formato Bedrock `.animation.json`) em `config/timescute/animations/` — também dá para arrastar os arquivos para a tela de Animações. Cada reação escolhe as partes do corpo, a velocidade, a intensidade, espelhar, somar e "caber" na duração. Se um eixo sair invertido, use *Inverter* (o clipe *calibrar* ajuda a conferir).

Os atores existem só no **seu** cliente: o servidor não os vê.

## Renderizar vídeo
Com pelo menos 2 keyframes de câmera, abra **Renderizar** (botão ou F9).
1. Instale o **FFmpeg** (o TimeScute não o inclui): Windows `winget install Gyan.FFmpeg`, Linux `sudo apt install ffmpeg`, macOS `brew install ffmpeg`. A tela procura no PATH; se estiver em outro lugar, informe o caminho e clique em *Testar*.
2. O vídeo tem o **tamanho da janela do jogo** (ou é redimensionado de 480p a 2160p). Para mais qualidade, aumente a janela antes.
3. Opções: qualidade (*Rascunho*, *Padrão*, *Alta qualidade*, *Sem perdas*), fps (24, 30, 48, 60), resolução, nome e *Congelar o mundo* (em um jogador, o servidor integrado para durante a renderização e o vídeo sai exato). Os vídeos ficam em `timescute/renders/`.

Durante a renderização a interface some e o progresso aparece no **título da janela**. Abrir o menu (Esc) ou perder o foco **pausa**; no menu há **Cancelar renderização** (o arquivo parcial é apagado). Cada quadro avança exatamente 1/fps, independente do tempo que o computador leve. Se o mundo ainda não estiver pronto (chunks), o quadro é segurado. O vídeo sai **sem áudio**.

## Estrutura
- `core/` — Java puro e testado: `Timeline`, `CameraPath`, `SpeedTrack`, `TimelineView`; atores (`Actor`, `ActorSet`, `Performance`, `ActorPlayer`, `Reaction`, `AnimClip`, `BedrockAnimations`, `Molang`, `ClipLibrary`...); arquivos (`PathFiles`, `PathJson`, `AnimFiles`); renderização (`RenderPlan`, `RenderPreset`, `FfmpegCommand`, `FfmpegTool`, `FrameWriter`); `Settings`.
- `client/` — `TimelineScreen`, `ActorLanes`, `ActorsScreen`, `ReactionScreen`, `AnimationsScreen`, `RenderScreen` (telas); `CameraDirector`, `ActorManager` (atores no mundo), `ActorRecorder` (gravação), `RenderSession` (renderização), `TimeScuteClient` (teclas e menus).
- `mixin/` — `CameraMixin`/`GameRendererMixin` (câmera, FOV e captura do quadro), `RenderTickCounterMixin` (passo fixo de tick), `IntegratedServerMixin` (congela o servidor), `AbstractClientPlayerEntityMixin` (pele do ator), `BipedEntityModelMixin` (animações nas partes do corpo).

## Limitações conhecidas
- A renderização não captura áudio e usa o tamanho da janela do jogo; não redimensione a janela durante ela.
- Roll da câmera existe no modelo mas não é aplicado (o Minecraft não tem roll nativo).
- A trilha de velocidade (`SpeedTrack`) existe no núcleo e nos arquivos, mas a pista dela está desligada no editor e não afeta a reprodução nem a renderização.
- As guias 3D usam `WorldRenderEvents.LAST`; com shaders podem aparecer com atraso ou sumir.
