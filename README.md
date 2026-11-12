# TimeScute — editor de câmera cinematográfica (Fabric 1.20.1)

Reescrita do zero, focada em uma timeline simples. Código 100% próprio (não usa código do Replay Mod).

## Como compilar
Precisa de JDK 17 e Gradle.
1. Na pasta do projeto: `gradle wrapper --gradle-version 8.8` (gera o `gradlew`).
2. `./gradlew build` → o mod fica em `build/libs/timescute-0.2.0.jar`.
3. Coloque o jar em `mods/` junto com o **Fabric API** (1.20.1).
4. Testes do núcleo (sem Minecraft): `./gradlew coreTests`.

Se algo não compilar (nomes de API do Yarn), me mande a mensagem de erro que eu corrijo.

### Compilar pelo GitHub (sem instalar nada)
O projeto já tem `.github/workflows/build.yml`. Suba a pasta para um repositório no GitHub e a cada push o Actions compila, roda os testes do núcleo e guarda o jar:
1. Aba **Actions** → clique na execução → **Artifacts** → baixe `timescute-jar`.
2. Para gerar uma release com o jar anexado, crie uma tag que comece com `v` (ex.: `git tag v0.2.0 && git push --tags`).
3. Também dá para rodar manualmente: Actions → build → **Run workflow**.

## Como usar
| Tecla | Ação |
|---|---|
| **K** | Abre o editor (dentro dele, K captura um keyframe) |
| **J** | Captura keyframe sem abrir o editor; playhead avança 2 s |
| **H** | Reproduz/pausa o trajeto sem abrir o editor |

Todas as teclas podem ser mudadas em Controles → TimeScute.

**Dentro do editor**
- Clique num keyframe: seleciona e mostra aquela tomada. Shift+clique: soma/remove da seleção.
- Arraste um keyframe (ou vários): move no tempo. Vermelho = posição inválida.
- Arraste no vazio: seleção em caixa. Clique simples no vazio: move o playhead.
- Clique/arraste na régua: scrub. Roda: zoom no cursor. Shift+Roda ou botão do meio: rolar.
- Botão direito num keyframe, ou os botões da direita: muda a interpolação (Suave, Linear, Acelera/Freia, Parado).
- Espaço: play/pausa. Del: apagar. Ctrl+Z / Ctrl+Y: desfazer/refazer (ilimitado). Ctrl+A: selecionar tudo.
- ←/→: keyframe anterior/próximo. Shift+←/→: ±100 ms. Home/End: início/fim.

### Novidades da fase 2
**Guias no mundo 3D** (botão *Guias*): a linha do trajeto, colorida pela interpolação de cada trecho, e uma pirâmide de câmera em cada keyframe (a pirâmide branca é a selecionada; o triângulo mostra qual lado é "cima"). O FOV de cada keyframe muda a abertura da pirâmide.

**Salvar e abrir** — arquivos `.json` em `config/timescute/paths/`:
- *Salvar* (Ctrl+S) salva no nome atual; na primeira vez, ou com Ctrl+Shift+S, pede um nome. Avisa antes de substituir um arquivo existente.
- *Abrir* (Ctrl+O) lista os trajetos do mais recente ao mais antigo. Clique para abrir; *Excluir* pede confirmação.
- *Novo* (Ctrl+N) e *Abrir* são desfazíveis com Ctrl+Z.
- **Autosave**: o trajeto é salvo sozinho ao fechar o editor, ao capturar com J e ao fechar o jogo, e volta quando você abre o Minecraft de novo.
- Arquivos corrompidos ou de versão futura dão uma mensagem clara em vez de travar.

**Trilha de velocidade** (botão *Velocidade*): controla a câmera lenta/rápida do replay (0.1x a 4x). Isso só tem efeito visível na fase 4 (reprodução); por enquanto você já pode desenhar e salvar a curva.
- Duplo clique no vazio: cria um ponto. Arraste: muda tempo e valor. Botão direito ou Del: apaga.
- A escala é logarítmica (0.5x e 2x ficam à mesma distância de 1x). Com *Snap*, os valores grudam em 0.25x, 0.5x, 1x, 2x e 4x.
- O valor no playhead aparece na lateral esquerda da trilha.

**Barra de ferramentas adaptável**: se a janela for estreita (GUI scale alto), os botões quebram em mais de uma linha em vez de se sobrepor.

## O que foi simplificado em relação ao Replay Mod
- **Um tipo só de keyframe**: posição + rotação + FOV juntos. Sem keyframes de "tempo" e "posição" separados.
- A cor da faixa entre keyframes mostra a interpolação, então dá para ler o trajeto de relance.
- Desfazer/refazer em tudo, snap à grade, zoom/pan fluidos, dicas ao passar o mouse e atalhos sempre visíveis.

## Estrutura
- `core/` — Java puro e testado: `Timeline`, `CameraPath`, `Keyframe`, `Easing`, `TimelineView` (zoom/grade/hit-test).
- `client/` — `TimelineScreen` (UI), `CameraDirector` (estado/reprodução), `TimeScuteClient` (teclas).
- `mixin/` — sobrescreve câmera e FOV durante preview/reprodução.

## Limitações conhecidas
- Ainda não grava nem reproduz replays: a câmera capturada é a do próprio jogador, e a trilha de velocidade ainda não afeta nada visível.
- Roll da câmera existe no modelo mas ainda não é aplicado (o Minecraft não tem roll nativo).
- As guias 3D usam o evento `WorldRenderEvents.LAST`; se aparecerem com atraso ou sumirem com shaders, me avise.

## Roteiro
1. **Fase 1** ✔ timeline + keyframes + preview.
2. **Fase 2 (esta)** ✔ salvar/carregar trajetos (JSON); linha do trajeto e câmeras no mundo; trilha de velocidade.
3. **Fase 3**: gravação própria (captura de pacotes do servidor + formato de arquivo próprio) e lista de replays com miniatura.
4. **Fase 4**: reprodução (mundo simulado, câmera livre, controle de velocidade, HUD limpa).
5. **Fase 5**: renderização para vídeo (captura de frames + FFmpeg), com painel de configurações simples e presets.
