#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QTextStream>

#include "ChatSessionController.h"
#include "CharacterCatalog.h"
#include "FrontendSettings.h"
#include "SttAudioController.h"

namespace {

void qtMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &message) {
    QFile logFile(QCoreApplication::applicationDirPath() + QStringLiteral("/arklight_qt_debug.log"));
    if (!logFile.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) {
        return;
    }

    QString level = QStringLiteral("INFO");
    switch (type) {
    case QtDebugMsg:
        level = QStringLiteral("DEBUG");
        break;
    case QtInfoMsg:
        level = QStringLiteral("INFO");
        break;
    case QtWarningMsg:
        level = QStringLiteral("WARN");
        break;
    case QtCriticalMsg:
        level = QStringLiteral("CRITICAL");
        break;
    case QtFatalMsg:
        level = QStringLiteral("FATAL");
        break;
    }

    QTextStream stream(&logFile);
    stream << QDateTime::currentDateTime().toString(Qt::ISODate) << " [" << level << "] "
           << message;
    if (context.file) {
        stream << " (" << context.file << ":" << context.line << ")";
    }
    stream << '\n';
    stream.flush();
}

} // namespace

int main(int argc, char *argv[]) {
    qInstallMessageHandler(qtMessageHandler);
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    // Create the Qt Quick GUI application object; it owns the event loop, windowing, and app lifetime.
    QGuiApplication app(argc, argv);

    // These C++ objects are exposed to QML, so they must stay alive until app.exec() returns.
    FrontendSettings settings;
    CharacterCatalog catalog;
    ChatSessionController chatSession(&settings, &catalog);
    SttAudioController sttAudio(&settings);

    // QQmlApplicationEngine loads the QML module and creates the UI object tree.
    QQmlApplicationEngine engine;

    // setContextProperty registers each C++ QObject as a global variable in QML.
    engine.rootContext()->setContextProperty(QStringLiteral("frontendSettings"), &settings);
    engine.rootContext()->setContextProperty(QStringLiteral("characterCatalog"), &catalog);
    engine.rootContext()->setContextProperty(QStringLiteral("chatSession"), &chatSession);
    engine.rootContext()->setContextProperty(QStringLiteral("sttAudio"), &sttAudio);

    // If QML root creation fails, quit the app; QueuedConnection schedules the quit in the event queue.
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);

    // Load Arklight/Main.qml from the QML module declared by qt_add_qml_module in CMake.
    engine.loadFromModule(QStringLiteral("Arklight"), QStringLiteral("Main"));

    // Enter the Qt event loop; windows, network callbacks, timers, and input are dispatched here.
    return app.exec();
}
