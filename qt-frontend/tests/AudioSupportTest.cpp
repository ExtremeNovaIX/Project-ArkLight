#include "audio/SttAudioProcessing.h"
#include "tts/TtsAudioCodecSupport.h"

#include <QtEndian>
#include <QtTest/QtTest>

class AudioSupportTest final : public QObject {
    Q_OBJECT

private slots:
    void resamplesSttMonoToPcm16k();
    void escapesCsvValues();
    void parsesTtsMediaTypeParameters();
    void reportsTtsSampleFormatSizes();
};

void AudioSupportTest::resamplesSttMonoToPcm16k() {
    const QByteArray pcm = sttaudio::resampleMonoToPcm16k(QVector<float>{1.0f, -1.0f}, sttaudio::TargetSampleRate);

    QCOMPARE(pcm.size(), 2 * sttaudio::TargetBytesPerSample);
    const auto *bytes = reinterpret_cast<const uchar *>(pcm.constData());
    QVERIFY(qFromLittleEndian<qint16>(bytes) > 0);
    QVERIFY(qFromLittleEndian<qint16>(bytes + sttaudio::TargetBytesPerSample) < 0);
}

void AudioSupportTest::escapesCsvValues() {
    QCOMPARE(sttaudio::csvEscape(QStringLiteral("a\"b")), QStringLiteral("\"a\"\"b\""));
}

void AudioSupportTest::parsesTtsMediaTypeParameters() {
    const QString mediaType = QStringLiteral("audio/pcm; rate=24000; channels=2; format=s16");

    QCOMPARE(ttsaudio::mediaTypeIntParameter(mediaType, QStringLiteral("rate"), 16000), 24000);
    QCOMPARE(ttsaudio::mediaTypeIntParameter(mediaType, QStringLiteral("channels"), 1), 2);
    QVERIFY(ttsaudio::mediaTypeHasToken(mediaType, QStringLiteral("s16")));
}

void AudioSupportTest::reportsTtsSampleFormatSizes() {
    QCOMPARE(ttsaudio::bytesForSampleFormat(QAudioFormat::UInt8), 1);
    QCOMPARE(ttsaudio::bytesForSampleFormat(QAudioFormat::Int16), 2);
    QCOMPARE(ttsaudio::bytesForSampleFormat(QAudioFormat::Float), 4);
}

QTEST_GUILESS_MAIN(AudioSupportTest)
#include "AudioSupportTest.moc"