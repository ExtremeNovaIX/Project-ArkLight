import QtQuick

Item {
    id: field
    property int count: 42
    property real scaleFactor: 1
    property color moteColor: "#E85D04"

    function sp(value) {
        return Math.round(value * scaleFactor)
    }

    Repeater {
        model: field.count
        Rectangle {
            readonly property real moteSize: field.sp(1.6 + Math.random() * 1.8)
            readonly property real baseX: Math.random() * field.width
            readonly property real baseY: Math.random() * field.height
            x: baseX
            y: baseY
            width: moteSize
            height: moteSize
            color: field.moteColor
            opacity: 0.13 + Math.random() * 0.12

            SequentialAnimation on y {
                loops: Animation.Infinite
                NumberAnimation {
                    to: baseY - field.sp(28 + Math.random() * 56)
                    duration: 9000 + Math.random() * 9000
                    easing.type: Easing.InOutSine
                }
                NumberAnimation {
                    to: baseY + field.sp(14 + Math.random() * 40)
                    duration: 9000 + Math.random() * 9000
                    easing.type: Easing.InOutSine
                }
            }
        }
    }
}
