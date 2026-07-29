# Attribution and porting notes

This Android application is a clean Android port inspired by the GPL-3.0 Windows project supplied with the development request.

The original program used Python and wxPython for the desktop interface and Praat through `praat-parselmouth` for pitch detection, pitch-tier replacement and resynthesis.

The Android port does not bundle Praat, Parselmouth or wxPython. Its audio engine is implemented in Java and performs WAV decoding, fundamental-frequency estimation, pitch resampling, SOLA-style duration compensation, fades, normalization and WAV encoding locally on the device.

Both the original project and this port are distributed under GPL-3.0.
