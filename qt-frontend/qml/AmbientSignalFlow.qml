import QtQuick

Item {
    id: flow
    property real scaleFactor: 1
    property string mode: "surface"
    property color coreColor: "#2A7C79"
    property color haloColor: "#F26422"
    property bool running: true
    property int particleCount: 10

    function sp(value) {
        return Math.max(1, Math.round(value * scaleFactor))
    }

    function pathX(t, lane) {
        if (mode === "stage") {
            if (lane === 0) {
                return width * (0.06 + 0.76 * t)
            }
            if (lane === 1) {
                return width * (0.945 + Math.sin(t * Math.PI * 2) * 0.006)
            }
            return width * (0.05 + 0.48 * t)
        }

        if (lane === 0) {
            return width * (0.16 + 0.72 * t)
        }
        if (lane === 1) {
            return width * (0.968 + Math.sin(t * Math.PI * 2) * 0.005)
        }
        return width * (0.08 + 0.36 * t)
    }

    function pathY(t, lane) {
        if (mode === "stage") {
            if (lane === 0) {
                return height * (0.13 + Math.sin(t * Math.PI) * 0.022)
            }
            if (lane === 1) {
                return height * (0.20 + 0.62 * t)
            }
            return height * (0.885 - Math.sin(t * Math.PI) * 0.018)
        }

        if (lane === 0) {
            return height * (0.115 + Math.sin(t * Math.PI) * 0.016)
        }
        if (lane === 1) {
            return height * (0.17 + 0.67 * t)
        }
        return height * (0.835 - Math.sin(t * Math.PI) * 0.012)
    }

    Repeater {
        model: flow.particleCount

        Item {
            id: particle
            required property int index
            property int lane: particle.index % 3
            property real progress: 0
            property real phase: (progress + particle.index / Math.max(1, flow.particleCount)) % 1
            property real pulse: 0.5 + 0.5 * Math.sin(phase * Math.PI)
            property real edgeFade: Math.min(1, phase / 0.08, (1 - phase) / 0.08)

            x: flow.pathX(phase, lane) - width / 2
            y: flow.pathY(phase, lane) - height / 2
            width: flow.sp(5)
            height: flow.sp(5)
            opacity: flow.running ? edgeFade * (0.04 + pulse * 0.12) : 0

            Rectangle {
                anchors.centerIn: parent
                width: flow.sp(particle.index % 5 === 0 ? 4 : 3)
                height: width
                radius: width / 2
                color: particle.index % 5 === 0 ? flow.haloColor : flow.coreColor
                opacity: 0.06 + particle.pulse * 0.08
            }

            Rectangle {
                anchors.centerIn: parent
                width: flow.sp(1)
                height: width
                radius: width / 2
                color: flow.coreColor
                opacity: 0.58 + particle.pulse * 0.24
            }

            NumberAnimation on progress {
                from: 0
                to: 1
                duration: 6200 + (particle.index % 3) * 1400
                loops: Animation.Infinite
                running: flow.running
                easing.type: Easing.Linear
            }
        }
    }
}
