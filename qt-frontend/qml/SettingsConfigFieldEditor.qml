import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: editor
    property var host
    property var tokens
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
    property string storageKey: editor.host.fieldKey(fileName, fieldName)
    property int valuesRevision: configCatalog.valuesRevision
    property var historyItems: {
        editor.valuesRevision
        return configCatalog.historyFor(editor.fileName, editor.fieldName)
    }
    property bool historyAvailable: configCatalog.fieldSupportsHistory(field || {}) && editor.historyItems.length > 0
    function historyPreview(value) {
        return String(value || "").replace(/\s+/g, " ").trim()
    }
    function openHistoryDropdown() {
        if (!editor.historyAvailable) {
            return
        }
        editor.host.activeHistoryKey = editor.storageKey
        historyPopup.open()
    }
    Layout.fillWidth: true
    implicitHeight: Math.max(editor.host.sp(132), editorColumn.implicitHeight + editor.host.sp(32))
    radius: editor.host.sp(editor.tokens.radiusFrame)
    color: editor.tokens.whiteAlpha(0.78)
    border.color: fieldHover.hovered ? editor.tokens.ink : editor.tokens.inkAlpha(0.15)
    border.width: 1

    HoverHandler {
        id: fieldHover
    }

    ColumnLayout {
        id: editorColumn
        anchors.fill: parent
        anchors.margins: editor.host.sp(16)
        spacing: editor.host.sp(12)

        ColumnLayout {
            Layout.fillWidth: true
            spacing: editor.host.sp(7)
            Text {
                Layout.fillWidth: true
                text: editor.fieldLabel
                color: editor.tokens.ink
                font.family: editor.tokens.sansFont
                font.pixelSize: editor.host.sp(13)
                font.weight: Font.Black
                wrapMode: Text.WordWrap
            }
            Text {
                Layout.fillWidth: true
                visible: editor.fieldDescription.length > 0
                text: editor.fieldDescription
                color: editor.tokens.inkAlpha(0.5)
                font.family: editor.tokens.sansFont
                font.pixelSize: editor.host.sp(11)
                wrapMode: Text.WordWrap
            }
            Text {
                Layout.fillWidth: true
                text: editor.fieldPath
                color: editor.tokens.inkAlpha(0.38)
                font.family: editor.tokens.monoFont
                font.pixelSize: editor.host.sp(10)
                wrapMode: Text.WrapAnywhere
            }
        }

        Item {
            id: inputSlot
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            Layout.preferredHeight: Math.max(editor.host.sp(46), inputStack.implicitHeight)
            z: editor.host.activeHistoryKey === editor.storageKey ? 10 : 0

            ColumnLayout {
                id: inputStack
                anchors.fill: parent
                spacing: editor.host.sp(6)

                SettingsUiCombo {

                    host: editor.host

                    tokens: editor.tokens

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
                    implicitHeight: editor.host.sp(44)
                    radius: editor.host.sp(editor.tokens.radiusFrame)
                    color: editor.tokens.ink
                    border.color: editor.tokens.ink
                    border.width: 1
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: editor.host.sp(12)
                        anchors.rightMargin: editor.host.sp(12)
                        Text {
                            Layout.fillWidth: true
                            text: {
                                editor.valuesRevision
                                return Boolean(configCatalog.fieldValue(editor.fileName, editor.fieldName)) ? "已开启" : "已关闭"
                            }
                            color: "#FFFFFF"
                            font.family: editor.tokens.sansFont
                            font.pixelSize: editor.host.sp(13)
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
                    Layout.preferredHeight: editor.fieldType === "list" ? editor.host.sp(124) : editor.host.sp(102)
                    text: {
                        editor.valuesRevision
                        return String(configCatalog.fieldValue(editor.fileName, editor.fieldName) || "")
                    }
                    placeholderText: editor.fieldType === "list" ? "每行一条" : editor.fieldPlaceholder
                    wrapMode: TextEdit.WrapAnywhere
                    color: editor.tokens.ink
                    placeholderTextColor: editor.tokens.inkAlpha(0.4)
                    selectedTextColor: "#FFFFFF"
                    selectionColor: editor.tokens.orange
                    font.family: editor.tokens.sansFont
                    font.pixelSize: editor.host.sp(13)
                    leftPadding: editor.host.sp(12)
                    rightPadding: editor.host.sp(12)
                    topPadding: editor.host.sp(10)
                    bottomPadding: editor.host.sp(10)
                    selectByMouse: true
                    background: Rectangle {
                        radius: editor.host.sp(editor.tokens.radiusFrame)
                        color: editor.tokens.inputPaper
                        border.color: areaInput.activeFocus ? editor.tokens.orange : editor.tokens.inkAlpha(0.2)
                        border.width: 1
                    }
                    onActiveFocusChanged: {
                        if (activeFocus) {
                            editor.openHistoryDropdown()
                        }
                    }
                    TapHandler {
                        onTapped: editor.openHistoryDropdown()
                    }
                    onTextChanged: {
                        if (activeFocus) {
                            configCatalog.setFieldValue(editor.fileName, editor.fieldName, text)
                        }
                    }
                }

                SettingsUiField {

                    host: editor.host

                    tokens: editor.tokens

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
                        if (activeFocus) {
                            editor.openHistoryDropdown()
                        }
                    }
                    onTextEdited: configCatalog.setFieldValue(editor.fileName, editor.fieldName, text)
                    TapHandler {
                        onTapped: editor.openHistoryDropdown()
                    }
                }

                Popup {
                    id: historyPopup
                    parent: inputSlot
                    x: 0
                    y: (areaInput.visible ? areaInput.y + areaInput.height : textInput.y + textInput.height) + editor.host.sp(6)
                    width: inputSlot.width
                    implicitHeight: Math.min(historyList.contentHeight + editor.host.sp(8), editor.host.sp(164))
                    padding: editor.host.sp(4)
                    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent
                    transformOrigin: Item.Top
                    opacity: 0
                    scale: 0.985
                    modal: false
                    dim: false

                    onClosed: {
                        if (editor.host.activeHistoryKey === editor.storageKey) {
                            editor.host.activeHistoryKey = ""
                        }
                    }

                    enter: Transition {
                        ParallelAnimation {
                            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 150; easing.type: Easing.OutCubic }
                            NumberAnimation { property: "scale"; from: 0.985; to: 1; duration: 180; easing.type: Easing.OutCubic }
                            NumberAnimation {
                                property: "y"
                                from: (areaInput.visible ? areaInput.y + areaInput.height : textInput.y + textInput.height)
                                to: (areaInput.visible ? areaInput.y + areaInput.height : textInput.y + textInput.height) + editor.host.sp(6)
                                duration: 180
                                easing.type: Easing.OutCubic
                            }
                        }
                    }
                    exit: Transition {
                        ParallelAnimation {
                            NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 110; easing.type: Easing.InCubic }
                            NumberAnimation { property: "scale"; from: 1; to: 0.985; duration: 110; easing.type: Easing.InCubic }
                        }
                    }

                    Connections {
                        target: editor.host
                        function onActiveHistoryKeyChanged() {
                            if (editor.host.activeHistoryKey !== editor.storageKey && historyPopup.opened) {
                                historyPopup.close()
                            }
                        }
                    }

                    contentItem: ListView {
                        id: historyList
                        clip: true
                        implicitHeight: contentHeight
                        model: historyPopup.visible ? editor.historyItems : []
                        boundsBehavior: Flickable.StopAtBounds
                        delegate: ItemDelegate {
                            id: historyDelegate
                            width: historyList.width
                            height: editor.host.sp(38)
                            focusPolicy: Qt.NoFocus
                            hoverEnabled: true
                            text: editor.historyPreview(modelData)
                            onClicked: {
                                configCatalog.applyHistory(editor.fileName, editor.fieldName, modelData)
                                editor.host.activeHistoryKey = ""
                                historyPopup.close()
                            }
                            contentItem: Text {
                                text: historyDelegate.text
                                color: historyDelegate.hovered ? "#FFFFFF" : editor.tokens.inkAlpha(0.78)
                                font.family: editor.tokens.monoFont
                                font.pixelSize: editor.host.sp(10)
                                elide: Text.ElideRight
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                radius: editor.host.sp(editor.tokens.radiusFrame)
                                color: historyDelegate.hovered ? editor.tokens.ink : "transparent"
                                Behavior on color { ColorAnimation { duration: editor.tokens.fastMotion } }
                            }
                        }
                        ScrollBar.vertical: ScrollBar {
                            policy: ScrollBar.AsNeeded
                            contentItem: Rectangle {
                                implicitWidth: editor.host.sp(4)
                                radius: editor.host.sp(2)
                                color: editor.tokens.inkAlpha(0.18)
                            }
                            background: Rectangle { color: "transparent" }
                        }
                    }
                    background: Rectangle {
                        radius: editor.host.sp(editor.tokens.radiusFrame)
                        color: editor.tokens.paperLight
                        border.color: editor.tokens.inkAlpha(0.24)
                        border.width: 1
                    }
                }
            }
        }
    }
}
