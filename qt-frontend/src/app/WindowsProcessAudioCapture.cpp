#include "WindowsProcessAudioCapture.h"

#ifdef Q_OS_WIN

#include <QMetaObject>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include <audioclient.h>
#include <avrt.h>
#include <ksmedia.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
#include <objbase.h>
#include <propidl.h>
#include <tlhelp32.h>
#include <windows.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace {

constexpr int TargetSampleRate = 16000;
constexpr wchar_t VirtualAudioDeviceProcessLoopback[] = L"VAD\\Process_Loopback";
constexpr GUID PcmSubFormatGuid = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
constexpr GUID FloatSubFormatGuid = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

#ifndef AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
enum AUDIOCLIENT_ACTIVATION_TYPE_COMPAT {
    AUDIOCLIENT_ACTIVATION_TYPE_DEFAULT_COMPAT = 0,
    AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK_COMPAT = 1
};

enum PROCESS_LOOPBACK_MODE_COMPAT {
    PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE_COMPAT = 0,
    PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE_COMPAT = 1
};

struct AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS_COMPAT {
    DWORD TargetProcessId;
    PROCESS_LOOPBACK_MODE_COMPAT ProcessLoopbackMode;
};

struct AUDIOCLIENT_ACTIVATION_PARAMS_COMPAT {
    AUDIOCLIENT_ACTIVATION_TYPE_COMPAT ActivationType;
    union {
        AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS_COMPAT ProcessLoopbackParams;
    };
};
#else
using AUDIOCLIENT_ACTIVATION_PARAMS_COMPAT = AUDIOCLIENT_ACTIVATION_PARAMS;
#endif

QString hresultText(HRESULT result, const QString &fallback) {
    wchar_t *buffer = nullptr;
    const DWORD flags = FORMAT_MESSAGE_ALLOCATE_BUFFER
        | FORMAT_MESSAGE_FROM_SYSTEM
        | FORMAT_MESSAGE_IGNORE_INSERTS;
    const DWORD length = FormatMessageW(
        flags,
        nullptr,
        static_cast<DWORD>(result),
        MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        reinterpret_cast<LPWSTR>(&buffer),
        0,
        nullptr);
    QString message = fallback;
    if (length > 0 && buffer != nullptr) {
        message = QString::fromWCharArray(buffer).trimmed();
    }
    if (buffer != nullptr) {
        LocalFree(buffer);
    }
    return QStringLiteral("%1 (0x%2)")
        .arg(message)
        .arg(static_cast<qulonglong>(static_cast<unsigned long>(result)), 8, 16, QLatin1Char('0'));
}

qint16 floatToPcm16(float value) {
    const float clamped = std::clamp(value, -1.0f, 1.0f);
    return static_cast<qint16>(std::lround(clamped * 32767.0f));
}

float intSampleToFloat(const BYTE *data, int bytesPerSample) {
    if (bytesPerSample == 1) {
        return (static_cast<int>(*data) - 128) / 128.0f;
    }
    if (bytesPerSample == 2) {
        qint16 value = 0;
        std::memcpy(&value, data, sizeof(value));
        return value / 32768.0f;
    }
    if (bytesPerSample == 3) {
        qint32 value = (static_cast<qint32>(data[0]))
            | (static_cast<qint32>(data[1]) << 8)
            | (static_cast<qint32>(data[2]) << 16);
        if ((value & 0x00800000) != 0) {
            value |= static_cast<qint32>(0xff000000);
        }
        return value / 8388608.0f;
    }
    if (bytesPerSample >= 4) {
        qint32 value = 0;
        std::memcpy(&value, data, sizeof(value));
        return value / 2147483648.0f;
    }
    return 0.0f;
}

bool formatIsFloat(const WAVEFORMATEX *format) {
    if (format == nullptr) {
        return false;
    }
    if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) {
        return true;
    }
    if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE && format->cbSize >= 22) {
        const auto *extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE *>(format);
        return IsEqualGUID(extensible->SubFormat, FloatSubFormatGuid);
    }
    return false;
}

bool formatIsPcm(const WAVEFORMATEX *format) {
    if (format == nullptr) {
        return false;
    }
    if (format->wFormatTag == WAVE_FORMAT_PCM) {
        return true;
    }
    if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE && format->cbSize >= 22) {
        const auto *extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE *>(format);
        return IsEqualGUID(extensible->SubFormat, PcmSubFormatGuid);
    }
    return false;
}

QByteArray convertPacketToPcm16Mono16k(
    const BYTE *data,
    UINT32 frames,
    DWORD flags,
    const WAVEFORMATEX *format,
    qreal *level) {
    if (format == nullptr || frames == 0 || level == nullptr) {
        return {};
    }

    const int channels = std::max<int>(1, format->nChannels);
    const int sampleRate = std::max<int>(1, format->nSamplesPerSec);
    const int bytesPerSample = std::max<int>(1, format->wBitsPerSample / 8);
    const int blockAlign = std::max<int>(1, format->nBlockAlign);
    const bool silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == nullptr;
    const bool floatFormat = formatIsFloat(format);
    const bool pcmFormat = formatIsPcm(format);
    if (!silent && !floatFormat && !pcmFormat) {
        *level = 0.0;
        return {};
    }

    std::vector<float> mono;
    mono.reserve(frames);
    double energy = 0.0;
    for (UINT32 frame = 0; frame < frames; ++frame) {
        float mixed = 0.0f;
        if (!silent) {
            const BYTE *frameStart = data + frame * blockAlign;
            for (int channel = 0; channel < channels; ++channel) {
                const BYTE *sample = frameStart + channel * bytesPerSample;
                if (floatFormat && bytesPerSample >= static_cast<int>(sizeof(float))) {
                    float value = 0.0f;
                    std::memcpy(&value, sample, sizeof(value));
                    mixed += std::clamp(value, -1.0f, 1.0f);
                } else {
                    mixed += intSampleToFloat(sample, bytesPerSample);
                }
            }
            mixed /= static_cast<float>(channels);
        }
        mono.push_back(mixed);
        energy += mixed * mixed;
    }

    *level = std::clamp(std::sqrt(energy / std::max<UINT32>(1, frames)) * 4.0, 0.0, 1.0);

    const int targetFrames = std::max<int>(1, static_cast<int>(std::llround(frames * (TargetSampleRate / static_cast<double>(sampleRate)))));
    QByteArray output;
    output.resize(targetFrames * static_cast<int>(sizeof(qint16)));
    auto *out = reinterpret_cast<qint16 *>(output.data());
    for (int index = 0; index < targetFrames; ++index) {
        const int sourceIndex = std::min<int>(
            static_cast<int>(frames) - 1,
            static_cast<int>(std::floor(index * (sampleRate / static_cast<double>(TargetSampleRate)))));
        out[index] = floatToPcm16(mono.at(static_cast<size_t>(sourceIndex)));
    }
    return output;
}

class AudioActivationHandler final : public IActivateAudioInterfaceCompletionHandler {
public:
    AudioActivationHandler()
        : m_event(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}

    ~AudioActivationHandler() {
        if (m_unknown != nullptr) {
            m_unknown->Release();
        }
        if (m_event != nullptr) {
            CloseHandle(m_event);
        }
    }

    HANDLE eventHandle() const {
        return m_event;
    }

    HRESULT activationResult() const {
        return m_activationResult;
    }

    IUnknown *activatedInterface() const {
        return m_unknown;
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **object) override {
        if (object == nullptr) {
            return E_POINTER;
        }
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) {
            *object = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
            AddRef();
            return S_OK;
        }
        *object = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&m_refs));
    }

    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG refs = static_cast<ULONG>(InterlockedDecrement(&m_refs));
        if (refs == 0) {
            delete this;
        }
        return refs;
    }

    HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation *operation) override {
        HRESULT asyncResult = E_FAIL;
        IUnknown *unknown = nullptr;
        const HRESULT operationResult = operation == nullptr
            ? E_POINTER
            : operation->GetActivateResult(&asyncResult, &unknown);
        m_activationResult = SUCCEEDED(operationResult) ? asyncResult : operationResult;
        m_unknown = unknown;
        if (m_event != nullptr) {
            SetEvent(m_event);
        }
        return S_OK;
    }

private:
    volatile LONG m_refs = 1;
    HANDLE m_event = nullptr;
    HRESULT m_activationResult = E_FAIL;
    IUnknown *m_unknown = nullptr;
};

bool activateProcessAudioClient(quint32 processId, ComPtr<IAudioClient> *audioClient, QString *errorMessage) {
    if (audioClient == nullptr) {
        return false;
    }

    AUDIOCLIENT_ACTIVATION_PARAMS_COMPAT activationParams{};
#ifndef AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
    activationParams.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK_COMPAT;
    activationParams.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE_COMPAT;
#else
    activationParams.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    activationParams.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
#endif
    activationParams.ProcessLoopbackParams.TargetProcessId = static_cast<DWORD>(processId);

    PROPVARIANT property;
    PropVariantInit(&property);
    property.vt = VT_BLOB;
    property.blob.cbSize = sizeof(activationParams);
    property.blob.pBlobData = reinterpret_cast<BYTE *>(&activationParams);

    AudioActivationHandler *handler = new AudioActivationHandler();
    if (handler->eventHandle() == nullptr) {
        handler->Release();
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("Could not create audio activation event.");
        }
        return false;
    }

    IActivateAudioInterfaceAsyncOperation *operation = nullptr;
    const HRESULT activateResult = ActivateAudioInterfaceAsync(
        VirtualAudioDeviceProcessLoopback,
        __uuidof(IAudioClient),
        &property,
        handler,
        &operation);
    if (FAILED(activateResult)) {
        handler->Release();
        if (errorMessage != nullptr) {
            *errorMessage = hresultText(activateResult, QStringLiteral("Could not activate process audio loopback."));
        }
        return false;
    }
    const DWORD waitResult = WaitForSingleObject(handler->eventHandle(), 5000);
    if (waitResult != WAIT_OBJECT_0) {
        if (operation != nullptr) {
            operation->Release();
        }
        handler->Release();
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("Timed out while activating process audio loopback.");
        }
        return false;
    }
    if (operation != nullptr) {
        operation->Release();
    }

    const HRESULT asyncResult = handler->activationResult();
    if (FAILED(asyncResult)) {
        handler->Release();
        if (errorMessage != nullptr) {
            *errorMessage = hresultText(asyncResult, QStringLiteral("Process audio loopback activation failed."));
        }
        return false;
    }

    IUnknown *unknown = handler->activatedInterface();
    if (unknown == nullptr) {
        handler->Release();
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("Process audio loopback returned no audio client.");
        }
        return false;
    }

    IAudioClient *client = nullptr;
    const HRESULT queryResult = unknown->QueryInterface(__uuidof(IAudioClient), reinterpret_cast<void **>(&client));
    handler->Release();
    if (FAILED(queryResult) || client == nullptr) {
        if (errorMessage != nullptr) {
            *errorMessage = hresultText(queryResult, QStringLiteral("Could not query process audio client."));
        }
        return false;
    }
    audioClient->Attach(client);
    return true;
}

} // namespace

WindowsProcessAudioCapture::WindowsProcessAudioCapture(QObject *parent)
    : QObject(parent) {}

WindowsProcessAudioCapture::~WindowsProcessAudioCapture() {
    stop();
}

QList<WindowsProcessAudioCapture::ProcessInfo> WindowsProcessAudioCapture::enumerateProcesses() {
    QList<ProcessInfo> result;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return result;
    }

    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            const quint32 processId = static_cast<quint32>(entry.th32ProcessID);
            if (processId == 0) {
                continue;
            }
            const QString executable = QString::fromWCharArray(entry.szExeFile).trimmed();
            if (executable.isEmpty()) {
                continue;
            }
            ProcessInfo info;
            info.processId = processId;
            info.executableName = executable;
            info.label = QStringLiteral("%1 (%2)").arg(executable).arg(processId);
            result.append(info);
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);

    std::sort(result.begin(), result.end(), [](const ProcessInfo &left, const ProcessInfo &right) {
        const int compare = QString::localeAwareCompare(left.executableName, right.executableName);
        if (compare != 0) {
            return compare < 0;
        }
        return left.processId < right.processId;
    });
    return result;
}

bool WindowsProcessAudioCapture::running() const {
    return m_thread.joinable();
}

bool WindowsProcessAudioCapture::start(quint32 processId, QString *errorMessage) {
    if (running()) {
        return true;
    }
    if (processId == 0) {
        if (errorMessage != nullptr) {
            *errorMessage = QStringLiteral("Select a Windows process to capture.");
        }
        return false;
    }
    m_stopRequested.store(false);
    m_thread = std::thread(&WindowsProcessAudioCapture::captureLoop, this, processId);
    return true;
}

void WindowsProcessAudioCapture::stop() {
    m_stopRequested.store(true);
    if (m_thread.joinable()) {
        m_thread.join();
    }
}

void WindowsProcessAudioCapture::captureLoop(quint32 processId) {
    QString errorMessage;
    const HRESULT comResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool shouldUninitializeCom = SUCCEEDED(comResult);
    if (FAILED(comResult) && comResult != RPC_E_CHANGED_MODE) {
        errorMessage = hresultText(comResult, QStringLiteral("Could not initialize COM for audio capture."));
    }

    ComPtr<IAudioClient> audioClient;
    WAVEFORMATEX *mixFormat = nullptr;
    HANDLE captureEvent = nullptr;
    ComPtr<IAudioCaptureClient> captureClient;
    HANDLE avrtHandle = nullptr;
    DWORD avrtTaskIndex = 0;

    if (errorMessage.isEmpty() && !activateProcessAudioClient(processId, &audioClient, &errorMessage)) {
        audioClient.Reset();
    }

    if (errorMessage.isEmpty()) {
        const HRESULT formatResult = audioClient->GetMixFormat(&mixFormat);
        if (FAILED(formatResult) || mixFormat == nullptr) {
            errorMessage = hresultText(formatResult, QStringLiteral("Could not read process audio format."));
        }
    }

    if (errorMessage.isEmpty()) {
        captureEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (captureEvent == nullptr) {
            errorMessage = QStringLiteral("Could not create audio capture event.");
        }
    }

    if (errorMessage.isEmpty()) {
        const REFERENCE_TIME bufferDuration = 10000000;
        const HRESULT initResult = audioClient->Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
            bufferDuration,
            0,
            mixFormat,
            nullptr);
        if (FAILED(initResult)) {
            errorMessage = hresultText(initResult, QStringLiteral("Could not initialize process audio loopback."));
        }
    }

    if (errorMessage.isEmpty()) {
        const HRESULT eventResult = audioClient->SetEventHandle(captureEvent);
        if (FAILED(eventResult)) {
            errorMessage = hresultText(eventResult, QStringLiteral("Could not bind process audio event."));
        }
    }

    if (errorMessage.isEmpty()) {
        IAudioCaptureClient *client = nullptr;
        const HRESULT serviceResult = audioClient->GetService(__uuidof(IAudioCaptureClient), reinterpret_cast<void **>(&client));
        if (FAILED(serviceResult) || client == nullptr) {
            errorMessage = hresultText(serviceResult, QStringLiteral("Could not create process audio capture client."));
        } else {
            captureClient.Attach(client);
        }
    }

    if (errorMessage.isEmpty()) {
        avrtHandle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &avrtTaskIndex);
        const HRESULT startResult = audioClient->Start();
        if (FAILED(startResult)) {
            errorMessage = hresultText(startResult, QStringLiteral("Could not start process audio capture."));
        }
    }

    while (errorMessage.isEmpty() && !m_stopRequested.load()) {
        const DWORD waitResult = WaitForSingleObject(captureEvent, 250);
        if (waitResult == WAIT_TIMEOUT) {
            continue;
        }
        if (waitResult != WAIT_OBJECT_0) {
            errorMessage = QStringLiteral("Process audio capture wait failed.");
            break;
        }

        UINT32 packetFrames = 0;
        HRESULT packetResult = captureClient->GetNextPacketSize(&packetFrames);
        if (FAILED(packetResult)) {
            errorMessage = hresultText(packetResult, QStringLiteral("Could not read process audio packet size."));
            break;
        }

        while (packetFrames > 0 && !m_stopRequested.load()) {
            BYTE *data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            const HRESULT bufferResult = captureClient->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
            if (FAILED(bufferResult)) {
                errorMessage = hresultText(bufferResult, QStringLiteral("Could not read process audio buffer."));
                break;
            }

            qreal level = 0.0;
            const QByteArray pcm = convertPacketToPcm16Mono16k(data, frames, flags, mixFormat, &level);
            captureClient->ReleaseBuffer(frames);
            if (!pcm.isEmpty()) {
                QMetaObject::invokeMethod(
                    this,
                    [this, pcm, level]() { emit pcmReady(pcm, level); },
                    Qt::QueuedConnection);
            }

            packetResult = captureClient->GetNextPacketSize(&packetFrames);
            if (FAILED(packetResult)) {
                errorMessage = hresultText(packetResult, QStringLiteral("Could not read next process audio packet."));
                break;
            }
        }
    }

    if (audioClient) {
        audioClient->Stop();
    }
    if (avrtHandle != nullptr) {
        AvRevertMmThreadCharacteristics(avrtHandle);
    }
    if (captureEvent != nullptr) {
        CloseHandle(captureEvent);
    }
    if (mixFormat != nullptr) {
        CoTaskMemFree(mixFormat);
    }
    if (shouldUninitializeCom) {
        CoUninitialize();
    }

    QMetaObject::invokeMethod(
        this,
        [this, errorMessage]() { emit stopped(errorMessage); },
        Qt::QueuedConnection);
}

#else

WindowsProcessAudioCapture::WindowsProcessAudioCapture(QObject *parent)
    : QObject(parent) {}

WindowsProcessAudioCapture::~WindowsProcessAudioCapture() {
    stop();
}

QList<WindowsProcessAudioCapture::ProcessInfo> WindowsProcessAudioCapture::enumerateProcesses() {
    return {};
}

bool WindowsProcessAudioCapture::running() const {
    return false;
}

bool WindowsProcessAudioCapture::start(quint32, QString *errorMessage) {
    if (errorMessage != nullptr) {
        *errorMessage = QStringLiteral("Process audio capture is only available on Windows.");
    }
    return false;
}

void WindowsProcessAudioCapture::stop() {}

void WindowsProcessAudioCapture::captureLoop(quint32) {}

#endif
