import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: dialog
    property real scaleFactor: 1
    property string activeView: "frontend"
    property string gameStateText: "STOPPED"
    property int gameStepCount: 0
    property string gameLastUpdatedAt: ""
    property string gameErrorText: ""
    property bool gameLoading: false

    modal: true
    dim: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    padding: 0
    background: Rectangle {
        color: "transparent"
    }
    Overlay.modal: Rectangle {
        color: Qt.rgba(0, 0, 0, 0.2)
    }
    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 180; easing.type: Easing.OutCubic }
            NumberAnimation { property: "scale"; from: 0.975; to: 1; duration: 220; easing.type: Easing.OutCubic }
        }
    }
    exit: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 130; easing.type: Easing.InCubic }
            NumberAnimation { property: "scale"; from: 1; to: 0.985; duration: 130; easing.type: Easing.InCubic }
        }
    }

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    function normalizedBaseUrl() {
        let value = frontendSettings.backendBaseUrl.trim()
        while (value.endsWith("/")) {
            value = value.slice(0, -1)
        }
        return value.length > 0 ? value : "http://localhost:8080"
    }

    function characterNamesWithEmpty() {
        const names = characterCatalog.characterNames()
        return names.length > 0 ? names : ["未选择"]
    }

    function syncCharacterCombo(combo) {
        const names = combo.model
        const index = names.indexOf(frontendSettings.characterName)
        combo.currentIndex = index >= 0 ? index : 0
    }

    function resolvedGameName() {
        return frontendSettings.gameName.trim().length > 0 ? frontendSettings.gameName.trim() : "STS2MCP"
    }

    function resolvedGameSessionId() {
        const configured = frontendSettings.gameSessionId.trim()
        return configured.length > 0 ? configured : (frontendSettings.sessionId.trim().length > 0 ? frontendSettings.sessionId.trim() : "default")
    }

    function resolvedGameRpSessionId() {
        const configured = frontendSettings.gameRpSessionId.trim()
        return configured.length > 0 ? configured : resolvedGameSessionId()
    }

    function requestJson(method, url, body, done) {
        const xhr = new XMLHttpRequest()
        xhr.onreadystatechange = function() {
            if (xhr.readyState !== 4) {
                return
            }
            let payload = null
            try {
                payload = xhr.responseText.length > 0 ? JSON.parse(xhr.responseText) : null
            } catch (error) {
                payload = null
            }
            done(xhr.status, xhr.statusText, payload)
        }
        xhr.open(method, url)
        xhr.setRequestHeader("Accept", "application/json")
        if (body !== null && body !== undefined) {
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.send(JSON.stringify(body))
        } else {
            xhr.send()
        }
    }

    function refreshGameStatus() {
        gameLoading = true
        gameErrorText = ""
        const url = normalizedBaseUrl()
            + "/api/gamer/loop/status?gameName=" + encodeURIComponent(resolvedGameName())
            + "&sessionId=" + encodeURIComponent(resolvedGameSessionId())
        requestJson("GET", url, null, function(status, statusText, payload) {
            gameLoading = false
            gameLastUpdatedAt = new Date().toLocaleString()
            if (status < 200 || status >= 300 || payload === null) {
                gameErrorText = statusText || "无法读取游戏循环状态。"
                return
            }
            const session = payload.session || {}
            gameStateText = session.state || (payload.running ? "RUNNING" : "STOPPED")
            gameStepCount = session.totalStepCount || 0
        })
    }

    function sendGameCommand(action) {
        gameLoading = true
        gameErrorText = ""
        requestJson("POST", normalizedBaseUrl() + "/api/gamer/loop/" + action, {
            gameName: resolvedGameName(),
            sessionId: resolvedGameSessionId(),
            rpSessionId: resolvedGameRpSessionId()
        }, function(status, statusText, payload) {
            gameLoading = false
            if (status < 200 || status >= 300) {
                gameErrorText = payload && payload.error ? payload.error : (statusText || "游戏循环操作失败。")
                return
            }
            refreshGameStatus()
        })
    }

    onOpened: {
        activeView = "frontend"
    }

    onClosed: {
        frontendSettings.save()
    }

    component UiButton: Button {
        id: button
        property bool primary: false
        property color accentColor: tokens.ink
        focusPolicy: Qt.NoFocus
        leftPadding: dialog.sp(14)
        rightPadding: dialog.sp(14)
        topPadding: dialog.sp(10)
        bottomPadding: dialog.sp(10)
        font.family: tokens.sansFont
        font.pixelSize: dialog.sp(11)
        font.weight: Font.Black
        scale: pressed ? 0.97 : (hovered && enabled ? 1.01 : 1)

        Behavior on scale {
            NumberAnimation { duration: 130; easing.type: Easing.OutCubic }
        }

        contentItem: Text {
            text: button.text
            color: !button.enabled ? tokens.whiteAlpha(0.55) : (button.primary ? "#FFFFFF" : (button.hovered ? "#FFFFFF" : tokens.ink))
            font: button.font
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        background: Rectangle {
            color: !button.enabled ? tokens.inkAlpha(0.35) : (button.primary ? (button.hovered ? tokens.orange : tokens.ink) : (button.hovered ? tokens.ink : "transparent"))
            border.color: button.primary ? tokens.ink : button.accentColor
            border.width: 2
        }
    }

    component UiField: TextField {
        id: field
        Layout.fillWidth: true
        font.family: tokens.sansFont
        font.pixelSize: dialog.sp(14)
        color: tokens.ink
        selectedTextColor: "#FFFFFF"
        selectionColor: tokens.orange
        placeholderTextColor: tokens.inkAlpha(0.4)
        leftPadding: dialog.sp(12)
        rightPadding: dialog.sp(12)
        topPadding: dialog.sp(10)
        bottomPadding: dialog.sp(10)
        background: Rectangle {
            color: tokens.inputPaper
            border.color: field.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
            border.width: 1
        }
    }

    component UiCombo: ComboBox {
        id: combo
        focusPolicy: Qt.NoFocus
        font.family: tokens.sansFont
        font.pixelSize: dialog.sp(14)
        leftPadding: dialog.sp(12)
        rightPadding: dialog.sp(30)
        topPadding: dialog.sp(10)
        bottomPadding: dialog.sp(10)
        contentItem: Text {
            text: combo.displayText
            color: tokens.ink
            font: combo.font
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        indicator: Text {
            x: combo.width - width - dialog.sp(12)
            y: (combo.height - height) / 2
            text: "v"
            color: tokens.inkAlpha(0.55)
            font.pixelSize: dialog.sp(12)
        }
        background: Rectangle {
            color: tokens.inputPaper
            border.color: combo.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
            border.width: 1
        }
    }

    component UiCard: Rectangle {
        id: card
        default property alias content: body.data
        property string title: ""
        property string detail: ""
        Layout.fillWidth: true
        implicitHeight: body.implicitHeight + dialog.sp(40)
        color: tokens.whiteAlpha(0.7)
        border.color: tokens.ink
        border.width: 2
        ColumnLayout {
            id: body
            anchors.fill: parent
            anchors.margins: dialog.sp(18)
            spacing: dialog.sp(12)
            Text {
                Layout.fillWidth: true
                text: card.title
                color: tokens.ink
                font.family: tokens.sansFont
                font.pixelSize: dialog.sp(11)
                font.weight: Font.Black
                elide: Text.ElideRight
            }
            Text {
                Layout.fillWidth: true
                visible: card.detail.length > 0
                text: card.detail
                color: tokens.inkAlpha(0.55)
                font.family: tokens.sansFont
                font.pixelSize: dialog.sp(12)
                wrapMode: Text.WordWrap
            }
        }
    }

    component BlackToggle: Rectangle {
        id: blackToggle
        property string title: ""
        property string detail: ""
        property bool checked: false
        signal toggled(bool value)
        Layout.fillWidth: true
        implicitHeight: Math.max(dialog.sp(78), toggleRow.implicitHeight + dialog.sp(28))
        color: tokens.ink
        border.color: tokens.ink
        border.width: 2
        RowLayout {
            id: toggleRow
            anchors.fill: parent
            anchors.margins: dialog.sp(16)
            spacing: dialog.sp(16)
            ColumnLayout {
                Layout.fillWidth: true
                Layout.minimumWidth: 0
                spacing: dialog.sp(8)
                Text {
                    Layout.fillWidth: true
                    text: blackToggle.title
                    color: "#FFFFFF"
                    font.family: tokens.sansFont
                    font.pixelSize: dialog.sp(11)
                    font.weight: Font.Black
                    elide: Text.ElideRight
                }
                Text {
                    Layout.fillWidth: true
                    text: blackToggle.detail
                    color: tokens.whiteAlpha(0.6)
                    font.family: tokens.sansFont
                    font.pixelSize: dialog.sp(12)
                    wrapMode: Text.WordWrap
                }
            }
            CheckBox {
                checked: blackToggle.checked
                onToggled: blackToggle.toggled(checked)
            }
        }
    }

    contentItem: Item {
        Rectangle {
            x: dialog.sp(18)
            y: dialog.sp(18)
            width: parent.width - dialog.sp(18)
            height: parent.height - dialog.sp(18)
            color: tokens.inkAlpha(0.18)
        }

        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            width: parent.width - dialog.sp(18)
            height: parent.height - dialog.sp(18)
            color: tokens.shell
            border.color: tokens.ink
            border.width: 2
            clip: true

            RowLayout {
                anchors.fill: parent
                spacing: 0

                Rectangle {
                    Layout.preferredWidth: dialog.sp(320)
                    Layout.fillHeight: true
                    color: tokens.panelAlt
                    border.color: tokens.inkAlpha(0.1)
                    border.width: 1

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: dialog.sp(24)
                        spacing: dialog.sp(12)

                        Text {
                            Layout.fillWidth: true
                            text: "系统设置"
                            color: tokens.teal
                            font.family: tokens.monoFont
                            font.pixelSize: dialog.sp(10)
                            elide: Text.ElideRight
                        }
                        Text {
                            Layout.fillWidth: true
                            text: "设置面板"
                            color: tokens.ink
                            font.family: tokens.sansFont
                            font.pixelSize: dialog.sp(30)
                            font.weight: Font.Black
                            wrapMode: Text.WordWrap
                        }
                        Item { Layout.preferredHeight: dialog.sp(16) }

                        Repeater {
                            model: [
                                { key: "frontend", kicker: "本地", label: "前端设置" },
                                { key: "backend", kicker: "配置", label: "本地配置" },
                                { key: "game", kicker: "游戏", label: "游戏模式" },
                                { key: "voice", kicker: "识别", label: "语音调试" }
                            ]
                            Button {
                                id: navButton
                                Layout.fillWidth: true
                                Layout.preferredHeight: dialog.sp(78)
                                focusPolicy: Qt.NoFocus
                                scale: pressed ? 0.985 : (hovered ? 1.008 : 1)

                                Behavior on scale {
                                    NumberAnimation { duration: 140; easing.type: Easing.OutCubic }
                                }

                                onClicked: {
                                    dialog.activeView = modelData.key
                                    if (modelData.key === "game") {
                                        dialog.refreshGameStatus()
                                    }
                                }
                                contentItem: Item {
                                    anchors.fill: parent

                                    Column {
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        anchors.leftMargin: dialog.sp(14)
                                        anchors.rightMargin: dialog.sp(14)
                                        spacing: dialog.sp(8)
                                        Text {
                                            width: parent.width
                                            text: modelData.kicker
                                            color: dialog.activeView === modelData.key ? tokens.whiteAlpha(0.6) : tokens.inkAlpha(0.55)
                                            font.family: tokens.monoFont
                                            font.pixelSize: dialog.sp(10)
                                            elide: Text.ElideRight
                                        }
                                        Text {
                                            width: parent.width
                                            text: modelData.label
                                            color: dialog.activeView === modelData.key ? "#FFFFFF" : tokens.ink
                                            font.family: tokens.sansFont
                                            font.pixelSize: dialog.sp(13)
                                            font.weight: Font.Black
                                            elide: Text.ElideRight
                                        }
                                    }
                                }
                                background: Rectangle {
                                    color: dialog.activeView === modelData.key ? tokens.ink : tokens.whiteAlpha(0.5)
                                    border.color: dialog.activeView === modelData.key ? tokens.ink : (navButton.hovered ? tokens.ink : tokens.inkAlpha(0.15))
                                    border.width: 2

                                    Behavior on color {
                                        ColorAnimation { duration: 150 }
                                    }

                                    Behavior on border.color {
                                        ColorAnimation { duration: 150 }
                                    }
                                }
                            }
                        }

                        Item { Layout.fillHeight: true }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    spacing: 0

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: dialog.sp(82)
                        color: tokens.whiteAlpha(0.5)
                        border.color: tokens.inkAlpha(0.1)
                        border.width: 1
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: dialog.sp(24)
                            anchors.rightMargin: dialog.sp(24)
                            spacing: dialog.sp(16)
                            Text {
                                Layout.fillWidth: true
                                text: dialog.activeView === "frontend" ? "前端设置"
                                      : dialog.activeView === "backend" ? "本地配置"
                                      : dialog.activeView === "game" ? "游戏模式"
                                      : "语音调试"
                                color: tokens.orange
                                font.family: tokens.monoFont
                                font.pixelSize: dialog.sp(10)
                                elide: Text.ElideRight
                            }
                            UiButton {
                                text: "关闭"
                                onClicked: dialog.close()
                            }
                        }
                    }

                    ScrollView {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        contentWidth: availableWidth

                        ColumnLayout {
                            width: dialog.width - dialog.sp(320) - dialog.sp(58)
                            spacing: dialog.sp(24)
                            anchors.margins: dialog.sp(28)

                            ColumnLayout {
                                visible: dialog.activeView === "frontend"
                                Layout.fillWidth: true
                                spacing: dialog.sp(24)

                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(24)
                                    ColumnLayout {
                                        Layout.fillWidth: true
                                        spacing: dialog.sp(8)
                                        Text {
                                            text: "前端设置"
                                            color: tokens.teal
                                            font.family: tokens.monoFont
                                            font.pixelSize: dialog.sp(11)
                                        }
                                        Text {
                                            text: "当前会话"
                                            color: tokens.ink
                                            font.family: tokens.sansFont
                                            font.pixelSize: dialog.sp(30)
                                            font.weight: Font.Black
                                        }
                                    }
                                    UiButton {
                                        text: "重置"
                                        onClicked: frontendSettings.reset()
                                    }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 2
                                    columnSpacing: dialog.sp(24)
                                    rowSpacing: dialog.sp(24)
                                    UiCard {
                                        title: "当前角色"
                                        detail: "角色来自本地角色目录。"
                                        UiCombo {
                                            id: characterCombo
                                            Layout.fillWidth: true
                                            model: dialog.characterNamesWithEmpty()
                                            Component.onCompleted: dialog.syncCharacterCombo(characterCombo)
                                            onActivated: {
                                                if (currentText !== "未选择") {
                                                    frontendSettings.characterName = currentText
                                                    chatSession.selectCharacter(currentText)
                                                }
                                            }
                                            Connections {
                                                target: characterCatalog
                                                function onCharactersChanged() {
                                                    characterCombo.model = dialog.characterNamesWithEmpty()
                                                    dialog.syncCharacterCombo(characterCombo)
                                                }
                                            }
                                        }
                                    }
                                    UiCard {
                                        title: "当前后端地址"
                                        detail: "保存在本地客户端配置里。"
                                        UiField {
                                            text: frontendSettings.backendBaseUrl
                                            onEditingFinished: frontendSettings.backendBaseUrl = text
                                        }
                                    }
                                    UiCard {
                                        title: "会话 ID"
                                        detail: "聊天请求会使用这个会话标识。"
                                        UiField {
                                            text: frontendSettings.sessionId
                                            onEditingFinished: frontendSettings.sessionId = text
                                        }
                                    }
                                    UiCard {
                                        title: "角色目录"
                                        detail: characterCatalog.charactersPath.length > 0 ? characterCatalog.charactersPath : "未找到角色目录。"
                                        UiButton {
                                            text: "打开目录"
                                            onClicked: characterCatalog.openCharactersFolder()
                                        }
                                    }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 2
                                    columnSpacing: dialog.sp(16)
                                    rowSpacing: dialog.sp(16)
                                    BlackToggle {
                                        title: "短句模式"
                                        detail: "关闭后会让后端停用短句模式。"
                                        checked: frontendSettings.shortModeEnabled
                                        onToggled: function(value) {
                                            frontendSettings.shortModeEnabled = value
                                        }
                                    }
                                    BlackToggle {
                                        title: "启动动画"
                                        detail: "开启或关闭启动动画。"
                                        checked: frontendSettings.bootAnimationEnabled
                                        onToggled: function(value) {
                                            frontendSettings.bootAnimationEnabled = value
                                        }
                                    }
                                }
                            }

                            ColumnLayout {
                                visible: dialog.activeView === "backend"
                                Layout.fillWidth: true
                                spacing: dialog.sp(20)
                                Text {
                                    text: "本地配置"
                                    color: tokens.ink
                                    font.family: tokens.sansFont
                                    font.pixelSize: dialog.sp(30)
                                    font.weight: Font.Black
                                }
                                UiCard {
                                    title: "当前连接地址"
                                    detail: "主界面和语音链路都会使用这个后端地址。"
                                    UiField {
                                        text: frontendSettings.backendBaseUrl
                                        onEditingFinished: frontendSettings.backendBaseUrl = text
                                    }
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: "完整配置编辑器会在设置页迁移阶段恢复。当前阶段先保证主界面迁移和必要连接设置可用。"
                                    color: tokens.inkAlpha(0.6)
                                    font.family: tokens.sansFont
                                    font.pixelSize: dialog.sp(13)
                                    wrapMode: Text.WordWrap
                                }
                            }

                            ColumnLayout {
                                visible: dialog.activeView === "game"
                                Layout.fillWidth: true
                                spacing: dialog.sp(20)
                                Text {
                                    text: "游戏控制"
                                    color: tokens.ink
                                    font.family: tokens.sansFont
                                    font.pixelSize: dialog.sp(30)
                                    font.weight: Font.Black
                                }
                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 3
                                    columnSpacing: dialog.sp(24)
                                    rowSpacing: dialog.sp(18)
                                    UiCard { title: "游戏名"; UiField { text: frontendSettings.gameName; onEditingFinished: frontendSettings.gameName = text } }
                                    UiCard { title: "游戏会话"; detail: "留空时使用聊天会话。"; UiField { text: frontendSettings.gameSessionId; onEditingFinished: frontendSettings.gameSessionId = text } }
                                    UiCard { title: "RP 会话"; detail: "留空时使用聊天会话。"; UiField { text: frontendSettings.gameRpSessionId; onEditingFinished: frontendSettings.gameRpSessionId = text } }
                                }
                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 3
                                    columnSpacing: dialog.sp(18)
                                    UiCard { title: "状态"; Text { text: dialog.gameStateText; color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(24); font.weight: Font.Black } }
                                    UiCard { title: "步数"; Text { text: String(dialog.gameStepCount); color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(24); font.weight: Font.Black } }
                                    UiCard { title: "刷新"; Text { text: dialog.gameLastUpdatedAt || "未刷新"; color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(13); elide: Text.ElideRight } }
                                }
                                Text {
                                    Layout.fillWidth: true
                                    visible: dialog.gameErrorText.length > 0
                                    text: dialog.gameErrorText
                                    color: "#9A3412"
                                    font.family: tokens.sansFont
                                    font.pixelSize: dialog.sp(14)
                                    wrapMode: Text.WordWrap
                                }
                                Row {
                                    spacing: dialog.sp(12)
                                    UiButton { text: "启动"; primary: true; enabled: !dialog.gameLoading; onClicked: dialog.sendGameCommand("start") }
                                    UiButton { text: "暂停"; enabled: !dialog.gameLoading; onClicked: dialog.sendGameCommand("pause") }
                                    UiButton { text: "恢复"; enabled: !dialog.gameLoading; onClicked: dialog.sendGameCommand("resume") }
                                    UiButton { text: "停止"; enabled: !dialog.gameLoading; onClicked: dialog.sendGameCommand("stop") }
                                    UiButton { text: "刷新"; accentColor: tokens.teal; enabled: !dialog.gameLoading; onClicked: dialog.refreshGameStatus() }
                                }
                            }

                            ColumnLayout {
                                visible: dialog.activeView === "voice"
                                Layout.fillWidth: true
                                spacing: dialog.sp(24)
                                Text {
                                    text: "语音调试"
                                    color: tokens.ink
                                    font.family: tokens.sansFont
                                    font.pixelSize: dialog.sp(30)
                                    font.weight: Font.Black
                                }
                                BlackToggle {
                                    title: "只调试不执行"
                                    detail: "运行语音识别和意图路由，但不把识别文本发给大模型，也不执行游戏副作用。"
                                    checked: frontendSettings.voiceDebugEnabled
                                    onToggled: function(value) {
                                        frontendSettings.voiceDebugEnabled = value
                                    }
                                }
                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 2
                                    columnSpacing: dialog.sp(24)
                                    rowSpacing: dialog.sp(18)
                                    UiCard {
                                        title: "音频来源"
                                        UiCombo {
                                            Layout.fillWidth: true
                                            model: ["麦克风", "应用声音"]
                                            currentIndex: sttAudio.sourceMode
                                            onActivated: sttAudio.sourceMode = currentIndex
                                        }
                                    }
                                    UiCard {
                                        title: "采集目标"
                                        UiCombo {
                                            Layout.fillWidth: true
                                            model: sttAudio.sourceMode === 1 ? sttAudio.processNames : sttAudio.deviceNames
                                            currentIndex: sttAudio.sourceMode === 1
                                                          ? Math.max(0, Math.min(sttAudio.selectedProcessIndex, count - 1))
                                                          : Math.max(0, Math.min(sttAudio.selectedDeviceIndex, count - 1))
                                            onActivated: {
                                                if (sttAudio.sourceMode === 1) {
                                                    sttAudio.selectedProcessIndex = currentIndex
                                                } else {
                                                    sttAudio.selectedDeviceIndex = currentIndex
                                                }
                                            }
                                        }
                                    }
                                }
                                UiCard {
                                    title: "实时链路"
                                    RowLayout {
                                        Layout.fillWidth: true
                                        spacing: dialog.sp(12)
                                        Rectangle {
                                            Layout.fillWidth: true
                                            Layout.preferredHeight: dialog.sp(34)
                                            radius: dialog.sp(17)
                                            color: tokens.inkAlpha(0.08)
                                            clip: true
                                            Rectangle {
                                                anchors.left: parent.left
                                                anchors.top: parent.top
                                                anchors.bottom: parent.bottom
                                                width: parent.width * sttAudio.audioLevel
                                                radius: dialog.sp(17)
                                                color: sttAudio.running ? tokens.teal : tokens.inkAlpha(0.4)
                                            }
                                            Text {
                                                anchors.centerIn: parent
                                                text: sttAudio.backendConnected ? "后端识别" : "本地预览"
                                                color: tokens.ink
                                                font.family: tokens.monoFont
                                                font.pixelSize: dialog.sp(10)
                                                font.weight: Font.Black
                                            }
                                        }
                                        UiButton {
                                            text: "刷新"
                                            enabled: !sttAudio.running
                                            onClicked: {
                                                sttAudio.refreshDevices()
                                                sttAudio.refreshProcesses()
                                            }
                                        }
                                        UiButton {
                                            text: sttAudio.running ? "停止" : "监听"
                                            primary: sttAudio.running
                                            onClicked: sttAudio.running ? sttAudio.stop() : sttAudio.start()
                                        }
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: sttAudio.statusText
                                        color: tokens.inkAlpha(0.55)
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(12)
                                        wrapMode: Text.WrapAnywhere
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: sttAudio.transcriptText.length > 0 ? sttAudio.transcriptText : "暂无识别文本。"
                                        color: tokens.ink
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(14)
                                        wrapMode: Text.WordWrap
                                    }
                                }
                                UiCard {
                                    title: "识别审计"
                                    RowLayout {
                                        Layout.fillWidth: true
                                        Text {
                                            Layout.fillWidth: true
                                            text: "事件数：" + sttAudio.debugEventCount
                                            color: tokens.ink
                                            font.family: tokens.sansFont
                                            font.pixelSize: dialog.sp(13)
                                            font.weight: Font.Black
                                        }
                                        UiButton { text: "导出"; onClicked: sttAudio.exportDebugEvents() }
                                        UiButton { text: "清空"; onClicked: sttAudio.clearDebugEvents() }
                                    }
                                    ListView {
                                        Layout.fillWidth: true
                                        Layout.preferredHeight: dialog.sp(260)
                                        clip: true
                                        spacing: dialog.sp(8)
                                        model: sttAudio.debugEvents
                                        delegate: Rectangle {
                                            width: ListView.view.width
                                            implicitHeight: eventText.implicitHeight + dialog.sp(20)
                                            color: tokens.whiteAlpha(0.8)
                                            border.color: tokens.inkAlpha(0.15)
                                            border.width: 1
                                            Text {
                                                id: eventText
                                                anchors.fill: parent
                                                anchors.margins: dialog.sp(10)
                                                text: modelData
                                                color: tokens.ink
                                                font.family: tokens.monoFont
                                                font.pixelSize: dialog.sp(11)
                                                wrapMode: Text.WrapAnywhere
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
