#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

namespace {

enum class FrontendMode {
    Qt,
    Web,
    BackendOnly
};

struct Endpoint {
    std::wstring baseUrl = L"http://127.0.0.1:8080";
    std::wstring host = L"127.0.0.1";
    std::wstring port = L"8080";
};

struct ChildProcess {
    PROCESS_INFORMATION info{};
    bool started = false;

    void closeHandles() {
        if (info.hThread) {
            CloseHandle(info.hThread);
            info.hThread = nullptr;
        }
        if (info.hProcess) {
            CloseHandle(info.hProcess);
            info.hProcess = nullptr;
        }
        started = false;
    }
};

fs::path g_rootDir;
fs::path g_logFile;

std::wstring toLower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t ch) {
        return static_cast<wchar_t>(towlower(ch));
    });
    return value;
}

std::string toUtf8(const std::wstring &value) {
    if (value.empty()) {
        return {};
    }
    const int size = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (size <= 1) {
        return {};
    }
    std::string result(static_cast<size_t>(size - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, result.data(), size, nullptr, nullptr);
    return result;
}

std::wstring fromEnv(const wchar_t *name) {
    DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0) {
        return {};
    }
    std::wstring value(size, L'\0');
    DWORD written = GetEnvironmentVariableW(name, value.data(), size);
    value.resize(written);
    return value;
}

std::wstring quote(const std::wstring &value) {
    std::wstring escaped;
    escaped.reserve(value.size() + 2);
    escaped.push_back(L'"');
    for (wchar_t ch : value) {
        if (ch == L'"') {
            escaped.push_back(L'\\');
        }
        escaped.push_back(ch);
    }
    escaped.push_back(L'"');
    return escaped;
}

std::wstring quote(const fs::path &path) {
    return quote(path.wstring());
}

void logLine(const std::wstring &message) {
    if (g_logFile.empty()) {
        return;
    }
    SYSTEMTIME time{};
    GetLocalTime(&time);
    wchar_t prefix[64];
    swprintf_s(prefix, L"%04u-%02u-%02u %02u:%02u:%02u ",
               time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond);

    std::ofstream out(g_logFile, std::ios::binary | std::ios::app);
    out << toUtf8(prefix) << toUtf8(message) << "\r\n";
}

void showError(const std::wstring &message) {
    logLine(L"ERROR: " + message);
    MessageBoxW(nullptr, message.c_str(), L"Arklight Launcher", MB_OK | MB_ICONERROR);
}

fs::path executablePath() {
    std::wstring buffer(MAX_PATH, L'\0');
    DWORD written = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    while (written == buffer.size()) {
        buffer.resize(buffer.size() * 2);
        written = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    }
    buffer.resize(written);
    return fs::path(buffer);
}

std::vector<std::wstring> commandLineArgs() {
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::vector<std::wstring> args;
    if (!argv) {
        return args;
    }
    for (int i = 1; i < argc; ++i) {
        args.emplace_back(argv[i]);
    }
    LocalFree(argv);
    return args;
}

std::optional<fs::path> searchPathExe(const std::wstring &name) {
    DWORD size = SearchPathW(nullptr, name.c_str(), nullptr, 0, nullptr, nullptr);
    if (size == 0) {
        return std::nullopt;
    }
    std::wstring buffer(size, L'\0');
    DWORD written = SearchPathW(nullptr, name.c_str(), nullptr, size, buffer.data(), nullptr);
    if (written == 0) {
        return std::nullopt;
    }
    buffer.resize(written);
    return fs::path(buffer);
}

std::optional<fs::path> firstExisting(const std::vector<fs::path> &paths) {
    for (const auto &path : paths) {
        std::error_code ec;
        if (fs::is_regular_file(path, ec)) {
            return path;
        }
    }
    return std::nullopt;
}

std::optional<fs::path> findFirstJarIn(const fs::path &dir) {
    std::error_code ec;
    if (!fs::is_directory(dir, ec)) {
        return std::nullopt;
    }
    for (const auto &entry : fs::directory_iterator(dir, ec)) {
        if (ec) {
            return std::nullopt;
        }
        if (!entry.is_regular_file(ec)) {
            continue;
        }
        fs::path path = entry.path();
        if (toLower(path.extension().wstring()) == L".jar") {
            return path;
        }
    }
    return std::nullopt;
}

std::optional<fs::path> findBackendJar() {
    if (auto explicitJar = firstExisting({
            g_rootDir / L"backend" / L"ArcLight-chat.jar",
            g_rootDir / L"backend" / L"ArcLight-chat-0.0.1-SNAPSHOT.jar"
        })) {
        return explicitJar;
    }
    if (auto jar = findFirstJarIn(g_rootDir / L"backend")) {
        return jar;
    }
    return findFirstJarIn(g_rootDir / L"backend" / L"target");
}

std::optional<fs::path> findJavaExe() {
    if (auto explicitJava = firstExisting({
            g_rootDir / L"runtime" / L"bin" / L"java.exe",
            g_rootDir / L"runtime" / L"bin" / L"javaw.exe"
        })) {
        return explicitJava;
    }
    if (auto java = searchPathExe(L"java.exe")) {
        return java;
    }
    return searchPathExe(L"javaw.exe");
}

std::optional<fs::path> findQtExe() {
    return firstExisting({
        g_rootDir / L"qt" / L"arklight_qt.exe",
        g_rootDir / L"qt-frontend" / L"build" / L"qt-frontend" / L"arklight_qt.exe",
        g_rootDir / L"qt-frontend" / L"build" / L"qt-frontend" / L"Debug" / L"arklight_qt.exe",
        g_rootDir / L"qt-frontend" / L"build" / L"qt-frontend" / L"Release" / L"arklight_qt.exe"
    });
}

Endpoint parseEndpoint(std::wstring url) {
    Endpoint endpoint;
    if (url.empty()) {
        return endpoint;
    }
    while (!url.empty() && url.back() == L'/') {
        url.pop_back();
    }
    endpoint.baseUrl = url;

    std::wstring rest = url;
    size_t scheme = rest.find(L"://");
    std::wstring defaultPort = L"80";
    if (scheme != std::wstring::npos) {
        std::wstring schemeName = toLower(rest.substr(0, scheme));
        if (schemeName == L"https") {
            defaultPort = L"443";
        }
        rest = rest.substr(scheme + 3);
    }

    size_t slash = rest.find(L'/');
    std::wstring hostPort = slash == std::wstring::npos ? rest : rest.substr(0, slash);
    endpoint.host = hostPort;
    endpoint.port = defaultPort;

    if (!hostPort.empty() && hostPort.front() == L'[') {
        size_t close = hostPort.find(L']');
        if (close != std::wstring::npos) {
            endpoint.host = hostPort.substr(1, close - 1);
            if (close + 2 <= hostPort.size() && hostPort[close + 1] == L':') {
                endpoint.port = hostPort.substr(close + 2);
            }
        }
    } else {
        size_t colon = hostPort.rfind(L':');
        if (colon != std::wstring::npos) {
            endpoint.host = hostPort.substr(0, colon);
            endpoint.port = hostPort.substr(colon + 1);
        }
    }

    if (endpoint.host.empty()) {
        endpoint.host = L"127.0.0.1";
    }
    if (endpoint.port.empty()) {
        endpoint.port = defaultPort;
    }
    return endpoint;
}

bool connectWithTimeout(const ADDRINFOW *address, int timeoutMs) {
    SOCKET socketHandle = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
    if (socketHandle == INVALID_SOCKET) {
        return false;
    }

    u_long nonBlocking = 1;
    ioctlsocket(socketHandle, FIONBIO, &nonBlocking);

    int result = connect(socketHandle, address->ai_addr, static_cast<int>(address->ai_addrlen));
    if (result == 0) {
        closesocket(socketHandle);
        return true;
    }

    int error = WSAGetLastError();
    if (error != WSAEWOULDBLOCK && error != WSAEINPROGRESS && error != WSAEINVAL) {
        closesocket(socketHandle);
        return false;
    }

    fd_set writeSet;
    FD_ZERO(&writeSet);
    FD_SET(socketHandle, &writeSet);

    timeval timeout{};
    timeout.tv_sec = timeoutMs / 1000;
    timeout.tv_usec = (timeoutMs % 1000) * 1000;

    result = select(0, nullptr, &writeSet, nullptr, &timeout);
    if (result > 0 && FD_ISSET(socketHandle, &writeSet)) {
        int socketError = 0;
        int length = sizeof(socketError);
        getsockopt(socketHandle, SOL_SOCKET, SO_ERROR, reinterpret_cast<char *>(&socketError), &length);
        closesocket(socketHandle);
        return socketError == 0;
    }

    closesocket(socketHandle);
    return false;
}

bool isTcpReady(const Endpoint &endpoint, int timeoutMs) {
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        return false;
    }

    ADDRINFOW hints{};
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    ADDRINFOW *addresses = nullptr;
    int result = GetAddrInfoW(endpoint.host.c_str(), endpoint.port.c_str(), &hints, &addresses);
    if (result != 0) {
        WSACleanup();
        return false;
    }

    bool ready = false;
    for (ADDRINFOW *address = addresses; address != nullptr; address = address->ai_next) {
        if (connectWithTimeout(address, timeoutMs)) {
            ready = true;
            break;
        }
    }

    FreeAddrInfoW(addresses);
    WSACleanup();
    return ready;
}

std::optional<std::wstring> resolveExplicitBackendUrl(const std::vector<std::wstring> &args);

Endpoint endpointForPort(int port) {
    Endpoint endpoint;
    endpoint.host = L"127.0.0.1";
    endpoint.port = std::to_wstring(port);
    endpoint.baseUrl = L"http://127.0.0.1:" + endpoint.port;
    return endpoint;
}

Endpoint resolveEndpoint(const std::vector<std::wstring> &args) {
    if (auto explicitUrl = resolveExplicitBackendUrl(args)) {
        return parseEndpoint(*explicitUrl);
    }

    std::vector<int> candidatePorts;
    candidatePorts.push_back(8080);
    for (int port = 18080; port <= 18120; ++port) {
        candidatePorts.push_back(port);
    }

    for (int port : candidatePorts) {
        Endpoint candidate = endpointForPort(port);
        if (!isTcpReady(candidate, 100)) {
            logLine(L"Selected backend port: " + candidate.port);
            return candidate;
        }
    }

    logLine(L"No free candidate port found; falling back to 8080.");
    return endpointForPort(8080);
}

bool waitForBackend(const Endpoint &endpoint, const ChildProcess &backend, int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        if (isTcpReady(endpoint, 250)) {
            return true;
        }

        if (backend.started) {
            DWORD exitCode = 0;
            if (GetExitCodeProcess(backend.info.hProcess, &exitCode) && exitCode != STILL_ACTIVE) {
                logLine(L"Backend process exited before port became ready. ExitCode=" + std::to_wstring(exitCode));
                return false;
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(300));
    }
    return false;
}

HANDLE openAppendLogHandle(const fs::path &path) {
    SECURITY_ATTRIBUTES security{};
    security.nLength = sizeof(security);
    security.bInheritHandle = TRUE;
    HANDLE handle = CreateFileW(
        path.wstring().c_str(),
        FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        &security,
        OPEN_ALWAYS,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (handle != INVALID_HANDLE_VALUE) {
        SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
    }
    return handle;
}

bool launchProcess(const fs::path &exe,
                   const std::wstring &args,
                   const fs::path &workingDir,
                   DWORD creationFlags,
                   HANDLE outputHandle,
                   ChildProcess &process) {
    std::wstring commandLine = quote(exe);
    if (!args.empty()) {
        commandLine += L" ";
        commandLine += args;
    }

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    if (outputHandle && outputHandle != INVALID_HANDLE_VALUE) {
        startup.dwFlags |= STARTF_USESTDHANDLES;
        startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        startup.hStdOutput = outputHandle;
        startup.hStdError = outputHandle;
    }

    PROCESS_INFORMATION info{};
    BOOL ok = CreateProcessW(
        exe.wstring().c_str(),
        commandLine.data(),
        nullptr,
        nullptr,
        outputHandle && outputHandle != INVALID_HANDLE_VALUE,
        creationFlags,
        nullptr,
        workingDir.wstring().c_str(),
        &startup,
        &info);

    if (!ok) {
        logLine(L"CreateProcess failed: " + exe.wstring() + L", error=" + std::to_wstring(GetLastError()));
        return false;
    }

    process.info = info;
    process.started = true;
    logLine(L"Started process: " + commandLine);
    return true;
}

bool openBrowser(const std::wstring &url) {
    HINSTANCE result = ShellExecuteW(nullptr, L"open", url.c_str(), nullptr, g_rootDir.wstring().c_str(), SW_SHOWNORMAL);
    auto code = reinterpret_cast<intptr_t>(result);
    if (code <= 32) {
        logLine(L"ShellExecute failed for url: " + url + L", code=" + std::to_wstring(code));
        return false;
    }
    logLine(L"Opened browser: " + url);
    return true;
}

void terminateProcessTree(DWORD pid) {
    if (pid == 0) {
        return;
    }
    auto taskkill = searchPathExe(L"taskkill.exe");
    if (!taskkill) {
        return;
    }
    ChildProcess process;
    std::wstring args = L"/PID " + std::to_wstring(pid) + L" /T /F";
    launchProcess(*taskkill, args, g_rootDir, CREATE_NO_WINDOW, nullptr, process);
    if (process.started) {
        WaitForSingleObject(process.info.hProcess, 5000);
        process.closeHandles();
    }
}

void stopBackend(ChildProcess &backend) {
    if (!backend.started) {
        return;
    }
    DWORD exitCode = 0;
    if (GetExitCodeProcess(backend.info.hProcess, &exitCode) && exitCode == STILL_ACTIVE) {
        logLine(L"Stopping backend process tree. PID=" + std::to_wstring(backend.info.dwProcessId));
        terminateProcessTree(backend.info.dwProcessId);
    }
    backend.closeHandles();
}

FrontendMode resolveMode(const std::vector<std::wstring> &args, const fs::path &exePath) {
    std::wstring exeName = toLower(exePath.filename().wstring());
    FrontendMode mode = exeName.find(L"web") != std::wstring::npos ? FrontendMode::Web : FrontendMode::Qt;
    if (exeName.find(L"backend") != std::wstring::npos) {
        mode = FrontendMode::BackendOnly;
    }

    for (const auto &arg : args) {
        std::wstring lower = toLower(arg);
        if (lower == L"--web") {
            mode = FrontendMode::Web;
        } else if (lower == L"--qt") {
            mode = FrontendMode::Qt;
        } else if (lower == L"--backend-only") {
            mode = FrontendMode::BackendOnly;
        }
    }
    return mode;
}

std::optional<std::wstring> resolveExplicitBackendUrl(const std::vector<std::wstring> &args) {
    for (const auto &arg : args) {
        const std::wstring prefix = L"--backend-url=";
        if (arg.rfind(prefix, 0) == 0) {
            return arg.substr(prefix.size());
        }
    }
    std::wstring env = fromEnv(L"ARCLIGHT_BACKEND_URL");
    if (!env.empty()) {
        return env;
    }
    return std::nullopt;
}

int resolveTimeoutMs(const std::vector<std::wstring> &args) {
    for (const auto &arg : args) {
        const std::wstring prefix = L"--timeout-ms=";
        if (arg.rfind(prefix, 0) == 0) {
            try {
                return std::max(1000, std::stoi(arg.substr(prefix.size())));
            } catch (...) {
                return 60000;
            }
        }
    }
    std::wstring env = fromEnv(L"ARCLIGHT_BACKEND_START_TIMEOUT_MS");
    if (!env.empty()) {
        try {
            return std::max(1000, std::stoi(env));
        } catch (...) {
            return 60000;
        }
    }
    return 60000;
}

void prepareRuntimeDirectories() {
    std::error_code ec;
    fs::create_directories(g_rootDir / L"logs", ec);
    fs::create_directories(g_rootDir / L"data", ec);
    fs::create_directories(g_rootDir / L"config", ec);
}

bool validateReleaseLayout() {
    std::error_code ec;
    if (!fs::is_directory(g_rootDir / L"chara", ec)) {
        showError(L"发布包缺少 chara 目录：" + (g_rootDir / L"chara").wstring()
                  + L"\n请重新运行 scripts\\package-windows.bat。");
        return false;
    }
    return true;
}

void setReleaseEnvironment() {
    SetEnvironmentVariableW(L"ARCLIGHT_APP_HOME", g_rootDir.wstring().c_str());
    SetEnvironmentVariableW(L"ARCLIGHT_CONFIG_DIR", (g_rootDir / L"config").wstring().c_str());
    SetEnvironmentVariableW(L"ARCLIGHT_CHARA_DIR", (g_rootDir / L"chara").wstring().c_str());
    SetEnvironmentVariableW(L"MCP_SERVERS_DIR", (g_rootDir / L"mcp-servers").wstring().c_str());
    SetEnvironmentVariableW(L"MCP_REGISTRY_FILE", (g_rootDir / L"config" / L"mcp-registry.json").wstring().c_str());
}

bool startBackendIfNeeded(const Endpoint &endpoint, ChildProcess &backend) {
    if (isTcpReady(endpoint, 250)) {
        logLine(L"Backend port is already ready. Reusing existing backend at " + endpoint.baseUrl);
        return true;
    }

    auto javaExe = findJavaExe();
    if (!javaExe) {
        showError(L"找不到 Java 运行时。请把 Java 21 放到 runtime 目录，或把 java.exe 加入 PATH。");
        return false;
    }

    auto jar = findBackendJar();
    if (!jar) {
        showError(L"找不到后端 jar。期望位置：backend\\ArcLight-chat.jar。");
        return false;
    }

    fs::path backendLog = g_rootDir / L"logs" / L"backend.stdout.log";
    HANDLE outputHandle = openAppendLogHandle(backendLog);
    if (outputHandle == INVALID_HANDLE_VALUE) {
        outputHandle = nullptr;
    }

    std::wstring args = L"-Dfile.encoding=UTF-8 -Darclight.config.dir=" + quote(g_rootDir / L"config")
        + L" -Dassistant.rp.character-directory=" + quote(g_rootDir / L"chara")
        + L" -jar " + quote(*jar)
        + L" --server.address=127.0.0.1"
        + L" --server.port=" + endpoint.port;

    bool started = launchProcess(*javaExe, args, g_rootDir, CREATE_NO_WINDOW, outputHandle, backend);
    if (outputHandle) {
        CloseHandle(outputHandle);
    }
    if (!started) {
        showError(L"后端启动失败。请查看 logs\\launcher.log。");
        return false;
    }
    return true;
}

int runLauncher() {
    fs::path exePath = executablePath();
    g_rootDir = exePath.parent_path();
    g_logFile = g_rootDir / L"logs" / L"launcher.log";
    prepareRuntimeDirectories();
    setReleaseEnvironment();

    std::vector<std::wstring> args = commandLineArgs();
    FrontendMode mode = resolveMode(args, exePath);
    Endpoint endpoint = resolveEndpoint(args);
    int timeoutMs = resolveTimeoutMs(args);

    logLine(L"Launcher started. Root=" + g_rootDir.wstring() + L", backend=" + endpoint.baseUrl);
    if (!validateReleaseLayout()) {
        return 1;
    }

    ChildProcess backend;
    bool backendWasAlreadyReady = isTcpReady(endpoint, 250);
    if (!backendWasAlreadyReady) {
        if (!startBackendIfNeeded(endpoint, backend)) {
            return 1;
        }
    } else {
        logLine(L"Backend already running before launcher start.");
    }

    if (!waitForBackend(endpoint, backend, timeoutMs)) {
        stopBackend(backend);
        showError(L"等待后端启动超时。请查看 logs\\backend.stdout.log 和 logs\\launcher.log。");
        return 1;
    }

    if (mode == FrontendMode::BackendOnly) {
        MessageBoxW(nullptr, L"后端正在运行。点击“确定”会停止本启动器拉起的后端。", L"Arklight Launcher", MB_OK | MB_ICONINFORMATION);
        stopBackend(backend);
        return 0;
    }

    if (mode == FrontendMode::Web) {
        std::wstring url = endpoint.baseUrl + L"/";
        if (!openBrowser(url)) {
            stopBackend(backend);
            showError(L"浏览器打开失败。你可以手动访问：" + url);
            return 1;
        }
        MessageBoxW(nullptr, L"Web 前端已经打开。点击“确定”会停止本启动器拉起的后端。", L"Arklight Launcher", MB_OK | MB_ICONINFORMATION);
        stopBackend(backend);
        return 0;
    }

    auto qtExe = findQtExe();
    if (!qtExe) {
        stopBackend(backend);
        showError(L"找不到 Qt 前端。期望位置：qt\\arklight_qt.exe。");
        return 1;
    }

    ChildProcess qtProcess;
    if (!launchProcess(*qtExe, L"", g_rootDir, 0, nullptr, qtProcess)) {
        stopBackend(backend);
        showError(L"Qt 前端启动失败。请查看 logs\\launcher.log。");
        return 1;
    }

    WaitForSingleObject(qtProcess.info.hProcess, INFINITE);
    qtProcess.closeHandles();
    stopBackend(backend);
    logLine(L"Launcher exited.");
    return 0;
}

} // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, LPWSTR, int) {
    try {
        return runLauncher();
    } catch (const std::exception &e) {
        std::wstring message = L"启动器异常：";
        std::string what = e.what();
        int size = MultiByteToWideChar(CP_UTF8, 0, what.c_str(), -1, nullptr, 0);
        if (size > 1) {
            std::wstring wide(size - 1, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, what.c_str(), -1, wide.data(), size);
            message += wide;
        } else {
            message += L"未知错误";
        }
        showError(message);
        return 1;
    } catch (...) {
        showError(L"启动器发生未知异常。");
        return 1;
    }
}
