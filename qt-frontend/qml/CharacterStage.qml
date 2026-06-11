import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: stage
    property real scaleFactor: 1
    property string workspaceName: "ArkLight Pioneer"
    property string characterName: ""
    property string characterImageUrl: ""
    property string activeEmotion: ""

    color: tokens.panel
    border.color: tokens.inkAlpha(0.1)
    border.width: 1
    clip: true

    ArkLightTokens {
        id: tokens
    }

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: stage.sp(64)
            color: tokens.whiteAlpha(0.26)
            border.color: tokens.inkAlpha(0.1)
            border.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: stage.sp(32)
                anchors.rightMargin: stage.sp(32)
                spacing: stage.sp(16)

                Row {
                    spacing: stage.sp(4)
                    Repeater {
                        model: 4
                        Rectangle {
                            width: stage.sp(8)
                            height: stage.sp(8)
                            color: tokens.orange
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    text: stage.workspaceName
                    color: tokens.ink
                    font.family: tokens.sansFont
                    font.pixelSize: stage.sp(12)
                    font.weight: Font.Black
                    elide: Text.ElideRight
                }

                Row {
                    spacing: stage.sp(8)
                    Rectangle { width: stage.sp(32); height: stage.sp(4); color: tokens.ink }
                    Rectangle { width: stage.sp(32); height: stage.sp(4); color: tokens.ink }
                }
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Grid {
                anchors.centerIn: parent
                columns: 20
                spacing: stage.sp(16)
                opacity: 0.1
                Repeater {
                    model: 200
                    Rectangle {
                        width: stage.sp(2)
                        height: stage.sp(2)
                        radius: stage.sp(1)
                        color: tokens.ink
                    }
                }
            }

            Rectangle {
                anchors.fill: parent
                color: tokens.whiteAlpha(0.05)
                border.color: tokens.inkAlpha(0.1)
                border.width: 0
            }

            Rectangle {
                x: stage.sp(16)
                y: stage.sp(16)
                width: stage.sp(64)
                height: stage.sp(64)
                color: "transparent"
                border.color: tokens.orange
                border.width: stage.sp(4)
            }

            Rectangle {
                x: stage.sp(50)
                y: stage.sp(52)
                width: stage.sp(96)
                height: stage.sp(24)
                color: tokens.orange
                Rectangle {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: stage.sp(8)
                    anchors.rightMargin: stage.sp(8)
                    height: 1
                    color: tokens.whiteAlpha(0.3)
                }
            }

            Rectangle {
                x: stage.sp(50)
                y: stage.sp(92)
                width: stage.sp(48)
                height: stage.sp(24)
                color: tokens.ink
            }

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                anchors.bottomMargin: parent.height * 0.11
                width: Math.min(parent.width * 0.55, stage.sp(420))
                height: width
                rotation: 45
                color: tokens.whiteAlpha(0.1)
                border.color: tokens.ink
                border.width: stage.sp(4)
            }

            Rectangle {
                anchors.centerIn: parent
                width: Math.min(parent.width * 0.6, stage.sp(450))
                height: width
                radius: width / 2
                color: "transparent"
                border.color: tokens.inkAlpha(0.2)
                border.width: 1
                RotationAnimation on rotation {
                    loops: Animation.Infinite
                    from: 0
                    to: 360
                    duration: 30000
                }
            }

            Rectangle {
                anchors.centerIn: parent
                width: Math.min(parent.width * 0.72, stage.sp(550))
                height: width
                radius: width / 2
                color: "transparent"
                border.color: tokens.inkAlpha(0.05)
                border.width: 1
                RotationAnimation on rotation {
                    loops: Animation.Infinite
                    from: 360
                    to: 0
                    duration: 25000
                }
            }

            Grid {
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.rightMargin: stage.sp(48)
                anchors.bottomMargin: stage.sp(48)
                columns: 4
                spacing: stage.sp(8)
                Repeater {
                    model: 12
                    Rectangle {
                        width: stage.sp(16)
                        height: stage.sp(16)
                        color: tokens.tealAlpha(0.1)
                        border.color: tokens.tealAlpha(0.2)
                        border.width: 1
                    }
                }
            }

            Image {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.leftMargin: stage.sp(28)
                anchors.rightMargin: stage.sp(28)
                anchors.topMargin: stage.sp(28)
                source: stage.characterImageUrl
                cache: false
                fillMode: Image.PreserveAspectFit
                horizontalAlignment: Image.AlignHCenter
                verticalAlignment: Image.AlignBottom
                visible: source.toString().length > 0
                opacity: status === Image.Ready ? 1 : 0
                scale: status === Image.Ready ? 1 : 0.985

                Behavior on opacity {
                    NumberAnimation { duration: 260; easing.type: Easing.OutCubic }
                }

                Behavior on scale {
                    NumberAnimation { duration: 320; easing.type: Easing.OutCubic }
                }
            }

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                anchors.bottomMargin: stage.sp(40)
                width: parent.width * 0.78
                height: parent.height * 0.72
                color: tokens.whiteAlpha(0.2)
                border.color: tokens.inkAlpha(0.2)
                border.width: 1
                visible: stage.characterImageUrl.length === 0

                IconUser {
                    anchors.centerIn: parent
                    width: stage.sp(220)
                    height: stage.sp(220)
                    strokeColor: tokens.inkAlpha(0.35)
                }
            }

            Text {
                anchors.left: parent.left
                anchors.bottom: parent.bottom
                anchors.leftMargin: stage.sp(48)
                anchors.bottomMargin: stage.sp(28)
                text: stage.activeEmotion.length > 0 ? "表情：" + stage.activeEmotion : ""
                color: tokens.inkAlpha(0.55)
                font.family: tokens.sansFont
                font.pixelSize: stage.sp(12)
                visible: text.length > 0
            }

            Column {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: -stage.sp(4)
                spacing: stage.sp(16)
                Repeater {
                    model: 5
                    Rectangle {
                        width: stage.sp(8)
                        height: stage.sp(8)
                        color: tokens.inkAlpha(0.1)
                    }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: stage.sp(96)
            color: tokens.ink
            clip: true

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: stage.sp(32)
                anchors.rightMargin: stage.sp(32)
                spacing: stage.sp(24)

                Rectangle {
                    Layout.preferredWidth: stage.sp(48)
                    Layout.preferredHeight: stage.sp(48)
                    color: tokens.whiteAlpha(0.1)
                    border.color: tokens.whiteAlpha(0.2)
                    border.width: 1

                    IconOrbit {
                        anchors.centerIn: parent
                        width: stage.sp(24)
                        height: stage.sp(24)
                        strokeColor: "#FFFFFF"
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    spacing: stage.sp(6)

                    Text {
                        Layout.fillWidth: true
                        text: "轨道链接"
                        color: tokens.whiteAlpha(0.5)
                        font.family: tokens.monoFont
                        font.pixelSize: stage.sp(10)
                        elide: Text.ElideRight
                    }

                    Text {
                        Layout.fillWidth: true
                        text: "已建立"
                        color: "#FFFFFF"
                        font.family: tokens.sansFont
                        font.pixelSize: stage.sp(14)
                        font.weight: Font.Black
                        elide: Text.ElideRight
                    }
                }

                Text {
                    text: "›"
                    color: tokens.whiteAlpha(0.3)
                    font.pixelSize: stage.sp(30)
                }
            }
        }
    }
}
