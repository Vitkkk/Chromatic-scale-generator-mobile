#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <vector>

#include "Manipulation.h"
#include "PitchTier.h"
#include "RealTier.h"
#include "Sound.h"
#include "praat.h"

// Parselmouth calls this global initializer through INCLUDE_LIBRARY.
// Declare it outside the anonymous namespace so the linker resolves the
// upstream Praat symbol rather than looking for a private namespaced copy.
extern void praat_uvafon_init();

namespace {

constexpr double kDesktopSampleRate = 48000.0;
constexpr double kManipulationTimeStep = 0.05;
constexpr double kPitchFloorHz = 60.0;
constexpr double kPitchCeilingHz = 600.0;

std::once_flag gPraatInit;

void initialisePraat() {
    std::call_once(gPraatInit, [] {
        // Same initialization sequence used by Parselmouth 0.4.1 before it
        // exposes the Praat commands to Python.
        praatlib_init();
        praat_uvafon_init();
        praat_testPlatformAssumptions();
    });
}

void throwJava(JNIEnv *env, const char *className, const char *message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) env->ThrowNew(type, message);
}

autoSound createSound(const std::vector<float> &input, int sampleRate) {
    const double duration = input.size() / static_cast<double>(sampleRate);
    autoSound sound = Sound_createSimple(1, duration, static_cast<double>(sampleRate));
    const integer count = std::min<integer>(sound->nx, static_cast<integer>(input.size()));
    for (integer i = 1; i <= count; ++i) {
        const float sample = input[static_cast<std::size_t>(i - 1)];
        sound->z[1][i] = std::isfinite(sample) ? sample : 0.0;
    }
    return sound;
}

autoSound prepareExactlyLikeDesktop(const std::vector<float> &input, int sampleRate) {
    autoSound source = createSound(input, sampleRate);
    // chromatic_gen.py:
    //   praat.call(sound, "Resample", 48000, 1)
    //   praat.call(resampled, "Convert to mono")
    autoSound resampled = Sound_resample(source.get(), kDesktopSampleRate, 1);
    return Sound_convertToMono(resampled.get());
}

void applyDesktopFormula(PitchTier pitchTier, double targetFrequency) {
    // Praat's Formula command replaces every existing voiced PitchTier point.
    // It intentionally does not invent pitch points in unvoiced regions.
    for (integer i = 1; i <= pitchTier->points.size; ++i) {
        pitchTier->points.at[i]->value = targetFrequency;
    }
}

void applyDynamicFormula(PitchTier pitchTier,
                         double targetFrequency,
                         double holdSeconds,
                         double glideSeconds) {
    // Mobile-only extension, applied inside the same Praat PitchTier. There is
    // still only one overlap-add resynthesis and no waveform crossfade.
    for (integer i = 1; i <= pitchTier->points.size; ++i) {
        RealPoint point = pitchTier->points.at[i];
        const double relativeTime = point->number - pitchTier->xmin;
        if (relativeTime <= holdSeconds) continue;
        if (glideSeconds <= 0.0 || relativeTime >= holdSeconds + glideSeconds) {
            point->value = targetFrequency;
            continue;
        }

        double position = (relativeTime - holdSeconds) / glideSeconds;
        position = std::max(0.0, std::min(1.0, position));
        const double smooth = position * position * (3.0 - 2.0 * position);
        const double original = std::max(1.0, point->value);
        point->value = std::exp(
                std::log(original) * (1.0 - smooth)
                + std::log(targetFrequency) * smooth);
    }
}

autoSound resynthesizeExactlyLikeDesktop(Sound prepared,
                                          double targetFrequency,
                                          double holdSeconds,
                                          double glideSeconds) {
    // Literal equivalent of chromatic_gen.py:
    //   To Manipulation, 0.05, 60, 600
    //   Extract pitch tier
    //   Formula: constant target frequency
    //   Replace pitch tier
    //   Get resynthesis (overlap-add)
    autoManipulation manipulation = Sound_to_Manipulation(
            prepared, kManipulationTimeStep, kPitchFloorHz, kPitchCeilingHz);
    if (!manipulation->pitch) {
        throw std::runtime_error("O Praat não criou um PitchTier para este sample.");
    }

    autoPitchTier extractedPitch = Data_copy(manipulation->pitch.get());
    if (holdSeconds > 0.0 || glideSeconds > 0.0) {
        applyDynamicFormula(extractedPitch.get(), targetFrequency, holdSeconds, glideSeconds);
    } else {
        applyDesktopFormula(extractedPitch.get(), targetFrequency);
    }
    Manipulation_replacePitchTier(manipulation.get(), extractedPitch.get());
    return Manipulation_to_Sound(manipulation.get(), Manipulation_OVERLAPADD);
}

std::vector<float> copyNaturalDuration(Sound sound) {
    const std::size_t length = static_cast<std::size_t>(std::max<integer>(0, sound->nx));
    if (length == 0) throw std::runtime_error("O Praat retornou um sample vazio.");

    std::vector<float> output(length);
    for (std::size_t i = 0; i < length; ++i) {
        const double sample = sound->z[1][static_cast<integer>(i + 1)];
        output[i] = std::isfinite(sample) ? static_cast<float>(sample) : 0.0f;
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_chromatic_audio_NativePitchShifter_nativeShift(
        JNIEnv *env,
        jclass,
        jfloatArray inputArray,
        jint sourceSampleRate,
        jdouble targetFrequency,
        jint holdSamples,
        jint glideSamples) {
    try {
        if (inputArray == nullptr || sourceSampleRate < 8000 || sourceSampleRate > 192000
                || !std::isfinite(targetFrequency) || targetFrequency <= 0.0) {
            throw std::invalid_argument("Parâmetros inválidos para o motor Praat.");
        }

        initialisePraat();

        const jsize inputLength = env->GetArrayLength(inputArray);
        if (inputLength <= 0) throw std::invalid_argument("O sample está vazio.");

        std::vector<float> input(static_cast<std::size_t>(inputLength));
        env->GetFloatArrayRegion(inputArray, 0, inputLength, input.data());

        autoSound prepared = prepareExactlyLikeDesktop(input, sourceSampleRate);
        const double holdSeconds = std::max(0, holdSamples) / kDesktopSampleRate;
        const double glideSeconds = std::max(0, glideSamples) / kDesktopSampleRate;
        autoSound processed = resynthesizeExactlyLikeDesktop(
                prepared.get(), targetFrequency, holdSeconds, glideSeconds);
        std::vector<float> output = copyNaturalDuration(processed.get());

        if (output.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            throw std::runtime_error("O sample resultante ficou grande demais.");
        }
        const jsize outputLength = static_cast<jsize>(output.size());
        jfloatArray result = env->NewFloatArray(outputLength);
        if (result == nullptr) throw std::runtime_error("Sem memória para criar o áudio final.");
        env->SetFloatArrayRegion(result, 0, outputLength, output.data());
        return result;
    } catch (MelderError &) {
        Melder_clearError();
        throwJava(env, "java/lang/IllegalStateException",
                  "O Praat 6.1.38 não conseguiu ressintetizar este sample.");
        return nullptr;
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException",
                  "Falha desconhecida no motor Praat 6.1.38.");
        return nullptr;
    }
}
