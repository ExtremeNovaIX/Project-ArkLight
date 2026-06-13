import QtQuick

Item {
    id: strip
    property real scaleFactor: 1
    implicitHeight: Math.round(8 * scaleFactor)

    ArkLightTokens {
        id: tokens
    }

    Row {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            width: parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: tokens.statusRed }
                GradientStop { position: 1; color: tokens.statusRedLight }
            }
        }
        Rectangle {
            width: parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: tokens.statusYellowDark }
                GradientStop { position: 1; color: tokens.statusYellow }
            }
        }
        Rectangle {
            width: parent.width - 2 * parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: tokens.statusTealDark }
                GradientStop { position: 1; color: tokens.statusTeal }
            }
        }
    }
}
