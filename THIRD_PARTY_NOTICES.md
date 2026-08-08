# Attribution and third-party notices

## Original Windows project

This Android application is a GPL-3.0 port of the original **Chromatic Scale Generator** desktop project recovered from its distributed source package.

The original program is written in Python with wxPython and uses Praat through `praat-parselmouth`. Its pitch path is:

- resample to 48 kHz;
- convert to mono;
- `To Manipulation` with `0.05`, `60`, `600`;
- extract the PitchTier;
- apply the target-frequency formula;
- replace the PitchTier;
- overlap-add resynthesis.

The Android UI does not bundle Python or wxPython. The native audio library calls the corresponding Praat C/C++ functions directly.

## Parselmouth and Praat

Version 0.10 builds from the official **Parselmouth 0.4.1** source tag, which contains **Praat 6.1.38**.

- Parselmouth project: Yannick Jadoul and contributors
- Source tag: `v0.4.1`
- Praat authors: Paul Boersma and David Weenink
- Parselmouth licence: GNU General Public License, version 3 or later
- Praat licence: GNU General Public License, version 2 or later
- Integration: Praat is compiled by the Android NDK into `libchromatic_pitch.so`

The Android build excludes only the unused desktop `sendpraat` IPC helper because it depends on X11 and is unrelated to Sound, Manipulation, PitchTier or resynthesis. The phonetics and audio engine used by the original application remains the upstream Praat implementation.

## DirectWave format

DirectWave support was implemented from binary analysis of a user-supplied `.dwp` reference file and cross-checking against publicly available open-source format research. The exporter writes a monolithic `DwPr` program with MIDI zones, optional loop points and embedded PCM audio; it does not bundle code or binaries from FL Studio or DirectWave.

The Android application and the supplied source code are distributed under GPL-3.0.
