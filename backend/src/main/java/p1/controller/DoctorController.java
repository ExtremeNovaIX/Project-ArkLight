package p1.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import p1.service.RuntimeDoctorService;

/**
 * 本地运行时健康检查入口。
 * <p>
 * 该接口面向 Qt 和命令行脚本，返回本机依赖缺失与下一步处理建议。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
public class DoctorController {

    private final RuntimeDoctorService runtimeDoctorService;

    @GetMapping("/status")
    public RuntimeDoctorService.DoctorSnapshot status() {
        return runtimeDoctorService.snapshot();
    }
}
