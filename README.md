# Chromatic Scale Generator Mobile

Port Android do **Chromatic Scale Generator** para criação de chromatics de FNF diretamente no celular.

## Como usar

1. Crie uma pasta no celular.
2. Coloque os samples WAV nela com nomes sequenciais: `1.wav`, `2.wav`, `3.wav`… sem pular números.
3. Abra o aplicativo e selecione essa pasta.
4. Escolha a nota inicial, oitava, quantidade de notas, duração, gap e demais opções.
5. Toque em **Gerar chromatic.wav**.
6. Depois da geração, ouça o resultado ou toque em **Criar DirectWave (.dwp)**.

## Motor de pitch — versão 0.8

A geração das notas usa o **Rubber Band Library 4.0.0**, compilado nativamente no APK pelo Android NDK.

- Motor R3/Finer em processamento offline para as notas estáticas.
- Preservação de formantes para manter a identidade vocal.
- Pitch e mudança de duração são processados juntos pelo mesmo motor.
- Não existe mistura do áudio original dentro da vogal afinada.
- O antigo PSOLA Java não participa mais da geração final.
- Bibliotecas incluídas para `arm64-v8a`, `armeabi-v7a` e `x86_64`.
- Segmentos ELF alinhados para páginas de memória de 16 KB.

Essa troca foi feita para eliminar period doubling, sub-harmônicos fantasmas e quedas instáveis de pitch que ainda podiam aparecer no motor artesanal.

## Ataque dinâmico

A opção **Ataque dinâmico: pitch original → nota** agora usa uma mudança contínua de pitch dentro do próprio motor nativo.

- **Pitch original (ms):** tempo inicial em escala 1×.
- **Transição (ms):** glide suave e logarítmico até a nota final.
- Valores padrão: 20 ms de pitch original e 45 ms de transição.
- Não é mais feito crossfade entre duas waveforms com pitches diferentes.
- A opção continua desligada por padrão.

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
- WAV mono PCM 16-bit em 48 kHz.
- Fade, normalização e duração configuráveis.
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

Requer Java 17, Android SDK 35, Android NDK 27.2.12479018, CMake 3.22.1 e Gradle 8.10.2. A configuração baixa a fonte oficial fixada do Rubber Band 4.0.0 durante o primeiro build.

```bash
gradle assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Licença

GPL-3.0. O aplicativo original também foi distribuído sob GPL-3.0. O Rubber Band Library é distribuído sob GPL-2.0-or-later; consulte `THIRD_PARTY_NOTICES.md`.
