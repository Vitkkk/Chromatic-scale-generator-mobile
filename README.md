# Chromatic Scale Generator Mobile

Port Android do **Chromatic Scale Generator** para criação de chromatics de FNF diretamente no celular.

## Como usar

1. Crie uma pasta no celular.
2. Coloque os samples WAV nela com nomes sequenciais: `1.wav`, `2.wav`, `3.wav`… sem pular números.
3. Abra o aplicativo e selecione essa pasta.
4. Escolha a nota inicial, oitava, quantidade de notas, duração, gap e demais opções.
5. Para reproduzir o comportamento do PC, mantenha **Duração da nota = 0**, fade 0, normalização desligada e ataque dinâmico desligado.
6. Toque em **Gerar chromatic.wav**.
7. Depois da geração, ouça o resultado ou toque em **Criar DirectWave (.dwp)**.

## Motor de pitch — versão 0.9

A versão de PC usa `praat-parselmouth`. O aplicativo Android agora compila nativamente a mesma geração do motor usada pelo programa original: **Parselmouth 0.4.1 / Praat 6.1.38**.

O caminho estático reproduz o `chromatic_gen.py` original:

1. Carrega o WAV completo, sem detector de F0 externo e sem cortar silêncio.
2. Reamostra pelo próprio Praat para 48 kHz com precisão 1.
3. Converte para mono pelo próprio Praat.
4. Executa `To Manipulation` com `timeStep = 0.05`, pitch mínimo de 60 Hz e máximo de 600 Hz.
5. Extrai o `PitchTier` criado pelo Praat.
6. Aplica a mesma fórmula de frequência do aplicativo de PC a todos os pontos vozeados.
7. Recoloca o `PitchTier` na `Manipulation`.
8. Executa uma única ressíntese `overlap-add`.
9. Mantém a duração natural devolvida pelo Praat.

Não há Rubber Band, PSOLA Java, `DurationTier`, múltiplas passagens, crossfade entre pitches nem mistura do WAV original no resultado estático.

### Duração da nota

- **0 ms:** modo fiel ao PC. Cada nota mantém exatamente a duração natural retornada pelo Praat.
- **40–10000 ms:** extensão opcional mobile aplicada somente depois da ressíntese original.
- Ao encurtar, o final é cortado.
- Ao alongar, o aplicativo usa uma região de sustain com loop e crossfade, preservando a frequência já gerada pelo Praat em vez de analisar ou mudar o pitch outra vez.

O padrão é **0 ms**. Assim, o alongador opcional não participa do teste de estabilidade e tonalidade do motor original.

## Ataque dinâmico

A opção **Ataque dinâmico: pitch original → nota** continua disponível como extensão mobile.

- **Pitch original (ms):** preserva inicialmente os pontos originais do `PitchTier` criado pelo Praat.
- **Transição (ms):** move esses mesmos pontos suavemente até a frequência final.
- O áudio passa por uma única ressíntese overlap-add.
- Não existe crossfade entre duas waveforms nem um segundo motor de pitch.
- A opção continua desligada por padrão.

## DirectWave monolítico

- Exportação da chromatic inteira ou de um trecho escolhido por índice.
- O exportador usa o offset e a duração reais de cada nota, inclusive no modo de duração natural variável.
- Cada zona contém PCM 16-bit embutido no próprio `.dwp`.
- A primeira zona se estende até MIDI 0 e a última até MIDI 127.
- Notas fora do range reutilizam a amostra de borda com pitch normal do DirectWave.
- Loop sustentado opcional com início e fim em porcentagem e busca de fase semelhante.

## Recursos

- Android 8.0+.
- Seleção de pasta pelo Storage Access Framework.
- Detecção automática de `1.wav`, `2.wav`, `3.wav`…
- WAV mono PCM 16-bit em 48 kHz.
- Motor Praat/Parselmouth do aplicativo original.
- Duração natural por padrão e alongamento opcional separado.
- Fade e normalização opcionais, ambos desativados por padrão.
- Exportação de samples afinados individuais.
- Prévia do WAV dentro do aplicativo.
- Exportação DirectWave monolítica, ranges MIDI estendidos e loop opcional.
- Processamento totalmente local, sem servidor e sem API.

## Como baixar o APK pelo GitHub

1. Abra a aba **Actions** do repositório.
2. Abra a execução mais recente de **Build Android APK**.
3. Em **Artifacts**, baixe `chromatic-scale-generator-debug`.
4. Extraia o ZIP e instale `app-debug.apk`.

## Build local

Requer Java 17, Android SDK 35, Android NDK 27.2.12479018, CMake 3.22.1 e Gradle 8.10.2. A configuração baixa a fonte oficial fixada do Parselmouth 0.4.1, incluindo o Praat 6.1.38, durante o primeiro build.

```bash
gradle assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Licença

GPL-3.0. O aplicativo original e o Parselmouth são distribuídos sob GPL; consulte `THIRD_PARTY_NOTICES.md`.
