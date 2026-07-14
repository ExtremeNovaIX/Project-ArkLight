import QtQuick

Item {
    id: backdrop
    property real scaleFactor: 1

    function sp(value) {
        return Math.max(1, Math.round(value * scaleFactor))
    }

    Image {
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/technical-graphics-raster.png"
        fillMode: Image.PreserveAspectFit
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.008
    }

    Item {
        id: megastructureZone
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.topMargin: Math.max(backdrop.sp(246), parent.height * 0.30)
        anchors.bottomMargin: backdrop.sp(98)
        anchors.rightMargin: -backdrop.sp(32)
        width: Math.min(parent.width * 0.56, backdrop.sp(560))

        Image {
            anchors.fill: parent
            source: "../assets/ui/technical-overlays/megastructure-raster.png"
            fillMode: Image.PreserveAspectFit
            horizontalAlignment: Image.AlignRight
            verticalAlignment: Image.AlignVCenter
            smooth: true
            mipmap: true
            opacity: 0.028
        }
    }

    Item {
        id: contourZone
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.topMargin: backdrop.sp(92)
        anchors.rightMargin: -backdrop.sp(22)
        width: Math.min(parent.width * 0.32, backdrop.sp(260))
        height: Math.min(parent.height * 0.16, backdrop.sp(132))

        Canvas {
            id: contourCanvas
            anchors.fill: parent
            antialiasing: true

            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                ctx.clearRect(0, 0, width, height)
                ctx.strokeStyle = "rgba(70,78,67,0.036)"
                ctx.lineWidth = 1

                const contourX = width * 0.58
                const contourY = height * 0.48
                for (let ring = 0; ring < 8; ring += 1) {
                    ctx.beginPath()
                    for (let point = 0; point <= 84; point += 1) {
                        const angle = Math.PI * 2 * point / 84
                        const wobble = 1
                            + Math.sin(angle * 3 + ring * 0.72) * 0.08
                            + Math.cos(angle * 5) * 0.035
                        const radiusX = width * (0.17 + ring * 0.034) * wobble
                        const radiusY = height * (0.16 + ring * 0.032) * wobble
                        const x = contourX + Math.cos(angle) * radiusX
                        const y = contourY + Math.sin(angle) * radiusY
                        if (point === 0) {
                            ctx.moveTo(x, y)
                        } else {
                            ctx.lineTo(x, y)
                        }
                    }
                    ctx.closePath()
                    ctx.stroke()
                }
            }

            onWidthChanged: requestPaint()
            onHeightChanged: requestPaint()
        }
    }

    Item {
        id: rasterGeometryZone
        visible: backdrop.width / Math.max(0.01, backdrop.scaleFactor) >= 690
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.leftMargin: backdrop.sp(52)
        anchors.topMargin: backdrop.sp(116)
        width: Math.min(parent.width * 0.34, backdrop.sp(280))
        height: Math.min(parent.height * 0.28, backdrop.sp(220))

        Canvas {
            id: rasterCanvas
            anchors.fill: parent
            antialiasing: true

            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                ctx.clearRect(0, 0, width, height)

                const cx = width * 0.54
                const cy = height * 0.48
                const radiusX = width * 0.46
                const radiusY = height * 0.42
                const cell = Math.max(backdrop.sp(6), 5)

                for (let px = 0; px < width; px += cell) {
                    for (let py = 0; py < height; py += cell) {
                        const nx = (px - cx) / radiusX
                        const ny = (py - cy) / radiusY
                        const radius = Math.sqrt(nx * nx + ny * ny)
                        const angle = Math.atan2(ny, nx)
                        const outerRing = Math.abs(radius - 0.88) < 0.038
                        const innerRing = Math.abs(radius - 0.58) < 0.034
                        const spoke = radius > 0.30
                            && radius < 0.92
                            && Math.abs(Math.sin(angle * 7 + 0.28)) < 0.072
                        const gate = Math.abs(Math.sin(px * 0.071 + py * 0.047)) > 0.46
                        if ((outerRing || innerRing || spoke) && gate) {
                            const alpha = 0.010 + Math.max(0, 0.9 - radius) * 0.011
                            ctx.fillStyle = "rgba(73,67,57," + alpha + ")"
                            const block = Math.max(1, Math.round(cell * 0.30))
                            ctx.fillRect(px, py, block, block)
                        }
                    }
                }
            }

            onWidthChanged: requestPaint()
            onHeightChanged: requestPaint()
        }
    }

    Canvas {
        id: materialCanvas
        anchors.fill: parent
        antialiasing: true

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const hatch = Math.max(backdrop.sp(18), 14)
            ctx.strokeStyle = "rgba(101,89,70,0.013)"
            ctx.lineWidth = 1
            for (let x = -height; x < width + height; x += hatch) {
                ctx.beginPath()
                ctx.moveTo(x, 0)
                ctx.lineTo(x + height, height)
                ctx.stroke()
            }

            function cross(x, y, size) {
                ctx.beginPath()
                ctx.moveTo(x - size, y)
                ctx.lineTo(x + size, y)
                ctx.moveTo(x, y - size)
                ctx.lineTo(x, y + size)
                ctx.stroke()
            }

            ctx.strokeStyle = "rgba(67,75,66,0.055)"
            cross(width * 0.065, height * 0.20, backdrop.sp(7))
            cross(width * 0.94, height * 0.36, backdrop.sp(8))
            cross(width * 0.08, height * 0.74, backdrop.sp(7))
            cross(width * 0.90, height * 0.72, backdrop.sp(6))

            ctx.strokeStyle = "rgba(74,67,55,0.080)"
            ctx.beginPath()
            ctx.moveTo(width * 0.07, height * 0.80)
            ctx.lineTo(width * 0.29, height * 0.80)
            for (let tick = 0; tick <= 10; tick += 1) {
                const tickX = width * 0.07 + width * 0.022 * tick
                ctx.moveTo(tickX, height * 0.792)
                ctx.lineTo(tickX, height * 0.808)
            }
            ctx.stroke()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onScaleFactorChanged: {
        contourCanvas.requestPaint()
        rasterCanvas.requestPaint()
        materialCanvas.requestPaint()
    }
}
