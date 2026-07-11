import QtQuick

Item {
    id: root
    property var host
    property var tokens
    property string kind: "general"
    property bool active: false
    property bool hovered: false
    property real assembly: active ? 1 : (hovered ? 0.48 : 0)

    Behavior on assembly {
        NumberAnimation {
            duration: root.active ? (root.tokens ? root.tokens.baseMotion : 210)
                                  : (root.tokens ? root.tokens.fastMotion : 140)
            easing.type: Easing.OutCubic
        }
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const cx = width / 2
            const cy = height / 2 - root.assembly * Math.max(1, height * 0.025)
            const radius = Math.min(width, height) * 0.34
            const ink = root.tokens ? root.tokens.ink : "#202322"
            const orange = root.tokens ? root.tokens.orange : "#F26422"

            ctx.translate(cx, cy)
            ctx.strokeStyle = ink
            ctx.fillStyle = ink
            ctx.lineWidth = Math.max(1.2, width * 0.025)
            ctx.lineCap = "round"
            ctx.lineJoin = "round"
            ctx.globalAlpha = root.active ? 0.92 : (root.hovered ? 0.72 : 0.58)

            const loosen = 1 - root.assembly
            ctx.save()
            ctx.rotate(loosen * 0.12)
            ctx.beginPath()
            ctx.arc(0, 0, radius, Math.PI * 0.12, Math.PI * 1.82)
            ctx.stroke()
            ctx.restore()

            if (root.kind === "general") {
                ctx.beginPath()
                ctx.arc(-radius * 0.16 - loosen * 2, 0, radius * 0.30, 0, Math.PI * 2)
                ctx.stroke()
                ctx.beginPath()
                ctx.moveTo(-radius * 0.72, 0)
                ctx.lineTo(radius * 0.72, 0)
                ctx.stroke()
                ctx.beginPath()
                ctx.arc(radius * 0.22 + loosen * 2, 0, radius * 0.13, 0, Math.PI * 2)
                ctx.fill()
            } else if (root.kind === "services") {
                ctx.beginPath()
                ctx.arc(-radius * 0.22 - loosen * 2, radius * 0.06, radius * 0.36, 0, Math.PI * 2)
                ctx.stroke()
                ctx.beginPath()
                ctx.arc(radius * 0.24 + loosen * 2, -radius * 0.06, radius * 0.36, 0, Math.PI * 2)
                ctx.stroke()
                ctx.beginPath()
                ctx.moveTo(-radius * 0.05, -radius * 0.65)
                ctx.lineTo(radius * 0.38, -radius * 0.65)
                ctx.stroke()
            } else if (root.kind === "game") {
                ctx.save()
                ctx.rotate(-Math.PI * 0.22)
                ctx.beginPath()
                ctx.arc(-radius * 0.20 - loosen * 2, 0, radius * 0.34, Math.PI * 0.20, Math.PI * 1.80)
                ctx.stroke()
                ctx.beginPath()
                ctx.arc(radius * 0.20 + loosen * 2, 0, radius * 0.34, Math.PI * 1.20, Math.PI * 2.80)
                ctx.stroke()
                ctx.restore()
                ctx.beginPath()
                ctx.arc(-radius * 0.72, radius * 0.60, radius * 0.10, 0, Math.PI * 2)
                ctx.fill()
            } else {
                const bars = [-0.56, -0.28, 0, 0.28, 0.56]
                for (let index = 0; index < bars.length; index++) {
                    const x = bars[index] * radius
                    const heightFactor = index === 2 ? 0.86 : (index === 1 || index === 3 ? 0.60 : 0.36)
                    const offset = loosen * (index % 2 === 0 ? 2 : -2)
                    ctx.beginPath()
                    ctx.moveTo(x, -radius * heightFactor + offset)
                    ctx.lineTo(x, radius * heightFactor + offset)
                    ctx.stroke()
                }
                ctx.beginPath()
                ctx.arc(-radius * 0.78, -radius * 0.56, radius * 0.10, 0, Math.PI * 2)
                ctx.fill()
            }

            if (root.active) {
                ctx.globalAlpha = 1
                ctx.fillStyle = orange
                ctx.beginPath()
                ctx.arc(radius * 0.82, radius * 0.56, Math.max(2, radius * 0.11), 0, Math.PI * 2)
                ctx.fill()
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onAssemblyChanged: canvas.requestPaint()
    onKindChanged: canvas.requestPaint()
    onActiveChanged: canvas.requestPaint()
    onHoveredChanged: canvas.requestPaint()
}
