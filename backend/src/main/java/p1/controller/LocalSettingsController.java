package p1.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.service.LocalConfigService;

import java.util.Map;

/**
 * 本机设置管理入口。
 * <p>
 * 该接口面向本地前端，用于读写外部 config 目录中的受支持配置项。
 */
@RestController
@CrossOrigin
@RequestMapping("/api/setting")
@RequiredArgsConstructor
public class LocalSettingsController {

    private final LocalConfigService localConfigService;

    @GetMapping
    public LocalConfigService.ConfigCatalogSnapshot getSettings() {
        return localConfigService.listConfigs();
    }

    @GetMapping("/configs")
    public LocalConfigService.ConfigCatalogSnapshot listConfigs() {
        return localConfigService.listConfigs();
    }

    @GetMapping("/configs/{fileName:.+}")
    public LocalConfigService.EditableConfigPage getConfig(@PathVariable String fileName) {
        return localConfigService.getConfig(fileName);
    }

    @PutMapping("/configs/{fileName:.+}")
    public LocalConfigService.EditableConfigPage saveConfig(
            @PathVariable String fileName,
            @RequestBody LocalConfigService.ConfigUpdateRequest request) {
        return localConfigService.saveConfig(fileName, request);
    }

    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restartBackend() {
        localConfigService.requestBackendRestart();
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "message", "后端将在短暂延迟后退出；如果 launcher 或外层脚本负责守护进程，它会重新拉起后端。"));
    }
}
