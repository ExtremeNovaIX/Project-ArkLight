import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: root
    property var host
    property var tokens
    visible: root.host.activeView === "backend"
    Layout.fillWidth: true
    spacing: root.host.sp(20)

    RowLayout {
        Layout.fillWidth: true
        spacing: root.host.sp(12)
        SettingsSectionTitle {
            host: root.host
            tokens: root.tokens
            eyebrow: "本地配置"; title: "配置编辑" }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 1
        columnSpacing: root.host.sp(16)
        rowSpacing: root.host.sp(16)
        SettingsUiCard {
            host: root.host
            tokens: root.tokens
            title: "配置目录"
            detail: configCatalog.configDir.length > 0 ? configCatalog.configDir : "尚未读取"
            Text {
                Layout.fillWidth: true
                text: configCatalog.responseStatus.length > 0
                      ? "响应: " + configCatalog.responseStatus + " / " + (configCatalog.lastFetchedAt || "未刷新")
                      : "未刷新"
                color: root.tokens.inkAlpha(0.55)
                font.family: root.tokens.monoFont
                font.pixelSize: root.host.sp(11)
                wrapMode: Text.WrapAnywhere
            }
        }
    }

    Rectangle {
        visible: configCatalog.errorMessage.length > 0
        Layout.fillWidth: true
        implicitHeight: errorText.implicitHeight + root.host.sp(24)
        radius: root.host.sp(root.tokens.radiusFrame)
        color: root.tokens.orangeDarkAlpha(0.08)
        border.color: root.tokens.orangeAlpha(0.5)
        border.width: 1
        Text {
            id: errorText
            anchors.fill: parent
            anchors.margins: root.host.sp(12)
            text: configCatalog.errorMessage
            color: root.tokens.orangeDark
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(13)
            wrapMode: Text.WordWrap
        }
    }

    Rectangle {
        visible: configCatalog.actionMessage.length > 0
        Layout.fillWidth: true
        implicitHeight: actionText.implicitHeight + root.host.sp(24)
        radius: root.host.sp(root.tokens.radiusFrame)
        color: root.tokens.tealAlpha(0.08)
        border.color: root.tokens.tealAlpha(0.55)
        border.width: 1
        Text {
            id: actionText
            anchors.fill: parent
            anchors.margins: root.host.sp(12)
            text: configCatalog.actionMessage
            color: root.tokens.tealDark
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(13)
            wrapMode: Text.WordWrap
        }
    }

    Flow {
        Layout.fillWidth: true
        spacing: root.host.sp(8)
        visible: configCatalog.configPages.length > 0
        Repeater {
            model: configCatalog.configPages
            Button {
                text: modelData.title
                focusPolicy: Qt.NoFocus
                onClicked: configCatalog.activeFileName = modelData.fileName
                contentItem: Column {
                    anchors.centerIn: parent
                    spacing: root.host.sp(3)
                    Text {
                        text: modelData.title
                        color: configCatalog.activeFileName === modelData.fileName ? "#FFFFFF" : root.tokens.ink
                        font.family: root.tokens.sansFont
                        font.pixelSize: root.host.sp(12)
                        font.weight: Font.Black
                    }
                    Text {
                        text: modelData.fileName
                        color: configCatalog.activeFileName === modelData.fileName ? root.tokens.whiteAlpha(0.62) : root.tokens.inkAlpha(0.52)
                        font.family: root.tokens.monoFont
                        font.pixelSize: root.host.sp(9)
                    }
                }
                background: Rectangle {
                    implicitWidth: root.host.sp(188)
                    implicitHeight: root.host.sp(58)
                    radius: root.host.sp(root.tokens.radiusFrame)
                    color: configCatalog.activeFileName === modelData.fileName ? root.tokens.ink : root.tokens.whiteAlpha(0.7)
                    border.color: configCatalog.activeFileName === modelData.fileName ? root.tokens.ink : root.tokens.inkAlpha(0.15)
                    border.width: 1
                }
            }
        }
    }

    Rectangle {
        visible: Boolean(configCatalog.activePage.fileName)
        Layout.fillWidth: true
        implicitHeight: activePageHeader.implicitHeight + root.host.sp(26)
        radius: root.host.sp(root.tokens.radiusFrame)
        color: root.tokens.whiteAlpha(0.7)
        border.color: root.tokens.inkAlpha(0.15)
        border.width: 1

        RowLayout {
            id: activePageHeader
            anchors.fill: parent
            anchors.margins: root.host.sp(13)
            spacing: root.host.sp(12)
            ColumnLayout {
                Layout.fillWidth: true
                Layout.minimumWidth: 0
                spacing: root.host.sp(4)
                Text {
                    Layout.fillWidth: true
                    text: configCatalog.activePage.title || ""
                    color: root.tokens.ink
                    font.family: root.tokens.sansFont
                    font.pixelSize: root.host.sp(18)
                    font.weight: Font.Black
                    elide: Text.ElideRight
                }
                Text {
                    Layout.fillWidth: true
                    text: configCatalog.activePage.description || ""
                    color: root.tokens.inkAlpha(0.58)
                    font.family: root.tokens.sansFont
                    font.pixelSize: root.host.sp(12)
                    wrapMode: Text.WordWrap
                }
            }
            Text {
                text: (configCatalog.activePage.fileName || "") + " / "
                      + ((configCatalog.activePage.fields || []).length) + " 项"
                color: root.tokens.inkAlpha(0.42)
                font.family: root.tokens.monoFont
                font.pixelSize: root.host.sp(10)
            }
        }
    }

    ColumnLayout {
        Layout.fillWidth: true
        spacing: root.host.sp(10)
        visible: Boolean(configCatalog.activePage.fileName)
        Repeater {
            model: configCatalog.activePage.fields || []
            SettingsConfigFieldEditor {
                host: root.host
                tokens: root.tokens
                page: configCatalog.activePage
                field: modelData
            }
        }
    }

    Rectangle {
        visible: !configCatalog.loading && configCatalog.configPages.length === 0
        Layout.fillWidth: true
        implicitHeight: emptyConfigText.implicitHeight + root.host.sp(40)
        radius: root.host.sp(root.tokens.radiusFrame)
        color: root.tokens.whiteAlpha(0.7)
        border.color: root.tokens.inkAlpha(0.15)
        border.width: 1
        Text {
            id: emptyConfigText
            anchors.fill: parent
            anchors.margins: root.host.sp(20)
            text: "没有找到可编辑的本地配置文件。请确认 config 目录存在。"
            color: root.tokens.inkAlpha(0.62)
            font.family: root.tokens.sansFont
            font.pixelSize: root.host.sp(13)
            wrapMode: Text.WordWrap
        }
    }
}
