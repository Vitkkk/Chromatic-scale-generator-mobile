#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <mutex>
#include <stdexcept>
#include <vector>

#include "Manipulation.h"
#include "PitchTier.h"
#include "RealTier.h"
#include "Sound.h"
#include "melder.h"

namespace {

constexpr double kDesktopSampleRate = 48000.0;
constexpr double kManipulationTimeStep = 0.05;
constexpr double kPitchFloorHz = 60.0;
constexpr double kPitchCeilingHz = 600.0;

std::once_flag gPraatInit;

void initialisePraat() {
    std::call_once(gPraatInit, [] {
        Melder_init();
        Melder_batch = true;
        Melder_backgrounding = true;
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
    // chromatic_gen.py executes Formula on the extracted PitchTier. Formula
    // replaces the value of every existing voiced point; it does not create a
    // pitch track in unvoiced regions.
    for (integer i = 1; i <= pitchTier->points.size; ++i) {
        RealPoint point = pitchTier->points.at[i];
        point->value = targetFrequency;
    }
}

void applyDynamicFormula(PitchTier pitchTier,
                         double targetFrequency,
                         double holdSeconds,
                         double glideSeconds) {
    // Mobile-only extension. It edits the same Praat PitchTier rather than
    // crossfading two waveforms, so only one resynthesis is ever audible.
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

std::vector<float> copyIntoConfiguredSlot(Sound sound, std::size_t targetLength) {
    // The PC generator does not time-stretch samples at all. Preserve the
    // exact Praat output and only fit it into the mobile app's configured slot:
    // trim if the slot is shorter, zero-pad if it is longer. No second pitch or
    // duration algorithm is applied after overlap-add.
    std::vector<float> output(targetLength, 0.0f);
    const std::size_t available = static_cast<std::size_t>(std::max<integer>(0, sound->nx));
    const std::size_t count = std::min(targetLength, available);
    for (std::size_t i = 0; i < count; ++i) {
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
        jint targetLength,
        jint holdSamples,
        jint glideSamples) {
    try {
        if (inputArray == nullptr || sourceSampleRate < 8000 || sourceSampleRate > 192000
                || targetLength <= 0 || !std::isfinite(targetFrequency)
                || targetFrequency <= 0.0) {
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
        std::vector<float> output = copyIntoConfiguredSlot(
                processed.get(), static_cast<std::size_t>(targetLength));

        jfloatArray result = env->NewFloatArray(targetLength);
        if (result == nullptr) throw std::runtime_error("Sem memória para criar o áudio final.");
        env->SetFloatArrayRegion(result, 0, targetLength, output.data());
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
