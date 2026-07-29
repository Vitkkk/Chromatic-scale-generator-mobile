# Chromatic Scale Generator Mobile

Port Android do **Chromatic Scale Generator** para criação de chromatics de FNF diretamente no celular.

## Como usar

1. Crie uma pasta no celular.
2. Coloque os samples WAV nela com nomes sequenciais: `1.wav`, `2.wav`, `3.wav`… sem pular números.
3. Abra o aplicativo e selecione essa pasta.
4. Escolha a nota inicial, oitava, quantidade de notas, duração, gap e demais opções.
5. Toque em **Gerar chromatic.wav**.
6. Depois da geração, ouça o resultado ou toque em **Criar DirectWave (.dwp)**.

O aplicativo detecta o pitch fundamental de cada sample, afina cada nota para a frequência musical desejada, preserva os formantes da voz, mantém uma duração configurável e gera um WAV mono PCM 16-bit em 48 kHz. Opcionalmente, também cria a pasta `pitched_samples` com cada nota separada.

## Ressíntese vocal da versão 0.5

A versão de PC fornecida usa Praat/Parselmouth com o fluxo `To Manipulation → Replace pitch tier → Get resynthesis (overlap-add)`. A versão 0.5 aproxima esse comportamento com um novo motor Java offline:

- análise de pitch local ao longo do sample, em vez de uma frequência única para o arquivo inteiro;
- separação de trechos voiced e unvoiced;
- localização dos pulsos glóticos por correlação cruzada, seguindo o princípio usado pelo Praat;
- overlap-add com janelas limitadas pelos períodos locais da fonte e da nota de destino;
- consoantes e regiões não periódicas preservadas pela trilha original;
- shifts grandes feitos diretamente a partir do sample original, sem várias etapas que acumulam artefatos;
- análise pesada realizada uma vez por sample e reutilizada em todas as notas da chromatic.

Isso reduz o som granulado, metálico ou “estragado” que aparecia no TD-PSOLA simplificado anterior.

## DirectWave monolítico

O aplicativo transforma o WAV recém-gerado em um instrumento `.dwp` para DirectWave no FL Studio Mobile e no FL Studio para PC.

- **Chromatic inteira:** cria uma zona para cada nota gerada.
- **Trecho personalizado:** escolha a primeira e a última nota pelo índice dentro da chromatic, começando em 1.
- Dentro do range, cada zona continua associada exatamente à própria tecla MIDI.
- A primeira zona se estende até a nota MIDI 0. Notas abaixo do range reutilizam o primeiro sample com pitch normal de sampler.
- A última zona se estende até a nota MIDI 127. Notas acima do range reutilizam o último sample com pitch normal de sampler.
- O gap entre as notas não é incluído nos samples.
- O áudio PCM 16-bit fica embutido dentro do próprio `.dwp`; não é necessário transportar uma pasta de WAVs junto.
- O arquivo é salvo na mesma pasta selecionada para a chromatic.

Exemplo: em uma chromatic de 24 notas, escolher `5` como primeira e `12` como última gera um DWP com oito zonas. A quinta nota da chromatic cobre também as teclas abaixo dela, e a décima segunda cobre as teclas acima dela.

## Loop DirectWave

A opção **Ativar loop sustentado no DWP** grava pontos de loop em cada zona.

- O início e o fim são configurados em porcentagem da duração da nota.
- Os valores padrão são 35% e 90%.
- O início é aproximado para um cruzamento ascendente por zero.
- O fim é procurado ao redor do valor escolhido para encontrar uma fase parecida com a região inicial, reduzindo estalos na repetição.
- O loop pode ser desligado para manter o comportamento seco.

O loop funciona melhor quando o trecho escolhido tem volume e timbre relativamente estáveis. Samples com fala muito curta, consoantes fortes ou grande mudança de timbre podem precisar de ajustes nos percentuais.

## Recursos da versão 0.5

- Seleção de pasta pelo Storage Access Framework do Android, sem permissão ampla de armazenamento.
- Detecção automática de `1.wav`, `2.wav`, `3.wav`…
- Quantidade de samples automática ou manual.
- Nota inicial de C a B e oitavas 1 a 6.
- Quantidade de notas configurável.
- Duração fixa por nota e gap em milissegundos.
- Ressíntese pitch-synchronous inspirada no pipeline Manipulation/overlap-add do Praat.
- Contorno de pitch local e pulsos por correlação cruzada.
- Preservação de formantes sem processamento em múltiplas etapas.
- Fade curto para evitar cliques.
- Normalização opcional.
- Exportação da chromatic e dos samples afinados.
- Prévia do WAV gerado dentro do aplicativo.
- Exportação DirectWave `.dwp` monolítica, completa ou por intervalo.
- Extensão automática das zonas de borda por todo o teclado MIDI.
- Loop DirectWave opcional com alinhamento automático de fase.
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

- Leitura de WAV PCM 8/16/24/32-bit e IEEE float 32-bit.
- Conversão para mono e 48 kHz.
- Detecção inicial da frequência fundamental baseada em YIN.
- Rastreamento local de pitch e decisão voiced/unvoiced.
- Pulsos pitch-synchronous encontrados por correlação cruzada.
- Ressíntese overlap-add direta a partir da forma de onda original.
- Fade, normalização, concatenação e codificação WAV PCM 16-bit.
- Escrita do formato DirectWave `DwPr` com zonas MIDI, pontos de loop e samples PCM embutidos.

## Licença

GPL-3.0. O aplicativo original também foi distribuído sob GPL-3.0.
