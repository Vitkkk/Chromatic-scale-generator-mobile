# Attribution and porting notes

This Android application is a clean Android port inspired by the GPL-3.0 Windows project supplied with the development request.

The original program used Python and wxPython for the desktop interface and Praat through `praat-parselmouth` for pitch analysis, pitch-tier replacement and overlap-add resynthesis.

The Android port does not bundle Praat, Parselmouth or wxPython. Its audio engine is implemented in Java. Version 0.5 follows the publicly documented Praat Manipulation principles: local pitch analysis, voiced/unvoiced intervals, correlation-aligned pulse locations and pitch-synchronous overlap-add. This is an independent implementation rather than copied Praat source code or a bundled Praat binary.

The rest of the audio pipeline performs WAV decoding, conversion to mono/48 kHz, fades, optional normalization and WAV encoding locally on the device.

DirectWave support was implemented from binary analysis of a user-supplied `.dwp` reference file and cross-checking against publicly available open-source format research. The exporter writes a monolithic `DwPr` program with MIDI zones, optional loop points and embedded PCM audio; it does not bundle code or binaries from FL Studio or DirectWave.

Both the original project and this port are distributed under GPL-3.0.
