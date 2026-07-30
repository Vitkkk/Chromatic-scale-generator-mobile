# Chromatic Scale Generator Mobile

Port Android do **Chromatic Scale Generator** para criação de chromatics de FNF diretamente no celular.

## Como usar

1. Crie uma pasta no celular.
2. Coloque os samples WAV nela com nomes sequenciais: `1.wav`, `2.wav`, `3.wav`… sem pular números.
3. Abra o aplicativo e selecione essa pasta.
4. Escolha a nota inicial, oitava, quantidade de notas, duração, gap e demais opções.
5. Toque em **Gerar chromatic.wav**.
6. Depois da geração, ouça o resultado ou toque em **Criar DirectWave (.dwp)**.

O aplicativo analisa pitch e pulsos da voz, afina cada nota por ressíntese pitch-synchronous, mantém uma duração configurável e gera WAV mono PCM 16-bit em 48 kHz. Opcionalmente, também cria a pasta `pitched_samples` com cada nota separada.

## Ataque dinâmico — versão 0.6

A opção **Ataque dinâmico: pitch original → nota** preserva o começo natural do sample antes de entrar na afinação final.

- **Pitch original (ms):** tempo em que o começo do sample permanece sem mudança de pitch.
- **Transição (ms):** crossfade suave do ataque original para a ressíntese afinada.
- Valores padrão: 20 ms de pitch original e 45 ms de transição.
- A opção fica desligada por padrão; assim o comportamento estático da versão 0.5 continua disponível.
- Quando a nota é muito curta, os tempos são reduzidos automaticamente para que o fim sempre chegue à nota correta.

O efeito reproduz de forma controlável a sensação do ataque preservado que aparece na versão de PC com Praat: consoantes e o início vocal mantêm mais da identidade original, enquanto o corpo da nota fica afinado.

## DirectWave monolítico

- Exportação da chromatic inteira ou de um trecho escolhido por índice.
- Cada zona contém PCM 16-bit embutido no próprio `.dwp`.
- A primeira zona se estende até MIDI 0 e a última até MIDI 127.
- Notas fora do range reutilizam a amostra de borda com pitch normal do DirectWave.
- Loop sustentado opcional com início e fim em porcentagem e busca de fase semelhante.

## Recursos

- Android 8.0+.
- Seleção de pasta pelo Storage Access Framework.
- Detecção automática de `1.wav`, `2.wav`, `3.wav`…
- Pitch local e pulsos alinhados por correlação, inspirados no pipeline de Manipulation/overlap-add do Praat.
- Regiões voiced/unvoiced e consoantes preservadas.
- Ataque dinâmico opcional.
- Fade, normalização e duração configuráveis.
- Exportação de samples afinados individuais.
- Prévia do WAV dentro do aplicativo.
- Exportação DirectWave monolítica, ranges MIDI estendidos e loop opcional.
- Processamento totalmente offline, sem servidor e sem API.

## Como baixar o APK pelo GitHub

1. Abra a aba **Actions** do repositório.
2. Abra a execução mais recente de **Build Android APK**.
3. Em **Artifacts**, baixe `chromatic-scale-generator-debug`.
4. Extraia o ZIP e instale `app-debug.apk`.

## Build local

Requer Java 17, Android SDK 35 e Gradle 8.10.2.

```bash
gradle assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Licença

GPL-3.0. O aplicativo original também foi distribuído sob GPL-3.0.
