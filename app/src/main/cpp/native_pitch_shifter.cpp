#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <mutex>
#include <stdexcept>
#include <vector>

#include "DurationTier.h"
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
constexpr double kMaximumDurationStage = 2.80;

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

double durationSeconds(Sound sound) {
    return sound->xmax - sound->xmin;
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
    // Exact desktop commands:
    //   Resample, 48000, 1
    //   Convert to mono
    autoSound resampled = Sound_resample(source.get(), kDesktopSampleRate, 1);
    return Sound_convertToMono(resampled.get());
}

void setConstantDuration(Manipulation manipulation, double factor) {
    factor = std::max(0.01, std::min(kMaximumDurationStage, factor));
    autoDurationTier tier = DurationTier_create(manipulation->xmin, manipulation->xmax);
    RealTier_addPoint(tier.get(), manipulation->xmin, factor);
    RealTier_addPoint(tier.get(), manipulation->xmax, factor);
    Manipulation_replaceDurationTier(manipulation, tier.get());
}

void setPitchTier(Manipulation manipulation,
                  double targetFrequency,
                  double holdSeconds,
                  double glideSeconds) {
    if (!manipulation->pitch) {
        throw std::runtime_error("O Praat não criou um PitchTier para este sample.");
    }

    for (integer i = 1; i <= manipulation->pitch->points.size; ++i) {
        RealPoint point = manipulation->pitch->points.at[i];
        if (holdSeconds <= 0.0 && glideSeconds <= 0.0) {
            // Exact equivalent of the desktop command:
            // Formula: <constant target frequency>
            point->value = targetFrequency;
            continue;
        }

        const double originalFrequency = point->value;
        const double time = point->number - manipulation->xmin;
        if (time <= holdSeconds) {
            continue;  // Preserve the PitchTier produced by To Manipulation.
        }
        if (glideSeconds <= 0.0 || time >= holdSeconds + glideSeconds) {
            point->value = targetFrequency;
            continue;
        }

        double position = (time - holdSeconds) / glideSeconds;
        position = std::max(0.0, std::min(1.0, position));
        const double smooth = position * position * (3.0 - 2.0 * position);
        // Interpolate in musical/log-frequency space, within Praat's PitchTier.
        point->value = std::exp(
                std::log(std::max(1.0, originalFrequency)) * (1.0 - smooth)
                + std::log(targetFrequency) * smooth);
    }
}

autoSound resynthesizeOneStage(Sound source,
                                double durationFactor,
                                bool replacePitch,
                                double targetFrequency,
                                double holdSeconds,
                                double glideSeconds) {
    // Exact desktop analysis and synthesis core:
    //   To Manipulation, 0.05, 60, 600
    //   Replace pitch tier
    //   Get resynthesis (overlap-add)
    autoManipulation manipulation = Sound_to_Manipulation(
            source, kManipulationTimeStep, kPitchFloorHz, kPitchCeilingHz);

    if (replacePitch) {
        setPitchTier(manipulation.get(), targetFrequency, holdSeconds, glideSeconds);
    }
    setConstantDuration(manipulation.get(), durationFactor);
    return Manipulation_to_Sound(manipulation.get(), Manipulation_OVERLAPADD);
}

autoSound processWithPraat(Sound prepared,
                           double targetFrequency,
                           double targetDuration,
                           double holdSeconds,
                           double glideSeconds) {
    autoSound current = Data_copy(prepared);

    // Praat 6.1.38 allocates at most 3x the source length in one duration pass.
    // For larger stretches, perform pitch-preserving Praat duration passes first.
    // The actual target pitch is applied exactly once, in the final pass.
    while (targetDuration / durationSeconds(current.get()) > kMaximumDurationStage) {
        current = resynthesizeOneStage(
                current.get(), kMaximumDurationStage, false,
                targetFrequency, 0.0, 0.0);
    }

    const double finalFactor = targetDuration / durationSeconds(current.get());
    return resynthesizeOneStage(
            current.get(), finalFactor, true,
            targetFrequency, holdSeconds, glideSeconds);
}

std::vector<float> copyExactLength(Sound sound, std::size_t targetLength) {
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
        const double targetDuration = targetLength / kDesktopSampleRate;
        const double holdSeconds = std::max(0, holdSamples) / kDesktopSampleRate;
        const double glideSeconds = std::max(0, glideSamples) / kDesktopSampleRate;
        autoSound processed = processWithPraat(
                prepared.get(), targetFrequency, targetDuration,
                holdSeconds, glideSeconds);
        std::vector<float> output = copyExactLength(
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
