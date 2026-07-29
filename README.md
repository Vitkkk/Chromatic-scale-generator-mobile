# Chromatic Scale Generator Mobile

Port Android do **Chromatic Scale Generator** para criação de chromatics de FNF diretamente no celular.

## Como usar

1. Crie uma pasta no celular.
2. Coloque os samples WAV nela com nomes sequenciais: `1.wav`, `2.wav`, `3.wav`… sem pular números.
3. Abra o aplicativo e selecione essa pasta.
4. Escolha a nota inicial, oitava, quantidade de notas, duração, gap e demais opções.
5. Toque em **Gerar chromatic.wav**.

O aplicativo detecta o pitch fundamental de cada sample, afina cada nota para a frequência musical desejada, preserva os formantes da voz para evitar o efeito “Alvin”, mantém uma duração configurável e gera um WAV mono PCM 16-bit em 48 kHz. Opcionalmente, também cria a pasta `pitched_samples` com cada nota separada.

## Recursos da versão 0.2

- Seleção de pasta pelo Storage Access Framework do Android, sem permissão ampla de armazenamento.
- Detecção automática de `1.wav`, `2.wav`, `3.wav`…
- Quantidade de samples automática ou manual.
- Nota inicial de C a B e oitavas 1 a 6.
- Quantidade de notas configurável.
- Duração fixa por nota e gap em milissegundos.
- Correção automática de formantes semelhante ao **F-Mode** para manter a voz natural.
- Processamento TD-PSOLA em estágios para mudanças maiores que uma oitava.
- Fade curto para evitar cliques.
- Normalização opcional.
- Exportação da chromatic e dos samples afinados.
- Prévia do WAV gerado dentro do aplicativo.
- Processamento totalmente offline, sem servidor e sem API.

## Como baixar o APK gerado pelo GitHub

1. Abra a aba **Actions** do repositório.
2. Abra a execução mais recente de **Build Android APK**.
3. Em **Artifacts**, baixe `chromatic-scale-generator-debug`.
4. Extraia o ZIP e instale `app-debug.apk` no Android.

## Build local

Requer Java 17, Android SDK 35 e Gradle 8.10.2.

```bash
gradle assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Implementação de áudio

O port substitui Praat/Parselmouth por um motor Java offline que inclui:

- Leitura de WAV PCM 8/16/24/32-bit e IEEE float 32-bit.
- Conversão para mono e 48 kHz.
- Detecção de frequência fundamental baseada em YIN.
- Pitch shifting vocal por TD-PSOLA com preservação de formantes.
- Fallback SOLA para ataques, consoantes e regiões sem periodicidade suficiente.
- Fade, normalização, concatenação e codificação WAV PCM 16-bit.

## Licença

GPL-3.0. O aplicativo original também foi distribuído sob GPL-3.0.
