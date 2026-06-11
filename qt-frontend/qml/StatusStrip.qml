import QtQuick

Item {
    id: strip
    property real scaleFactor: 1
    implicitHeight: Math.round(8 * scaleFactor)

    Row {
        anchors.fill: parent
        spacing: 0

        Rectangle {
            width: parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: "#8F2A1F" }
                GradientStop { position: 1; color: "#A63B28" }
            }
        }
        Rectangle {
            width: parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: "#DEA03B" }
                GradientStop { position: 1; color: "#F0B649" }
            }
        }
        Rectangle {
            width: parent.width / 3
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: "#4C8E8B" }
                GradientStop { position: 1; color: "#67B6B0" }
            }
        }
    }
}
