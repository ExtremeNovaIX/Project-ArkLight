import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: dialog
    property real scaleFactor: 1
    property string activeView: "frontend"
    property string activeHistoryKey: ""
    property string gameStateText: "STOPPED"
    property int gameStepCount: 0
    property string gameSessionText: ""
    property string gameRpSessionText: ""
    property string gameStartedAt: ""
    property string gameLastActivityAt: ""
    property string gameLastUpdatedAt: ""
    property string gameErrorText: ""
    property bool gameLoading: false

    modal: true
    dim: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    padding: 0
    background: Rectangle { color: "transparent" }
    Overlay.modal: Rectangle { color: tokens.inkAlpha(0.45) }

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

    function fieldKey(fileName, key) {
        return fileName + ":" + key
    }

    function characterNameModel() {
        const names = characterCatalog.characterNames()
        return names.length > 0 ? ["未选择"].concat(names) : ["未选择"]
    }

    function resolvedGameName() {
        return frontendSettings.gameName.trim().length > 0 ? frontendSettings.gameName.trim() : "STS2MCP"
    }

    function resolvedGameSessionId() {
        const configured = frontendSettings.gameSessionId.trim()
        return configured.length > 0
            ? configured
            : (frontendSettings.sessionId.trim().length > 0 ? frontendSettings.sessionId.trim() : "default")
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
            gameSessionText = session.sessionId || resolvedGameSessionId()
            gameRpSessionText = session.rpSessionId || resolvedGameRpSessionId()
            gameStartedAt = session.startedAt || "-"
            gameLastActivityAt = session.lastActivityAt || "-"
        })
    }

    function sendGameCommand(action) {
        gameLoading = true
        gameErrorText = ""
        requestJson("POST", normalizedBaseUrl() + "/api/gamer/loop/" + action, {
            gameName: resolvedGameName(),
            sessionId: resolvedGameSessionId(),
            rpSessionId: resolvedGameRpSessionId(),
            characterName: frontendSettings.characterName,
            shortMode: frontendSettings.shortModeEnabled
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
        configCatalog.fetchConfigs()
    }

    onClosed: {
        configCatalog.saveChangedConfigs()
        frontendSettings.save()
        activeHistoryKey = ""
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
        scale: pressed ? 0.95 : (hovered && enabled ? 1.01 : 1)

        Behavior on scale { NumberAnimation { duration: 130; easing.type: Easing.OutCubic } }

        contentItem: Text {
            text: button.text
            color: !button.enabled
                   ? tokens.whiteAlpha(0.55)
                   : (button.primary ? "#FFFFFF" : (button.hovered ? "#FFFFFF" : button.accentColor))
            font: button.font
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }

        background: Rectangle {
            radius: dialog.sp(tokens.radiusFrame)
            color: !button.enabled
                   ? tokens.inkAlpha(0.35)
                   : (button.primary ? (button.hovered ? tokens.orange : tokens.ink) : (button.hovered ? button.accentColor : "transparent"))
            border.color: button.primary ? tokens.ink : button.accentColor
            border.width: 2
            Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
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
        implicitHeight: dialog.sp(46)
        horizontalAlignment: TextInput.AlignLeft
        verticalAlignment: TextInput.AlignVCenter
        selectByMouse: true
        background: Rectangle {
            radius: dialog.sp(tokens.radiusFrame)
            color: tokens.inputPaper
            border.color: field.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
            border.width: 1
            Behavior on border.color { ColorAnimation { duration: tokens.fastMotion } }
        }
    }

    component UiCombo: ComboBox {
        id: combo
        Layout.fillWidth: true
        focusPolicy: Qt.NoFocus
        font.family: tokens.sansFont
        font.pixelSize: dialog.sp(14)
        leftPadding: dialog.sp(12)
        rightPadding: dialog.sp(30)
        topPadding: dialog.sp(10)
        bottomPadding: dialog.sp(10)
        implicitHeight: dialog.sp(46)
        contentItem: Text {
            text: combo.displayText
            color: tokens.ink
            font: combo.font
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        indicator: Canvas {
            id: comboChevron
            x: combo.width - width - dialog.sp(13)
            y: (combo.height - height) / 2
            width: dialog.sp(12)
            height: dialog.sp(8)
            rotation: combo.popup.visible ? 180 : 0

            Behavior on rotation { NumberAnimation { duration: 170; easing.type: Easing.OutCubic } }

            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                ctx.strokeStyle = tokens.inkAlpha(combo.enabled ? 0.55 : 0.24)
                ctx.lineWidth = Math.max(1, dialog.sp(1.4))
                ctx.lineCap = "round"
                ctx.lineJoin = "round"
                ctx.beginPath()
                ctx.moveTo(dialog.sp(1), dialog.sp(1))
                ctx.lineTo(width / 2, height - dialog.sp(1))
                ctx.lineTo(width - dialog.sp(1), dialog.sp(1))
                ctx.stroke()
            }

            onWidthChanged: requestPaint()
            onHeightChanged: requestPaint()
            onRotationChanged: requestPaint()
        }
        background: Rectangle {
            radius: dialog.sp(tokens.radiusFrame)
            color: tokens.inputPaper
            border.color: combo.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
            border.width: 1
        }
        delegate: ItemDelegate {
            id: comboDelegate
            width: combo.popup.width - dialog.sp(8)
            height: dialog.sp(38)
            leftPadding: dialog.sp(12)
            rightPadding: dialog.sp(12)
            text: modelData
            highlighted: combo.highlightedIndex === index
            font.family: tokens.sansFont
            font.pixelSize: dialog.sp(13)
            contentItem: Text {
                text: comboDelegate.text
                color: comboDelegate.highlighted ? "#FFFFFF" : tokens.ink
                font: comboDelegate.font
                elide: Text.ElideRight
                verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                radius: dialog.sp(tokens.radiusFrame)
                color: comboDelegate.highlighted ? tokens.ink : "transparent"
                Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
            }
        }
        popup: Popup {
            y: combo.height + dialog.sp(6)
            width: combo.width
            implicitHeight: Math.min(contentItem.implicitHeight + dialog.sp(8), dialog.sp(260))
            padding: dialog.sp(4)
            transformOrigin: Item.Top
            opacity: 0
            scale: 0.985
            enter: Transition {
                ParallelAnimation {
                    NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 150; easing.type: Easing.OutCubic }
                    NumberAnimation { property: "scale"; from: 0.985; to: 1; duration: 180; easing.type: Easing.OutCubic }
                    NumberAnimation { property: "y"; from: combo.height; to: combo.height + dialog.sp(6); duration: 180; easing.type: Easing.OutCubic }
                }
            }
            exit: Transition {
                ParallelAnimation {
                    NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 110; easing.type: Easing.InCubic }
                    NumberAnimation { property: "scale"; from: 1; to: 0.985; duration: 110; easing.type: Easing.InCubic }
                }
            }
            contentItem: ListView {
                clip: true
                implicitHeight: contentHeight
                model: combo.popup.visible ? combo.delegateModel : null
                currentIndex: combo.highlightedIndex
                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                    contentItem: Rectangle {
                        implicitWidth: dialog.sp(4)
                        radius: dialog.sp(2)
                        color: tokens.inkAlpha(0.18)
                    }
                    background: Rectangle { color: "transparent" }
                }
            }
            background: Rectangle {
                radius: dialog.sp(tokens.radiusFrame)
                color: tokens.paperLight
                border.color: tokens.inkAlpha(0.24)
                border.width: 1
            }
        }
    }

    component UiCard: Rectangle {
        id: card
        default property alias content: body.data
        property string title: ""
        property string detail: ""
        Layout.fillWidth: true
        implicitHeight: Math.max(dialog.sp(112), body.implicitHeight + dialog.sp(40))
        radius: dialog.sp(tokens.radiusFrame)
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
                font.capitalization: Font.AllUppercase
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
        radius: dialog.sp(tokens.radiusFrame)
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
                    font.capitalization: Font.AllUppercase
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

    component NavButton: Button {
        id: navButton
        property string viewKey: ""
        property string kicker: ""
        property string label: ""
        Layout.fillWidth: true
        Layout.preferredHeight: dialog.sp(78)
        focusPolicy: Qt.NoFocus
        scale: pressed ? 0.985 : (hovered ? 1.008 : 1)

        Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }

        onClicked: {
            dialog.activeView = viewKey
            dialog.activeHistoryKey = ""
            if (viewKey === "backend") {
                if (configCatalog.configPages.length === 0) {
                    configCatalog.fetchConfigs()
                }
            } else if (viewKey === "game") {
                dialog.refreshGameStatus()
            }
        }

        contentItem: Column {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: dialog.sp(14)
            anchors.rightMargin: dialog.sp(14)
            spacing: dialog.sp(8)
            Text {
                width: parent.width
                text: navButton.kicker
                color: dialog.activeView === navButton.viewKey ? tokens.whiteAlpha(0.6) : tokens.inkAlpha(0.55)
                font.family: tokens.monoFont
                font.pixelSize: dialog.sp(10)
                font.capitalization: Font.AllUppercase
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: navButton.label
                color: dialog.activeView === navButton.viewKey ? "#FFFFFF" : tokens.ink
                font.family: tokens.sansFont
                font.pixelSize: dialog.sp(13)
                font.weight: Font.Black
                elide: Text.ElideRight
            }
        }

        background: Rectangle {
            radius: dialog.sp(tokens.radiusFrame)
            color: dialog.activeView === navButton.viewKey ? tokens.ink : tokens.whiteAlpha(0.5)
            border.color: dialog.activeView === navButton.viewKey ? tokens.ink : (navButton.hovered ? tokens.ink : tokens.inkAlpha(0.15))
            border.width: 2
            Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
            Behavior on border.color { ColorAnimation { duration: tokens.fastMotion } }
        }
    }

    component SectionTitle: ColumnLayout {
        id: sectionTitle
        property string eyebrow: ""
        property string title: ""
        Layout.fillWidth: true
        spacing: dialog.sp(8)
        Text {
            Layout.fillWidth: true
            text: sectionTitle.eyebrow
            color: tokens.teal
            font.family: tokens.monoFont
            font.pixelSize: dialog.sp(10)
            font.capitalization: Font.AllUppercase
            elide: Text.ElideRight
        }
        Text {
            Layout.fillWidth: true
            text: sectionTitle.title
            color: tokens.ink
            font.family: tokens.sansFont
            font.pixelSize: dialog.sp(30)
            font.weight: Font.Black
            wrapMode: Text.WordWrap
        }
    }

    component ConfigFieldEditor: Rectangle {
        id: editor
        property var page
        property var field
        property string fileName: page && page.fileName ? page.fileName : ""
        property string fieldName: field && field.key ? field.key : ""
        property string fieldType: field && field.type ? field.type : "text"
        property var fieldOptions: field && field.options ? field.options : []
        property string fieldLabel: field && field.label ? field.label : fieldName
        property string fieldDescription: field && field.description ? field.description : ""
        property string fieldPath: field && field.path ? field.path : fieldName
        property string fieldPlaceholder: field && field.placeholder ? field.placeholder : ""
        property string storageKey: dialog.fieldKey(fileName, fieldName)
        property int valuesRevision: configCatalog.valuesRevision
        Layout.fillWidth: true
        implicitHeight: Math.max(dialog.sp(132), editorColumn.implicitHeight + dialog.sp(32))
        radius: dialog.sp(tokens.radiusFrame)
        color: tokens.whiteAlpha(0.78)
        border.color: fieldHover.hovered ? tokens.ink : tokens.inkAlpha(0.15)
        border.width: 1

        HoverHandler {
            id: fieldHover
        }

        ColumnLayout {
            id: editorColumn
            anchors.fill: parent
            anchors.margins: dialog.sp(16)
            spacing: dialog.sp(12)

            ColumnLayout {
                Layout.fillWidth: true
                spacing: dialog.sp(7)
                Text {
                    Layout.fillWidth: true
                    text: editor.fieldLabel
                    color: tokens.ink
                    font.family: tokens.sansFont
                    font.pixelSize: dialog.sp(13)
                    font.weight: Font.Black
                    wrapMode: Text.WordWrap
                }
                Text {
                    Layout.fillWidth: true
                    visible: editor.fieldDescription.length > 0
                    text: editor.fieldDescription
                    color: tokens.inkAlpha(0.5)
                    font.family: tokens.sansFont
                    font.pixelSize: dialog.sp(11)
                    wrapMode: Text.WordWrap
                }
                Text {
                    Layout.fillWidth: true
                    text: editor.fieldPath
                    color: tokens.inkAlpha(0.38)
                    font.family: tokens.monoFont
                    font.pixelSize: dialog.sp(10)
                    wrapMode: Text.WrapAnywhere
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.minimumWidth: 0
                Layout.preferredHeight: Math.max(dialog.sp(46), inputStack.implicitHeight)

                ColumnLayout {
                    id: inputStack
                    anchors.fill: parent
                    spacing: dialog.sp(6)

                    UiCombo {
                        visible: editor.fieldType === "select"
                        model: editor.fieldOptions
                        currentIndex: {
                            editor.valuesRevision
                            return Math.max(0, editor.fieldOptions.indexOf(String(configCatalog.fieldValue(editor.fileName, editor.fieldName))))
                        }
                        onActivated: configCatalog.setFieldValue(editor.fileName, editor.fieldName, currentText)
                    }

                    Rectangle {
                        visible: editor.fieldType === "boolean"
                        Layout.fillWidth: true
                        implicitHeight: dialog.sp(44)
                        radius: dialog.sp(tokens.radiusFrame)
                        color: tokens.ink
                        border.color: tokens.ink
                        border.width: 1
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: dialog.sp(12)
                            anchors.rightMargin: dialog.sp(12)
                            Text {
                                Layout.fillWidth: true
                                text: {
                                    editor.valuesRevision
                                    return Boolean(configCatalog.fieldValue(editor.fileName, editor.fieldName)) ? "已开启" : "已关闭"
                                }
                                color: "#FFFFFF"
                                font.family: tokens.sansFont
                                font.pixelSize: dialog.sp(13)
                            }
                            CheckBox {
                                checked: {
                                    editor.valuesRevision
                                    return Boolean(configCatalog.fieldValue(editor.fileName, editor.fieldName))
                                }
                                onClicked: configCatalog.setFieldValue(editor.fileName, editor.fieldName, checked)
                            }
                        }
                    }

                    TextArea {
                        id: areaInput
                        visible: editor.fieldType === "textarea" || editor.fieldType === "list"
                        Layout.fillWidth: true
                        Layout.preferredHeight: editor.fieldType === "list" ? dialog.sp(124) : dialog.sp(102)
                        text: {
                            editor.valuesRevision
                            return String(configCatalog.fieldValue(editor.fileName, editor.fieldName) || "")
                        }
                        placeholderText: editor.fieldType === "list" ? "每行一条" : editor.fieldPlaceholder
                        wrapMode: TextEdit.WrapAnywhere
                        color: tokens.ink
                        placeholderTextColor: tokens.inkAlpha(0.4)
                        selectedTextColor: "#FFFFFF"
                        selectionColor: tokens.orange
                        font.family: tokens.sansFont
                        font.pixelSize: dialog.sp(13)
                        leftPadding: dialog.sp(12)
                        rightPadding: dialog.sp(12)
                        topPadding: dialog.sp(10)
                        bottomPadding: dialog.sp(10)
                        selectByMouse: true
                        background: Rectangle {
                            radius: dialog.sp(tokens.radiusFrame)
                            color: tokens.inputPaper
                            border.color: areaInput.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
                            border.width: 1
                        }
                        onActiveFocusChanged: {
                            if (activeFocus && configCatalog.fieldSupportsHistory(field || {})) {
                                dialog.activeHistoryKey = editor.storageKey
                            }
                        }
                        onTextChanged: {
                            if (activeFocus) {
                                configCatalog.setFieldValue(editor.fileName, editor.fieldName, text)
                            }
                        }
                    }

                    UiField {
                        id: textInput
                        visible: editor.fieldType !== "select" && editor.fieldType !== "boolean" && editor.fieldType !== "textarea" && editor.fieldType !== "list"
                        text: {
                            editor.valuesRevision
                            return String(configCatalog.fieldValue(editor.fileName, editor.fieldName) || "")
                        }
                        placeholderText: editor.fieldPlaceholder
                        echoMode: TextInput.Normal
                        inputMethodHints: editor.fieldType === "number" ? Qt.ImhFormattedNumbersOnly : Qt.ImhNone
                        onActiveFocusChanged: {
                            if (activeFocus && configCatalog.fieldSupportsHistory(field || {})) {
                                dialog.activeHistoryKey = editor.storageKey
                            }
                        }
                        onTextEdited: configCatalog.setFieldValue(editor.fileName, editor.fieldName, text)
                    }

                    Row {
                        visible: dialog.activeHistoryKey === editor.storageKey
                                 && configCatalog.fieldSupportsHistory(field || {})
                                 && configCatalog.historyFor(editor.fileName, editor.fieldName).length > 0
                        spacing: dialog.sp(6)
                        Repeater {
                            model: configCatalog.historyFor(editor.fileName, editor.fieldName)
                            Button {
                                id: historyButton
                                text: modelData
                                focusPolicy: Qt.NoFocus
                                font.family: tokens.monoFont
                                font.pixelSize: dialog.sp(10)
                                onClicked: {
                                    configCatalog.applyHistory(editor.fileName, editor.fieldName, modelData)
                                    dialog.activeHistoryKey = ""
                                }
                                contentItem: Text {
                                    text: historyButton.text
                                    color: tokens.inkAlpha(0.76)
                                    font: historyButton.font
                                    elide: Text.ElideRight
                                    verticalAlignment: Text.AlignVCenter
                                }
                                background: Rectangle {
                                    radius: dialog.sp(tokens.radiusFrame)
                                    color: tokens.inputPaper
                                    border.color: tokens.inkAlpha(0.18)
                                    border.width: 1
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    contentItem: Item {
        implicitWidth: dialog.sp(1180)
        implicitHeight: dialog.sp(720)

        Rectangle {
            x: dialog.sp(18)
            y: dialog.sp(18)
            width: parent.width - dialog.sp(18)
            height: parent.height - dialog.sp(18)
            radius: dialog.sp(tokens.radiusFrame)
            color: tokens.inkAlpha(0.18)
        }

        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            width: parent.width - dialog.sp(18)
            height: parent.height - dialog.sp(18)
            radius: dialog.sp(tokens.radiusFrame)
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
                    radius: dialog.sp(tokens.radiusFrame)
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
                            font.capitalization: Font.AllUppercase
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

                        NavButton { viewKey: "frontend"; kicker: "本地"; label: "前端设置" }
                        NavButton { viewKey: "backend"; kicker: "Config"; label: "本地配置" }
                        NavButton { viewKey: "game"; kicker: "Game"; label: "游戏模式" }
                        NavButton { viewKey: "voice"; kicker: "Voice"; label: "语音调试" }

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
                        radius: dialog.sp(tokens.radiusFrame)
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
                                font.capitalization: Font.AllUppercase
                                elide: Text.ElideRight
                            }
                            UiButton {
                                text: "关闭"
                                onClicked: dialog.close()
                            }
                        }
                    }

                    ScrollView {
                        id: settingsScroll
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        contentWidth: availableWidth

                        ColumnLayout {
                            width: Math.max(0, settingsScroll.availableWidth - dialog.sp(56))
                            x: dialog.sp(28)
                            y: dialog.sp(28)
                            spacing: dialog.sp(24)

                            ColumnLayout {
                                visible: dialog.activeView === "frontend"
                                Layout.fillWidth: true
                                spacing: dialog.sp(24)

                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(20)
                                    SectionTitle { eyebrow: "前端设置"; title: "当前会话" }
                                    UiButton {
                                        text: "重置"
                                        onClicked: frontendSettings.reset()
                                    }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1080) ? 2 : 1
                                    columnSpacing: dialog.sp(20)
                                    rowSpacing: dialog.sp(20)

                                    UiCard {
                                        title: "界面主题"
                                        detail: "当前 Qt 版本先复刻 ArkLight 默认主题。"
                                        UiCombo {
                                            model: ["默认主题"]
                                            currentIndex: 0
                                            onActivated: frontendSettings.themeId = "arklight"
                                        }
                                    }
                                    UiCard {
                                        title: "当前角色"
                                        detail: "角色来自项目 chara 目录。"
                                        UiCombo {
                                            model: dialog.characterNameModel()
                                            currentIndex: Math.max(0, dialog.characterNameModel().indexOf(frontendSettings.characterName))
                                            onActivated: {
                                                if (currentText === "未选择") {
                                                    frontendSettings.characterName = ""
                                                } else {
                                                    frontendSettings.characterName = currentText
                                                    chatSession.selectCharacter(currentText)
                                                }
                                            }
                                        }
                                    }
                                    UiCard {
                                        title: "当前后端地址"
                                        detail: "主界面、配置编辑器、游戏控制和语音链路都会使用这个地址。"
                                        UiField {
                                            text: frontendSettings.backendBaseUrl
                                            onEditingFinished: frontendSettings.backendBaseUrl = text
                                        }
                                    }
                                    UiCard {
                                        title: "Session ID"
                                        detail: "聊天请求和主动消息订阅会使用这个会话标识。"
                                        UiField {
                                            text: frontendSettings.sessionId
                                            onEditingFinished: frontendSettings.sessionId = text
                                        }
                                    }
                                    UiCard {
                                        title: "工作区名称"
                                        detail: "显示在左侧舞台和启动遮罩中。"
                                        UiField {
                                            text: frontendSettings.workspaceName
                                            onEditingFinished: frontendSettings.workspaceName = text
                                        }
                                    }
                                    UiCard {
                                        title: "操作员名称"
                                        detail: "用于用户消息标签。"
                                        UiField {
                                            text: frontendSettings.operatorName
                                            onEditingFinished: frontendSettings.operatorName = text
                                        }
                                    }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1080) ? 2 : 1
                                    columnSpacing: dialog.sp(16)
                                    rowSpacing: dialog.sp(16)
                                    BlackToggle {
                                        title: "短句模式"
                                        detail: "关闭后会让后端 shortMode=false，并停用按句长调整的显示间隔。"
                                        checked: frontendSettings.shortModeEnabled
                                        onToggled: function(value) { frontendSettings.shortModeEnabled = value }
                                    }
                                    BlackToggle {
                                        title: "启动动画"
                                        detail: "开启或关闭 ArkLight 启动遮罩。"
                                        checked: frontendSettings.bootAnimationEnabled
                                        onToggled: function(value) { frontendSettings.bootAnimationEnabled = value }
                                    }
                                }

                                Rectangle {
                                    Layout.fillWidth: true
                                    height: 1
                                    color: tokens.inkAlpha(0.1)
                                }

                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(16)
                                    SectionTitle { eyebrow: "角色选择"; title: "Chara" }
                                    UiButton {
                                        text: "刷新"
                                        onClicked: characterCatalog.reload()
                                    }
                                    UiButton {
                                        text: "打开目录"
                                        accentColor: tokens.teal
                                        onClicked: characterCatalog.openCharactersFolder()
                                    }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1250) ? 3 : (dialog.width > dialog.sp(940) ? 2 : 1)
                                    columnSpacing: dialog.sp(16)
                                    rowSpacing: dialog.sp(16)
                                    Repeater {
                                        model: characterCatalog.characters
                                        Rectangle {
                                            Layout.fillWidth: true
                                            implicitHeight: dialog.sp(334)
                                            radius: dialog.sp(tokens.radiusFrame)
                                            color: "#FFFFFF"
                                            border.color: frontendSettings.characterName === modelData.name ? tokens.teal : tokens.inkAlpha(0.1)
                                            border.width: 2
                                            clip: true

                                            ColumnLayout {
                                                anchors.fill: parent
                                                anchors.margins: dialog.sp(14)
                                                spacing: dialog.sp(12)
                                                Rectangle {
                                                    Layout.fillWidth: true
                                                    Layout.preferredHeight: dialog.sp(208)
                                                    radius: dialog.sp(tokens.radiusFrame)
                                                    color: tokens.inputPaper
                                                    border.color: tokens.inkAlpha(0.1)
                                                    border.width: 1
                                                    clip: true
                                                    Image {
                                                        anchors.fill: parent
                                                        anchors.margins: dialog.sp(10)
                                                        source: modelData.iconUrl
                                                        fillMode: Image.PreserveAspectFit
                                                    }
                                                    Rectangle {
                                                        anchors.left: parent.left
                                                        anchors.top: parent.top
                                                        anchors.leftMargin: dialog.sp(10)
                                                        anchors.topMargin: dialog.sp(10)
                                                        radius: dialog.sp(tokens.radiusFrame)
                                                        color: tokens.whiteAlpha(0.9)
                                                        implicitWidth: emotionText.implicitWidth + dialog.sp(16)
                                                        implicitHeight: emotionText.implicitHeight + dialog.sp(8)
                                                        Text {
                                                            id: emotionText
                                                            anchors.centerIn: parent
                                                            text: modelData.defaultEmotion || "default"
                                                            color: tokens.teal
                                                            font.family: tokens.monoFont
                                                            font.pixelSize: dialog.sp(10)
                                                        }
                                                    }
                                                }
                                                RowLayout {
                                                    Layout.fillWidth: true
                                                    spacing: dialog.sp(12)
                                                    ColumnLayout {
                                                        Layout.fillWidth: true
                                                        Layout.minimumWidth: 0
                                                        spacing: dialog.sp(4)
                                                        Text {
                                                            Layout.fillWidth: true
                                                            text: modelData.name
                                                            color: tokens.ink
                                                            font.family: tokens.sansFont
                                                            font.pixelSize: dialog.sp(18)
                                                            font.weight: Font.Black
                                                            elide: Text.ElideRight
                                                        }
                                                        Text {
                                                            Layout.fillWidth: true
                                                            text: "表情数: " + (modelData.emotions ? modelData.emotions.length : 0)
                                                            color: tokens.inkAlpha(0.55)
                                                            font.family: tokens.sansFont
                                                            font.pixelSize: dialog.sp(12)
                                                        }
                                                    }
                                                    UiButton {
                                                        text: frontendSettings.characterName === modelData.name ? "使用中" : "使用"
                                                        primary: frontendSettings.characterName === modelData.name
                                                        accentColor: tokens.teal
                                                        onClicked: {
                                                            frontendSettings.characterName = modelData.name
                                                            chatSession.selectCharacter(modelData.name)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ColumnLayout {
                                visible: dialog.activeView === "backend"
                                Layout.fillWidth: true
                                spacing: dialog.sp(20)

                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(12)
                                    SectionTitle { eyebrow: "本地配置"; title: "配置编辑" }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: 1
                                    columnSpacing: dialog.sp(16)
                                    rowSpacing: dialog.sp(16)
                                    UiCard {
                                        title: "配置目录"
                                        detail: configCatalog.configDir.length > 0 ? configCatalog.configDir : "尚未读取"
                                        Text {
                                            Layout.fillWidth: true
                                            text: configCatalog.responseStatus.length > 0
                                                  ? "响应: " + configCatalog.responseStatus + " / " + (configCatalog.lastFetchedAt || "未刷新")
                                                  : "未刷新"
                                            color: tokens.inkAlpha(0.55)
                                            font.family: tokens.monoFont
                                            font.pixelSize: dialog.sp(11)
                                            wrapMode: Text.WrapAnywhere
                                        }
                                    }
                                }

                                Rectangle {
                                    visible: configCatalog.errorMessage.length > 0
                                    Layout.fillWidth: true
                                    implicitHeight: errorText.implicitHeight + dialog.sp(24)
                                    radius: dialog.sp(tokens.radiusFrame)
                                    color: tokens.orangeDarkAlpha(0.08)
                                    border.color: tokens.orangeAlpha(0.5)
                                    border.width: 1
                                    Text {
                                        id: errorText
                                        anchors.fill: parent
                                        anchors.margins: dialog.sp(12)
                                        text: configCatalog.errorMessage
                                        color: tokens.orangeDark
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(13)
                                        wrapMode: Text.WordWrap
                                    }
                                }

                                Rectangle {
                                    visible: configCatalog.actionMessage.length > 0
                                    Layout.fillWidth: true
                                    implicitHeight: actionText.implicitHeight + dialog.sp(24)
                                    radius: dialog.sp(tokens.radiusFrame)
                                    color: tokens.tealAlpha(0.08)
                                    border.color: tokens.tealAlpha(0.55)
                                    border.width: 1
                                    Text {
                                        id: actionText
                                        anchors.fill: parent
                                        anchors.margins: dialog.sp(12)
                                        text: configCatalog.actionMessage
                                        color: tokens.tealDark
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(13)
                                        wrapMode: Text.WordWrap
                                    }
                                }

                                Flow {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(8)
                                    visible: configCatalog.configPages.length > 0
                                    Repeater {
                                        model: configCatalog.configPages
                                        Button {
                                            text: modelData.title
                                            focusPolicy: Qt.NoFocus
                                            onClicked: configCatalog.activeFileName = modelData.fileName
                                            contentItem: Column {
                                                anchors.centerIn: parent
                                                spacing: dialog.sp(3)
                                                Text {
                                                    text: modelData.title
                                                    color: configCatalog.activeFileName === modelData.fileName ? "#FFFFFF" : tokens.ink
                                                    font.family: tokens.sansFont
                                                    font.pixelSize: dialog.sp(12)
                                                    font.weight: Font.Black
                                                }
                                                Text {
                                                    text: modelData.fileName
                                                    color: configCatalog.activeFileName === modelData.fileName ? tokens.whiteAlpha(0.62) : tokens.inkAlpha(0.52)
                                                    font.family: tokens.monoFont
                                                    font.pixelSize: dialog.sp(9)
                                                }
                                            }
                                            background: Rectangle {
                                                implicitWidth: dialog.sp(188)
                                                implicitHeight: dialog.sp(58)
                                                radius: dialog.sp(tokens.radiusFrame)
                                                color: configCatalog.activeFileName === modelData.fileName ? tokens.ink : tokens.whiteAlpha(0.7)
                                                border.color: configCatalog.activeFileName === modelData.fileName ? tokens.ink : tokens.inkAlpha(0.15)
                                                border.width: 1
                                            }
                                        }
                                    }
                                }

                                Rectangle {
                                    visible: Boolean(configCatalog.activePage.fileName)
                                    Layout.fillWidth: true
                                    implicitHeight: activePageHeader.implicitHeight + dialog.sp(26)
                                    radius: dialog.sp(tokens.radiusFrame)
                                    color: tokens.whiteAlpha(0.7)
                                    border.color: tokens.inkAlpha(0.15)
                                    border.width: 1

                                    RowLayout {
                                        id: activePageHeader
                                        anchors.fill: parent
                                        anchors.margins: dialog.sp(13)
                                        spacing: dialog.sp(12)
                                        ColumnLayout {
                                            Layout.fillWidth: true
                                            Layout.minimumWidth: 0
                                            spacing: dialog.sp(4)
                                            Text {
                                                Layout.fillWidth: true
                                                text: configCatalog.activePage.title || ""
                                                color: tokens.ink
                                                font.family: tokens.sansFont
                                                font.pixelSize: dialog.sp(18)
                                                font.weight: Font.Black
                                                elide: Text.ElideRight
                                            }
                                            Text {
                                                Layout.fillWidth: true
                                                text: configCatalog.activePage.description || ""
                                                color: tokens.inkAlpha(0.58)
                                                font.family: tokens.sansFont
                                                font.pixelSize: dialog.sp(12)
                                                wrapMode: Text.WordWrap
                                            }
                                        }
                                        Text {
                                            text: (configCatalog.activePage.fileName || "") + " / "
                                                  + ((configCatalog.activePage.fields || []).length) + " 项"
                                            color: tokens.inkAlpha(0.42)
                                            font.family: tokens.monoFont
                                            font.pixelSize: dialog.sp(10)
                                        }
                                    }
                                }

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: dialog.sp(10)
                                    visible: Boolean(configCatalog.activePage.fileName)
                                    Repeater {
                                        model: configCatalog.activePage.fields || []
                                        ConfigFieldEditor {
                                            page: configCatalog.activePage
                                            field: modelData
                                        }
                                    }
                                }

                                Rectangle {
                                    visible: !configCatalog.loading && configCatalog.configPages.length === 0
                                    Layout.fillWidth: true
                                    implicitHeight: emptyConfigText.implicitHeight + dialog.sp(40)
                                    radius: dialog.sp(tokens.radiusFrame)
                                    color: tokens.whiteAlpha(0.7)
                                    border.color: tokens.inkAlpha(0.15)
                                    border.width: 1
                                    Text {
                                        id: emptyConfigText
                                        anchors.fill: parent
                                        anchors.margins: dialog.sp(20)
                                        text: "没有找到可编辑的本地配置文件。请确认 config 目录存在。"
                                        color: tokens.inkAlpha(0.62)
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(13)
                                        wrapMode: Text.WordWrap
                                    }
                                }
                            }

                            ColumnLayout {
                                visible: dialog.activeView === "game"
                                Layout.fillWidth: true
                                spacing: dialog.sp(20)

                                SectionTitle { eyebrow: "Game Mode"; title: "游戏控制" }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1180) ? 3 : 1
                                    columnSpacing: dialog.sp(20)
                                    rowSpacing: dialog.sp(20)
                                    UiCard { title: "游戏名"; UiField { text: frontendSettings.gameName; onEditingFinished: frontendSettings.gameName = text } }
                                    UiCard { title: "游戏 Session"; detail: "留空时使用聊天 Session。"; UiField { text: frontendSettings.gameSessionId; onEditingFinished: frontendSettings.gameSessionId = text } }
                                    UiCard { title: "RP Session"; detail: "留空时使用聊天 Session。"; UiField { text: frontendSettings.gameRpSessionId; onEditingFinished: frontendSettings.gameRpSessionId = text } }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1180) ? 4 : 2
                                    columnSpacing: dialog.sp(16)
                                    rowSpacing: dialog.sp(16)
                                    UiCard { title: "状态"; Text { text: dialog.gameStateText; color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(24); font.weight: Font.Black } }
                                    UiCard { title: "游戏"; Text { text: dialog.resolvedGameName(); color: tokens.ink; font.family: tokens.monoFont; font.pixelSize: dialog.sp(13); wrapMode: Text.WrapAnywhere } }
                                    UiCard { title: "步数"; Text { text: String(dialog.gameStepCount); color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(24); font.weight: Font.Black } }
                                    UiCard { title: "刷新"; Text { text: dialog.gameLastUpdatedAt || "未刷新"; color: tokens.ink; font.family: tokens.sansFont; font.pixelSize: dialog.sp(13); wrapMode: Text.WrapAnywhere } }
                                }

                                UiCard {
                                    title: "会话详情"
                                    Text {
                                        Layout.fillWidth: true
                                        text: "gameSessionId=" + (dialog.gameSessionText || dialog.resolvedGameSessionId())
                                              + "\nrpSessionId=" + (dialog.gameRpSessionText || dialog.resolvedGameRpSessionId())
                                              + "\nstartedAt=" + (dialog.gameStartedAt || "-")
                                              + "\nlastActivityAt=" + (dialog.gameLastActivityAt || "-")
                                        color: tokens.ink
                                        font.family: tokens.monoFont
                                        font.pixelSize: dialog.sp(12)
                                        wrapMode: Text.WrapAnywhere
                                    }
                                }

                                Rectangle {
                                    visible: dialog.gameErrorText.length > 0
                                    Layout.fillWidth: true
                                    implicitHeight: gameErrorTextItem.implicitHeight + dialog.sp(24)
                                    color: tokens.orangeDarkAlpha(0.08)
                                    border.color: tokens.orangeAlpha(0.5)
                                    border.width: 1
                                    Text {
                                        id: gameErrorTextItem
                                        anchors.fill: parent
                                        anchors.margins: dialog.sp(12)
                                        text: dialog.gameErrorText
                                        color: tokens.orangeDark
                                        font.family: tokens.sansFont
                                        font.pixelSize: dialog.sp(13)
                                        wrapMode: Text.WordWrap
                                    }
                                }

                                Flow {
                                    Layout.fillWidth: true
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

                                SectionTitle { eyebrow: "Voice Debug"; title: "语音调试" }

                                UiCard {
                                    title: "ASR Runtime"
                                    Text {
                                        Layout.fillWidth: true
                                        text: "sherpa-qwen-onnx / ASR v2 / partial 500ms / segment 3000ms"
                                        color: tokens.inkAlpha(0.62)
                                        font.family: tokens.monoFont
                                        font.pixelSize: dialog.sp(11)
                                        wrapMode: Text.WrapAnywhere
                                    }
                                }

                                BlackToggle {
                                    title: "只调试不执行"
                                    detail: "运行语音识别和意图路由，但不把识别文本发给大模型，也不执行游戏副作用。"
                                    checked: frontendSettings.voiceDebugEnabled
                                    onToggled: function(value) { frontendSettings.voiceDebugEnabled = value }
                                }

                                GridLayout {
                                    Layout.fillWidth: true
                                    columns: dialog.width > dialog.sp(1080) ? 2 : 1
                                    columnSpacing: dialog.sp(20)
                                    rowSpacing: dialog.sp(20)
                                    UiCard {
                                        title: "ASR Runtime"
                                        Text {
                                            Layout.fillWidth: true
                                            text: "sherpa-qwen-onnx / ASR v2 / port 6006"
                                            color: tokens.inkAlpha(0.55)
                                            font.family: tokens.sansFont
                                            font.pixelSize: dialog.sp(12)
                                            wrapMode: Text.WrapAnywhere
                                        }
                                    }
                                    UiCard {
                                        title: "音频输入设备"
                                        UiCombo {
                                            model: sttAudio.deviceNames
                                            currentIndex: Math.max(0, Math.min(sttAudio.selectedDeviceIndex, count - 1))
                                            onActivated: sttAudio.selectedDeviceIndex = currentIndex
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
                                            text: "事件数: " + sttAudio.debugEventCount
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
