import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import Arklight

ApplicationWindow {
    id: window
    width: 1320
    height: 820
    minimumWidth: Math.round(980 * uiScale)
    minimumHeight: Math.round(640 * uiScale)
    visible: true
    title: "ArkLight Qt"
    flags: Qt.Window | Qt.FramelessWindowHint
    color: paletteToken.window

    property real uiScale: frontendSettings.uiScalePercent / 100
    property bool systemIsDark: (systemPalette.window.r * 0.299 + systemPalette.window.g * 0.587 + systemPalette.window.b * 0.114) < 0.55
    property bool darkMode: frontendSettings.themeId === "dark" || (frontendSettings.themeId !== "light" && systemIsDark)

    function sp(value) {
        return Math.round(value * uiScale)
    }

    readonly property bool chineseUi: frontendSettings.languageId === "zh"

    function trText(en, zh) {
        return chineseUi ? zh : en
    }

    function languageOptions() {
        return chineseUi ? ["English", "中文"] : ["English", "Chinese"]
    }

    function themeOptions() {
        return chineseUi ? ["跟随系统", "浅色", "深色"] : ["System", "Light", "Dark"]
    }

    SystemPalette {
        id: systemPalette
        colorGroup: SystemPalette.Active
    }

    QtObject {
        id: paletteToken
        readonly property color window: darkMode ? "#0b0b0a" : "#F6F0DC"
        readonly property color panel: darkMode ? "#141412" : "#F1E9D4"
        readonly property color panelAlt: darkMode ? "#1d1b17" : "#EBE1C8"
        readonly property color card: darkMode ? "#1f1c18" : "#fffaf0"
        readonly property color cardSoft: darkMode ? "#12110f" : "#ffffff"
        readonly property color border: darkMode ? "#4d4639" : "#1A1A1A"
        readonly property color hairline: darkMode ? "#39342b" : "#d8cdb4"
        readonly property color text: darkMode ? "#f4ead4" : "#1A1A1A"
        readonly property color muted: darkMode ? "#b9aa8d" : "#786848"
        readonly property color faint: darkMode ? "#827763" : "#9b8d70"
        readonly property color accent: "#E85D04"
        readonly property color accentStrong: darkMode ? "#ff7a1f" : "#c74600"
        readonly property color accentSoft: darkMode ? "#4b2414" : "#f7d4bd"
        readonly property color accentSofter: darkMode ? "#2b1a13" : "#fff0e5"
        readonly property color teal: darkMode ? "#67b6b0" : "#4D908E"
        readonly property color tealSoft: darkMode ? "#163532" : "#d9eeea"
        readonly property color ink: darkMode ? "#f4ead4" : "#1A1A1A"
        readonly property color inkInverse: darkMode ? "#0b0b0a" : "#ffffff"
        readonly property color accentText: "#ffffff"
        readonly property color warn: darkMode ? "#F0B649" : "#A15C00"
        readonly property color shadow: darkMode ? "#99000000" : "#331a1a1a"
        readonly property color input: darkMode ? "#12110f" : "#ffffff"
        readonly property color gridLine: darkMode ? "#f4ead414" : "#7868481c"
    }

    palette {
        window: paletteToken.window
        windowText: paletteToken.text
        base: paletteToken.input
        alternateBase: paletteToken.panelAlt
        text: paletteToken.text
        button: paletteToken.card
        buttonText: paletteToken.text
        highlight: paletteToken.accent
        highlightedText: paletteToken.accentText
        placeholderText: paletteToken.faint
    }

    component AppButton: Button {
        id: button
        property bool primary: false
        focusPolicy: Qt.NoFocus
        leftPadding: sp(16)
        rightPadding: sp(16)
        topPadding: sp(8)
        bottomPadding: sp(8)
        font.pixelSize: sp(14)
        font.weight: Font.Black
        font.capitalization: Font.AllUppercase
        palette {
            button: button.primary ? paletteToken.accent : paletteToken.card
            buttonText: button.primary ? paletteToken.accentText : paletteToken.text
            highlight: paletteToken.accentSoft
            highlightedText: paletteToken.text
        }
        contentItem: Text {
            text: button.text
            font.pixelSize: button.font.pixelSize
            font.weight: button.font.weight
            font.capitalization: button.font.capitalization
            font.letterSpacing: sp(1)
            color: button.primary ? (button.down || button.hovered ? paletteToken.accentText : paletteToken.inkInverse)
                                  : (button.down || button.hovered ? paletteToken.inkInverse : paletteToken.text)
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        background: Rectangle {
            radius: 0
            color: button.primary ? (button.down || button.hovered ? paletteToken.accent : paletteToken.ink)
                                  : (button.down || button.hovered ? paletteToken.ink : paletteToken.card)
            border.color: paletteToken.border
            border.width: 2
        }
    }

    component SettingField: TextField {
        id: field
        Layout.fillWidth: true
        color: paletteToken.text
        placeholderTextColor: paletteToken.faint
        selectedTextColor: paletteToken.accentText
        selectionColor: paletteToken.accent
        font.pixelSize: sp(14)
        leftPadding: sp(12)
        rightPadding: sp(12)
        topPadding: sp(9)
        bottomPadding: sp(9)
        background: Rectangle {
            radius: 0
            color: paletteToken.input
            border.color: field.activeFocus ? paletteToken.accent : paletteToken.border
            border.width: field.activeFocus ? 2 : 1
        }
    }

    component AppComboBox: ComboBox {
        id: combo
        focusPolicy: Qt.NoFocus
        font.pixelSize: sp(14)
        leftPadding: sp(12)
        rightPadding: sp(34)
        topPadding: sp(8)
        bottomPadding: sp(8)
        palette {
            base: paletteToken.input
            button: paletteToken.input
            buttonText: paletteToken.text
            text: paletteToken.text
            highlight: paletteToken.accentSoft
            highlightedText: paletteToken.accentText
        }
        contentItem: Text {
            text: combo.displayText
            color: paletteToken.text
            font: combo.font
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        indicator: Text {
            x: combo.width - width - sp(12)
            y: (combo.height - height) / 2
            text: "v"
            color: paletteToken.muted
            font.pixelSize: sp(13)
        }
        background: Rectangle {
            radius: 0
            color: combo.pressed || combo.hovered ? paletteToken.accentSofter : paletteToken.input
            border.color: combo.activeFocus || combo.hovered ? paletteToken.accent : paletteToken.border
            border.width: 2
        }
        delegate: ItemDelegate {
            width: combo.width
            text: modelData
            highlighted: combo.highlightedIndex === index
            font.pixelSize: sp(14)
            contentItem: Text {
                text: modelData
                color: highlighted ? paletteToken.inkInverse : paletteToken.text
                font: parent.font
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
            }
            background: Rectangle {
                color: highlighted ? paletteToken.ink : (hovered ? paletteToken.accentSofter : paletteToken.panel)
            }
        }
        popup: Popup {
            y: combo.height + sp(4)
            width: combo.width
            implicitHeight: Math.min(contentItem.implicitHeight, sp(240))
            padding: sp(4)
            contentItem: ListView {
                clip: true
                implicitHeight: contentHeight
                model: combo.popup.visible ? combo.delegateModel : null
                currentIndex: combo.highlightedIndex
                ScrollBar.vertical: ScrollBar {
                    contentItem: Rectangle {
                        implicitWidth: sp(5)
                        radius: sp(3)
                        color: paletteToken.muted
                    }
                    background: Rectangle {
                        color: "transparent"
                    }
                }
            }
            background: Rectangle {
                radius: 0
                color: paletteToken.panel
                border.color: paletteToken.border
                border.width: 2
            }
        }
    }

    component AppSlider: Slider {
        id: slider
        focusPolicy: Qt.NoFocus
        implicitHeight: sp(28)
        background: Rectangle {
            x: slider.leftPadding
            y: slider.topPadding + slider.availableHeight / 2 - height / 2
            implicitHeight: sp(5)
            width: slider.availableWidth
            height: sp(5)
            radius: 0
            color: paletteToken.hairline

            Rectangle {
                width: slider.visualPosition * parent.width
                height: parent.height
                radius: 0
                color: paletteToken.accent
            }
        }
        handle: Rectangle {
            x: slider.leftPadding + slider.visualPosition * (slider.availableWidth - width)
            y: slider.topPadding + slider.availableHeight / 2 - height / 2
            width: sp(18)
            height: sp(18)
            radius: 0
            color: slider.pressed ? paletteToken.accent : paletteToken.panel
            border.color: slider.pressed ? paletteToken.accentStrong : paletteToken.accent
            border.width: 2
        }
    }

    component SettingSection: Rectangle {
        id: section
        property string title: ""
        property string description: ""
        default property alias content: sectionBody.data
        Layout.fillWidth: true
        implicitHeight: sectionColumn.implicitHeight + sp(28)
        radius: sp(8)
        color: paletteToken.card
        border.color: paletteToken.hairline
        border.width: 1

        ColumnLayout {
            id: sectionColumn
            anchors.fill: parent
            anchors.margins: sp(14)
            spacing: sp(12)

            ColumnLayout {
                Layout.fillWidth: true
                spacing: sp(4)

                Label {
                    Layout.fillWidth: true
                    text: section.title
                    color: paletteToken.text
                    font.pixelSize: sp(16)
                    font.weight: Font.Black
                    elide: Text.ElideRight
                }

                Label {
                    Layout.fillWidth: true
                    visible: section.description.length > 0
                    text: section.description
                    color: paletteToken.muted
                    wrapMode: Text.WordWrap
                    font.pixelSize: sp(12)
                }
            }

            ColumnLayout {
                id: sectionBody
                Layout.fillWidth: true
                spacing: sp(10)
            }
        }
    }

    component GridOverlay: Canvas {
        opacity: darkMode ? 0.45 : 0.7
        onPaint: {
            const ctx = getContext("2d")
            ctx.clearRect(0, 0, width, height)
            ctx.strokeStyle = paletteToken.gridLine
            ctx.lineWidth = 1
            const step = sp(56)
            for (let x = 0; x < width + step; x += step) {
                ctx.beginPath()
                ctx.moveTo(x, 0)
                ctx.lineTo(x, height)
                ctx.stroke()
            }
            for (let y = 0; y < height + step; y += step) {
                ctx.beginPath()
                ctx.moveTo(0, y)
                ctx.lineTo(width, y)
                ctx.stroke()
            }
            for (let x2 = -height; x2 < width; x2 += step) {
                ctx.beginPath()
                ctx.moveTo(x2, 0)
                ctx.lineTo(x2 + height, height)
                ctx.stroke()
            }
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        Connections {
            target: window
            function onDarkModeChanged() {
                requestPaint()
            }
        }
    }

    component WindowButton: Button {
        id: titleButton
        property bool closeButton: false
        focusPolicy: Qt.NoFocus
        Layout.preferredWidth: sp(46)
        Layout.fillHeight: true
        padding: 0
        font.pixelSize: sp(13)
        palette {
            button: paletteToken.panel
            buttonText: paletteToken.text
            highlight: paletteToken.accentSoft
            highlightedText: paletteToken.text
        }
        contentItem: Text {
            text: titleButton.text
            color: titleButton.closeButton && titleButton.hovered ? "#ffffff" : (titleButton.hovered ? paletteToken.inkInverse : paletteToken.text)
            font: titleButton.font
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            color: titleButton.closeButton && titleButton.hovered ? "#d83b3b"
                  : (titleButton.down || titleButton.hovered ? paletteToken.ink : paletteToken.panel)
        }
    }

    header: Rectangle {
        color: paletteToken.panel
        implicitHeight: sp(106)
        border.color: paletteToken.border
        border.width: 2

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: sp(32)
                color: paletteToken.panel

                RowLayout {
                    anchors.fill: parent
                    spacing: 0

                    Item {
                        Layout.fillWidth: true
                        Layout.fillHeight: true

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: sp(10)
                            spacing: sp(8)

                            Rectangle {
                                Layout.preferredWidth: sp(16)
                                Layout.preferredHeight: sp(16)
                                radius: 0
                                color: paletteToken.accent
                                border.color: paletteToken.border

                                Text {
                                    anchors.centerIn: parent
                                    text: "A"
                                    color: paletteToken.accentText
                                    font.pixelSize: sp(11)
                                    font.bold: true
                                }
                            }

                            Label {
                                Layout.fillWidth: true
                                text: window.title
                                color: paletteToken.text
                                elide: Text.ElideRight
                                font.pixelSize: sp(12)
                            }
                        }

                        MouseArea {
                            anchors.fill: parent
                            acceptedButtons: Qt.LeftButton
                            onPressed: window.startSystemMove()
                            onDoubleClicked: window.visibility === Window.Maximized ? window.showNormal() : window.showMaximized()
                        }
                    }

                    WindowButton {
                        text: "-"
                        onClicked: window.showMinimized()
                    }

                    WindowButton {
                        text: window.visibility === Window.Maximized ? "[]" : "[ ]"
                        onClicked: window.visibility === Window.Maximized ? window.showNormal() : window.showMaximized()
                    }

                    WindowButton {
                        text: "x"
                        closeButton: true
                        onClicked: window.close()
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: sp(74)
                Layout.margins: sp(16)
                spacing: sp(14)

                Rectangle {
                    Layout.preferredWidth: sp(44)
                    Layout.preferredHeight: sp(44)
                    radius: 0
                    color: paletteToken.ink

                    Text {
                        anchors.centerIn: parent
                        text: "A"
                        color: paletteToken.inkInverse
                        font.pixelSize: sp(24)
                        font.bold: true
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    spacing: sp(2)

                    Label {
                        Layout.fillWidth: true
                        text: frontendSettings.workspaceName
                        color: paletteToken.text
                        font.pixelSize: sp(24)
                        font.bold: true
                        elide: Text.ElideRight
                    }

                    Label {
                        Layout.fillWidth: true
                        text: trText("Session: ", "会话：") + frontendSettings.sessionId + "    " + trText("Backend: ", "后端：") + frontendSettings.backendBaseUrl
                        color: paletteToken.muted
                        font.pixelSize: sp(12)
                        elide: Text.ElideRight
                    }
                }

                AppButton {
                    text: trText("Reload", "重载")
                    onClicked: characterCatalog.reload()
                }

                AppButton {
                    text: trText("Settings", "设置")
                    primary: true
                    onClicked: settingsDialog.open()
                }
            }
        }
    }

    Dialog {
        id: settingsDialog
        modal: true
        width: Math.min(window.width - sp(48), sp(760))
        height: Math.min(window.height - sp(48), sp(760))
        padding: sp(18)
        anchors.centerIn: Overlay.overlay

        background: Rectangle {
            color: paletteToken.panel
            radius: sp(10)
            border.color: paletteToken.border
            border.width: 2
            layer.enabled: true
        }

        contentItem: ColumnLayout {
            spacing: sp(14)

            RowLayout {
                Layout.fillWidth: true
                spacing: sp(12)

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    spacing: sp(3)

                    Label {
                        Layout.fillWidth: true
                        text: trText("Local Settings", "本地设置")
                        color: paletteToken.text
                        font.pixelSize: sp(22)
                        font.weight: Font.Black
                        elide: Text.ElideRight
                    }

                    Label {
                        Layout.fillWidth: true
                        text: trText("Changes are saved locally and apply immediately where possible.", "设置会保存在本机，支持的项目会立即生效。")
                        color: paletteToken.muted
                        wrapMode: Text.WordWrap
                        font.pixelSize: sp(12)
                    }
                }

                AppButton {
                    text: trText("Reset", "重置")
                    onClicked: frontendSettings.reset()
                }
            }

            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true

                ColumnLayout {
                    width: settingsDialog.availableWidth - sp(6)
                    spacing: sp(12)

                    SettingSection {
                        title: trText("Appearance", "外观")
                        description: trText("Tune the interface language, theme, and overall scale.", "调整界面语言、主题和整体缩放。")

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label {
                                Layout.preferredWidth: sp(132)
                                text: trText("Language", "语言")
                                color: paletteToken.text
                                font.pixelSize: sp(14)
                            }

                            AppComboBox {
                                id: languageCombo
                                Layout.fillWidth: true
                                model: languageOptions()

                                function syncSelection() {
                                    currentIndex = frontendSettings.languageId === "zh" ? 1 : 0
                                }

                                Component.onCompleted: syncSelection()
                                onActivated: frontendSettings.languageId = currentIndex === 1 ? "zh" : "en"

                                Connections {
                                    target: frontendSettings
                                    function onSettingsChanged() {
                                        languageCombo.syncSelection()
                                    }
                                }
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label {
                                Layout.preferredWidth: sp(132)
                                text: trText("Theme", "主题")
                                color: paletteToken.text
                                font.pixelSize: sp(14)
                            }

                            AppComboBox {
                                id: themeCombo
                                Layout.fillWidth: true
                                model: themeOptions()

                                function syncSelection() {
                                    currentIndex = frontendSettings.themeId === "light" ? 1 : (frontendSettings.themeId === "dark" ? 2 : 0)
                                }

                                Component.onCompleted: syncSelection()
                                onActivated: frontendSettings.themeId = currentIndex === 1 ? "light" : (currentIndex === 2 ? "dark" : "system")

                                Connections {
                                    target: frontendSettings
                                    function onSettingsChanged() {
                                        themeCombo.syncSelection()
                                    }
                                }
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label {
                                Layout.preferredWidth: sp(132)
                                text: trText("UI Scale", "界面缩放")
                                color: paletteToken.text
                                font.pixelSize: sp(14)
                            }

                            AppSlider {
                                Layout.fillWidth: true
                                from: 80
                                to: 140
                                stepSize: 5
                                value: frontendSettings.uiScalePercent
                                onMoved: frontendSettings.uiScalePercent = Math.round(value / 5) * 5
                            }

                            Label {
                                Layout.preferredWidth: sp(54)
                                text: frontendSettings.uiScalePercent + "%"
                                color: paletteToken.text
                                horizontalAlignment: Text.AlignRight
                                font.pixelSize: sp(14)
                            }
                        }
                    }

                    SettingSection {
                        title: trText("Session", "会话")
                        description: trText("Identify the current workspace, user, and character.", "设置当前工作区、操作者和角色。")

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label { Layout.preferredWidth: sp(132); text: trText("Session ID", "会话 ID"); color: paletteToken.text; font.pixelSize: sp(14) }
                            SettingField {
                                placeholderText: trText("Session ID", "会话 ID")
                                text: frontendSettings.sessionId
                                onEditingFinished: frontendSettings.sessionId = text
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label { Layout.preferredWidth: sp(132); text: trText("Workspace", "工作区"); color: paletteToken.text; font.pixelSize: sp(14) }
                            SettingField {
                                placeholderText: trText("Workspace Name", "工作区名称")
                                text: frontendSettings.workspaceName
                                onEditingFinished: frontendSettings.workspaceName = text
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label { Layout.preferredWidth: sp(132); text: trText("Operator", "操作者"); color: paletteToken.text; font.pixelSize: sp(14) }
                            SettingField {
                                placeholderText: trText("Operator Name", "操作者名称")
                                text: frontendSettings.operatorName
                                onEditingFinished: frontendSettings.operatorName = text
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label { Layout.preferredWidth: sp(132); text: trText("Character", "角色"); color: paletteToken.text; font.pixelSize: sp(14) }
                            AppComboBox {
                                id: characterCombo
                                Layout.fillWidth: true
                                model: characterCatalog.characterNames()

                                function syncSelection() {
                                    const names = characterCatalog.characterNames()
                                    const index = names.indexOf(frontendSettings.characterName)
                                    currentIndex = index >= 0 ? index : (names.length > 0 ? 0 : -1)
                                }

                                Component.onCompleted: syncSelection()
                                onActivated: {
                                    frontendSettings.characterName = currentText
                                    chatSession.selectCharacter(currentText)
                                }

                                Connections {
                                    target: frontendSettings
                                    function onSettingsChanged() {
                                        characterCombo.syncSelection()
                                    }
                                }

                                Connections {
                                    target: characterCatalog
                                    function onCharactersChanged() {
                                        characterCombo.syncSelection()
                                    }
                                }
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label {
                                Layout.preferredWidth: sp(132)
                                text: trText("Reply Delay", "回复延迟")
                                color: paletteToken.text
                                font.pixelSize: sp(14)
                            }

                            AppSlider {
                                Layout.fillWidth: true
                                from: 0
                                to: 3000
                                stepSize: 50
                                value: frontendSettings.responseDelayMs
                                onMoved: frontendSettings.responseDelayMs = Math.round(value)
                            }

                            Label {
                                Layout.preferredWidth: sp(72)
                                text: Math.round(frontendSettings.responseDelayMs) + " ms"
                                color: paletteToken.text
                                horizontalAlignment: Text.AlignRight
                                font.pixelSize: sp(14)
                            }
                        }
                    }

                    SettingSection {
                        title: trText("Backend", "后端")
                        description: trText("Configure the local service endpoint used by chat requests.", "配置聊天请求使用的本地服务地址。")

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label { Layout.preferredWidth: sp(132); text: trText("Backend URL", "后端地址"); color: paletteToken.text; font.pixelSize: sp(14) }
                            SettingField {
                                placeholderText: "http://localhost:8080"
                                text: frontendSettings.backendBaseUrl
                                onEditingFinished: frontendSettings.backendBaseUrl = text
                            }
                        }
                    }

                }
            }

            RowLayout {
                Layout.fillWidth: true

                Item { Layout.fillWidth: true }

                AppButton {
                    text: trText("Close", "关闭")
                    primary: true
                    onClicked: {
                        frontendSettings.save()
                        settingsDialog.close()
                    }
                }
            }
        }
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.preferredWidth: Math.round(window.width * 0.38)
            Layout.minimumWidth: sp(320)
            Layout.fillHeight: true
            color: paletteToken.panelAlt
            border.color: paletteToken.border
            border.width: 2
            clip: true

            GridOverlay {
                anchors.fill: parent
                z: 0
            }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: sp(22)
                spacing: sp(16)

                Label {
                    Layout.fillWidth: true
                    text: frontendSettings.characterName.length > 0 ? frontendSettings.characterName : trText("No Character Selected", "未选择角色")
                    color: paletteToken.text
                    font.pixelSize: sp(24)
                    font.weight: Font.Black
                    font.capitalization: Font.AllUppercase
                    elide: Text.ElideRight
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    radius: 0
                    color: Qt.rgba(paletteToken.card.r, paletteToken.card.g, paletteToken.card.b, darkMode ? 0.82 : 0.58)
                    border.color: paletteToken.border
                    border.width: 2
                    clip: true

                    Rectangle {
                        x: sp(16)
                        y: sp(16)
                        width: sp(58)
                        height: sp(58)
                        color: "transparent"
                        border.color: paletteToken.accent
                        border.width: 4
                    }

                    Rectangle {
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        anchors.rightMargin: sp(16)
                        anchors.bottomMargin: sp(16)
                        width: sp(58)
                        height: sp(58)
                        color: "transparent"
                        border.color: paletteToken.teal
                        border.width: 4
                    }

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: sp(24)
                        spacing: sp(12)

                        Rectangle {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            radius: 0
                            color: paletteToken.cardSoft
                            border.color: paletteToken.hairline
                            border.width: 1
                            clip: true

                            GridOverlay {
                                anchors.fill: parent
                                opacity: darkMode ? 0.28 : 0.34
                            }

                            Image {
                                anchors.fill: parent
                                anchors.margins: sp(6)
                                fillMode: Image.PreserveAspectFit
                                source: chatSession.activeCharacterImagePath
                                cache: false
                                visible: source.length > 0
                            }

                            Text {
                                anchors.fill: parent
                                visible: chatSession.activeCharacterImagePath.length === 0
                                text: trText("No portrait asset", "没有角色立绘")
                                color: paletteToken.faint
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                                font.pixelSize: sp(17)
                            }
                        }

                        Label {
                            Layout.fillWidth: true
                            text: chatSession.activeEmotion.length > 0 ? (trText("Emotion: ", "情绪：") + chatSession.activeEmotion) : trText("Emotion: default", "情绪：默认")
                            color: paletteToken.muted
                            font.pixelSize: sp(13)
                            font.capitalization: Font.AllUppercase
                            elide: Text.ElideRight
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 0
                    color: paletteToken.ink
                    border.color: paletteToken.border
                    border.width: 2
                    implicitHeight: characterDirectoryContent.implicitHeight + sp(28)

                    ColumnLayout {
                        id: characterDirectoryContent
                        anchors.fill: parent
                        anchors.margins: sp(14)
                        spacing: sp(6)

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(10)

                            Label {
                                Layout.fillWidth: true
                                Layout.minimumWidth: 0
                                text: trText("Character Directory", "角色目录")
                                color: paletteToken.inkInverse
                                font.weight: Font.Black
                                font.pixelSize: sp(14)
                                font.capitalization: Font.AllUppercase
                                elide: Text.ElideRight
                            }

                            AppButton {
                                text: trText("Open", "打开")
                                enabled: characterCatalog.charactersPath.length > 0
                                onClicked: characterCatalog.openCharactersFolder()
                            }
                        }

                        Label {
                            Layout.fillWidth: true
                            text: characterCatalog.charactersPath.length > 0 ? characterCatalog.charactersPath : trText("Not found", "未找到")
                            color: darkMode ? paletteToken.faint : "#ffffffaa"
                            wrapMode: Text.WrapAnywhere
                            font.pixelSize: sp(12)
                        }
                    }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: paletteToken.window
            clip: true

            GridOverlay {
                anchors.fill: parent
                z: 0
            }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: sp(18)
                spacing: sp(14)

                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: sp(56)
                    radius: 0
                    color: Qt.rgba(paletteToken.card.r, paletteToken.card.g, paletteToken.card.b, darkMode ? 0.72 : 0.52)
                    border.color: paletteToken.border
                    border.width: 2

                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: sp(14)
                        spacing: sp(12)

                        Rectangle {
                            Layout.preferredWidth: sp(10)
                            Layout.preferredHeight: sp(10)
                            radius: sp(5)
                            color: chatSession.busy ? paletteToken.warn : paletteToken.teal
                        }

                        Label {
                            Layout.fillWidth: true
                            Layout.minimumWidth: 0
                            text: chatSession.statusText.length > 0 ? chatSession.statusText : trText("Ready.", "就绪。")
                            color: chatSession.busy ? paletteToken.warn : paletteToken.muted
                            elide: Text.ElideRight
                            verticalAlignment: Text.AlignVCenter
                            font.pixelSize: sp(14)
                            font.capitalization: Font.AllUppercase
                        }

                        AppButton {
                            text: trText("Story Replay", "故事回放")
                            enabled: !chatSession.busy
                            onClicked: chatSession.startStoryReplay()
                        }

                        AppButton {
                            text: trText("Clear", "清空")
                            onClicked: chatSession.clearMessages()
                        }
                    }
                }

                ListView {
                    id: chatList
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    spacing: sp(12)
                    clip: true
                    model: chatSession.messages

                    function scrollToEnd() {
                        Qt.callLater(function() {
                            chatList.positionViewAtEnd()
                        })
                    }

                    ScrollBar.vertical: ScrollBar {
                        contentItem: Rectangle {
                            implicitWidth: sp(6)
                            radius: sp(3)
                            color: paletteToken.muted
                        }
                        background: Rectangle {
                            color: "transparent"
                        }
                    }

                    delegate: Item {
                        width: chatList.width
                        height: bubble.implicitHeight + sp(8)

                        Rectangle {
                            id: bubble
                            readonly property bool fromUser: messageRole === "user"
                            width: Math.min(parent.width * 0.76, Math.max(sp(220), messageText.implicitWidth + sp(34)))
                            implicitHeight: messageColumn.implicitHeight + sp(24)
                            radius: sp(14)
                            color: fromUser ? paletteToken.ink : paletteToken.cardSoft
                            border.color: fromUser ? paletteToken.ink : paletteToken.hairline
                            border.width: fromUser ? 2 : 1
                            anchors.right: fromUser ? parent.right : undefined

                            Column {
                                id: messageColumn
                                anchors.fill: parent
                                anchors.margins: sp(12)
                                spacing: sp(6)

                                Label {
                                    id: speakerLabel
                                    width: parent.width
                                    text: bubble.fromUser ? frontendSettings.operatorName : frontendSettings.characterName
                                    color: bubble.fromUser ? paletteToken.accent : paletteToken.teal
                                    font.pixelSize: sp(11)
                                    font.weight: Font.Black
                                    font.capitalization: Font.AllUppercase
                                    elide: Text.ElideRight
                                }

                                TextEdit {
                                    id: messageText
                                    width: bubble.width - sp(24)
                                    text: messageContent
                                    readOnly: true
                                    selectByMouse: true
                                    wrapMode: TextEdit.Wrap
                                    color: bubble.fromUser ? paletteToken.inkInverse : paletteToken.text
                                    font.pixelSize: sp(15)
                                    selectionColor: paletteToken.accent
                                    selectedTextColor: paletteToken.accentText
                                }
                            }
                        }
                    }

                    onCountChanged: scrollToEnd()
                    onContentHeightChanged: scrollToEnd()
                    onHeightChanged: scrollToEnd()
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 0
                    color: Qt.rgba(paletteToken.card.r, paletteToken.card.g, paletteToken.card.b, darkMode ? 0.76 : 0.5)
                    border.color: paletteToken.border
                    border.width: 2
                    implicitHeight: sp(128)

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: sp(14)
                        spacing: sp(10)

                        TextArea {
                            id: inputArea
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            placeholderText: trText("Type a message...", "输入消息...")
                            wrapMode: TextEdit.Wrap
                            color: paletteToken.text
                            placeholderTextColor: paletteToken.faint
                            selectionColor: paletteToken.accent
                            selectedTextColor: paletteToken.accentText
                            font.pixelSize: sp(15)
                            background: Rectangle {
                                radius: 0
                                color: paletteToken.input
                                border.color: inputArea.activeFocus ? paletteToken.accent : paletteToken.border
                                border.width: inputArea.activeFocus ? 2 : 1
                            }

                            function submit() {
                                if (chatSession.busy || text.trim().length === 0) {
                                    return
                                }
                                chatSession.sendMessage(text)
                                text = ""
                            }

                            onTextChanged: chatSession.reportTypingActivity()

                            Keys.onPressed: function(event) {
                                if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                                    if (event.modifiers & Qt.ShiftModifier) {
                                        return
                                    }
                                    submit()
                                    event.accepted = true
                                }
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: sp(12)

                            Label {
                                Layout.fillWidth: true
                                Layout.minimumWidth: 0
                                text: chatSession.busy ? trText("Assistant is replying...", "助手正在回复...") : trText("Enter to send, Shift+Enter for newline", "Enter 发送，Shift+Enter 换行")
                                color: paletteToken.muted
                                font.pixelSize: sp(12)
                                font.capitalization: Font.AllUppercase
                                elide: Text.ElideRight
                            }

                            AppButton {
                                enabled: !chatSession.busy
                                primary: true
                                text: chatSession.busy ? trText("Sending...", "发送中...") : trText("Send", "发送")
                                onClicked: inputArea.submit()
                            }
                        }
                    }
                }

                Row {
                    Layout.fillWidth: true
                    Layout.preferredHeight: sp(8)
                    spacing: 0

                    Rectangle { width: parent.width / 3; height: parent.height; color: "#8F2A1F" }
                    Rectangle { width: parent.width / 3; height: parent.height; color: "#F0B649" }
                    Rectangle { width: parent.width / 3; height: parent.height; color: paletteToken.teal }
                }
            }
        }
    }
}
