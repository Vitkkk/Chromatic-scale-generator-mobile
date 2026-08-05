# Attribution and third-party notices

## Original Windows project

This Android application is a port inspired by the GPL-3.0 Windows project supplied with the development request.

The original program uses Python, wxPython and Praat through `praat-parselmouth`. Its pitch path is `To Manipulation`, pitch-tier replacement and overlap-add resynthesis. Praat, Parselmouth and wxPython are not bundled in this Android application.

## Rubber Band Library

Version 0.8 builds and links **Rubber Band Library 4.0.0** from the official Breakfast Quay source repository.

- Project: Rubber Band Library
- Copyright: 2007–2024 Particular Programs Ltd. t/a Breakfast Quay
- Licence: GNU General Public License, version 2 or any later version
- Source tag: `v4.0.0`
- Integration: the official `single/RubberBandSingle.cpp` compilation unit is compiled into `libchromatic_pitch.so` using the Android NDK.

The CMake configuration in this repository pins the upstream version and provides the complete build recipe. The application uses the R3/Finer engine, offline high-quality pitch processing, formant preservation and high-consistency dynamic pitch processing.

## DirectWave format

DirectWave support was implemented from binary analysis of a user-supplied `.dwp` reference file and cross-checking against publicly available open-source format research. The exporter writes a monolithic `DwPr` program with MIDI zones, optional loop points and embedded PCM audio; it does not bundle code or binaries from FL Studio or DirectWave.

The Android application and the supplied source code are distributed under GPL-3.0.
