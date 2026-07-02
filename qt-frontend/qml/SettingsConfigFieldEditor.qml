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
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            Layout.preferredHeight: Math.max(editor.host.sp(46), inputStack.implicitHeight)

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
                        if (activeFocus && configCatalog.fieldSupportsHistory(field || {})) {
                            editor.host.activeHistoryKey = editor.storageKey
                        }
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
                        if (activeFocus && configCatalog.fieldSupportsHistory(field || {})) {
                            editor.host.activeHistoryKey = editor.storageKey
                        }
                    }
                    onTextEdited: configCatalog.setFieldValue(editor.fileName, editor.fieldName, text)
                }

                Row {
                    visible: editor.host.activeHistoryKey === editor.storageKey
                             && configCatalog.fieldSupportsHistory(field || {})
                             && configCatalog.historyFor(editor.fileName, editor.fieldName).length > 0
                    spacing: editor.host.sp(6)
                    Repeater {
                        model: configCatalog.historyFor(editor.fileName, editor.fieldName)
                        Button {
                            id: historyButton
                            text: modelData
                            focusPolicy: Qt.NoFocus
                            font.family: editor.tokens.monoFont
                            font.pixelSize: editor.host.sp(10)
                            onClicked: {
                                configCatalog.applyHistory(editor.fileName, editor.fieldName, modelData)
                                editor.host.activeHistoryKey = ""
                            }
                            contentItem: Text {
                                text: historyButton.text
                                color: editor.tokens.inkAlpha(0.76)
                                font: historyButton.font
                                elide: Text.ElideRight
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                radius: editor.host.sp(editor.tokens.radiusFrame)
                                color: editor.tokens.inputPaper
                                border.color: editor.tokens.inkAlpha(0.18)
                                border.width: 1
                            }
                        }
                    }
                }
            }
        }
    }
}
