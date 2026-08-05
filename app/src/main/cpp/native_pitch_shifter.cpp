#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <exception>
#include <stdexcept>
#include <vector>

#include <rubberband/RubberBandStretcher.h>

using RubberBand::RubberBandStretcher;

namespace {

constexpr std::size_t kOfflineBlock = 1024;
constexpr std::size_t kDynamicBlock = 128;

void throwJava(JNIEnv *env, const char *className, const char *message) {
    jclass type = env->FindClass(className);
    if (type != nullptr) env->ThrowNew(type, message);
}

double clampRatio(double value, double minimum, double maximum) {
    if (!std::isfinite(value)) return 1.0;
    return std::max(minimum, std::min(maximum, value));
}

float cubicSample(const std::vector<float> &input, double position) {
    if (input.empty()) return 0.0f;
    if (input.size() == 1) return input.front();

    int index = static_cast<int>(std::floor(position));
    double t = position - index;
    auto sample = [&](int i) -> double {
        i = std::max(0, std::min(static_cast<int>(input.size()) - 1, i));
        return input[static_cast<std::size_t>(i)];
    };

    double p0 = sample(index - 1);
    double p1 = sample(index);
    double p2 = sample(index + 1);
    double p3 = sample(index + 2);
    double a0 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;
    double a1 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
    double a2 = -0.5 * p0 + 0.5 * p2;
    double a3 = p1;
    return static_cast<float>(((a0 * t + a1) * t + a2) * t + a3);
}

std::vector<float> fitExactly(const std::vector<float> &input, std::size_t targetLength) {
    if (targetLength == 0) return {};
    if (input.empty()) return std::vector<float>(targetLength, 0.0f);
    if (input.size() == targetLength) return input;
    if (targetLength == 1) return {input.front()};

    std::vector<float> output(targetLength);
    double scale = (input.size() - 1.0) / (targetLength - 1.0);
    for (std::size_t i = 0; i < targetLength; ++i) {
        output[i] = cubicSample(input, i * scale);
    }
    return output;
}

void appendAvailable(RubberBandStretcher &stretcher,
                     std::vector<float> &output,
                     int &dropSamples,
                     std::size_t blockSize) {
    std::vector<float> buffer(blockSize);
    float *channels[1] = {buffer.data()};

    while (true) {
        int available = stretcher.available();
        if (available <= 0) break;
        std::size_t count = std::min<std::size_t>(static_cast<std::size_t>(available), blockSize);
        std::size_t received = stretcher.retrieve(channels, count);
        if (received == 0) break;

        std::size_t start = 0;
        if (dropSamples > 0) {
            std::size_t drop = std::min<std::size_t>(received, static_cast<std::size_t>(dropSamples));
            dropSamples -= static_cast<int>(drop);
            start = drop;
        }
        output.insert(output.end(), buffer.begin() + static_cast<std::ptrdiff_t>(start),
                      buffer.begin() + static_cast<std::ptrdiff_t>(received));
    }
}

std::vector<float> processOffline(const std::vector<float> &input,
                                  int sampleRate,
                                  double pitchScale,
                                  std::size_t targetLength) {
    double timeRatio = targetLength / static_cast<double>(input.size());
    int options = RubberBandStretcher::OptionProcessOffline
            | RubberBandStretcher::OptionEngineFiner
            | RubberBandStretcher::OptionFormantPreserved
            | RubberBandStretcher::OptionPitchHighQuality
            | RubberBandStretcher::OptionThreadingNever
            | RubberBandStretcher::OptionChannelsTogether;

    RubberBandStretcher stretcher(static_cast<std::size_t>(sampleRate), 1, options,
                                  timeRatio, pitchScale);
    if (stretcher.getEngineVersion() != 3) {
        throw std::runtime_error("Rubber Band R3 não foi ativado.");
    }
    stretcher.setExpectedInputDuration(input.size());
    stretcher.setMaxProcessSize(kOfflineBlock);

    for (std::size_t offset = 0; offset < input.size(); offset += kOfflineBlock) {
        std::size_t count = std::min(kOfflineBlock, input.size() - offset);
        const float *channels[1] = {input.data() + offset};
        bool finalBlock = offset + count >= input.size();
        stretcher.study(channels, count, finalBlock);
    }

    std::vector<float> output;
    output.reserve(targetLength + kOfflineBlock * 2);
    int dropSamples = 0;
    for (std::size_t offset = 0; offset < input.size(); offset += kOfflineBlock) {
        std::size_t count = std::min(kOfflineBlock, input.size() - offset);
        const float *channels[1] = {input.data() + offset};
        bool finalBlock = offset + count >= input.size();
        stretcher.process(channels, count, finalBlock);
        appendAvailable(stretcher, output, dropSamples, kOfflineBlock * 4);
    }
    appendAvailable(stretcher, output, dropSamples, kOfflineBlock * 4);
    return fitExactly(output, targetLength);
}

double dynamicScaleAt(double outputSample,
                      double finalScale,
                      int holdSamples,
                      int glideSamples) {
    if (outputSample <= holdSamples) return 1.0;
    if (glideSamples <= 0 || outputSample >= holdSamples + glideSamples) return finalScale;
    double t = (outputSample - holdSamples) / static_cast<double>(glideSamples);
    t = std::max(0.0, std::min(1.0, t));
    double smooth = t * t * (3.0 - 2.0 * t);
    return std::exp(std::log(finalScale) * smooth);
}

std::vector<float> processDynamic(const std::vector<float> &input,
                                  int sampleRate,
                                  double finalPitchScale,
                                  std::size_t targetLength,
                                  int holdSamples,
                                  int glideSamples) {
    double timeRatio = targetLength / static_cast<double>(input.size());
    int options = RubberBandStretcher::OptionProcessRealTime
            | RubberBandStretcher::OptionEngineFiner
            | RubberBandStretcher::OptionWindowShort
            | RubberBandStretcher::OptionFormantPreserved
            | RubberBandStretcher::OptionPitchHighConsistency
            | RubberBandStretcher::OptionThreadingNever
            | RubberBandStretcher::OptionChannelsTogether;

    RubberBandStretcher stretcher(static_cast<std::size_t>(sampleRate), 1, options,
                                  timeRatio, 1.0);
    if (stretcher.getEngineVersion() != 3) {
        throw std::runtime_error("Rubber Band R3 não foi ativado.");
    }
    stretcher.setMaxProcessSize(kDynamicBlock);

    int startPad = stretcher.getPreferredStartPad();
    int dropSamples = stretcher.getStartDelay();
    std::vector<float> output;
    output.reserve(targetLength + kDynamicBlock * 8);
    std::vector<float> zeros(kDynamicBlock, 0.0f);

    while (startPad > 0) {
        std::size_t count = std::min<std::size_t>(kDynamicBlock,
                                                  static_cast<std::size_t>(startPad));
        const float *channels[1] = {zeros.data()};
        stretcher.setPitchScale(1.0);
        stretcher.process(channels, count, false);
        appendAvailable(stretcher, output, dropSamples, kDynamicBlock * 8);
        startPad -= static_cast<int>(count);
    }

    for (std::size_t offset = 0; offset < input.size(); offset += kDynamicBlock) {
        std::size_t count = std::min(kDynamicBlock, input.size() - offset);
        double outputPosition = (offset + count * 0.5) * timeRatio;
        stretcher.setPitchScale(dynamicScaleAt(outputPosition, finalPitchScale,
                                               holdSamples, glideSamples));
        const float *channels[1] = {input.data() + offset};
        bool finalBlock = offset + count >= input.size();
        stretcher.process(channels, count, finalBlock);
        appendAvailable(stretcher, output, dropSamples, kDynamicBlock * 8);
    }
    appendAvailable(stretcher, output, dropSamples, kDynamicBlock * 8);
    return fitExactly(output, targetLength);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_chromatic_audio_NativePitchShifter_nativeShift(
        JNIEnv *env,
        jclass,
        jfloatArray inputArray,
        jint sampleRate,
        jdouble pitchScale,
        jint targetLength,
        jint holdSamples,
        jint glideSamples) {
    try {
        if (inputArray == nullptr || sampleRate < 8000 || sampleRate > 192000
                || targetLength <= 0 || !std::isfinite(pitchScale) || pitchScale <= 0.0) {
            throw std::invalid_argument("Parâmetros inválidos para o motor de pitch.");
        }

        jsize inputLength = env->GetArrayLength(inputArray);
        if (inputLength <= 0) throw std::invalid_argument("O sample está vazio.");

        std::vector<float> input(static_cast<std::size_t>(inputLength));
        env->GetFloatArrayRegion(inputArray, 0, inputLength, input.data());
        for (float &sample : input) {
            if (!std::isfinite(sample)) sample = 0.0f;
        }

        double safePitchScale = clampRatio(pitchScale, 1.0 / 16.0, 16.0);
        std::size_t safeTargetLength = static_cast<std::size_t>(targetLength);
        bool dynamic = holdSamples > 0 || glideSamples > 0;
        std::vector<float> output = dynamic
                ? processDynamic(input, sampleRate, safePitchScale, safeTargetLength,
                                 std::max(0, holdSamples), std::max(0, glideSamples))
                : processOffline(input, sampleRate, safePitchScale, safeTargetLength);

        jfloatArray result = env->NewFloatArray(targetLength);
        if (result == nullptr) throw std::runtime_error("Sem memória para criar o áudio final.");
        env->SetFloatArrayRegion(result, 0, targetLength, output.data());
        return result;
    } catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Falha desconhecida no motor de pitch nativo.");
        return nullptr;
    }
}
